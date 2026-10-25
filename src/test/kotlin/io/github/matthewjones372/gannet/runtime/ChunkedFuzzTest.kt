package io.github.matthewjones372.gannet.runtime

import com.code_intelligence.jazzer.junit.FuzzTest

/**
 * The chunked body decoder fed arbitrary bytes, in reads of every size. It fails only with the documented
 * exceptions, ends, and agrees with [referenceChunked] on the body, the failure and where the next request starts.
 */
class ChunkedFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun chunked(data: ByteArray) {
        val knobs = Knobs(data)
        checkChunked(knobs.input, knobs.maxBody, knobs.maxHead, knobs.cuts, knobs.lent)
    }
}

internal fun checkChunked(input: ByteArray, limit: Long, maxTrailer: Int, cuts: IntArray, lent: Int) {
    val expected = referenceChunked(input, limit, maxTrailer)
    val inbound = MemoryInbound(input, cuts, lent, maxTrailer)
    val actual = readBody(inbound, Framing.Chunked, limit, cuts)
    val agrees = actual.failure == expected.failure &&
        (actual.failure != null || (actual.body == expected.body && actual.consumed == expected.consumed))
    check(agrees) {
        "Decoded ${actual.failure ?: "${actual.body.size} bytes, taking ${actual.consumed}"}; expected " +
            (expected.failure ?: "${expected.body.size} bytes, taking ${expected.consumed}")
    }
}
