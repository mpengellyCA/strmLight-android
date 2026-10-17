package com.limelight.strmqt;

/** The wait before each reconnect attempt after the stream ends unexpectedly. */
public final class Backoff {
    static final long[] DELAYS_MS = {1000, 2000, 4000, 8000, 15000, 30000};

    /** A stream that stayed up this long counts as a success: the next failure starts over. */
    public static final long STABLE_STREAM_MS = 30000;

    private int failures;

    /** The delay before the next attempt, or -1 when it is time to give up. */
    public long nextDelayMs() {
        if (failures >= DELAYS_MS.length) {
            return -1;
        }
        return DELAYS_MS[failures++];
    }

    public int attempt() {
        return failures;
    }

    public int maxAttempts() {
        return DELAYS_MS.length;
    }

    public void reset() {
        failures = 0;
    }
}
