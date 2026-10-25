package io.github.matthewjones372.gannet.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Blocking socket calls for code running in a [Carrier]'s fiber. The caller sees ordinary calls; underneath, a
 * connection's receive is one multishot request into the carrier's [BufferRing], and a write is queued and returns
 * at once. Only [awaitData], [awaitSend] and [parkForWake] park, so a fiber parks only where its code says so.
 */
final class Io {
    private Io() {}

    static final int MSG_NOSIGNAL = 0x4000;
    private static final int IOSQE_IO_HARDLINK = 1 << 3;
    private static final int IOSQE_BUFFER_SELECT = 1 << 5;
    private static final int RECV_MULTISHOT = 1 << 1;
    private static final int FILE_INDEX_ALLOC = -1;
    private static final int SHUT_RDWR = 2;

    /** The fiber running on this thread, or null when this is not a carrier running one. */
    static Fiber fiber() {
        return Thread.currentThread() instanceof Carrier c ? c.current : null;
    }

    /** Makes [session] the current fiber's, serving [fd]. */
    static void attach(Session session, int fd) {
        Fiber f = Carrier.current().current;
        f.session = session;
        f.fd = fd;
    }

    /** A connection accepted on [listener], or a negative errno. */
    static int accept(int listener) {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        // Into a free slot of the ring's file table when it has one: the result is then that slot.
        c.ring.prep(Engine.OP_ACCEPT, 0, 0, listener, 0L, 0L, 0, 0, 0, c.fileFlag != 0 ? FILE_INDEX_ALLOC : 0,
            Carrier.WAKE | f.id);
        f.outstanding++;
        Fiber.park();
        return f.result;
    }

    /** Bytes read into [into] at [at], at most [max], without waiting: zero when nothing has arrived, -1 at the end. */
    static int readNow(byte[] into, int at, int max) {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        while (true) {
            if (f.bufferId != -1) {
                int n = Math.min(max, f.bufferEnd - f.bufferAt);
                if (f.bufferId == Fiber.OWN) {
                    MemorySegment.copy(f.own, JAVA_BYTE, f.bufferAt, into, at, n);
                } else {
                    MemorySegment.copy(c.buffers.slab(), JAVA_BYTE, c.buffers.offset(f.bufferId) + f.bufferAt, into,
                        at, n);
                }
                f.bufferAt += n;
                if (f.bufferAt == f.bufferEnd) {
                    if (f.bufferId >= 0) c.buffers.recycle(f.bufferId);
                    f.bufferId = -1;
                }
                return n;
            }
            if (!f.hasArrival()) return 0;
            long a = f.nextArrival();
            int bid = (int) (a >> 32);
            int result = (int) a;
            if (result <= 0 || bid == -1) {
                if (bid >= 0) c.buffers.recycle(bid);
                return -1;
            }
            f.bufferId = bid;
            f.bufferAt = 0;
            f.bufferEnd = result;
        }
    }

    /**
     * Parks until something has arrived (data, the end of the stream or an error), or until the carrier wakes the
     * fiber with [Carrier#wakeReader]; the caller reads again either way.
     */
    static void awaitData() {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        if (f.bufferId != -1 || f.hasArrival()) return;
        if (!f.recvArmed) {
            if (f.starved) {
                // The carrier's buffers ran out: one receive into a buffer of this fiber's own, so it still moves.
                f.starved = false;
                if (f.own == null) f.own = Arena.ofAuto().allocate(Carrier.OWN_BUFFER);
                c.ring.prep(Engine.OP_RECV, c.fileFlag, 0, f.fd, 0L, f.own.address(), Carrier.OWN_BUFFER, 0, 0, 0,
                    Carrier.OWN_RECV | f.id);
            } else {
                c.ring.prep(Engine.OP_RECV, IOSQE_BUFFER_SELECT | c.fileFlag, RECV_MULTISHOT, f.fd, 0L, 0L, 0, 0,
                    BufferRing.GROUP, 0, Carrier.RECV | f.id);
            }
            f.recvArmed = true;
            f.outstanding++;
        }
        f.waitingRecv = true;
        Fiber.park();
    }

    /** Parks until a task on the carrier calls [Carrier#wake] for this fiber. */
    static void parkForWake() {
        Fiber f = Carrier.current().current;
        f.waitingWake = true;
        Fiber.park();
    }

