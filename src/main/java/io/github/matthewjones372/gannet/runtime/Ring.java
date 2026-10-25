package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * One io_uring, the fast [Engine], owned by the thread that made it: submissions are queued with [prep] and sent,
 * with completions collected, by [enter]. Set up single-issuer with deferred task work, so completions are only
 * processed when that thread asks for them, never on an interrupt.
 */
final class Ring extends Engine {
    private static final int SETUP_CQSIZE = 1 << 3;
    private static final int SETUP_SINGLE_ISSUER = 1 << 12;
    private static final int SETUP_DEFER_TASKRUN = 1 << 13;
    private static final long ENTER_GETEVENTS = 1;
    private static final long ENTER_REGISTERED_RING = 1 << 4;
    private static final int REGISTER_RING_FDS = 20;

    private static final VarHandle INT = JAVA_INT.varHandle();

    private final int fd;
    /** What io_uring_enter is given: the ring's registered index, which spares the kernel a descriptor lookup. */
    private long enterFd;
    private long enterFlags = ENTER_GETEVENTS;
    private final MemorySegment sq;
    private final MemorySegment cq;
    private final MemorySegment sqes;
    private final long sqHead;
    private final long sqTail;
    private final long cqHead;
    private final long cqTail;
    private final long cqes;
    private final int sqMask;
    private final int sqEntries;
    private final int cqMask;

    private int tail;
    private int kernelHead;
    private int head;
    private int queued;

