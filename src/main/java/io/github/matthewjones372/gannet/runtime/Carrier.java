package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * One core's runtime: a platform thread with its own [Engine] (an io_uring, or epoll where that is refused) and its own
 * listening socket. It runs every fiber that
 * is ready, then sends all their queued I/O in one system call and collects what has completed, and again. A fiber
 * never leaves the carrier that started it; other threads reach one only through [execute].
 */
final class Carrier extends Thread implements Engine.Completions {
    // A request's user_data: the fiber's id in the low 32 bits, what the request was above it.
    static final long WAKE = 0L;
    static final long RECV = 1L << 32;
    static final long SEND = 2L << 32;
    static final long CLOSE = 3L << 32;
    static final long CANCEL = 4L << 32;
    static final long OWN_RECV = 5L << 32;
    // The carrier's own requests, which belong to no fiber.
    private static final long EVENT = 6L << 32;
    private static final long TICK = 7L << 32;
    private static final long QUIET = 8L << 32;

    private static final int CQE_F_BUFFER = 1;
    private static final int CQE_F_MORE = 2;
    private static final int ENOBUFS = 105;
    private static final int ECANCELED = 125;
    private static final int EPIPE = 32;
    static final int SEND_RING = 2 << 20;
    static final int OWN_BUFFER = 16 << 10;

    /** How a carrier is sized, and whether it runs on [Epoll] rather than io_uring; see `GannetConfig`. */
    record Settings(int ringEntries, int completionEntries, int bufferCount, int bufferSize, int fixedFiles,
                    long tickNanos, int heldBuffers, int maxConnections, int maxHandshakes, boolean epoll) {}

    private final int listener;
    private final Settings settings;
    private final IntConsumer serve;
    /** Connections open across every carrier of the server. */
    private final AtomicInteger open;
    /** Connections on this carrier whose handshake has not finished. */
    private int handshakes;
    private final ArrayDeque<Fiber> ready = new ArrayDeque<>();
    private Fiber[] fibers = new Fiber[1024];
    private int[] free = new int[1024];
    private int freeCount;
    private int nextId;
    private int live;

    /** Completes once the ring is up, or with what stopped it coming up. */
    final CompletableFuture<Void> started = new CompletableFuture<>();

    private final ConcurrentLinkedQueue<Task> inbox = new ConcurrentLinkedQueue<>();
    /** Set by the first thread to signal the eventfd since the carrier last drained [inbox]. */
    private final AtomicBoolean signalled = new AtomicBoolean();
    /** Threads between checking [closed] and signalling, which the eventfd's close waits out. */
    private final AtomicInteger posting = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean stopRequested;
    private volatile long graceNanos;
    private int eventFd = -1;
    private boolean listenerClosed;

    private Arena arena;
    Engine ring;
    final SendSlots slots = new SendSlots();
    private Sys.Mapping sendRingMapping;
    /**
     * Where a write that can go out at once is copied to: bump-allocated, and started again from the beginning
     * whenever no send from it is in flight, which is after nearly every pass, as a send of a few hundred bytes
     * completes in the system call that submits it. So the bytes every connection sends pass through the same few
     * cache lines instead of a buffer per connection. While sends from it are still in flight it only fills up, and
     * once full, writes go to the connection's own buffers until it empties.
     */
    MemorySegment sendRing;
    long sendRingAddress;
    int sendRingHead;
    int ringSends;
    /** IOSQE_FIXED_FILE when connections are direct descriptors in the ring's file table, else 0. */
    int fileFlag;
    BufferRing buffers;
    Fiber current;

    private MemorySegment eventBuffer;
    private MemorySegment tickSpec;
    private Fiber acceptor;
    private boolean acceptorInAccept;
    /** The acceptor, when it is waiting out a failed accept or a full cap, until the next tick or [admit]. */
    private Fiber tickSleeper;
    boolean stopping;
    private boolean forced;
    private boolean eventArmed;
    private boolean tickArmed;
    /** Past the last fiber: the eventfd read and the timer are cancelled, not re-armed. */
    private boolean exiting;
    private long deadline;

    /**
     * A carrier that serves each connection accepted on [listener] by calling [serve] in a fiber of its own, counting
     * the server's open connections in [open].
     */
    Carrier(String name, int listener, Settings settings, AtomicInteger open, IntConsumer serve) {
        super(name);
        this.listener = listener;
        this.settings = settings;
        this.open = open;
        this.serve = serve;
    }

    @Override
    public void run() {
        try {
            open();
        } catch (Throwable t) {
            release();
            started.completeExceptionally(t);
            return;
        }
        started.complete(null);
        try {
            loop();
        } finally {
            release();
        }
    }

