package com.limelight.strmqt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where another clock is relative to this one, from NTP exchanges with it
 * ({@link TimePacket}): the host's for playing in step, or another TV's for
 * checking the host's figures around the triangle.
 *
 * Each exchange gives offset = ((t2 - t1) + (t3 - t4)) / 2, wrong by half the
 * difference between the two directions' delays, and delay = (t4 - t1) -
 * (t3 - t2). An exchange that waited in a queue is slow one way, so only the
 * fastest {@link #BEST_FRACTION} of the window are believed (Snapcast takes a
 * median; min-delay filtering is NTP's). The two clocks also run at slightly
 * different rates, tens of ppm, a few ms a minute: once the believed exchanges
 * span {@link #MIN_SKEW_SPAN_NS}, a line is fitted through them and the offset
 * is read off it at the time asked for, rather than an average that lags. The
 * believed are the fastest of each of {@link #SLICES} stretches of the window,
 * not of the whole: a quiet spell would otherwise supply all of them, and a
 * line through one spell says nothing about the rate.
 *
 * Times are nanoseconds: this side's on System.nanoTime, the other's on its own.
 */
public final class ClockEstimate {
    static final int WINDOW = 200;
    static final double BEST_FRACTION = 0.15;
    static final int MIN_BEST = 3;
    static final int SLICES = 8;
    /** Exchanges needed before there is an estimate at all. */
    static final int MIN_SAMPLES = 8;
    /** How far apart the believed exchanges must be before the rates are compared. */
    static final long MIN_SKEW_SPAN_NS = 20_000_000_000L;

    /** An estimate: the other clock is {@link #at}(local) when this one reads local. */
    public static final class Result {
        /** Offset at {@link #reference}, in ns: other = local + offset. */
        public final long offsetNs;
        public final long reference;
        /** How much faster the other clock runs, in parts per million. */
        public final double skewPpm;
        /** The fastest round trip seen; half of it bounds the offset's error. */
        public final long minDelayNs;
        /** The believed exchanges' scatter about the fit, in ns. */
        public final long scatterNs;
        public final int samples;

        Result(long offsetNs, long reference, double skewPpm, long minDelayNs, long scatterNs, int samples) {
            this.offsetNs = offsetNs;
            this.reference = reference;
            this.skewPpm = skewPpm;
            this.minDelayNs = minDelayNs;
            this.scatterNs = scatterNs;
            this.samples = samples;
        }

        /** The other clock's reading when this one reads `local`. */
        public long at(long local) {
            return local + offsetNs + Math.round((local - reference) * skewPpm / 1e6);
        }
    }

    private final long[] local = new long[WINDOW];
    private final long[] offset = new long[WINDOW];
    private final long[] delay = new long[WINDOW];
    private int count;
    private int next;
    private long clockId;

    /**
     * Adds one exchange. A changed clockId means the other clock started over:
     * what was known is dropped, and the exchange begins the new window.
     *
     * @return false when the exchange makes no sense and was not kept
     */
    public synchronized boolean add(long t1, long t2, long t3, long t4, long otherClockId) {
        long roundTrip = (t4 - t1) - (t3 - t2);
        if (t4 < t1 || t3 < t2 || roundTrip < 0) {
            return false;
        }
        if (count > 0 && otherClockId != clockId) {
            count = 0;
            next = 0;
        }
        clockId = otherClockId;
        // Halved separately: the sum of two 63-bit differences can overflow.
        local[next] = t1 / 2 + t4 / 2;
        offset[next] = (t2 - t1) / 2 + (t3 - t4) / 2;
        delay[next] = roundTrip;
        next = (next + 1) % WINDOW;
        count = Math.min(count + 1, WINDOW);
        return true;
    }

    public synchronized void clear() {
        count = 0;
        next = 0;
    }

    /** The current estimate, or null before {@link #MIN_SAMPLES} exchanges. */
    public synchronized Result estimate() {
        if (count < MIN_SAMPLES) {
            return null;
        }
        // Oldest first, then the fastest of each slice, the fastest overall first.
        Integer[] chronological = new Integer[count];
        int oldest = (next - count + WINDOW) % WINDOW;
        for (int i = 0; i < count; i++) {
            chronological[i] = (oldest + i) % WINDOW;
        }
        int slices = Math.min(SLICES, Math.max(1, count / MIN_BEST));
        int wanted = Math.min(count, Math.max(MIN_BEST, (int) Math.round(count * BEST_FRACTION)));
        List<Integer> chosen = new ArrayList<>();
        for (int slice = 0; slice < slices; slice++) {
            int from = slice * count / slices;
            int to = (slice + 1) * count / slices;
            Integer[] part = Arrays.copyOfRange(chronological, from, to);
            Arrays.sort(part, (a, b) -> Long.compare(delay[a], delay[b]));
            int take = Math.max(1, (int) Math.round((double) wanted * (to - from) / count));
            for (int k = 0; k < Math.min(take, part.length); k++) {
                chosen.add(part[k]);
            }
        }
        Integer[] order = chosen.toArray(new Integer[0]);
        Arrays.sort(order, (a, b) -> Long.compare(delay[a], delay[b]));
        int best = order.length;

        long reference = Long.MIN_VALUE;
        long earliest = Long.MAX_VALUE;
        for (int k = 0; k < best; k++) {
            reference = Math.max(reference, local[order[k]]);
            earliest = Math.min(earliest, local[order[k]]);
        }

        double slope = 0;
        double intercept;
        if (reference - earliest >= MIN_SKEW_SPAN_NS) {
            // Least squares of offset against time, about the means.
            double meanX = 0;
            double meanY = 0;
            for (int k = 0; k < best; k++) {
                meanX += (double) (local[order[k]] - reference);
                meanY += (double) (offset[order[k]] - offset[order[0]]);
            }
            meanX /= best;
            meanY /= best;
            double sxy = 0;
            double sxx = 0;
            for (int k = 0; k < best; k++) {
                double x = (double) (local[order[k]] - reference) - meanX;
                double y = (double) (offset[order[k]] - offset[order[0]]) - meanY;
                sxy += x * y;
                sxx += x * x;
            }
            slope = sxx > 0 ? sxy / sxx : 0;
            intercept = offset[order[0]] + meanY - slope * meanX;
        } else {
            long[] believed = new long[best];
            for (int k = 0; k < best; k++) {
                believed[k] = offset[order[k]];
            }
            Arrays.sort(believed);
            intercept = believed[best / 2];
        }

        double squares = 0;
        for (int k = 0; k < best; k++) {
            double fitted = intercept + slope * (local[order[k]] - reference);
            double residual = offset[order[k]] - fitted;
            squares += residual * residual;
        }
        long scatter = Math.round(Math.sqrt(squares / best));
        return new Result(Math.round(intercept), reference, slope * 1e6, delay[order[0]], scatter, count);
    }
}
