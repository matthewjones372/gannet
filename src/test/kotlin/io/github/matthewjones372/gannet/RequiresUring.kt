package io.github.matthewjones372.gannet

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Whether io_uring tests run on this host: [GannetSupport]'s answer, unless `GANNET_REQUIRE_URING=1` says this host
 * must run them, in which case they run and fail where io_uring is unusable.
 */
object UringTests {
    private val required: Boolean = System.getenv("GANNET_REQUIRE_URING") == "1"

    /** Null when the io_uring tests should run here, else why they skip. */
    val skipReason: String? = GannetSupport.problem()?.takeUnless { required }

    /** True where the io_uring tests run, so a suite over several backends keeps io_uring in its list. */
    val run: Boolean get() = skipReason == null
}

/** Skips a test where io_uring cannot run, unless `GANNET_REQUIRE_URING=1` says this host must run it. */
fun assumeUring() {
    val problem = GannetSupport.problem() ?: return
    Assumptions.assumeTrue(UringTests.run, problem)
    throw AssertionError("GANNET_REQUIRE_URING=1, but: $problem")
}

/**
 * Skips a whole class or test where io_uring cannot run, as [assumeUring] does. It is decided before the class
 * initialises, so a class whose companion starts a server is skipped rather than failing to load.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(UringCondition::class)
annotation class RequiresUring

/** What [RequiresUring] runs. */
class UringCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        UringTests.skipReason?.let { ConditionEvaluationResult.disabled(it) }
            ?: ConditionEvaluationResult.enabled("io_uring tests run here")
}
