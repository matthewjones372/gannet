package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The [Engine] for where io_uring is refused: the same submissions carried out in user space over an epoll instance
 * and non-blocking sockets. An operation is tried at once when [enter] reaches it; one that would block waits for its
 * descriptor to become ready, and is tried again then. Its completion is posted as io_uring would post it, so the
 * carrier above cannot tell the two apart: a multishot receive takes buffers from the carrier's [BufferRing] and posts
 * one completion per arrival with more to come, a cancel ends what it names with ECANCELED, and a timeout posts ETIME.
 *
 * Level-triggered, and lazy about interest: a descriptor stays registered for what it last waited for until an event
 * comes that nothing waits for, which saves two epoll_ctl calls for every accept, and for every receive on a
 * keep-alive connection that pauses.
 */
final class Epoll extends Engine {
    private static final int EPOLLIN = 0x1;
    private static final int EPOLLOUT = 0x4;
    private static final int EPOLLERR = 0x8;
    private static final int EPOLLHUP = 0x10;
    private static final int CTL_ADD = 1;
    private static final int CTL_DEL = 2;
    private static final int CTL_MOD = 3;

    private static final int ENOENT = 2;
    private static final int EINTR = 4;
    private static final int EAGAIN = 11;
    private static final int EEXIST = 17;
    private static final int EINVAL = 22;
    private static final int ETIME = 62;
    private static final int ECONNABORTED = 103;
    private static final int ENOBUFS = 105;
    private static final int ECANCELED = 125;

    private static final int CQE_F_BUFFER = 1;
    private static final int CQE_F_MORE = 2;
    private static final int IOSQE_BUFFER_SELECT = 1 << 5;
    private static final int SOCK_NONBLOCK = 0x800;
    private static final int SOCK_CLOEXEC = 0x80000;
    private static final int O_NONBLOCK = 0x800;
    private static final int F_SETFL = 4;
    private static final int MSG_DONTWAIT = 0x40;
    private static final int REGISTER_PBUF_RING = 22;
    private static final int UNREGISTER_PBUF_RING = 23;
    /** Receives one ready multishot receive makes before the others get their turn. */
    private static final int RECV_BURST = 8;
    private static final int MAX_EVENTS = 256;

    // struct epoll_event is packed on x86-64, so its data follows the events word; arm64 aligns it to eight.
    private static final boolean PACKED = !"aarch64".equals(System.getProperty("os.arch"));
    private static final int EVENT_SIZE = PACKED ? 12 : 16;
    private static final int DATA_OFFSET = PACKED ? 4 : 8;
    private static final ValueLayout.OfLong DATA = JAVA_LONG.withByteAlignment(PACKED ? 4 : 8);