    private void open() {
        // Made here: a single-issuer ring, like an epoll engine, belongs to the thread that creates it.
        arena = Arena.ofConfined();
        ring = settings.epoll() ? new Epoll() : new Ring(settings.ringEntries(), settings.completionEntries());
        buffers = new BufferRing(ring, arena, settings.bufferCount(), settings.bufferSize());
        if (settings.fixedFiles() > 0 && ring.registerFiles(settings.fixedFiles())) fileFlag = 1;
        sendRingMapping = Sys.hugeSlab(SEND_RING);
        sendRing = sendRingMapping.segment();
        sendRingAddress = sendRing.address();
        eventFd = Sys.eventfd();
        eventBuffer = arena.allocate(8, 8);
        tickSpec = arena.allocate(16, 8); // struct __kernel_timespec
        tickSpec.set(JAVA_LONG, 0, settings.tickNanos() / 1_000_000_000L);
        tickSpec.set(JAVA_LONG, 8, settings.tickNanos() % 1_000_000_000L);
        armEvent();
        armTick();
        acceptor = spawn(this::acceptLoop);
    }

    /**
     * Accepts while the caps allow. Past either, the connection just accepted waits, unserved, and no other is
     * accepted, so the rest wait in the kernel's backlog, until a connection here ends or a handshake here finishes
     * ([admit]), or the next tick for a connection ending on another carrier.
     */
    private void acceptLoop() {
        while (!stopping) {
            acceptorInAccept = true;
            int fd = Io.accept(listener);
            acceptorInAccept = false;
            if (stopping) {
                if (fd >= 0) Io.closeNow(fd);
                break;
            }
            if (fd < 0) {
                // Out of descriptors, most likely: wait for connections to end rather than spin on the error.
                tickSleeper = current;
                Fiber.park();
                continue;
            }
            while (!stopping && !admissible()) {
                tickSleeper = current;
                Fiber.park();
            }
            if (stopping) {
                Io.closeNow(fd);
                break;
            }
            handshakes++;
            Fiber f = spawn(() -> {
                try {
                    serve.accept(fd);
                } finally {
                    endHandshake(current);
                    Io.finish(fd);
                    open.decrementAndGet();
                    admit();
                }
            });
            f.handshaking = true;
        }
        listenerClosed = true;
        Sys.close(listener);
    }

    private void loop() {
        while (true) {
            runReady();
            if (stopRequested && !stopping) beginStop();
            signalled.set(false);
            Task t;
            while ((t = inbox.poll()) != null) t.run();
            if (!ready.isEmpty()) {
                ring.enter(false);
                ring.reap(this);
                continue;
            }
            if (stopping && live == 0) {
                quiesce();
                return;
            }
            ring.enter(true);
            ring.reap(this);
        }
    }

    /**
     * Cancels the carrier's own requests and waits for them to end. Closing a ring tears it down asynchronously, so a
     * read still armed could complete into memory already freed and reused by then.
     */
    private void quiesce() {
        exiting = true;
        if (eventArmed) ring.prep(Engine.OP_ASYNC_CANCEL, 0, 0, -1, 0L, EVENT, 0, 0, 0, 0, QUIET);
        if (tickArmed) ring.prep(Engine.OP_ASYNC_CANCEL, 0, 0, -1, 0L, TICK, 0, 0, 0, 0, QUIET);
        while (eventArmed || tickArmed) {
            ring.enter(true);
            ring.reap(this);
        }
    }

    private void runReady() {
        Fiber f;
        while ((f = ready.poll()) != null) {
            current = f;
            boolean done;
            try {
                done = f.resume();
            } catch (Throwable t) {
                done = true;
            }
            current = null;
            if (done) ended(f);
        }
    }

    /**
     * Runs [task] on this carrier, from any thread: queued, and the carrier woken through its eventfd if it may be
     * waiting. Rejected, on the calling thread or the carrier, once the carrier has stopped.
     */
    void execute(Task task) {
        posting.incrementAndGet();
        try {
            if (closed) {
                task.reject();
                return;
            }
            inbox.add(task);
            if (Thread.currentThread() != this && !signalled.getAndSet(true)) Sys.signal(eventFd);
        } finally {
            posting.decrementAndGet();
        }
    }

    /** Resumes [f] if it is parked waiting to be woken. On the carrier only. */
    void wake(Fiber f) {
        if (f.waitingWake) {
            f.waitingWake = false;
            ready.add(f);
        }
    }

    /** Resumes [f] if it is parked waiting for data, which it then finds has not come. On the carrier only. */
    void wakeReader(Fiber f) {
        resumeReader(f);
    }

