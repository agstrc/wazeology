package com.wazeology.core;

/**
 * Cooperative cancellation for one job. Long loops (download, unpack, graft entries, session writes)
 * call {@link #check()}; a blocking network read is unblocked by the {@link #onCancel} hook, which
 * disconnects the live connection. apksig has no cancel hook, so a signing pass always finishes the
 * file it is on before the next check stops the job.
 */
public final class CancelToken {

    /** Thrown by {@link #check()} once the job is cancelled. Unchecked so it can cross callback
     *  interfaces (Graft.Progress) that do not declare exceptions. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** A token that is never cancelled, for callers that do not support cancellation. */
    public static final CancelToken NONE = new CancelToken();

    private volatile boolean cancelled;
    private volatile Runnable hook;

    public void cancel() {
        if (this == NONE) {
            return;
        }
        cancelled = true;
        final Runnable h = hook;
        if (h != null) {
            // Off the caller's thread: cancel comes from the UI thread, and disconnecting a TLS
            // connection does network I/O (refused there, which would leave the read blocked).
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        h.run();
                    } catch (RuntimeException ignored) {
                        // best effort: the loop's own check() stops the job anyway
                    }
                }
            }, "cancel");
            t.setDaemon(true);
            t.start();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void check() {
        if (cancelled) {
            throw new Cancelled();
        }
    }

    /** Run r on cancel (e.g. disconnect a blocking connection); null clears it. Runs at once if the
     *  token is already cancelled. */
    public void onCancel(Runnable r) {
        hook = r;
        if (r != null && cancelled) {
            r.run();
        }
    }
}
