package io.github.matthewjones372.gannet.runtime;

/** A connection as its carrier sees it between fiber runs: on the carrier thread, never inside the fiber. */
interface Session {
    /** The carrier's timer fired at [now], in [System#nanoTime] terms. */
    void tick(long now);

    /** The server is stopping; [force] once its grace period has run out. */
    void stop(boolean force);
}