    /** Whether one more connection fits under both caps; if it does, it is counted in [open]. */
    private boolean admissible() {
        if (handshakes >= settings.maxHandshakes()) return false;
        while (true) {
            int n = open.get();
            if (n >= settings.maxConnections()) return false;
            if (open.compareAndSet(n, n + 1)) return true;
        }
    }

    /** [f]'s handshake is over, done or failed: it no longer counts against the cap. On the carrier only. */
    void endHandshake(Fiber f) {
        if (!f.handshaking) return;
        f.handshaking = false;
        handshakes--;
        admit();
    }

    /** Room may have opened under a cap: the acceptor, if it is waiting, looks again. */
    private void admit() {
        if (tickSleeper != null) {
            ready.add(tickSleeper);
            tickSleeper = null;
        }
    }

    /** Asks the carrier to stop accepting and end its connections, forcing them after [grace] nanoseconds. */
    void requestStop(long grace) {
        graceNanos = grace;
        stopRequested = true;
        execute(new Task() {
            @Override
            public void run() {}

            @Override
            public void reject() {}
        });
    }

    private void beginStop() {
        stopping = true;
        deadline = System.nanoTime() + graceNanos;
        if (acceptorInAccept) {
            ring.prep(Engine.OP_ASYNC_CANCEL, 0, 0, -1, 0L, WAKE | acceptor.id, 0, 0, 0, 0, QUIET);
        } else if (tickSleeper != null) {
            ready.add(tickSleeper);
            tickSleeper = null;
        }
        forEachSession(false);
    }

    private void forEachSession(boolean force) {
        for (int i = 0; i < nextId; i++) {
            Fiber f = fibers[i];
            if (f != null && f.session != null && !f.zombie) f.session.stop(force);
        }
    }

    private void tick() {
        long now = System.nanoTime();
        if (tickSleeper != null) {
            ready.add(tickSleeper);
            tickSleeper = null;
        }
        for (int i = 0; i < nextId; i++) {
            Fiber f = fibers[i];
            if (f != null && f.session != null && !f.zombie) f.session.tick(now);
        }
        if (stopping && !forced && now - deadline >= 0) {
            forced = true;
            forEachSession(true);
        }
    }

    private void armEvent() {
        eventArmed = true;
        ring.prep(Engine.OP_READ, 0, 0, eventFd, 0L, eventBuffer.address(), 8, 0, 0, 0, EVENT);
    }

    private void armTick() {
        tickArmed = true;
        ring.prep(Engine.OP_TIMEOUT, 0, 0, -1, 0L, tickSpec.address(), 1, 0, 0, 0, TICK);
    }

    @Override
    public void complete(long userData, int result, int flags) {
        long kind = userData & ~0xffffffffL;
        if (kind >= EVENT) {
            if (kind == EVENT) {
                eventArmed = false;
                if (!exiting) armEvent();
            } else if (kind == TICK) {
                tickArmed = false;
                if (!exiting) {
                    tick();
                    armTick();
                }
            }
            return;
        }
        Fiber f = fibers[(int) userData];
        if (kind == RECV) received(f, result, flags);
        else if (kind == SEND) sent(f, result);
        else {
            f.outstanding--;
            if (kind == OWN_RECV) {
                f.recvArmed = false;
                if (!f.zombie) {
                    f.arrived(result > 0 ? Fiber.OWN : -1, result);
                    resumeReader(f);
                }
            } else if (kind == WAKE) {
                f.result = result;
                ready.add(f);
            }
        }
        if (f.zombie && f.outstanding == 0) freeId(f);
    }

    private void received(Fiber f, int result, int flags) {
        boolean more = (flags & CQE_F_MORE) != 0;
        if (!more) {
            f.outstanding--;
            f.recvArmed = false;
            f.recvCancelling = false;
        }
        int bid = (flags & CQE_F_BUFFER) != 0 ? flags >>> 16 : -1;
        if (bid >= 0) buffers.lent();
        if (f.zombie) {
            if (bid >= 0) buffers.recycle(bid);
            return;
        }
        if (result == -ENOBUFS) {
            f.starved = true;
        } else if (result != -ECANCELED) {
            f.arrived(bid, result);
            // A reader that has fallen behind holds carrier buffers every other connection needs; stopping its
            // receive leaves the rest in the kernel, whose window then slows the sender.
            if (more && f.arrivalCount >= settings.heldBuffers() && !f.recvCancelling) {
                f.recvCancelling = true;
                ring.prep(Engine.OP_ASYNC_CANCEL, 0, 0, -1, 0L, RECV | f.id, 0, 0, 0, 0, CANCEL | f.id);
                f.outstanding++;
            }
        }
        resumeReader(f);
    }