    private static final MethodHandle EPOLL_CREATE1 =
        Sys.libc("epoll_create1", FunctionDescriptor.of(JAVA_INT, JAVA_INT), Sys.ERRNO_OPTION);
    private static final MethodHandle EPOLL_CTL = Sys.libc("epoll_ctl",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), Sys.ERRNO_OPTION);
    private static final MethodHandle EPOLL_WAIT = Sys.libc("epoll_wait",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Sys.ERRNO_OPTION);
    // Addresses go as longs: the same register either way on both architectures, and no segment to make per call.
    private static final MethodHandle ACCEPT4 =
        fast("accept4", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle RECV =
        fast("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SEND =
        fast("send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle READ =
        fast("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle SHUTDOWN = fast("shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle FCNTL = Sys.libc("fcntl",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2),
        Sys.ERRNO_OPTION);

    /**
     * A call on a non-blocking descriptor, which returns at once: no thread-state transition, which a call that may
     * block must have.
     */
    private static MethodHandle fast(String name, FunctionDescriptor descriptor) {
        return Sys.libc(name, descriptor, Sys.ERRNO_OPTION, Linker.Option.critical(false));
    }

    /** One submission: queued, then tried, then (if it would block) waiting on its descriptor or its deadline. */
    private static final class Op {
        byte op;
        int sqeFlags;
        int fd;
        long addr;
        int len;
        int opFlags;
        long userData;
        long deadline;
        Op nextFree;
    }

    /** A descriptor as the epoll instance has it: what waits to read and to write, and the interest registered. */
    private static final class Watch {
        Op reader;
        Op writer;
        int interest;
        boolean nonblocking;
    }

    private final Arena arena = Arena.ofConfined();
    private final int epfd;
    private final MemorySegment state;
    private final MemorySegment events;
    private final MemorySegment ctlEvent;

    private Op[] queue = new Op[64];
    private int queued;
    private Op freeOps;

    private long[] cqData = new long[64];
    private int[] cqResult = new int[64];
    private int[] cqFlags = new int[64];
    private int cqCount;

    private Watch[] watches = new Watch[1024];
    /** What a cancel can name: every operation waiting, by its user data. */
    private final HashMap<Long, Op> waiting = new HashMap<>();
    private final ArrayList<Op> timers = new ArrayList<>();

    // The carrier's provided buffers, read as the kernel would read them: entries up to the tail its recycle bumps.
    private MemorySegment buffers;
    private int bufferMask;
    private int bufferHead;

    /**
     * An epoll instance for the calling thread.
     *
     * @throws UnsupportedOperationException when the kernel refuses epoll, with the reason in its message
     */
    Epoll() {
        try {
            state = Sys.errnoState(arena);
            events = arena.allocate((long) EVENT_SIZE * MAX_EVENTS, 8);
            ctlEvent = arena.allocate(16, 8);
            int fd = (int) EPOLL_CREATE1.invokeExact(state, 0x80000 /* EPOLL_CLOEXEC */);
            if (fd < 0) throw new UnsupportedOperationException("epoll_create1 failed: errno " + Sys.errno(state));
            epfd = fd;
        } catch (Throwable t) {
            arena.close();
            throw Sys.rethrow(t);
        }
    }

    /** Null if an epoll instance can be made here, else why not. */
    static String probe() {
        try {
            new Epoll().close();
            return null;
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        }
    }

    @Override
    void prep(byte op, int sqeFlags, int ioprio, int fd, long off, long addr, int len, int opFlags, int bufGroup,
              int fileIndex, long userData) {
        Op o = freeOps;
        if (o != null) freeOps = o.nextFree;
        else o = new Op();
        o.op = op;
        o.sqeFlags = sqeFlags;
        o.fd = fd;
        o.addr = addr;
        o.len = len;
        o.opFlags = opFlags;
        o.userData = userData;
        if (queued == queue.length) queue = Arrays.copyOf(queue, queued * 2);
        queue[queued++] = o;
    }

    @Override
    boolean hasQueued() {
        return queued > 0;
    }

    @Override
    void enter(boolean wait) {
        // Nothing [execute] does queues more, so the count is fixed for the pass.
        for (int i = 0; i < queued; i++) {
            Op o = queue[i];
            queue[i] = null;
            execute(o);
        }
        queued = 0;
        poll(wait && cqCount == 0 ? timeoutMillis() : 0);
        expireTimers();
    }

    @Override
    int reap(Completions to) {
        int n = cqCount;
        for (int i = 0; i < n; i++) to.complete(cqData[i], cqResult[i], cqFlags[i]);
        cqCount = 0;
        return n;
    }

    @Override
    long tryRegister(int opcode, MemorySegment arg, int count) {
        if (opcode == REGISTER_PBUF_RING) {
            int entries = arg.get(JAVA_INT, 8);
            buffers = MemorySegment.ofAddress(arg.get(JAVA_LONG, 0)).reinterpret(16L * entries);
            bufferMask = entries - 1;
            bufferHead = 0;
            return 0;
        }
        if (opcode == UNREGISTER_PBUF_RING) {
            buffers = null;
            return 0;
        }
        return -1;
    }

    /** No direct descriptors: every connection is an ordinary one. */
    @Override
    boolean registerFiles(int count) {
        return false;
    }

    @Override
    int waiting() {
        return waiting.size();
    }

    @Override
    void close() {
        Sys.close(epfd);
        arena.close();
    }

    // ------------------------------------------------------------------ carrying out a submission

    private void execute(Op o) {
        switch (o.op) {
            case OP_ACCEPT, OP_RECV, OP_READ, OP_SEND -> {
                if (o.op == OP_ACCEPT) nonblocking(o.fd);
                if (attempt(o)) release(o);
                else wait(o);
            }
            case OP_SHUTDOWN -> {
                int r = shutdown(o.fd, o.len);
                post(o.userData, r < 0 ? -Sys.errno(state) : 0, 0);
                release(o);
            }
            case OP_CLOSE -> closeNow(o);
            case OP_ASYNC_CANCEL -> cancel(o);
            case OP_TIMEOUT -> {
                MemorySegment spec = MemorySegment.ofAddress(o.addr).reinterpret(16);
                o.deadline = System.nanoTime() + spec.get(JAVA_LONG, 0) * 1_000_000_000L + spec.get(JAVA_LONG, 8);
                timers.add(o);
                waiting.put(o.userData, o);
            }
            default -> {
                post(o.userData, -EINVAL, 0);
                release(o);
            }
        }
    }

    /** Tries [o] once more; true when it has posted its last completion, false when it has to wait again. */
    private boolean attempt(Op o) {
        return switch (o.op) {
            case OP_ACCEPT -> accept(o);
            case OP_RECV -> (o.sqeFlags & IOSQE_BUFFER_SELECT) != 0 ? receiveMultishot(o) : receiveOnce(o);
            case OP_READ -> receiveOnce(o);
            default -> sendOnce(o);
        };
    }

    private boolean accept(Op o) {
        while (true) {
            int fd = accept4(o.fd);
            if (fd >= 0) return finish(o, fd);
            int errno = Sys.errno(state);
            // A connection reset before it was taken, or a signal: neither is this accept's to report.
            if (errno == EAGAIN) return false;
            if (errno != EINTR && errno != ECONNABORTED) return finish(o, -errno);
        }
    }

    /**
     * A buffer-selecting receive, which the carrier only ever makes multishot: each arrival into the next provided
     * buffer, posted with more to come while it stays armed. It ends at the end of the stream, on an error, or when
     * the buffers run out, as io_uring's does.
     */
    private boolean receiveMultishot(Op o) {
        for (int i = 0; i < RECV_BURST; i++) {
            if (buffers == null || (short) (buffers.get(JAVA_SHORT, 14) - bufferHead) == 0) {
                return finish(o, -ENOBUFS);
            }
            long e = 16L * (bufferHead & bufferMask);
            long address = buffers.get(JAVA_LONG, e);
            int size = buffers.get(JAVA_INT, e + 8);
            int bid = buffers.get(JAVA_SHORT, e + 12) & 0xffff;
            long n = recv(o.fd, address, size);
            if (n > 0) {
                bufferHead++;
                post(o.userData, (int) n, CQE_F_BUFFER | CQE_F_MORE | (bid << 16));
                // Short of the buffer: the socket is most likely empty, and the next try would only say so.
                if (n < size) return false;
                continue;
            }
            if (n == 0) return finish(o, 0);
            int errno = Sys.errno(state);
            if (errno == EAGAIN) return false;
            if (errno != EINTR) return finish(o, -errno);
        }
        return false;
    }

    /** One receive, or a read for the eventfd, into the submission's own memory. */
    private boolean receiveOnce(Op o) {
        while (true) {
            long n = o.op == OP_RECV ? recv(o.fd, o.addr, o.len) : read(o.fd, o.addr, o.len);
            if (n >= 0) return finish(o, (int) n);
            int errno = Sys.errno(state);
            if (errno == EAGAIN) return false;
            if (errno != EINTR) return finish(o, -errno);
        }
    }

    /** One send: what the socket takes is posted, all of it or part, and the carrier sends the rest. */
    private boolean sendOnce(Op o) {
        while (true) {
            long n = send(o.fd, o.addr, o.len, o.opFlags | MSG_DONTWAIT);
            if (n >= 0) return finish(o, (int) n);
            int errno = Sys.errno(state);
            if (errno == EAGAIN) return false;
            if (errno != EINTR) return finish(o, -errno);
        }
    }

    private boolean finish(Op o, int result) {
        post(o.userData, result, 0);
        return true;
    }

    /**
     * Closes [o]'s descriptor, first ending whatever still waits on it: closing it takes it out of the epoll set, so
     * nothing would ever wake those, where io_uring would have them hold the file open.
     */
    private void closeNow(Op o) {
        Watch w = o.fd < watches.length ? watches[o.fd] : null;
        if (w != null) {
            if (w.reader != null) cancelled(w.reader);
            if (w.writer != null) cancelled(w.writer);
            w.reader = w.writer = null;
            if (w.interest != 0) ctl(CTL_DEL, o.fd, 0);
            w.interest = 0;
            w.nonblocking = false;
        }
        Sys.close(o.fd);
        post(o.userData, 0, 0);
        release(o);
    }

    private void cancel(Op o) {
        Op target = waiting.get(o.addr);
        if (target == null) {
            post(o.userData, -ENOENT, 0);
        } else {
            if (target.op == OP_TIMEOUT) {
                timers.remove(target);
            } else {
                Watch w = watches[target.fd];
                if (w.reader == target) w.reader = null;
                else w.writer = null;
            }
            cancelled(target);
            post(o.userData, 0, 0);
        }
        release(o);
    }

    private void cancelled(Op o) {
        waiting.remove(o.userData);
        post(o.userData, -ECANCELED, 0);
        release(o);
    }

    // ------------------------------------------------------------------ waiting

    private void wait(Op o) {
        Watch w = watch(o.fd);
        if (o.op == OP_SEND) w.writer = o;
        else w.reader = o;
        waiting.put(o.userData, o);
        int want = wanted(w);
        if ((w.interest & want) != want) interest(o.fd, w, want);
    }

    private Watch watch(int fd) {
        if (fd >= watches.length) watches = Arrays.copyOf(watches, Math.max(fd + 1, watches.length * 2));
        Watch w = watches[fd];
        if (w == null) watches[fd] = w = new Watch();
        return w;
    }

    private static int wanted(Watch w) {
        return (w.reader != null ? EPOLLIN : 0) | (w.writer != null ? EPOLLOUT : 0);
    }

    /** Registers [want] for [fd], adding or modifying as the kernel's set turns out to need, or removes it for none. */
    private void interest(int fd, Watch w, int want) {
        if (want == 0) {
            ctl(CTL_DEL, fd, 0);
        } else if (w.interest == 0) {
            // A descriptor closed behind the engine's back may still be registered under its reused number.
            if (ctl(CTL_ADD, fd, want) == -EEXIST) ctl(CTL_MOD, fd, want);
        } else if (ctl(CTL_MOD, fd, want) == -ENOENT) {
            ctl(CTL_ADD, fd, want);
        }
        w.interest = want;
    }

    private int ctl(int op, int fd, int mask) {
        ctlEvent.set(JAVA_INT, 0, mask);
        ctlEvent.set(DATA, DATA_OFFSET, fd);
        try {
            int r = (int) EPOLL_CTL.invokeExact(state, epfd, op, fd, ctlEvent);
            return r < 0 ? -Sys.errno(state) : 0;
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    private void poll(int timeout) {
        int n;
        try {
            n = (int) EPOLL_WAIT.invokeExact(state, epfd, events, MAX_EVENTS, timeout);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
        for (int i = 0; i < n; i++) {
            long e = (long) EVENT_SIZE * i;
            int ready = events.get(JAVA_INT, e);
            int fd = (int) events.get(DATA, e + DATA_OFFSET);
            Watch w = fd < watches.length ? watches[fd] : null;
            if (w == null) continue;
            if (w.reader != null && (ready & (EPOLLIN | EPOLLERR | EPOLLHUP)) != 0 && attempt(w.reader)) {
                Op o = w.reader;
                w.reader = null;
                waiting.remove(o.userData);
                release(o);
            }
            if (w.writer != null && (ready & (EPOLLOUT | EPOLLERR | EPOLLHUP)) != 0 && attempt(w.writer)) {
                Op o = w.writer;
                w.writer = null;
                waiting.remove(o.userData);
                release(o);
            }
            // Something nobody waits for any more: stop hearing about it, or level triggering repeats it every pass.
            int want = wanted(w);
            if ((ready & ~want) != 0) interest(fd, w, want);
        }
    }

    /** Milliseconds to the nearest timer, rounded up so it has passed on waking; -1 for none. */
    private int timeoutMillis() {
        if (timers.isEmpty()) return -1;
        long nearest = Long.MAX_VALUE;
        for (Op t : timers) nearest = Math.min(nearest, t.deadline);
        long left = nearest - System.nanoTime();
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (left + 999_999) / 1_000_000);
    }

    private void expireTimers() {
        if (timers.isEmpty()) return;
        long now = System.nanoTime();
        for (int i = timers.size() - 1; i >= 0; i--) {
            Op t = timers.get(i);
            if (now - t.deadline >= 0) {
                timers.remove(i);
                waiting.remove(t.userData);
                post(t.userData, -ETIME, 0);
                release(t);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Makes [fd] non-blocking, once: a listening socket comes blocking, and an accept must never wait in the call. */
    private void nonblocking(int fd) {
        Watch w = watch(fd);
        if (w.nonblocking) return;
        try {
            int ignored = (int) FCNTL.invokeExact(state, fd, F_SETFL, O_NONBLOCK);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
        w.nonblocking = true;
    }

    private void post(long userData, int result, int flags) {
        if (cqCount == cqData.length) {
            int grown = cqCount * 2;
            cqData = Arrays.copyOf(cqData, grown);
            cqResult = Arrays.copyOf(cqResult, grown);
            cqFlags = Arrays.copyOf(cqFlags, grown);
        }
        cqData[cqCount] = userData;
        cqResult[cqCount] = result;
        cqFlags[cqCount] = flags;
        cqCount++;
    }

    private void release(Op o) {
        o.nextFree = freeOps;
        freeOps = o;
    }

    private int accept4(int fd) {
        try {
            return (int) ACCEPT4.invokeExact(state, fd, 0L, 0L, SOCK_NONBLOCK | SOCK_CLOEXEC);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    private long recv(int fd, long address, long length) {
        try {
            return (long) RECV.invokeExact(state, fd, address, length, 0);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    private long read(int fd, long address, long length) {
        try {
            return (long) READ.invokeExact(state, fd, address, length);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    private long send(int fd, long address, long length, int flags) {
        try {
            return (long) SEND.invokeExact(state, fd, address, length, flags);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }

    private int shutdown(int fd, int how) {
        try {
            return (int) SHUTDOWN.invokeExact(state, fd, how);
        } catch (Throwable t) {
            throw Sys.rethrow(t);
        }
    }
}
