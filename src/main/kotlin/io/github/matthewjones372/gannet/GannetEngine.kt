package io.github.matthewjones372.gannet

/** What a [GannetServer]'s carriers do their I/O through. */
enum class GannetEngine {
    /** io_uring where this machine allows it, else epoll; the choice and its reason are logged once, at INFO. */
    AUTO,

    /** io_uring only, the faster engine: Linux 6.1 or later, with io_uring neither disabled nor blocked by seccomp. */
    IO_URING,

    /** epoll and non-blocking sockets only, which runs where io_uring is refused, as in Docker's default profile. */
    EPOLL,
}
