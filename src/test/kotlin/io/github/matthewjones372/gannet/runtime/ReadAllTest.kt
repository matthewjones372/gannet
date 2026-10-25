package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.BodyTooLargeException
import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpVersion
import io.github.matthewjones372.gannet.MalformedBodyException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * [BodyReader.readAll] off memory, in reads cut every which way into a lent buffer small enough to be outgrown: what it
 * returns, how large an array it returns it in, and what it allocates on the way.
 */
class ReadAllTest {
    private fun head(framing: Framing, expectContinue: Boolean = false) =
        Head("POST", "/", "/", null, HttpVersion.HTTP_1_1, Headers(emptyList()), framing, true, expectContinue)

    private fun bytes(size: Int) = ByteArray(size) { ('a' + it % 26).code.toByte() }

    private fun chunked(body: ByteArray, chunk: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < body.size) {
            val n = minOf(chunk, body.size - at)
            out.write("${Integer.toHexString(n)}\r\n".toByteArray())
            out.write(body, at, n)
            out.write("\r\n".toByteArray())
            at += n
        }
        out.write("0\r\n\r\n".toByteArray())
        return out.toByteArray()
    }

    /** Read cuts: everything at once, a byte at a time, odd sizes that park, and reads larger than the lent buffer. */
    private val cutsTried = listOf(intArrayOf(Int.MAX_VALUE), intArrayOf(1), intArrayOf(7, 64, 3), intArrayOf(5000))

    private val sizes = listOf(1, 2, 63, 64, 65, 1000, 16 * 1024, 16 * 1024 + 1, 70_000)

    @Test
    fun `a known length is read into an array of exactly its size, however it arrives`() {
        for (size in sizes) {
            for (cuts in cutsTried) {
                for (lent in listOf(16, 4096)) {
                    withClue("size $size cuts ${cuts.toList()} lent $lent") {
                        val body = bytes(size)
                        val inbound = MemoryInbound(body + "GET".toByteArray(), cuts, lent)
                        val reader = BodyReader(inbound, head(Framing.Fixed(size.toLong())))
                        val all = reader.readAll(Long.MAX_VALUE)
                        all.size shouldBe size
                        all.contentEquals(body) shouldBe true
                        inbound.consumed shouldBe size
                        reader.done shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `a chunked body of any size comes whole, in chunks of any size`() {
        for (size in listOf(0) + sizes + 200_000) {
            for (chunk in listOf(1, 10, 4096, 100_000)) {
                for (cuts in cutsTried) {
                    withClue("size $size chunk $chunk cuts ${cuts.toList()}") {
                        val body = bytes(size)
                        val wire = chunked(body, chunk)
                        val inbound = MemoryInbound(wire + "GET".toByteArray(), cuts, lent = 64)
                        val reader = BodyReader(inbound, head(Framing.Chunked))
                        val all = reader.readAll(Long.MAX_VALUE)
                        all.size shouldBe size
                        all.contentEquals(body) shouldBe true
                        inbound.consumed shouldBe wire.size
                        reader.mustClose(0) shouldBe false
                    }
                }
            }
        }
    }

    @Test
    fun `a known length at or under the limit is read, and over it refused before anything is read`() {
        for (size in listOf(2, 64, 20_000)) {
            val body = bytes(size)
            for (limit in listOf(size.toLong(), size + 1L)) {
                val inbound = MemoryInbound(body, intArrayOf(7))
                BodyReader(inbound, head(Framing.Fixed(size.toLong()))).readAll(limit).contentEquals(body) shouldBe true
            }
            val inbound = MemoryInbound(body, intArrayOf(7))
            val reader = BodyReader(inbound, head(Framing.Fixed(size.toLong()), expectContinue = true))
            shouldThrow<BodyTooLargeException> { reader.readAll(size - 1L) }.limit shouldBe size - 1L
            inbound.consumed shouldBe 0
            inbound.continues shouldBe 0
            // Refused for size, the body's framing is intact, but it was never asked for, so the connection closes.
            reader.mustClose(Long.MAX_VALUE) shouldBe true
        }
    }

    @Test
    fun `a chunked body at the limit is read, and one byte past it refused`() {
        for (size in listOf(0, 1, 64, 65, 5000)) {
            val body = bytes(size)
            for (chunk in listOf(1, 64, 10_000)) {
                withClue("size $size chunk $chunk") {
                    val wire = chunked(body, chunk)
                    val atLimit = BodyReader(MemoryInbound(wire, intArrayOf(9)), head(Framing.Chunked))
                    atLimit.readAll(size.toLong()).contentEquals(body) shouldBe true
                    if (size > 0) {
                        val over = BodyReader(MemoryInbound(wire, intArrayOf(9)), head(Framing.Chunked))
                        shouldThrow<BodyTooLargeException> { over.readAll(size - 1L) }
                        // The refusal leaves the framing intact, so the rest can be drained.
                        over.finish(Long.MAX_VALUE) shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `100-continue is asked for once, when the body is read whole`() {
        for (framing in listOf(Framing.Fixed(5), Framing.Chunked)) {
            val wire = if (framing is Framing.Fixed) "hello".toByteArray() else chunked("hello".toByteArray(), 2)
            val inbound = MemoryInbound(wire, intArrayOf(3))
            val reader = BodyReader(inbound, head(framing, expectContinue = true))
            inbound.continues shouldBe 0
            String(reader.readAll(Long.MAX_VALUE)) shouldBe "hello"
            inbound.continues shouldBe 1
            reader.mustClose(0) shouldBe false
        }
    }

    @Test
    fun `a body cut short by the end of the connection is malformed`() {
        val fixed = BodyReader(MemoryInbound("short".toByteArray(), intArrayOf(2)), head(Framing.Fixed(100)))
        shouldThrow<MalformedBodyException> { fixed.readAll(Long.MAX_VALUE) }
        fixed.mustClose(Long.MAX_VALUE) shouldBe true
        val wire = chunked(bytes(300), 100)
        for (cut in listOf(10, 150, wire.size - 1)) {
            val reader = BodyReader(MemoryInbound(wire.copyOf(cut), intArrayOf(7)), head(Framing.Chunked))
            shouldThrow<MalformedBodyException> { reader.readAll(Long.MAX_VALUE) }
        }
    }

    @Test
    fun `readAll after reads in part returns exactly the rest, and nothing for no body`() {
        val reader = BodyReader(MemoryInbound(bytes(100), intArrayOf(7)), head(Framing.Fixed(100)))
        val first = ByteArray(30)
        var at = 0
        while (at < first.size) at += reader.read(first, at, first.size - at, Long.MAX_VALUE)
        val rest = reader.readAll(Long.MAX_VALUE)
        rest.size shouldBe 70
        (first + rest).contentEquals(bytes(100)) shouldBe true
        reader.readAll(Long.MAX_VALUE).size shouldBe 0
        BodyReader(MemoryInbound(ByteArray(0)), head(Framing.None)).readAll(Long.MAX_VALUE).size shouldBe 0
        // Reads in part count towards the limit.
        val counted = BodyReader(MemoryInbound(bytes(100), intArrayOf(7)), head(Framing.Fixed(100)))
        counted.read(first, 0, 30, Long.MAX_VALUE)
        shouldThrow<BodyTooLargeException> { counted.readAll(99) }
    }

    @Test
    fun `a large declared length is not allocated before its bytes arrive`() {
        // A client may declare a length it never sends: the array starts at a megabyte, not at the declared size.
        val reader = BodyReader(MemoryInbound(bytes(10)), head(Framing.Fixed(1L shl 30)))
        val before = allocated()
        shouldThrow<MalformedBodyException> { reader.readAll(Long.MAX_VALUE) }
        (allocated() - before) shouldBeLessThan (4L shl 20)
    }

    @Test
    fun `reading a small body whole allocates little more than the body`() {
        fun readers(framing: Framing, wire: ByteArray) =
            List(ROUNDS) { BodyReader(MemoryInbound(wire, lent = 64), head(framing)) }

        val cases = listOf(
            // One array of 2 bytes: 24 with its header.
            Triple("fixed", 64, { readers(Framing.Fixed(2), "20".toByteArray()) }),
            // One of 64 bytes, trimmed to one of 2: 104.
            Triple("chunked", 256, { readers(Framing.Chunked, "2\r\n20\r\n0\r\n\r\n".toByteArray()) }),
        )
        for ((name, most, make) in cases) {
            // Warmed first, so what is measured is the read and not the JIT or class loading.
            repeat(WARM) { for (reader in make()) reader.readAll(Long.MAX_VALUE).size shouldBe 2 }
            val each = make()
            val before = allocated()
            for (reader in each) reader.readAll(Long.MAX_VALUE)
            val perRead = (allocated() - before) / ROUNDS
            withClue("$name allocated $perRead bytes a read") { perRead.toInt() shouldBeLessThan most }
        }
    }

    private fun allocated(): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }

    private companion object {
        const val ROUNDS = 2000
        const val WARM = 10
    }
}
