package io.github.matthewjones372.gannet.runtime;

import java.lang.foreign.MemorySegment;

/**
 * Where a [Carrier] sends its I/O and collects what has finished: submissions queued with [prep], sent with completions
 * gathered by [enter], and handed over by [reap]. The vocabulary is io_uring's (its opcodes, flags and completion
 * fields), because io_uring is the fast path: [Ring] passes it to the kernel as it is, and [Epoll] carries it out
 * itself over readiness, for kernels and containers that refuse io_uring. Owned by the thread that made it.
 */
abstract class Engine {
    static final byte OP_TIMEOUT = 11;
    static final byte OP_ACCEPT = 13;
    static final byte OP_ASYNC_CANCEL = 14;
    static final byte OP_CLOSE = 19;
    static final byte OP_READ = 22;
    static final byte OP_SEND = 26;
    static final byte OP_RECV = 27;
    static final byte OP_SHUTDOWN = 34;

    /**
     * Queues one submission, which reaches the engine at the next [enter]: the opcode, the submission's own flags, its
     * ioprio field, a descriptor, an offset, an address and length, the op's flags, a provided-buffer group, a
     * fixed-file index (for an accept or close of a direct descriptor), and what the completion hands back.
     */
    abstract void prep(byte op, int sqeFlags, int ioprio, int fd, long off, long addr, int len, int opFlags,
                       int bufGroup, int fileIndex, long userData);

    /** Whether submissions are queued that have not yet been handed on. */
    abstract boolean hasQueued();

    /** Sends what is queued and gathers completions, waiting for at least one when [wait]. */
    abstract void enter(boolean wait);

    /** Hands every completion gathered so far to [to], and says how many there were. */
    abstract int reap(Completions to);

    /** io_uring_register: [opcode] with [arg] and [count]; throws if refused. */
    final void register(int opcode, MemorySegment arg, int count) {
        if (tryRegister(opcode, arg, count) < 0) {
            throw new UnsupportedOperationException("io_uring_register " + opcode + " was refused");
        }
    }

    /** io_uring_register's result: what it returns, or -1 when it fails. */
    abstract long tryRegister(int opcode, MemorySegment arg, int count);

    /** A sparse table of [count] direct descriptors; false where there is none to have. */
    abstract boolean registerFiles(int count);

    /** Operations held waiting in user space, for the soak to see none is forgotten; -1 where the kernel holds them. */
    int waiting() {
        return -1;
    }

    /** Ends the engine and frees what it holds. */
    abstract void close();

    interface Completions {
        void complete(long userData, int result, int flags);
    }
}
