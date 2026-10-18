package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;

/**
 * This TV's part in playing together with the other TVs streaming from the
 * same StrmQt host (docs/synchronized-playback.md): about once a second it
 * tells the host how long its own path from the host to its speaker is, and
 * is told how far behind the host to play, the same for every TV; 0 when it
 * is the only one, when it plays as soon as it can.
 *
 * A host from before this answers 404, and is not asked again. A host that
 * does not answer keeps the delay it gave for {@link #KEEP_MS}, then 0.
 */
public final class PlayTogether {
    static final long REPORT_MS = 1000;
    static final long KEEP_MS = 5000;

    private static volatile PlayTogether current;

    private final StrmQtClient client;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile double latencyMs = -1;
    private volatile double videoLatencyMs = -1;
    private volatile int delayMs;
    private volatile int tvs;
    private volatile long errorNs;
    private volatile boolean held;

    private PlayTogether(StrmQtClient client) {
        this.client = client;
        this.thread = new Thread(this::run, "StrmQt together");
        thread.setDaemon(true);
    }

    /** Starts for this stream, and makes it {@link #current}; null when this TV is not paired with a StrmQt. */
    public static PlayTogether forStream(StrmQtPrefs prefs) {
        String host = prefs.remoteHost();
        if (host == null || prefs.certFingerprint() == null || prefs.token() == null) {
            return null;
        }
        StrmQtClient client;
        try {
            client = new StrmQtClient(host, prefs.remotePort(), prefs.certFingerprint(), prefs.token(), null);
        } catch (GeneralSecurityException | RuntimeException e) {
            LimeLog.warning("StrmQt together: " + e);
            return null;
        }
        PlayTogether together = new PlayTogether(client);
        current = together;
        together.thread.start();
        return together;
    }

    public static PlayTogether current() {
        return current;
    }

    /** How far behind the host every TV plays, in ms; 0 for as soon as it can. */
    public static int delayMs() {
        PlayTogether together = current;
        return together != null ? together.delayMs : 0;
    }

    public void stop() {
        stopped = true;
        if (current == this) {
            current = null;
        }
        thread.interrupt();
        client.stop();
    }

    /** This TV's own path, host to speaker, as the audio renderer measures it. */
    public void setLatencyMs(double latency) {
        latencyMs = latency;
    }

    /** This TV's own video path, host to screen, as the video hold estimates it. */
    public void setVideoLatencyMs(double latency) {
        videoLatencyMs = latency;
    }

    /** How far off its due time the audio plays, for the overlay; positive is late. */
    public void setErrorNs(long error) {
        errorNs = error;
    }

    /**
     * Whether `delay` counts this TV: its sound and its picture are both
     * measured, and the delay is no less than the slower of them.
     */
    public boolean counted(int delay) {
        double audio = latencyMs;
        double video = videoLatencyMs;
        return audio >= 0 && video >= 0 && delay >= Math.max(audio, video);
    }

    /** Whether the host has answered yet, so that a delay of 0 is its. */
    public boolean answered() {
        return tvs > 0;
    }

    /** Whether this TV is held while it joins; the audio renderer's. */
    public void setHeld(boolean hold) {
        held = hold;
    }

    /**
     * Whether this TV should be held, muted and covered: it has just joined
     * TVs playing together and is not in step with them yet.
     */
    public boolean holding() {
        return held;
    }

    /** One line for the performance overlay. */
    public String describe() {
        if (delayMs <= 0) {
            return tvs > 1 ? "Together: measuring" : "Together: this TV alone";
        }
        return String.format(Locale.ROOT, "Together: %d TVs, %d ms behind the host; this TV %.0f ms, %+.2f ms off",
                tvs, delayMs, latencyMs, errorNs / 1e6);
    }

    /** The "delayMs" and "tvs" of the host's answer; null when it is not one. */
    static int[] answer(String body) {
        Map<String, Object> answer = body != null ? StrmQtDiscovery.Json.parseObject(body.trim()) : null;
        Object delay = answer != null ? answer.get("delayMs") : null;
        Object count = answer != null ? answer.get("tvs") : null;
        if (!(delay instanceof Double) || (Double) delay < 0 || (Double) delay > 1000) {
            return null;
        }
        return new int[]{(int) Math.round((Double) delay), count instanceof Double ? (int) Math.round((Double) count) : 0};
    }

    private void run() {
        long answeredAt = System.nanoTime();
        while (!stopped) {
            try {
                Thread.sleep(REPORT_MS);
            } catch (InterruptedException e) {
                return;
            }
            // The slower of sound and picture: both are held by the same D.
            // Until it is measured, the report says only that this TV is
            // streaming, so it is told D at once and can be held.
            double latency = latencyMs;
            if (latency >= 0) {
                latency = Math.max(latency, videoLatencyMs);
            }
            StrmQtClient.SyncAnswer reply = client.reportSync(latency);
            if (reply.unknown) {
                LimeLog.info("StrmQt together: this host does not know playing together");
                delayMs = 0;
                return;
            }
            int[] answer = answer(reply.body);
            if (answer != null) {
                if (answer[0] != delayMs) {
                    LimeLog.info("StrmQt together: " + answer[1] + " TVs, " + answer[0] + " ms behind the host");
                }
                delayMs = answer[0];
                tvs = answer[1];
                answeredAt = System.nanoTime();
            } else if (System.nanoTime() - answeredAt > KEEP_MS * 1_000_000L) {
                delayMs = 0;
            }
        }
    }
}
