package io.github.matthewjones372.gannet.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.net.BindException;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** The libc calls and io_uring system calls the runtime needs, over FFM, on Linux; [Epoll] links its own. */
final class Sys {
    private Sys() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO =
        CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno")).withInvokeExactBehavior();

    static MethodHandle libc(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor, options);
    }

    /** Has a call's first argument take the errno it leaves, in memory from [errnoState]. */
    static final Linker.Option ERRNO_OPTION = Linker.Option.captureCallState("errno");

    /** Somewhere for calls made with [ERRNO_OPTION] to leave errno; [errno] reads it back. */
    static MemorySegment errnoState(Arena arena) {
        return arena.allocate(CAPTURE);
    }

    static int errno(MemorySegment state) {
        return (int) ERRNO.get(state, 0L);
    }

    // syscall(long number, ...): every variadic argument goes as a long, which is what glibc reads them as. It returns
    // -1 and sets errno on failure, not the kernel's negative errno.
    private static final MethodHandle SYSCALL3 = libc("syscall",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG), Linker.Option.firstVariadicArg(1), ERRNO_OPTION);
    private static final FunctionDescriptor SYSCALL7_TYPE = FunctionDescriptor.of(
        JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    static final MethodHandle SYSCALL7 = libc("syscall", SYSCALL7_TYPE, Linker.Option.firstVariadicArg(1));
    // For an io_uring_enter that never waits: no thread-state transition, which a call that may block must have.
    static final MethodHandle SYSCALL7_CRITICAL = libc("syscall", SYSCALL7_TYPE,
        Linker.Option.firstVariadicArg(1), Linker.Option.critical(false));

    private static final MethodHandle SOCKET =
        libc("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPTION);
    private static final MethodHandle SETSOCKOPT = libc("setsockopt",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPTION);
    private static final MethodHandle BIND =
        libc("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPTION);
    private static final MethodHandle LISTEN =
        libc("listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPTION);
    private static final MethodHandle GETSOCKNAME =
        libc("getsockname", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), ERRNO_OPTION);
    private static final MethodHandle CLOSE = libc("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle EVENTFD =
        libc("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPTION);
    private static final MethodHandle WRITE = libc("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle MMAP = libc("mmap",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle MUNMAP = libc("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle MADVISE = libc("madvise", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

    static final long IO_URING_SETUP = 425;
    static final long IO_URING_ENTER = 426;
    static final long IO_URING_REGISTER = 427;

    static final int EPERM = 1;
    static final int ENOSYS = 38;
    private static final int EADDRINUSE = 98;

    private static final MemorySegment ONE = Arena.global().allocate(JAVA_INT);
    // What an eventfd write adds; read by the kernel only, so one copy serves every thread.
    private static final MemorySegment EVENT = Arena.global().allocate(JAVA_LONG);

    static {
        ONE.set(JAVA_INT, 0, 1);
        EVENT.set(JAVA_LONG, 0, 1L);
    }

    /** Memory from [mmap]: the aligned part handed out, and the mapping it came from, which [unmap] gives back. */
    record Mapping(MemorySegment segment, long base, long length) {
        void unmap() {
            Sys.munmap(base, length);
        }
    }

    /**
     * Native memory of [size] bytes on transparent huge pages where the kernel allows it: memory the kernel copies
     * into and out of for every request, so one TLB entry covering 2 MiB of it instead of 4 KiB matters.
     */
    static Mapping hugeSlab(long size) {
        long huge = 2L << 20;
        long rounded = (size + huge - 1) & -huge;
        try {
            // Anonymous and untouched, so the advice comes before the first fault; over-sized to align it.
            MemorySegment m = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, rounded + huge,
                3 /* PROT_READ | PROT_WRITE */, 0x22 /* MAP_PRIVATE | MAP_ANONYMOUS */, -1, 0L);
            if (m.address() == -1L) throw new IllegalStateException("mmap of " + size + " bytes failed");
            MemorySegment aligned = MemorySegment.ofAddress((m.address() + huge - 1) & -huge).reinterpret(rounded);
            int ignored = (int) MADVISE.invokeExact(aligned, rounded, 14 /* MADV_HUGEPAGE */);
            return new Mapping(aligned.asSlice(0, size), m.address(), rounded + huge);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** io_uring_setup with [params]: the ring's descriptor, or a negative errno. */
    static int setup(int entries, MemorySegment params) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            long r = (long) SYSCALL3.invokeExact(state, IO_URING_SETUP, (long) entries, params.address());
            return r < 0 ? -(int) ERRNO.get(state, 0L) : (int) r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * A TCP socket listening on [address] (IPv4, network order in an int) at [port], sharing the port with the other
     * carriers' through SO_REUSEPORT.
     */
    static int listen(int address, int port, int backlog) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int fd = (int) SOCKET.invokeExact(state, 2 /* AF_INET */, 1 | 0x80000 /* SOCK_STREAM | SOCK_CLOEXEC */, 0);
            check(fd, state, "socket");
            try {
                check((int) SETSOCKOPT.invokeExact(state, fd, 1 /* SOL_SOCKET */, 2 /* SO_REUSEADDR */, ONE, 4), state,
                    "SO_REUSEADDR");
                check((int) SETSOCKOPT.invokeExact(state, fd, 1, 15 /* SO_REUSEPORT */, ONE, 4), state, "SO_REUSEPORT");
                // Accepted sockets inherit it, which spares a system call per connection.
                check((int) SETSOCKOPT.invokeExact(state, fd, 6 /* IPPROTO_TCP */, 1 /* TCP_NODELAY */, ONE, 4), state,
                    "TCP_NODELAY");
                MemorySegment sockaddr = arena.allocate(16); // sockaddr_in
                sockaddr.set(JAVA_SHORT, 0, (short) 2);
                sockaddr.set(JAVA_BYTE, 2, (byte) (port >> 8));
                sockaddr.set(JAVA_BYTE, 3, (byte) port);
                sockaddr.set(JAVA_BYTE, 4, (byte) (address >>> 24));
                sockaddr.set(JAVA_BYTE, 5, (byte) (address >>> 16));
                sockaddr.set(JAVA_BYTE, 6, (byte) (address >>> 8));
                sockaddr.set(JAVA_BYTE, 7, (byte) address);
                if ((int) BIND.invokeExact(state, fd, sockaddr, 16) < 0) {
                    int errno = (int) ERRNO.get(state, 0L);
                    if (errno == EADDRINUSE) throw new BindException("Address already in use: port " + port);
                    throw new IOException("bind failed: errno " + errno);
                }
                check((int) LISTEN.invokeExact(state, fd, backlog), state, "listen");
                return fd;
            } catch (Throwable t) {
                close(fd);
                throw t;
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The port [fd] is bound to, which is how a carrier learns the one the kernel chose for port 0. */
    static int localPort(int fd) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment sockaddr = arena.allocate(16);
            MemorySegment length = arena.allocate(JAVA_INT);
            length.set(JAVA_INT, 0, 16);
            check((int) GETSOCKNAME.invokeExact(state, fd, sockaddr, length), state, "getsockname");
            return ((sockaddr.get(JAVA_BYTE, 2) & 0xff) << 8) | (sockaddr.get(JAVA_BYTE, 3) & 0xff);
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A non-blocking eventfd: other threads write to it, and a read kept armed on a ring wakes that ring's carrier. */
    static int eventfd() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int fd = (int) EVENTFD.invokeExact(state, 0, 0x80000 | 0x800 /* EFD_CLOEXEC | EFD_NONBLOCK */);
            if (fd < 0) throw new IllegalStateException("eventfd failed: errno " + (int) ERRNO.get(state, 0L));
            return fd;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Adds one to the eventfd [fd]; never blocks, as the counter cannot come near overflowing. */
    static void signal(int fd) {
        try {
            long ignored = (long) WRITE.invokeExact(fd, EVENT, 8L);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void close(int fd) {
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment mmap(long size, int fd, long offset) {
        try {
            MemorySegment m = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, size,
                3 /* PROT_READ | PROT_WRITE */, 0x8001 /* MAP_SHARED | MAP_POPULATE */, fd, offset);
            if (m.address() == -1L) throw new IllegalStateException("mmap failed at offset " + offset);
            return m.reinterpret(size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void munmap(long address, long length) {
        try {
            int ignored = (int) MUNMAP.invokeExact(MemorySegment.ofAddress(address), length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void check(int result, MemorySegment state, String what) throws IOException {
        if (result < 0) throw new IOException(what + " failed: errno " + (int) ERRNO.get(state, 0L));
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
