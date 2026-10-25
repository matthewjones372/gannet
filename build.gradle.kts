import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

// Plugin classpaths carry advisories of their own: Dokka resolves an old
// `jackson-databind`, and Kover's reporter an old `freemarker`. Neither ships
// in the artifact, but the newer versions cost nothing.
buildscript {
    dependencies {
        constraints {
            classpath("com.fasterxml.jackson.core:jackson-databind:2.22.3")
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.diffplug.spotless") version "8.10.4"
    id("dev.detekt") version "2.0.0-alpha.6"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    // The version comes from the nearest `v` tag, so cutting a release is
    // `git tag v0.1.0 && git push --tags`. An untagged commit is a -SNAPSHOT of
    // the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.4"
    // Publishing to the Central Portal. It wraps `maven-publish` and `signing`.
    id("com.vanniktech.maven.publish") version "0.37.0"
    // Renders the KDoc into the javadoc jar Maven Central requires.
    id("org.jetbrains.dokka") version "2.2.0"
    // The published binary surface, as a file somebody reads in a diff.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

scmVersion {
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` rather than axion's default, which decorates a
    // snapshot with the branch name.
    versionCreator("simple")
}

group = "io.github.matthewjones372"
version = scmVersion.version

repositories { mavenCentral() }

// The runtime reaches the JDK's own continuation, which java.base does not
// export, and calls io_uring and epoll through FFM. Both are flags on every JVM
// that runs it, on either engine; `GannetSupport.FLAGS` names them, and
// `GannetSupport.problem()` says which one is missing.
val runtimeFlags =
    listOf("--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")

// Where the io_uring tests must run rather than skip. CI sets it; a laptop off
// Linux, or a container whose seccomp profile refuses `io_uring_setup`, does
// not, and its tests skip.
val uringRequired = System.getenv("GANNET_REQUIRE_URING") == "1"

kotlin {
    // FFM and `jdk.internal.vm.Continuation` as this runtime uses them need 25.
    jvmToolchain(25)
    compilerOptions {
        // Interfaces with a body emit real JVM default methods, with no
        // `DefaultImpls` copies for the API dump to keep.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    // Assertions only: the tests run on the JUnit platform.
    testImplementation("io.kotest:kotest-assertions-core:6.2.6")
    // A second, independent HTTP/1.1 client, beside the JDK's, for the replies
    // each one has to read.
    testImplementation("org.eclipse.jetty:jetty-client:12.1.13")
    // Fuzz targets: `test` replays their seeds and saved crashes, and the `fuzz` task fuzzes one of them.
    testImplementation("com.code-intelligence:jazzer-junit:0.30.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(runtimeFlags)
    // A backstop, not a budget: one wedged test cannot burn the whole job.
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

// The whole suite again on the epoll engine. `test` leaves `gannet.engine`
// unset, so its servers take AUTO: io_uring where the host allows it, which
// `GANNET_REQUIRE_URING=1` insists on.
val testEpoll = tasks.register<Test>("testEpoll") {
    description = "Runs the tests with every server on the epoll engine."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("gannet.engine", "EPOLL")
    shouldRunAfter(tasks.test)
}

tasks.named("check") { dependsOn(testEpoll) }

/**
 * Fuzzes the `@FuzzTest` named by `-PfuzzTarget` (a test filter, such as `*HeadFuzzTest.head`) for `-PfuzzSeconds`.
 * Jazzer fuzzes one target per run; `test` replays every target's seeds and saved findings without fuzzing.
 */
tasks.register<Test>("fuzz") {
    group = "verification"
    description = "Fuzzes one @FuzzTest target."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val target = providers.gradleProperty("fuzzTarget")
    val seconds = providers.gradleProperty("fuzzSeconds").orElse("60")
    filter { includeTestsMatching(target.getOrElse("*FuzzTest*")) }
    environment("JAZZER_FUZZ", "1")
    systemProperty("jazzer.max_duration", "${seconds.get()}s")
    systemProperty("jazzer.instrumentation_includes", "io.github.matthewjones372.gannet.**")
    systemProperty("junit.jupiter.execution.timeout.default", "30m")
    // A fuzzing run is never up to date, and never comes from the build cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

/**
 * The soak: a mixed load (keep-alive, POSTs, pipelining, streams, slow and vanishing clients, malformed requests,
 * TLS, churn past the connection cap) against a server in a JVM of its own, for `-PsoakMinutes` on each engine, with
 * checks at the end that nothing leaked or degraded. Its own source set, so `test` never runs it; `check` only
 * compiles it. See the README's "Soak testing".
 */
val soak: SourceSet = sourceSets.create("soak")

kotlin.target.compilations.named("soak") {
    // `internal` reaches across, for the server's counts.
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.register<JavaExec>("soak") {
    group = "verification"
    description = "Soaks the server on each engine under mixed load and checks for leaks and degradation."
    classpath = soak.runtimeClasspath
    mainClass.set("io.github.matthewjones372.gannet.soak.SoakKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs(runtimeFlags)
    jvmArgs("-Xmx1g")
    val names = listOf(
        "minutes", "engines", "intervalSeconds", "warmupSeconds", "graceSeconds", "serverCpus", "loadCpus",
        "carriers", "heapSlope", "directSlope", "scenarios",
    )
    names.forEach { name ->
        providers.gradleProperty("soak${name.replaceFirstChar { it.uppercase() }}").orNull?.let {
            systemProperty("soak.$name", it)
        }
    }
    systemProperty("soak.out", layout.buildDirectory.dir("soak").get().asFile.path)
    outputs.upToDateWhen { false }
}

// Compiled and linted with every `check`, never run by it.
tasks.named("check") { dependsOn("soakClasses", "detektSoak") }

// BCV reads classes with an ASM that stops short of class file 69 (JDK 25).
configurations.matching { it.name.startsWith("bcv-rt-jvm-cp") }.configureEach {
    resolutionStrategy.force("org.ow2.asm:asm:9.11", "org.ow2.asm:asm-tree:9.11")
}

// The Kotlin plugin resolves a Bouncy Castle with a known advisory here.
configurations.matching { it.name == "kotlinBouncyCastleConfiguration" }.configureEach {
    resolutionStrategy.force(
        "org.bouncycastle:bcprov-jdk18on:1.86",
        "org.bouncycastle:bcpg-jdk18on:1.86",
        "org.bouncycastle:bcpkix-jdk18on:1.86",
        "org.bouncycastle:bcutil-jdk18on:1.86",
    )
}

detekt {
    config.setFrom(file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

// The plain `detekt` task cannot see types, so the rules that need them are
// silently skipped there. `check` runs the type-resolving pair instead.
tasks.named("check") { dependsOn("detektMain", "detektTest") }

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this code is laid out by hand.
 * The rules that disagree with that are turned off in `.editorconfig`.
 */
val ktlintVersion = "1.8.0"
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
    // Whitespace rules for the Java core, which need no formatter download.
    java {
        target("src/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
}

// Coverage counts only where the io_uring tests must run. Elsewhere they may
// skip, and skipped lines would fail a floor that measures nothing but the
// host. The floor is a ratchet against regression, set below where the number
// sits, not a target to write tests towards.
kover {
    // Fuzzing measures nothing the floor is about, and must not run as part of `check`.
    currentProject {
        instrumentation { disabledForTestTasks.add("fuzz") }
        // The soak's own code is a harness, measured by nothing the floor is about.
        sources { excludedSourceSets.add("soak") }
    }
    reports {
        verify {
            rule {
                // Kover runs `koverVerify` as part of `check` by itself.
                disabled.set(!uringRequired)
                minBound(90)
            }
        }
    }
}

dokka {
    dokkaPublications.html {
        moduleName.set("Gannet")
    }
}

// Dokka's worker classpath, which the buildscript constraints do not reach.
dependencies {
    dokkaHtmlGeneratorRuntime(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    constraints {
        dokkaHtmlGeneratorRuntime("org.jsoup:jsoup:1.23.2")
        dokkaHtmlGeneratorRuntime("org.freemarker:freemarker:2.3.35")
    }
}

mavenPublishing {
    coordinates("io.github.matthewjones372", "gannet", version.toString())
    // Sources for a library someone else has to debug, and a javadoc jar,
    // which Maven Central requires, filled by Dokka.
    configure(
        com.vanniktech.maven.publish.KotlinJvm(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
        ),
    )
    pom {
        name.set("Gannet")
        description.set(
            "An experimental HTTP/1.1 server on io_uring for the JVM: a carrier thread per core, " +
                "each connection a continuation.",
        )
        url.set("https://github.com/matthewjones372/gannet")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("matthewjones372")
                name.set("Matt Jones")
            }
        }
        scm {
            url.set("https://github.com/matthewjones372/gannet")
            connection.set("scm:git:https://github.com/matthewjones372/gannet.git")
            developerConnection.set("scm:git:ssh://git@github.com/matthewjones372/gannet.git")
        }
    }
}

publishing {
    repositories {
        // `publishAllPublicationsToLocalRepository` for something to inspect
        // without installing it; `publishToMavenLocal` to try it from another
        // build.
        maven {
            name = "local"
            url = layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
    }
}
