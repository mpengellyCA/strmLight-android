package com.limelight.strmqt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Random;

public class AudioScheduleTest {
    private static final long MS = 1_000_000L;

    /** 5 ms packets sent from host time `base` on, each taking `fastest` plus up to `queue` to arrive. */
    private static long stream(AudioSchedule schedule, long base, long offset, long fastest, long queue,
                               int packets, long fromTs) {
        Random random = new Random(3);
        long local = 0;
        for (int i = 0; i < packets; i++) {
            long ts = fromTs + 5L * i;
            long hostArrival = base + ts * MS + fastest + (random.nextInt(3) == 0 ? (long) (random.nextDouble() * queue) : 0);
            local = hostArrival - offset;
            schedule.arrived(ts, hostArrival, local);
        }
        return local;
    }

    @Test
    public void theBaseIsFoundFromTheFastestArrivals() {
        AudioSchedule schedule = new AudioSchedule();
        long base = 7_000_000_000L;
        assertNull(schedule.baseNs(0));
        long now = stream(schedule, base, 123 * MS, 600_000, 8 * MS, 2000, 0);
        Long found = schedule.baseNs(now);
        assertNotNull(found);
        // The base and the fastest delivery, which every TV has too.
        assertEquals(base + 600_000, (long) found, 50_000);
        // And how late one can be.
        assertEquals(8 * MS, schedule.lateNs(now), MS);
    }

    @Test
    public void notEnoughArrivalsIsNoBase() {
        AudioSchedule schedule = new AudioSchedule();
        long now = stream(schedule, 0, 0, MS, 0, AudioSchedule.MIN_ARRIVALS - 1, 0);
        assertNull(schedule.baseNs(now));
        assertEquals(0, schedule.lateNs(now));
    }

    @Test
    public void theBaseFollowsTheSoundCardsClock() {
        // Its clock 100 ppm slow against the host's: host time per ts grows.
        AudioSchedule schedule = new AudioSchedule();
        long local = 0;
        for (int i = 0; i < 60 * 200; i++) {
            long ts = 5L * i;
            long host = ts * MS + Math.round(ts * MS * 1e-4) + MS;
            local = host;
            schedule.arrived(ts, host, local);
        }
        // A minute in, 6 ms have built up; the window holds the last 10 s.
        long expected = Math.round(60_000 * MS * 1e-4) + MS;
        assertEquals(expected, (long) schedule.baseNs(local), Math.round(10_000 * MS * 1e-4) + 1);
    }

    @Test
    public void aSampleIsDueItsTimestampAndTheDelayAfterTheBase() {
        assertEquals(1_000 * MS + (40 + 80) * MS, AudioSchedule.dueNs(1_000 * MS, 40, 0, 48000, 80));
        // 240 frames into a packet at 48 kHz is 5 ms on.
        assertEquals(1_000 * MS + (40 + 80 + 5) * MS, AudioSchedule.dueNs(1_000 * MS, 40, 240, 48000, 80));
    }

    @Test
    public void smallErrorsGoAFrameAtATimeLargeOnesAtOnce() {
        assertEquals(0, AudioSchedule.correction(100_000, 48000));
        assertEquals(0, AudioSchedule.correction(-100_000, 48000));
        assertEquals(1, AudioSchedule.correction(2 * MS, 48000));
        assertEquals(-1, AudioSchedule.correction(-2 * MS, 48000));
        // 20 ms late: skip 960 frames; 20 ms early: add as many.
        assertEquals(960, AudioSchedule.correction(20 * MS, 48000));
        assertEquals(-960, AudioSchedule.correction(-20 * MS, 48000));
    }

    @Test
    public void theHostsAnswerIsRead() {
        assertArrayEquals(new int[]{72, 2}, PlayTogether.answer("{\"delayMs\":72,\"tvs\":2}"));
        assertArrayEquals(new int[]{0, 1}, PlayTogether.answer("{\"delayMs\":0,\"tvs\":1}"));
        assertNull(PlayTogether.answer("{\"delayMs\":-5}"));
        assertNull(PlayTogether.answer("{\"delayMs\":\"72\"}"));
        assertNull(PlayTogether.answer("{}"));
        assertNull(PlayTogether.answer("nope"));
        assertNull(PlayTogether.answer(null));
    }
}
