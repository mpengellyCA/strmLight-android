package com.limelight.strmqt;

/**
 * When each audio sample or video frame of a stream is due, on the StrmQt
 * host's clock, for TVs playing together (docs/synchronized-playback.md), and
 * how to get there. One for the audio, one for the video.
 *
 * Sunshine's timestamps start from an epoch of its own (audio counts
 * milliseconds from 0; video's 90 kHz clock counts from when its capture
 * started), so the host time of timestamp 0 (the base) is found from
 * arrivals: host time of arrival minus timestamp is the base plus that
 * packet's delay, so its least over the last {@link #SLICES} slices of
 * {@link #SLICE_NS} is the base plus the fastest delivery. Using a window
 * rather than the least ever follows the sound card's clock drifting against
 * the host's. The most over the same window is how late one can be, which a
 * TV must allow for.
 *
 * What is ts into the stream is due at base + ts + D on the host's clock, for
 * the D the host gives every TV ({@link PlayTogether}). Audio and video are
 * due by the same D, so they keep the lip sync they had on the host.
 */
public final class MediaSchedule {
    static final long SLICE_NS = 2_000_000_000L;
    static final int SLICES = 5;
    /** Arrivals before the base is believed: a quarter second of 5 ms packets, a second of 60 fps. */
    static final int MIN_ARRIVALS = 50;

    /** Closer than this is on time: a track's timestamp is not steadier than that. */
    static final long DEADBAND_NS = 1_000_000;
    /** Off by this much or more, the error is taken out at once, not a frame at a time. */
    static final long JUMP_NS = 20_000_000;

    private final long[] sliceId = new long[SLICES];
    private final long[] least = new long[SLICES];
    private final long[] most = new long[SLICES];
    private int arrivals;

    public MediaSchedule() {
        java.util.Arrays.fill(sliceId, Long.MIN_VALUE);
    }

    /**
     * What the host stamped `tsNs` (its timestamp, in ns) arrived at
     * `hostArrivalNs` on the host's clock, `localArrivalNs` on this one's.
     */
    public synchronized void arrived(long tsNs, long hostArrivalNs, long localArrivalNs) {
        long q = hostArrivalNs - tsNs;
        long id = Math.floorDiv(localArrivalNs, SLICE_NS);
        int i = (int) Math.floorMod(id, (long) SLICES);
        if (sliceId[i] != id) {
            sliceId[i] = id;
            least[i] = q;
            most[i] = q;
        } else {
            least[i] = Math.min(least[i], q);
            most[i] = Math.max(most[i], q);
        }
        arrivals++;
    }

    /** The host time of ts 0; null until enough has arrived. */
    public synchronized Long baseNs(long localNowNs) {
        if (arrivals < MIN_ARRIVALS) {
            return null;
        }
        long now = Math.floorDiv(localNowNs, SLICE_NS);
        Long base = null;
        for (int i = 0; i < SLICES; i++) {
            if (sliceId[i] > now - SLICES && sliceId[i] <= now) {
                base = base == null ? least[i] : Math.min(base, least[i]);
            }
        }
        return base;
    }

    /** How much later than the fastest a packet arrived, over the window; 0 until known. */
    public synchronized long lateNs(long localNowNs) {
        Long base = baseNs(localNowNs);
        if (base == null) {
            return 0;
        }
        long now = Math.floorDiv(localNowNs, SLICE_NS);
        long late = 0;
        for (int i = 0; i < SLICES; i++) {
            if (sliceId[i] > now - SLICES && sliceId[i] <= now) {
                late = Math.max(late, most[i] - base);
            }
        }
        return late;
    }

    /** The host time audio `frame` frames into the packet of `tsMs` is due, `delayMs` behind. */
    public static long dueNs(long baseNs, long tsMs, int frame, int sampleRate, int delayMs) {
        return baseNs + (tsMs + delayMs) * 1_000_000L + frame * 1_000_000_000L / sampleRate;
    }

    /** The ns of a video frame's 90 kHz RTP timestamp, `unwrapped` past its 32 bits. */
    public static long videoNs(long unwrapped) {
        return unwrapped * 1_000_000L / 90;
    }

    /**
     * What to do before writing the next sample, which would play `errorNs`
     * after it is due (before, when negative): frames to skip (positive) or
     * to add (negative). Small errors are taken out a frame at a time, which
     * is not heard; large ones at once.
     */
    public static int correction(long errorNs, int sampleRate) {
        // Called once a packet, with the error smoothed over packets for the
        // small corrections; see AndroidAudioRenderer's scheduler.
        long abs = Math.abs(errorNs);
        if (abs < DEADBAND_NS) {
            return 0;
        }
        if (abs < JUMP_NS) {
            return errorNs > 0 ? 1 : -1;
        }
        return (int) (errorNs * sampleRate / 1_000_000_000L);
    }
}