    /**
     * Queues [length] bytes of [bytes] from [offset] without waiting: sent now if nothing is in flight, else collected
     * and sent when the send in flight completes. [bytes] is free for reuse as soon as this returns.
     */
    static void write(byte[] bytes, int offset, int length) throws IOException {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        if (f.sendError < 0) throw new IOException("Connection lost: send failed with errno " + -f.sendError);
        if (length == 0) return;
        if (!f.sendInFlight) {
            if (c.ringSends == 0) c.sendRingHead = 0;
            int at = c.sendRingHead;
            if (length <= Carrier.SEND_RING - at) {
                MemorySegment.copy(bytes, offset, c.sendRing, JAVA_BYTE, at, length);
                // Eight-byte aligned, so no two sends share a word.
                c.sendRingHead = (at + length + 7) & ~7;
                c.ringSends++;
                f.ringSend = true;
                send(c, f, c.sendRingAddress + at, length);
            } else {
                f.out = fit(c, f.out, 0, length);
                MemorySegment.copy(bytes, offset, f.out, JAVA_BYTE, 0, length);
                send(c, f, f.out.address(), length);
            }
        } else {
            f.next = fit(c, f.next, f.nextLength, f.nextLength + length);
            MemorySegment.copy(bytes, offset, f.next, JAVA_BYTE, f.nextLength, length);
            f.nextLength += length;
        }
    }

    /** Parks until everything written has been taken by the kernel; throws if the connection failed meanwhile. */
    static void awaitSend() throws IOException {
        Fiber f = Carrier.current().current;
        while (f.sendInFlight || f.nextLength > 0) {
            f.waitingSend = true;
            Fiber.park();
        }
        if (f.sendError < 0) throw new IOException("Connection lost: send failed with errno " + -f.sendError);
    }

    /** Called by the carrier when [f]'s send has completed and writes collected meanwhile. */
    static void sendNext(Carrier c, Fiber f) {
        c.endRingSend(f);
        MemorySegment sent = f.out;
        f.out = f.next;
        f.next = sent;
        int length = f.nextLength;
        f.nextLength = 0;
        send(c, f, f.out.address(), length);
    }

    private static void send(Carrier c, Fiber f, long address, int length) {
        f.sendAddress = address;
        f.sendLeft = length;
        f.sendInFlight = true;
        submitSend(c, f);
    }

    /** Sends what is left of [f]'s current send. */
    static void submitSend(Carrier c, Fiber f) {
        c.ring.prep(Engine.OP_SEND, c.fileFlag, 0, f.fd, 0L, f.sendAddress, f.sendLeft, MSG_NOSIGNAL, 0, 0,
            Carrier.SEND | f.id);
        f.outstanding++;
    }

    /**
     * [buffer] if it holds [needed] bytes, else a larger one with its first [keep] bytes copied over: a carrier's
     * slot while that is large enough, then one of its own. Never called on a buffer a send is reading.
     */
    private static MemorySegment fit(Carrier c, MemorySegment buffer, int keep, int needed) {
        if (buffer != null && buffer.byteSize() >= needed) return buffer;
        MemorySegment grown = buffer == null && needed <= SendSlots.SIZE ? c.slots.take()
            : Arena.ofAuto().allocate(Math.max(4096, Integer.highestOneBit(needed - 1) << 1));
        if (keep > 0) MemorySegment.copy(buffer, 0, grown, 0, keep);
        c.slots.give(buffer);
        return grown;
    }

    /**
     * Shuts [f]'s connection down in both directions, from its carrier, inside the fiber or not: a receive or send
     * waiting on it then completes, so a fiber parked on either wakes and finds the connection gone.
     */
    static void shutdown(Carrier c, Fiber f) {
        if (f.fd < 0 || f.zombie) return;
        c.ring.prep(Engine.OP_SHUTDOWN, c.fileFlag, 0, f.fd, 0L, 0L, SHUT_RDWR, 0, 0, 0, Carrier.CLOSE | f.id);
        f.outstanding++;
    }

    /** Closes [fd] through the ring without waiting: a direct descriptor has no number the close system call knows. */
    static void closeNow(int fd) {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        boolean fixed = c.fileFlag != 0;
        c.ring.prep(Engine.OP_CLOSE, 0, 0, fixed ? 0 : fd, 0L, 0L, 0, 0, 0, fixed ? fd + 1 : 0, Carrier.CLOSE | f.id);
        f.outstanding++;
    }

    /**
     * Waits for the last write, then closes [fd], ending a receive still armed on it. Nothing waits for either; the
     * fiber's id stays taken until both have completed.
     */
    static void finish(int fd) {
        Carrier c = Carrier.current();
        Fiber f = c.current;
        try {
            awaitSend();
        } catch (IOException ignored) {
            // The connection is already gone; closing it is all that is left.
        }
        if (f.recvArmed) {
            // Hard-linked, so the close runs after the shutdown even when the shutdown fails.
            c.ring.prep(Engine.OP_SHUTDOWN, c.fileFlag | IOSQE_IO_HARDLINK, 0, fd, 0L, 0L, SHUT_RDWR, 0, 0, 0,
                Carrier.CLOSE | f.id);
            f.outstanding++;
        }
        closeNow(fd);
        f.fd = -1;
    }
}
