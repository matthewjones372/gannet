package io.github.matthewjones372.gannet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Byte searches eight bytes at a time: each word is tested for the byte with one subtraction and two masks, so a
 * request line or header is crossed in a few steps instead of one per byte. In Java because Kotlin cannot call a
 * [VarHandle]'s signature-polymorphic methods with a primitive return.
 */
final class Scan {
    private Scan() {}

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    /** The first index of [c] in [b] from [from] (inclusive) to [to] (exclusive), or -1. */
    static int indexOf(byte[] b, int c, int from, int to) {
        long pattern = (c & 0xffL) * ONES;
        int i = from;
        while (i + 8 <= to) {
            long x = (long) LONGS.get(b, i) ^ pattern;
            long found = (x - ONES) & ~x & HIGHS;
            if (found != 0) return i + (Long.numberOfTrailingZeros(found) >>> 3);
            i += 8;
        }
        for (; i < to; i++) if (b[i] == (byte) c) return i;
        return -1;
    }
}
