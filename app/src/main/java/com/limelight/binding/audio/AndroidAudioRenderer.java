package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.strmqt.AudioSchedule;
import com.limelight.strmqt.ClockEstimate;
import com.limelight.strmqt.HostClock;
import com.limelight.strmqt.PlayTogether;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;
    // Streaming from a StrmQt that keeps time: a thread of its own writes
    // the track, so each sample can be played when it is due.
    private Scheduler scheduler;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        HostClock hostClock = HostClock.current();
        if (hostClock != null) {
            scheduler = new Scheduler(hostClock, audioConfiguration.channelCount, sampleRate, samplesPerFrame);
        }

        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData, int rtpTimestamp, long receiveTimeNs) {
        if (scheduler != null) {
            scheduler.arrived(audioData, rtpTimestamp & 0xFFFFFFFFL, receiveTimeNs);
        } else {
            playDecodedAudio(audioData);
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency at 40 ms in that situation.
            track.write(audioData, 0, audioData.length);
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }
    }

    @Override
    public void start() {
        if (scheduler != null) {
            scheduler.start();
        }
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        if (scheduler != null) {
            scheduler.stop();
        }
        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }

    /**
     * Plays each sample when the host says, for TVs playing together
     * (StrmLight's docs/synchronized-playback.md): D after the host sent it,
     * on the host's clock, the same D for every TV ({@link PlayTogether}).
     * With no D, as soon as it can, as upstream.
     *
     * Holding audio for D needs more room than the C library's queue, so the
     * decoder hands each packet to {@link #queue} and this thread writes the
     * track, with silence while nothing is due, so the track keeps playing
     * and its timestamp, which says when the next frame written will be
     * heard, stays good. Before each packet it compares that with when the
     * packet is due and skips or adds frames to close the difference.
     */
    private final class Scheduler implements Runnable {
        // Two seconds of 5 ms packets: more than any D.
        private static final int QUEUE = 400;
        // As soon as it can, at most this much waits, as upstream.
        private static final long ASAP_PENDING_NS = 40_000_000L;
        private static final long TIMESTAMP_EVERY_NS = 200_000_000L;
        // Allowed for decoding and handing over, in the latency reported.
        private static final long SLACK_NS = 2_000_000L;
        // Scheduled, at most this much is written ahead of what the track
        // has played. A track takes far more than it was asked for, and what
        // it holds is latency, which the host would make every TV wait for.
        private static final int AHEAD_MS = 60;
        // How much of each timestamp reading (5 a second) goes into frame 0's time.
        private static final double FRAME_ZERO_SMOOTHING = 0.1;
        // A reading this far off is a real change (an underrun the count missed).
        private static final double FRAME_ZERO_JUMP_NS = 30e6;
        // How much of each packet's error goes into the smoothed one.
        private static final double SMOOTHING = 0.05;
        // Seconds of measurement before this TV's latency is reported.
        private static final int LEADS_BEFORE_REPORT = 5;

        private final class Packet {
            final short[] pcm;
            final long tsMs;
            final long arrivedNs;

            Packet(short[] pcm, long tsMs, long arrivedNs) {
                this.pcm = pcm;
                this.tsMs = tsMs;
                this.arrivedNs = arrivedNs;
            }
        }

        private final HostClock hostClock;
        private final int channels;
        private final int sampleRate;
        private final short[] silence;
        private final long aheadFrames;
        private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(QUEUE);
        private final AudioSchedule schedule = new AudioSchedule();
        private final AudioTimestamp stamp = new AudioTimestamp();
        private final Thread thread;
        private volatile boolean stopped;

        // The writer thread's own.
        private long written;
        // When frame 0 would be heard, from the track's timestamps, smoothed:
        // some tracks' timestamps move a chunk at a time, many ms apart.
        private double frameZeroNs;
        private boolean haveFrameZero;
        private long stampReadAt;
        private int underruns;
        private long leadNs;
        private long leadSince;
        private long leadMostNs;
        // The last ten seconds' most, of which the median is reported: one
        // odd second does not move every TV.
        private final long[] leads = new long[10];
        private int leadCount;
        private int jumps;
        private int nudges;
        private int nudgesEarly;
        private long errorMinNs = Long.MAX_VALUE;
        private long errorMaxNs = Long.MIN_VALUE;
        private double reportedMs = -1;
        private double smoothedErrorNs;
        private long statusAt;
        // Counted on the decoder's thread, read on this one for the status line.
        private volatile int arrivals;
        private volatile int untimed;

        Scheduler(HostClock hostClock, int channels, int sampleRate, int samplesPerFrame) {
            this.hostClock = hostClock;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.silence = new short[samplesPerFrame * channels];
            this.aheadFrames = Math.max(2L * samplesPerFrame, (long) sampleRate * AHEAD_MS / 1000);
            this.thread = new Thread(this, "StrmQt audio");
            thread.setPriority(Thread.MAX_PRIORITY);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            thread.interrupt();
            // Pausing ends a write waiting on the track.
            track.pause();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** On the decoder's thread: never blocks. */
        void arrived(short[] pcm, long tsMs, long arrivedNs) {
            arrivals++;
            if (arrivedNs == 0) {
                untimed++;
            } else {
                ClockEstimate.Result clock = hostClock.estimate();
                if (clock != null) {
                    schedule.arrived(tsMs, clock.at(arrivedNs), arrivedNs);
                }
            }
            // The decoder fills one array for every packet; this one plays later.
            if (!queue.offer(new Packet(pcm.clone(), tsMs, arrivedNs))) {
                LimeLog.info("StrmQt audio: queue full, a packet dropped");
            }
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            Packet packet = null;
            int frame = 0;
            try {
                while (!stopped) {
                    int delayMs = PlayTogether.delayMs();
                    if (delayMs > 0 && written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) >= aheadFrames) {
                        Thread.sleep(2);
                        continue;
                    }
                    if (packet == null) {
                        // Scheduled, silence goes in when nothing is due; as
                        // soon as it can, nothing is written until there is
                        // something to play.
                        packet = delayMs > 0 ? queue.poll() : queue.poll(20, TimeUnit.MILLISECONDS);
                        frame = 0;
                    }
                    long now = System.nanoTime();
                    Long playAt = playTimeOfNextWrite(now);
                    if (playAt != null) {
                        noteLead(playAt - now, now);
                    }
                    ClockEstimate.Result clock = hostClock.estimate();
                    Long base = schedule.baseNs(now);
                    if (now - statusAt > 10_000_000_000L) {
                        statusAt = now;
                        LimeLog.info("StrmQt audio: " + arrivals + " packets (" + untimed + " untimed), queue "
                                + queue.size() + ", track time " + (playAt != null ? (playAt - now) / 1_000_000 + " ms ahead" : "none")
                                + ", host clock " + (clock != null ? "yes" : "no") + ", base " + (base != null ? "yes" : "no")
                                + ", late " + schedule.lateNs(now) / 1_000_000 + " ms, delay " + delayMs + " ms"
                                + ", reported " + Math.round(reportedMs) + " ms, " + jumps + " jumps, " + nudges
                                + " nudges (" + nudgesEarly + " early), " + underruns + " underruns"
                                + String.format(java.util.Locale.ROOT, ", error %.2f ms smoothed, %.2f..%.2f ms raw",
                                        smoothedErrorNs / 1e6, errorMinNs / 1e6, errorMaxNs / 1e6));
                        errorMinNs = Long.MAX_VALUE;
                        errorMaxNs = Long.MIN_VALUE;
                    }
                    if (delayMs <= 0 || playAt == null || clock == null || base == null) {
                        if (packet != null) {
                            write(packet.pcm, frame, packet.pcm.length / channels - frame);
                            packet = null;
                            dropStale(now);
                        } else if (delayMs > 0) {
                            write(silence, 0, silence.length / channels);
                        }
                        continue;
                    }
                    if (packet == null) {
                        write(silence, 0, silence.length / channels);
                        continue;
                    }

                    long due = clock.localAt(AudioSchedule.dueNs(base, packet.tsMs, frame, sampleRate, delayMs));
                    long error = playAt - due;
                    int frames = packet.pcm.length / channels;
                    // Far off: at once, from this error. Close: a frame at
                    // most each packet, from the smoothed error, since the
                    // track's timestamp wobbles by more than a frame.
                    int correction = AudioSchedule.correction(error, sampleRate);
                    if (correction > 1 || correction < -1) {
                        jumps++;
                        smoothedErrorNs = 0;
                        if (correction > 0) {
                            // Late: skip what would be heard late.
                            frame += correction;
                            if (frame >= frames) {
                                packet = null;
                            }
                        } else {
                            // Early: wait, playing silence.
                            write(silence, 0, Math.min(-correction, silence.length / channels));
                        }
                        continue;
                    }
                    smoothedErrorNs += (error - smoothedErrorNs) * SMOOTHING;
                    errorMinNs = Math.min(errorMinNs, error);
                    errorMaxNs = Math.max(errorMaxNs, error);
                    PlayTogether together = PlayTogether.current();
                    if (together != null) {
                        together.setErrorNs(Math.round(smoothedErrorNs));
                    }
                    int nudge = AudioSchedule.correction(Math.round(smoothedErrorNs), sampleRate);
                    long frameNs = 1_000_000_000L / sampleRate;
                    if (nudge > 0 && frame + 1 < frames) {
                        // A little late: one frame left out.
                        nudges++;
                        frame++;
                        smoothedErrorNs -= frameNs;
                    } else if (nudge < 0) {
                        // A little early: one frame twice.
                        nudges++;
                        nudgesEarly++;
                        write(packet.pcm, frame, 1);
                        smoothedErrorNs += frameNs;
                    }
                    write(packet.pcm, frame, frames - frame);
                    packet = null;
                }
            } catch (InterruptedException e) {
                // Stopped.
            }
        }

        /** Writes `frames` frames from `pcm` at `frame`, blocking while the track is full. */
        private void write(short[] pcm, int frame, int frames) {
            if (frames <= 0) {
                return;
            }
            int done = track.write(pcm, frame * channels, frames * channels);
            if (done > 0) {
                written += done / channels;
            }
        }

        /** When the next frame written will be heard, on System.nanoTime; null until the track says. */
        private Long playTimeOfNextWrite(long now) {
            int underrunsNow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? track.getUnderrunCount() : 0;
            boolean underran = underrunsNow != underruns;
            if (!haveFrameZero || now - stampReadAt > TIMESTAMP_EVERY_NS || underran) {
                underruns = underrunsNow;
                stampReadAt = now;
                if (track.getTimestamp(stamp) && stamp.nanoTime != 0) {
                    double reading = stamp.nanoTime - stamp.framePosition * 1e9 / sampleRate;
                    if (!haveFrameZero || underran || Math.abs(reading - frameZeroNs) > FRAME_ZERO_JUMP_NS) {
                        // A gap in what was heard, or the first reading.
                        frameZeroNs = reading;
                        haveFrameZero = true;
                    } else {
                        frameZeroNs += (reading - frameZeroNs) * FRAME_ZERO_SMOOTHING;
                    }
                }
            }
            if (!haveFrameZero) {
                return null;
            }
            return Math.round(frameZeroNs + written * 1e9 / sampleRate);
        }

        /** As soon as it can: at most ASAP_PENDING_NS waits, as upstream. */
        private void dropStale(long now) {
            Packet head;
            while ((head = queue.peek()) != null && head.arrivedNs != 0 && now - head.arrivedNs > ASAP_PENDING_NS) {
                queue.poll();
            }
        }

        /**
         * How far ahead of now what is written is heard, the most over each
         * second; with how late packets arrive, it is this TV's latency,
         * reported to the host.
         */
        private void noteLead(long lead, long now) {
            leadMostNs = Math.max(leadMostNs, lead);
            if (now - leadSince < 1_000_000_000L) {
                return;
            }
            leads[leadCount++ % leads.length] = leadMostNs;
            leadMostNs = 0;
            leadSince = now;
            long[] sorted = java.util.Arrays.copyOf(leads, Math.min(leadCount, leads.length));
            java.util.Arrays.sort(sorted);
            leadNs = sorted[sorted.length / 2];
            PlayTogether together = PlayTogether.current();
            if (together != null && schedule.baseNs(now) != null && leadCount >= LEADS_BEFORE_REPORT) {
                reportedMs = (schedule.lateNs(now) + leadNs + SLACK_NS) / 1e6;
                together.setLatencyMs(reportedMs);
            }
        }
    }
}
