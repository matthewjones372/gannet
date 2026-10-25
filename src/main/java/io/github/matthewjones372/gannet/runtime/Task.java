package io.github.matthewjones372.gannet.runtime;

/** Work another thread hands a carrier: [run] on the carrier, or [reject] on whichever thread finds it stopped. */
interface Task {
    void run();

    void reject();
}
