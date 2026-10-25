package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.MemorySegment;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/**
 * Straight-line code that can stop at an I/O call and be resumed later on the same carrier: the JDK's own
 * continuation, the one virtual threads are built on, without the JDK's scheduler or poller.
 *
 * It also holds its connection's I/O state, which only its carrier touches.
 */
final class Fiber {
    private static final ContinuationScope SCOPE = new ContinuationScope("gannet");

    // Typed Object, so Kotlin, which reads this file's signatures but not its bodies, never meets an unexported type.
    private final Object continuation;
    final int id;

    /** The connection this fiber serves, once it has one. */
    int fd = -1;
    /** What the carrier's timer and stop reach this fiber's connection through. */
    Session session;
    /** The result of a plain request (an accept). */
    int result;
    /** Requests in flight; a multishot receive counts once for as long as it stays armed. */
    int outstanding;
    /** Ended while requests were still in flight: its id is freed when the last one completes. */
    boolean zombie;
    /** Parked until a task on its carrier wakes it, rather than until I/O completes. */
    boolean waitingWake;

    // Receiving: arrivals not yet read, each a buffer id and a length (or a result <= 0 with no buffer). A buffer id
    // of OWN means the bytes are in [own], read there when the carrier's buffers ran out.
    static final int OWN = -2;
    boolean recvArmed;
    boolean recvCancelling;
    /** The last receive ended for want of a carrier buffer, so the next goes into [own]. */
    boolean starved;
    boolean waitingRecv;
    MemorySegment own;
    // The oldest arrival is held here, in the fiber itself, and only those behind it in [arrivals]: usually there is
    // just the one, and the fiber's own fields are already in cache when its completion is handled.
    private long firstArrival;
    int arrivalCount;
    private long[] arrivals;
    private int arrivalHead;
    private int arrivalTail;
    int bufferId = -1;
    int bufferAt;
    int bufferEnd;

    // Sending: one send in flight at most, from [out], resubmitted from where it stopped if the kernel takes part.
    // Writes made meanwhile collect in [next], which is sent, swapped with [out], once that send completes.
    MemorySegment out;
    MemorySegment next;
    int nextLength;
    boolean sendInFlight;
    /** The send in flight is from its carrier's send ring, not [out]. */
    boolean ringSend;
    boolean waitingSend;
    long sendAddress;
    int sendLeft;
    int sendError;
    /** Bytes the kernel has taken from this fiber's sends, so the carrier's timer can tell a stalled reader. */
    long sentBytes;
    /** Accepted with TLS still to set up: counted in its carrier's handshakes until [Carrier#endHandshake]. */
    boolean handshaking;

    Fiber(int id, Runnable body) {
        this.id = id;
        this.continuation = new Continuation(SCOPE, body);
    }

    /** Runs until the body parks or ends; true once it has ended. */
    boolean resume() {
        Continuation c = (Continuation) continuation;
        c.run();
        return c.isDone();
    }

    /** Stops the fiber running on this thread; its carrier resumes it when what it waits for happens. */
    static void park() {
        Continuation.yield(SCOPE);
    }

    void arrived(int bufferId, int result) {
        long a = ((long) bufferId << 32) | (result & 0xffffffffL);
        if (arrivalCount++ == 0) {
            firstArrival = a;
            return;
        }
        if (arrivals == null) arrivals = new long[16];
        if (arrivalTail - arrivalHead == arrivals.length) {
            long[] grown = new long[arrivals.length * 2];
            for (int i = 0; i < arrivals.length; i++) grown[i] = arrivals[(arrivalHead + i) & (arrivals.length - 1)];
            arrivalTail -= arrivalHead;
            arrivalHead = 0;
            arrivals = grown;
        }
        arrivals[arrivalTail++ & (arrivals.length - 1)] = a;
    }

    boolean hasArrival() {
        return arrivalCount != 0;
    }

    long nextArrival() {
        long a = firstArrival;
        if (--arrivalCount != 0) firstArrival = arrivals[arrivalHead++ & (arrivals.length - 1)];
        return a;
    }
}
