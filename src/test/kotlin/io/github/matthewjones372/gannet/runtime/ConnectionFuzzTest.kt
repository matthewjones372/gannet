package io.github.matthewjones372.gannet.runtime

import com.code_intelligence.jazzer.junit.FuzzTest

/**
 * A whole connection's input, without a socket: heads and bodies read off one stream as a connection reads them,
 * until it ends or must close. Whether the input arrives at once or in small reads through a small buffer, the same
 * requests are read, each head reads back the same, and a refused or broken request is the last one.
 */
class ConnectionFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun connection(data: ByteArray) {
        val knobs = Knobs(data)
        val atOnce = exchanges(knobs, MemoryInbound(knobs.input, maxHeaderBytes = knobs.maxHead))
        val inParts = exchanges(knobs, MemoryInbound(knobs.input, knobs.cuts, knobs.lent, knobs.maxHead))
        check(atOnce == inParts) { "At once: $atOnce\nin parts: $inParts" }
    }
}

/** What a connection makes of its input: one line for each request, refusal or broken body, in order. */
internal fun exchanges(knobs: Knobs, inbound: MemoryInbound): List<String> = buildList {
    while (true) {
        check(size <= knobs.input.size) { "More requests than bytes" }
        val before = inbound.consumed
        when (val parsed = inbound.nextHead(knobs.maxLine, knobs.maxHead, knobs.maxFields)) {
            null -> return@buildList

            is Parse.Refused -> {
                add("refused ${parsed.status}")
                return@buildList
            }

            is Parse.Complete -> {
                val head = parsed.head
                check(inbound.consumed > before) { "A head took no input" }
                val again = serialize(head)
                val reparsed = parseHead(again, 0, again.size, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
                check(reparsed is Parse.Complete && describe(reparsed.head) == describe(head)) { describe(head) }
                add(describe(head))
                val reader = BodyReader(inbound, head)
                val body = readBody(reader, inbound, knobs.maxBody, knobs.cuts)
                // How much of a refused body was read first depends on the reads' sizes, so only the refusal counts.
                add(body.failure ?: "body ${body.body.size} ${body.body.hashCode()}")
                // A body refused for size keeps its framing, so the rest is read and dropped, as a connection does.
                if (!reader.finish(DRAIN)) {
                    add("closed")
                    return@buildList
                }
                check(inbound.continues <= size) { "100 Continue asked for more than once a request" }
            }

            Parse.Incomplete -> error("nextHead returned Incomplete")
        }
        check(inbound.consumed <= knobs.input.size) { "Consumed ${inbound.consumed} of ${knobs.input.size}" }
    }
}

private const val DRAIN = 1L shl 20
