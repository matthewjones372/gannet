package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * A carrier's small send buffers, carved from huge-page slabs: every connection's first two send buffers come from
 * here, packed together, instead of a separate allocation each scattered over the address space. A response that
 * outgrows one moves to a buffer of its own.
 */
final class SendSlots {
    static final int SIZE = 2048;
    private static final int PER_SLAB = (2 << 20) / SIZE;

    private MemorySegment[] free = new MemorySegment[PER_SLAB];
    private int freeCount;
    private Sys.Mapping[] slabs = new Sys.Mapping[4];
    private int slabCount;

    MemorySegment take() {
        if (freeCount == 0) {
            Sys.Mapping slab = Sys.hugeSlab((long) SIZE * PER_SLAB);
            if (slabCount == slabs.length) slabs = Arrays.copyOf(slabs, slabCount * 2);
            slabs[slabCount++] = slab;
            // Highest first, so slots are handed out in address order.
            for (int i = PER_SLAB - 1; i >= 0; i--) give(slab.segment().asSlice((long) i * SIZE, SIZE));
        }
        return free[--freeCount];
    }

    /** Takes back [buffer] if it is one of these slots; anything else is left to the GC. */
    void give(MemorySegment buffer) {
        if (buffer == null || buffer.byteSize() != SIZE) return;
        if (freeCount == free.length) free = Arrays.copyOf(free, freeCount * 2);
        free[freeCount++] = buffer;
    }

    /** Once nothing can be sending from a slot any more. */
    void free() {
        for (int i = 0; i < slabCount; i++) slabs[i].unmap();
        slabCount = 0;
        freeCount = 0;
    }
}
