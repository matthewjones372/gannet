package io.github.matthewjones372.gannet.soak

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Latencies in microseconds, log-linear: exact below 64 µs, then 32 buckets per power of two, so a percentile is
 * within about 3% of the truth. Recorded into from any number of threads without a lock; [cut] hands back what an
 * interval collected and starts the next.
 */
internal class Histogram {
    private val current = AtomicReference(AtomicLongArray(BUCKETS))

    fun record(nanos: Long) {
        current.get().incrementAndGet(index(maxOf(0L, nanos / NANOS_PER_MICRO)))
    }

    /** What was recorded since the last cut. A record racing the cut may land in either interval. */
    fun cut(): Snapshot {
        val taken = current.getAndSet(AtomicLongArray(BUCKETS))
        return Snapshot(LongArray(BUCKETS) { taken.get(it) })
    }

    /** One interval's latencies. */
    class Snapshot(private val counts: LongArray) {
        val count: Long = counts.sum()

        /** The [p]th percentile, 0 to 100, in microseconds; zero for an empty interval. */
        fun percentile(p: Double): Long {
            if (count == 0L) return 0L
            val rank = maxOf(1L, kotlin.math.ceil(count * p / PERCENT).toLong())
            var seen = 0L
            for (i in counts.indices) {
                seen += counts[i]
                if (seen >= rank) return value(i)
            }
            return value(counts.size - 1)
        }

        fun merge(other: Snapshot) = Snapshot(LongArray(BUCKETS) { counts[it] + other.counts[it] })
    }

    companion object {
        private const val SUB_BITS = 5
        private const val SUB = 1 shl SUB_BITS
        private const val LINEAR = SUB * 2
        private const val BUCKETS = 64 * SUB
        private const val NANOS_PER_MICRO = 1000L
        private const val PERCENT = 100.0

        val EMPTY = Snapshot(LongArray(BUCKETS))

        fun index(micros: Long): Int {
            if (micros < LINEAR) return micros.toInt()
            val exponent = 63 - java.lang.Long.numberOfLeadingZeros(micros)
            val shift = exponent - SUB_BITS
            val top = (micros ushr shift).toInt()
            return minOf(BUCKETS - 1, (shift + 1) * SUB + top - SUB)
        }

        /** The middle of bucket [index]'s range. */
        fun value(index: Int): Long {
            if (index < LINEAR) return index.toLong()
            val shift = index / SUB - 1
            val top = (index % SUB + SUB).toLong()
            return (top shl shift) + (1L shl shift) / 2
        }
    }
}