    private void resumeReader(Fiber f) {
        if (f.waitingRecv) {
            f.waitingRecv = false;
            ready.add(f);
        }
    }

    private void sent(Fiber f, int result) {
        f.outstanding--;
        if (f.zombie) {
            endRingSend(f);
            return;
        }
        if (result <= 0) {
            endRingSend(f);
            f.sendError = result < 0 ? result : -EPIPE;
            f.sendInFlight = false;
            f.nextLength = 0;
        } else if (result < f.sendLeft) {
            f.sentBytes += result;
            f.sendAddress += result;
            f.sendLeft -= result;
            Io.submitSend(this, f);
        } else if (f.nextLength > 0) {
            f.sentBytes += result;
            Io.sendNext(this, f);
        } else {
            f.sentBytes += result;
            endRingSend(f);
            f.sendInFlight = false;
        }
        // A fiber waiting for everything has a limit of zero, so only the last completion wakes it.
        if (f.waitingSend && Io.unsent(f) <= f.sendWaitLimit) {
            f.waitingSend = false;
            ready.add(f);
        }
    }

    /** The send in flight for [f], if it was from [sendRing], has finished with it. */
    void endRingSend(Fiber f) {
        if (f.ringSend) {
            f.ringSend = false;
            ringSends--;
        }
    }

    Fiber spawn(Runnable body) {
        int id = freeCount > 0 ? free[--freeCount] : nextId++;
        if (id == fibers.length) fibers = Arrays.copyOf(fibers, id * 2);
        Fiber f = new Fiber(id, body);
        fibers[id] = f;
        live++;
        ready.add(f);
        return f;
    }

    private void ended(Fiber f) {
        if (f.bufferId >= 0) buffers.recycle(f.bufferId);
        f.bufferId = -1;
        while (f.hasArrival()) {
            int bid = (int) (f.nextArrival() >> 32);
            if (bid >= 0) buffers.recycle(bid);
        }
        f.zombie = true;
        if (f.outstanding == 0) freeId(f);
    }

    private void freeId(Fiber f) {
        // Nothing is in flight any more, so its send buffers can go to the next connection.
        slots.give(f.out);
        slots.give(f.next);
        f.out = f.next = null;
        f.session = null;
        fibers[f.id] = null;
        live--;
        if (freeCount == free.length) free = Arrays.copyOf(free, freeCount * 2);
        free[freeCount++] = f.id;
    }

    private void release() {
        closed = true;
        while (posting.get() != 0) Thread.onSpinWait();
        Task t;
        while ((t = inbox.poll()) != null) t.reject();
        if (buffers != null) buffers.unregister();
        if (ring != null) ring.close();
        if (eventFd >= 0) Sys.close(eventFd);
        if (buffers != null) buffers.free();
        if (sendRingMapping != null) sendRingMapping.unmap();
        slots.free();
        if (arena != null) arena.close();
        if (!listenerClosed) Sys.close(listener);
    }

    /**
     * What this carrier holds, counted on the carrier itself: for the soak and the tests to see that what connections
     * take is given back. Not API. Fails if the carrier has stopped.
     */
    CompletableFuture<Stats> stats() {
        CompletableFuture<Stats> result = new CompletableFuture<>();
        execute(new Task() {
            @Override
            public void run() {
                int sessions = 0;
                int zombies = 0;
                for (int i = 0; i < nextId; i++) {
                    Fiber f = fibers[i];
                    if (f == null) continue;
                    if (f.zombie) zombies++;
                    else if (f.session != null) sessions++;
                }
                result.complete(new Stats(live, sessions, zombies, handshakes, buffers.available(), buffers.count(),
                    slots.slabs(), slots.available(), ring.waiting()));
            }

            @Override
            public void reject() {
                result.completeExceptionally(new IllegalStateException(getName() + " has stopped"));
            }
        });
        return result;
    }

    /**
     * A carrier's counts: fibers alive (the acceptor among them), those serving a connection, those ended with I/O
     * still in flight, TLS handshakes in progress, receive buffers free of how many, send-slot slabs mapped and slots
     * free in them, and operations the engine holds waiting (epoll only; -1 on io_uring, which keeps them in the
     * kernel).
     */
    record Stats(int fibers, int sessions, int zombies, int handshakes, int freeBuffers, int buffers, int slotSlabs,
                 int freeSlots, int engineWaiting) {}

    static Carrier current() {
        return (Carrier) Thread.currentThread();
    }
}
