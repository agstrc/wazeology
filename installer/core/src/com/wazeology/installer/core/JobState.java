package com.wazeology.installer.core;

import com.wazeology.core.BuildPipeline;
import com.wazeology.core.CancelToken;

/**
 * The one piece of in-memory state: the job running right now, if any. Everything else the screen
 * shows (cached download, finished build, installed Waze) is re-read from disk and the package
 * manager, so an activity recreate or a process restart loses nothing but a job that was running.
 *
 * Progress updates only write volatile fields (no allocation per 64 KB chunk); readers take an
 * immutable {@link Snapshot}. {@link #tryBegin} is the single gate that makes a double start
 * impossible, whatever the UI enables.
 */
public final class JobState {

    public enum Kind { PREPARE, PICK, INSTALL, EXPORT, CLEAR }

    public enum Phase { LOOKUP, DOWNLOAD, WAIT_NETWORK, VERIFY, COPY, UNPACK, CHECK, GRAFT, SIGN, WRITE, FINALIZE }

    /** Phase and progress callbacks of a running job, plus a technical log line for the details view. */
    public interface Listener {
        void onPhase(Phase phase);

        void onProgress(long done, long total);

        void log(String line);
    }

    /** The build engine's steps reported as the job phases of the same name. */
    public static BuildPipeline.Listener steps(final Listener l) {
        return new BuildPipeline.Listener() {
            @Override
            public void onStep(BuildPipeline.Step step) {
                l.onPhase(Phase.valueOf(step.name()));
            }

            @Override
            public void onProgress(long done, long total) {
                l.onProgress(done, total);
            }

            @Override
            public void log(String line) {
                l.log(line);
            }
        };
    }

    /** An immutable view of the running job; kind is null when idle. */
    public static final class Snapshot {
        public final Kind kind;
        public final Phase phase;
        public final long done;
        public final long total;
        /** Smoothed transfer rate in bytes per second, 0 until measured. */
        public final long rate;
        public final boolean cancelling;
        /** Increments on every begin, phase change and end, so listeners can skip throttling. */
        public final long seq;

        Snapshot(Kind kind, Phase phase, long done, long total, long rate, boolean cancelling, long seq) {
            this.kind = kind;
            this.phase = phase;
            this.done = done;
            this.total = total;
            this.rate = rate;
            this.cancelling = cancelling;
            this.seq = seq;
        }

        public boolean running() {
            return kind != null;
        }

        /** 0..100, or -1 when the phase has no known size. */
        public int percent() {
            return total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
        }
    }

    public static final Snapshot IDLE = new Snapshot(null, null, 0, 0, 0, false, 0);

    private final Object lock = new Object();
    private volatile Kind kind;
    private volatile Phase phase;
    private volatile long done;
    private volatile long total;
    private volatile long rate;
    private volatile boolean cancelling;
    private volatile long seq;
    private CancelToken token;

    private long sampleAt;
    private long sampleBytes;

    /** Start a job of kind with its cancel token; false when another job is already running. */
    public boolean tryBegin(Kind k, CancelToken t) {
        synchronized (lock) {
            if (kind != null) {
                return false;
            }
            kind = k;
            token = t;
            phase = null;
            done = 0;
            total = 0;
            rate = 0;
            cancelling = false;
            seq++;
            return true;
        }
    }

    public void phase(Phase p) {
        synchronized (lock) {
            phase = p;
            done = 0;
            total = 0;
            rate = 0;
            sampleAt = 0;
            seq++;
        }
    }

    public void progress(long d, long t) {
        done = d;
        total = t;
        long now = System.nanoTime();
        if (sampleAt == 0 || d < sampleBytes) {
            sampleAt = now;
            sampleBytes = d;
            return;
        }
        long dt = now - sampleAt;
        if (dt >= 1_000_000_000L) {
            long inst = (d - sampleBytes) * 1_000_000_000L / dt;
            rate = rate == 0 ? inst : (rate * 7 + inst * 3) / 10;
            sampleAt = now;
            sampleBytes = d;
        }
    }

    /** Ask the running job to stop; false when nothing is running. */
    public boolean cancel() {
        CancelToken t;
        synchronized (lock) {
            if (kind == null) {
                return false;
            }
            cancelling = true;
            seq++;
            t = token;
        }
        if (t != null) {
            t.cancel();
        }
        return true;
    }

    public void end() {
        synchronized (lock) {
            kind = null;
            phase = null;
            token = null;
            done = 0;
            total = 0;
            rate = 0;
            cancelling = false;
            seq++;
        }
    }

    public Snapshot snapshot() {
        Kind k = kind;
        if (k == null) {
            return IDLE;
        }
        return new Snapshot(k, phase, done, total, rate, cancelling, seq);
    }

    public long seq() {
        return seq;
    }
}
