package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SupportTest {
    @Test
    fun `this machine can run the runtime`() {
        assumeUring()
        GannetSupport.problem().shouldBeNull()
        GannetSupport.require()
    }

    @Test
    fun `another OS, an old kernel and another architecture are each named`() {
        GannetSupport.platformProblem("Mac OS X", "aarch64", "14.0")!! shouldContain "this is Mac OS X"
        GannetSupport.platformProblem("Linux", "amd64", "5.15.0-91-generic")!! shouldContain "this is 5.15.0-91-generic"
        GannetSupport.platformProblem("Linux", "riscv64", "6.8")!! shouldContain "riscv64"
        GannetSupport.platformProblem("Linux", "amd64", "6.1.0").shouldBeNull()
    }

    @Test
    fun `kernel releases are compared by major and minor`() {
        GannetSupport.kernelAtLeast("6.18.44-fc-v153", 6, 1) shouldBe true
        GannetSupport.kernelAtLeast("7.0", 6, 1) shouldBe true
        GannetSupport.kernelAtLeast("6.0.9", 6, 1) shouldBe false
        GannetSupport.kernelAtLeast("garbage", 6, 1) shouldBe false
    }

    @Test
    fun `a configuration out of range is refused`() {
        shouldThrow<IllegalArgumentException> { GannetConfig(port = 70_000) }
        shouldThrow<IllegalArgumentException> { GannetConfig(carriers = 0) }
        shouldThrow<IllegalArgumentException> { GannetConfig(bufferCount = 100) }
        shouldThrow<IllegalArgumentException> { GannetConfig(bufferSize = 0) }
        shouldThrow<IllegalArgumentException> { GannetConfig(maxRequestLineBytes = 20_000) }
        shouldThrow<IllegalArgumentException> { GannetConfig(heldBuffers = 0) }
        shouldThrow<IllegalArgumentException> { GannetConfig(tick = java.time.Duration.ZERO) }
    }

    @Test
    fun `headers read without regard to case`() {
        val headers = Headers(listOf("Content-Type" to "a", "X-Y" to "1", "x-y" to "2"))
        headers["content-type"] shouldBe "a"
        headers.all("X-y") shouldBe listOf("1", "2")
        ("CONTENT-TYPE" in headers) shouldBe true
        headers.toString() shouldBe "Headers(Content-Type: a, X-Y: 1, x-y: 2)"
    }
}
