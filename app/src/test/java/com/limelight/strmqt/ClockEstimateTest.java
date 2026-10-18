package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class ClockEstimateTest {
    private static final long MS = 1_000_000L;

    /** Another clock, `offset` ahead and running `ppm` fast, reached over a network. */
    private static final class Other {
        final long offset;
        final double ppm;
        final Random random = new Random(42);

        Other(long offset, double ppm) {
            this.offset = offset;
            this.ppm = ppm;
        }

        long read(long local) {
            return local + offset + Math.round(local * ppm / 1e6);
        }

        /** One exchange at `local`: each way takes `base` plus a queue now and then. */
        void exchange(ClockEstimate estimate, long local, long base, long queue) {
            long up = base + (random.nextInt(4) == 0 ? (long) (random.nextDouble() * queue) : 0);
            long down = base + (random.nextInt(4) == 0 ? (long) (random.nextDouble() * queue) : 0);
            long t1 = local;
            long t2 = read(t1 + up);
            long t3 = t2 + 50_000; // the answerer's own turnaround
            long t4 = t1 + up + 50_000 + down;
            assertTrue(estimate.add(t1, t2, t3, t4, 7));
        }
    }

    @Test
    public void nothingIsGuessedFromTooFewExchanges() {
        ClockEstimate estimate = new ClockEstimate();
        Other other = new Other(5_000 * MS, 0);
        for (int i = 0; i < ClockEstimate.MIN_SAMPLES - 1; i++) {
            other.exchange(estimate, i * 250 * MS, MS, 0);
        }
        assertNull(estimate.estimate());
    }

    @Test
    public void theQueuedExchangesAreNotBelieved() {
        ClockEstimate estimate = new ClockEstimate();
        // 3 ms of queueing in either direction, one exchange in four.
        Other other = new Other(123_456_789_000L, 0);
        for (int i = 0; i < 100; i++) {
            other.exchange(estimate, 1_000_000 * MS + i * 250 * MS, MS, 3 * MS);
        }
        ClockEstimate.Result result = estimate.estimate();
        assertNotNull(result);
        long now = 1_000_000 * MS + 100 * 250 * MS;
        long error = Math.abs(result.at(now) - other.read(now));
        assertTrue("off by " + error + " ns", error < 20_000);
        // The answerer's turnaround is not delay.
        assertEquals(2 * MS, result.minDelayNs);
    }

    @Test
    public void aClockThatRunsFastIsFollowed() {
        ClockEstimate estimate = new ClockEstimate();
        // 50 ppm is 3 ms a minute: an average over the window would be ms out.
        Other other = new Other(-42 * MS, 50);
        long start = 5_000_000 * MS;
        for (int i = 0; i < ClockEstimate.WINDOW; i++) {
            other.exchange(estimate, start + i * 250 * MS, MS, 3 * MS);
        }
        ClockEstimate.Result result = estimate.estimate();
        assertEquals(50, result.skewPpm, 2);
        long later = start + ClockEstimate.WINDOW * 250 * MS + 1000 * MS;
        long error = Math.abs(result.at(later) - other.read(later));
        assertTrue("off by " + error + " ns", error < 100_000);
    }

    @Test
    public void aClockThatStartsOverIsLearntAgain() {
        ClockEstimate estimate = new ClockEstimate();
        Other before = new Other(10_000 * MS, 0);
        for (int i = 0; i < 50; i++) {
            before.exchange(estimate, i * 250 * MS, MS, 0);
        }
        // The host restarted: another clockId, another timeline.
        long t = 50 * 250 * MS;
        assertTrue(estimate.add(t, 5, 6, t + 2 * MS, 8));
        assertNull("one exchange of the new clock is not enough", estimate.estimate());
    }

    @Test
    public void anExchangeThatMakesNoSenseIsRefused() {
        ClockEstimate estimate = new ClockEstimate();
        assertFalse("answered before it was asked", estimate.add(100, 10, 20, 50, 1));
        assertFalse("sent before it was received", estimate.add(0, 30, 20, 100, 1));
        assertFalse("took longer there than the whole trip", estimate.add(0, 0, 200, 100, 1));
    }
}
