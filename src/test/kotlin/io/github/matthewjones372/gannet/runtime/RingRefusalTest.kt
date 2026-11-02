package io.github.matthewjones372.gannet.runtime

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** What a refused io_uring_setup says, where no kernel has to refuse one: the message is the whole diagnosis. */
class RingRefusalTest {
    @Test
    fun `a ring refused its locked memory names the limit, the ring's size and what to change`() {
        val message = Ring.refusal(Sys.ENOMEM, 4096, 16384)
        message shouldContain "ENOMEM"
        message shouldContain "RLIMIT_MEMLOCK"
        message shouldContain "4096 submissions and 16384 completions"
        message shouldContain "about 528 KiB"
        message shouldContain "lower ringEntries, completionEntries or carriers"
    }

    @Test
    fun `a ring's locked memory is its submission entries, their index and its completion entries`() {
        // 64-byte submissions with a 4-byte index each, 16-byte completions: the default carrier's 528 KiB, which
        // is how an unprivileged process with an 8 MiB limit fits fifteen of them and is refused the sixteenth.
        Ring.lockedKib(4096, 16384) shouldBe 528
        Ring.lockedKib(4, 4) shouldBe 1
    }

    @Test
    fun `an errno with no diagnosis of its own is still named`() {
        Ring.refusal(5, 4096, 16384) shouldBe "io_uring_setup failed: errno 5"
    }
}