    /**
     * A ring of [entries] submissions and [completionEntries] completions.
     *
     * @throws UnsupportedOperationException when the kernel refuses io_uring itself, with the reason in its message
     */
    Ring(int entries, int completionEntries) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(120); // struct io_uring_params
            p.set(JAVA_INT, 4, completionEntries);
            p.set(JAVA_INT, 8, SETUP_CQSIZE | SETUP_SINGLE_ISSUER | SETUP_DEFER_TASKRUN);
            int r = Sys.setup(entries, p);
            if (r < 0) throw new UnsupportedOperationException(refusal(-r));
            fd = r;
            sqEntries = p.get(JAVA_INT, 0);
            int cqEntries = p.get(JAVA_INT, 4);
            boolean singleMmap = (p.get(JAVA_INT, 20) & 1) != 0;
            // sq_off at 40: head, tail, ring_mask, ring_entries, flags, dropped, array. cq_off at 80: head, tail,
            // ring_mask, ring_entries, overflow, cqes.
            long array = p.get(JAVA_INT, 64);
            long sqSize = array + 4L * sqEntries;
            long cqSize = p.get(JAVA_INT, 100) + 16L * cqEntries;
            sq = Sys.mmap(singleMmap ? Math.max(sqSize, cqSize) : sqSize, fd, 0L);
            cq = singleMmap ? sq : Sys.mmap(cqSize, fd, 0x8000000L);
            sqes = Sys.mmap(64L * sqEntries, fd, 0x10000000L);
            sqHead = p.get(JAVA_INT, 40);
            sqTail = p.get(JAVA_INT, 44);
            sqMask = sq.get(JAVA_INT, p.get(JAVA_INT, 48));
            cqHead = p.get(JAVA_INT, 80);
            cqTail = p.get(JAVA_INT, 84);
            cqMask = cq.get(JAVA_INT, p.get(JAVA_INT, 88));
            cqes = p.get(JAVA_INT, 100);
            // The index array maps each slot to itself, once, so a submission is just the entry and the tail.
            for (int i = 0; i < sqEntries; i++) sq.set(JAVA_INT, array + 4L * i, i);
            tail = sq.get(JAVA_INT, sqTail);
            kernelHead = sq.get(JAVA_INT, sqHead);
            head = cq.get(JAVA_INT, cqHead);
            enterFd = fd;
            MemorySegment update = arena.allocate(16); // struct io_uring_rsrc_update: offset, resv, data
            update.set(JAVA_INT, 0, -1);
            update.set(JAVA_LONG, 8, fd);
            if (tryRegister(REGISTER_RING_FDS, update, 1) == 1) {
                enterFd = update.get(JAVA_INT, 0);
                enterFlags |= ENTER_REGISTERED_RING;
            }
        }
    }

    private static String refusal(int errno) {
        return switch (errno) {
            case Sys.EPERM -> "io_uring_setup was refused (EPERM): io_uring is disabled by kernel.io_uring_disabled or "
                + "blocked by a seccomp profile, as Docker's default one does";
            case Sys.ENOSYS -> "io_uring_setup does not exist (ENOSYS): the kernel was built without io_uring, or a "
                + "seccomp profile hides it";
            case 22 -> "io_uring_setup rejected its parameters (EINVAL): single-issuer deferred task work needs Linux "
                + "6.1 or later, and the completion queue must be at least as large as the submission queue";
            default -> "io_uring_setup failed: errno " + errno;
        };
    }

    @Override
    void prep(byte op, int sqeFlags, int ioprio, int fd, long off, long addr, int len, int opFlags, int bufGroup,
              int fileIndex, long userData) {
        // The kernel's head is read only when the last value seen says the ring may be full.
        if (tail - kernelHead == sqEntries) {
            kernelHead = (int) INT.getAcquire(sq, sqHead);
            if (tail - kernelHead == sqEntries) {
                enter(false);
                kernelHead = (int) INT.getAcquire(sq, sqHead);
            }
        }
        long e = (long) (tail & sqMask) * 64;
        MemorySegment s = sqes;
        // opcode, flags, ioprio, fd | off | addr | len, op flags | user_data | buf group, personality, file index |
        // addr3 | pad. No op here sets addr3 or the pad, so those stay as the kernel zeroed them.
        s.set(JAVA_LONG, e, (op & 0xffL) | ((sqeFlags & 0xffL) << 8) | ((ioprio & 0xffffL) << 16) | ((long) fd << 32));
        s.set(JAVA_LONG, e + 8, off);
        s.set(JAVA_LONG, e + 16, addr);
        s.set(JAVA_LONG, e + 24, (len & 0xffffffffL) | ((long) opFlags << 32));
        s.set(JAVA_LONG, e + 32, userData);
        s.set(JAVA_LONG, e + 40, (bufGroup & 0xffffL) | ((long) fileIndex << 32));
        tail++;
        queued++;
    }

    @Override
    void enter(boolean wait) {
        INT.setRelease(sq, sqTail, tail);
        int submitting = queued;
        try {
            long r = wait
                ? (long) Sys.SYSCALL7.invokeExact(Sys.IO_URING_ENTER, enterFd, (long) submitting, 1L, enterFlags, 0L, 0L)
                : (long) Sys.SYSCALL7_CRITICAL.invokeExact(Sys.IO_URING_ENTER, enterFd, (long) submitting, 0L,
                    enterFlags, 0L, 0L);
            // What the kernel did not take stays queued; an interrupted call took nothing.
            queued = r < 0 ? submitting : submitting - (int) r;
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    @Override
    long tryRegister(int opcode, MemorySegment arg, int count) {
        try {
            return (long) Sys.SYSCALL7.invokeExact(Sys.IO_URING_REGISTER, (long) fd, (long) opcode, arg.address(),
                (long) count, 0L, 0L);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    /**
     * A sparse table of [count] direct descriptors, which accepts fill and which receives and sends then name by
     * index, sparing the kernel a descriptor lookup and reference per request. False if the kernel refuses it.
     */
    @Override
    boolean registerFiles(int count) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment reg = arena.allocate(32); // struct io_uring_rsrc_register: nr, flags, resv2, data, tags
            reg.set(JAVA_INT, 0, count);
            reg.set(JAVA_INT, 4, 1); // IORING_RSRC_REGISTER_SPARSE
            return tryRegister(13 /* IORING_REGISTER_FILES2 */, reg, 32) >= 0;
        }
    }

    @Override
    int reap(Completions to) {
        int end = (int) INT.getAcquire(cq, cqTail);
        int h = head;
        int n = end - h;
        while (h != end) {
            long e = cqes + (long) (h & cqMask) * 16;
            to.complete(cq.get(JAVA_LONG, e), cq.get(JAVA_INT, e + 8), cq.get(JAVA_INT, e + 12));
            h++;
        }
        head = h;
        INT.setRelease(cq, cqHead, h);
        return n;
    }

    /** Ends the ring: the kernel cancels what is still in flight and drops its direct descriptors. */
    @Override
    void close() {
        Sys.munmap(sqes.address(), sqes.byteSize());
        if (cq != sq) Sys.munmap(cq.address(), cq.byteSize());
        Sys.munmap(sq.address(), sq.byteSize());
        Sys.close(fd);
    }
}
