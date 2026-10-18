package com.limelight.binding.video;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.strmqt.ClockEstimate;
import com.limelight.strmqt.HostClock;
import com.limelight.strmqt.MediaSchedule;
import com.limelight.strmqt.PlayTogether;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Shows each frame when the host says, for TVs playing together (StrmLight's
 * docs/synchronized-playback.md): D after the host captured it, on the host's
 * clock, by the same D as the sound, so picture and sound keep the lip sync
 * they had on the host. With no D, every frame goes to the decoder at once,
 * as upstream.
 *
 * Frames wait before the decoder, not after it: a Fire TV's decoder lends out
 * a few pictures at a time, so holding D of decoded ones would stall it. Each
 * is fed to the decoder {@link #LEAD_NS} before it is due, and the decoded
 * picture is handed to the display with its due time
 * (MediaCodec.releaseOutputBuffer(index, time)).
 */
final class VideoHold implements Runnable {
    /** Fed to the decoder this long before it is due: decoding takes a few tens of ms. */
    static final long LEAD_NS = 100_000_000L;
    /** Allowed for decoding and showing, in the latency reported. */
    static final long DECODE_NS = 40_000_000L;
    /** Four seconds of 60 fps: more than any D. */
    static final int QUEUE = 240;

    interface Decoder {
        /** Submits a held frame; `dueNs` (System.nanoTime) is when to show it, 0 for as soon as it can. */
        int submit(Unit unit, long dueNs);
    }

    static final class Unit {
        final byte[] data;
        final int length;
        final int type;
        final int frameNumber;
        final int frameType;
        final char hostLatency;
        final long receiveTimeUs;
        final long enqueueTimeUs;
        final long rtpNs;

        Unit(byte[] data, int length, int type, int frameNumber, int frameType, char hostLatency,
             long receiveTimeUs, long enqueueTimeUs, long rtpNs) {
            this.data = data;
            this.length = length;
            this.type = type;
            this.frameNumber = frameNumber;
            this.frameType = frameType;
            this.hostLatency = hostLatency;
            this.receiveTimeUs = receiveTimeUs;
            this.enqueueTimeUs = enqueueTimeUs;
            this.rtpNs = rtpNs;
        }
    }

    private final HostClock hostClock;
    private final Decoder decoder;
    private final MediaSchedule schedule = new MediaSchedule();
    private final ArrayDeque<Unit> queue = new ArrayDeque<>();
    private final Thread thread;
    private volatile boolean stopped;

    // The decoder's thread's own.
    private long lastRtp = -1;
    private long rtpWraps;
    private int lastFrameNumber = -1;

    // The display's thread's, read for the status line.
    private volatile int shown;
    private volatile int shownLate;
    private long statusAt;

    VideoHold(HostClock hostClock, Decoder decoder) {
        this.hostClock = hostClock;
        this.decoder = decoder;
        this.thread = new Thread(this, "StrmQt video");
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    void start() {
        thread.start();
    }

    void stop() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** On the C library's thread, as MediaCodecDecoderRenderer.submitDecodeUnit. */
    int submit(byte[] data, int length, int type, int frameNumber, int frameType, char hostLatency,
               long receiveTimeUs, long enqueueTimeUs, int rtpTimestamp, long receiveTimeNs) {
        long rtp = rtpTimestamp & 0xFFFFFFFFL;
        if (lastRtp >= 0 && rtp < lastRtp && lastRtp - rtp > (1L << 31)) {
            rtpWraps++;
        }
        lastRtp = rtp;
        long rtpNs = MediaSchedule.videoNs(rtp + (rtpWraps << 32));
        if (frameNumber != lastFrameNumber && receiveTimeNs != 0) {
            lastFrameNumber = frameNumber;
            ClockEstimate.Result clock = hostClock.estimate();
            if (clock != null) {
                schedule.arrived(rtpNs, clock.at(receiveTimeNs), receiveTimeNs);
            }
        }
        synchronized (queue) {
            if (queue.isEmpty() && dueNs(rtpNs, System.nanoTime()) == 0) {
                Unit unit = new Unit(data, length, type, frameNumber, frameType, hostLatency,
                        receiveTimeUs, enqueueTimeUs, rtpNs);
                return decoder.submit(unit, 0);
            }
            if (queue.size() >= QUEUE) {
                LimeLog.warning("StrmQt video: queue full, a frame dropped");
                MoonBridge.requestIdrFrame();
                return MoonBridge.DR_OK;
            }
            // The C library fills one array for every frame; this one is decoded later.
            queue.add(new Unit(Arrays.copyOf(data, length), length, type, frameNumber, frameType, hostLatency,
                    receiveTimeUs, enqueueTimeUs, rtpNs));
            queue.notifyAll();
        }
        return MoonBridge.DR_OK;
    }

    /** On the renderer's thread: a frame given `dueNs` went to the display at `releasedNs`. */
    void released(long dueNs, long releasedNs) {
        shown++;
        if (releasedNs > dueNs) {
            shownLate++;
        }
    }

    /** When to show what the host stamped `rtpNs`, on System.nanoTime; 0 for as soon as it can. */
    private long dueNs(long rtpNs, long now) {
        int delayMs = PlayTogether.delayMs();
        ClockEstimate.Result clock = hostClock.estimate();
        Long base = schedule.baseNs(now);
        if (delayMs <= 0 || clock == null || base == null) {
            return 0;
        }
        return clock.localAt(base + rtpNs + delayMs * 1_000_000L);
    }

    @Override
    public void run() {
        try {
            while (!stopped) {
                long now = System.nanoTime();
                report(now);
                synchronized (queue) {
                    Unit unit = queue.peek();
                    if (unit == null) {
                        queue.wait(5);
                        continue;
                    }
                    long due = dueNs(unit.rtpNs, now);
                    long wait = due == 0 ? 0 : due - LEAD_NS - now;
                    if (wait > 0) {
                        queue.wait(Math.max(1, Math.min(wait / 1_000_000L, 5)));
                        continue;
                    }
                    queue.poll();
                    if (decoder.submit(unit, due) != MoonBridge.DR_OK) {
                        MoonBridge.requestIdrFrame();
                    }
                }
            }
        } catch (InterruptedException e) {
            // Stopped.
        }
    }

    /** This TV's video path, for the host, and a status line every 10 s. */
    private void report(long now) {
        if (now - statusAt < 1_000_000_000L) {
            return;
        }
        boolean status = now / 10_000_000_000L != statusAt / 10_000_000_000L;
        statusAt = now;
        PlayTogether together = PlayTogether.current();
        Long base = schedule.baseNs(now);
        if (together != null && base != null && schedule.pastStart(now)) {
            // Typical, not the latest: a picture that misses its time is
            // shown a little late, while one late burst would hold back
            // every TV.
            together.setVideoLatencyMs((schedule.typicalLateNs(now) + DECODE_NS) / 1e6);
        }
        if (status) {
            int size;
            synchronized (queue) {
                size = queue.size();
            }
            LimeLog.info("StrmQt video: queue " + size + ", base " + (base != null ? "yes" : "no")
                    + ", late " + schedule.lateNs(now) / 1_000_000 + " ms (typically " + schedule.typicalLateNs(now) / 1_000_000 + "), delay " + PlayTogether.delayMs()
                    + " ms, " + shown + " shown on time or late, " + shownLate + " late");
        }
    }
}
