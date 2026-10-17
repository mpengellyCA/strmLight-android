package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pairs both halves with one code: the stream (Moonlight pairing with the
 * StrmQt host's Sunshine, the code as Moonlight's PIN) and the remote
 * (StrmQt's Web Remote, polled with the same code). Someone types the code
 * into StrmQt once; StrmQt arms its Web Remote with it and enters it into its
 * Sunshine, so both halves finish from that one entry.
 *
 * Each half retries by itself while this runs and is not paused: the stream
 * every 5 s after a refusal (1 s after a wrong PIN), the remote as
 * {@link StrmQtClient#startPairing} does. While paused, nothing new is sent;
 * a Moonlight request already waiting on the host is left to finish, since it
 * is one request rather than polling and cancelling it is not possible.
 *
 * All state lives on one thread of its own; the listener is called there.
 */
public final class OneCodePairing {
    public enum StreamState {
        /** The first request to Sunshine is on its way. */
        STARTING,
        /** Sunshine holds the pairing request and waits for the code. */
        WAITING,
        /** Sunshine has another pairing attempt open; trying again every 5 s. */
        SESSION_PENDING,
        /** A different code reached Sunshine; asking again with the same code. */
        WRONG_PIN,
        /** Sunshine refused; trying again in 5 s. */
        REFUSED,
        /** Sunshine does not answer; trying again in 5 s. */
        UNREACHABLE,
        PAIRED
    }

    public enum RemoteState {
        /** Reading the Web Remote's certificate. */
        STARTING,
        /** StrmQt waits for the code to be typed in. */
        WAITING,
        /** A different code was typed into StrmQt; still asking with this one. */
        WRONG_CODE,
        /** Too many wrong codes; asking again in 30 s. */
        LOCKED_OUT,
        /** The Web Remote does not answer; still trying. */
        UNREACHABLE,
        PAIRED,
        /** Stops pairing: the certificate changed while pairing. */
        CERT_CHANGED,
        /** Stops pairing: the certificate is not the one the host announced. */
        CERT_NOT_ANNOUNCED,
        /** Stops pairing: this StrmQt cannot pair by code. */
        UNSUPPORTED
    }

    /** Where to pair. */
    public static final class Target {
        final String host;
        final int remotePort;
        final int sunshinePort;
        /** The fingerprint discovery announced, lowercase hex; null when there is none to compare. */
        final String announcedFingerprint;
        /** A token from an earlier pairing, sent only to the certificate it was paired over. */
        final String knownToken;
        final String knownFingerprint;

        public Target(String host, int remotePort, int sunshinePort, String announcedFingerprint,
                      String knownToken, String knownFingerprint) {
            this.host = host;
            this.remotePort = remotePort;
            this.sunshinePort = sunshinePort;
            this.announcedFingerprint = announcedFingerprint;
            this.knownToken = knownToken;
            this.knownFingerprint = knownFingerprint;
        }
    }

    /** What both halves gave. */
    public static final class Result {
        public final String computerUuid;
        public final String computerName;
        /** The Web Remote token; null when StrmQt asks for no pairing. */
        public final String token;
        public final String fingerprint;

        Result(String computerUuid, String computerName, String token, String fingerprint) {
            this.computerUuid = computerUuid;
            this.computerName = computerName;
            this.token = token;
            this.fingerprint = fingerprint;
        }
    }

    public interface Listener {
        void onStream(StreamState state, String detail);

        void onRemote(RemoteState state);

        /** Both halves are paired; everything has stopped. */
        void onDone(Result result);

        /** The remote half cannot go on (see RemoteState); everything has stopped. */
        void onStopped(RemoteState why);
    }

    static final long STREAM_RETRY_MS = 5000;
    static final long WRONG_PIN_RETRY_MS = 1000;
    /** A Moonlight request unanswered this long is waiting on the host for the PIN. */
    static final long WAITING_AFTER_MS = 1500;
    static final long REMOTE_RETRY_MS = 2000;
    private static final int FETCH_TIMEOUT_MS = 4000;

    // Set by tests before start().
    long streamRetryMs = STREAM_RETRY_MS;
    long wrongPinRetryMs = WRONG_PIN_RETRY_MS;
    long waitingAfterMs = WAITING_AFTER_MS;
    long remoteRetryMs = REMOTE_RETRY_MS;
    long pairPollMs = StrmQtClient.PAIR_POLL_MS;
    long pairLockoutMs = StrmQtClient.PAIR_LOCKOUT_MS;

    private final Target target;
    private final StreamPairer pairer;
    private final String deviceName;
    private final Listener listener;
    private final ScheduledExecutorService control;
    private final ExecutorService workers;

    // Everything below is touched on the control thread only.
    private String code;
    private boolean started;
    private boolean paused;
    private boolean stopped;

    private StreamState streamState;
    private StreamPairer.Outcome streamPaired;
    private boolean streamInFlight;
    private int streamAttempt;
    private ScheduledFuture<?> streamRetry;

    private RemoteState remoteState;
    private String fingerprint;
    private boolean fetching;
    private StrmQtClient client;
    private int remoteGeneration;
    private ScheduledFuture<?> remoteRetry;
    private boolean remotePaired;
    private String token;

    public OneCodePairing(Target target, String code, String deviceName, StreamPairer pairer, Listener listener) {
        this.target = target;
        this.code = code;
        this.deviceName = deviceName;
        this.pairer = pairer;
        this.listener = listener;
        this.control = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StrmQt pairing");
            t.setDaemon(true);
            return t;
        });
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "StrmQt pairing request");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        post(() -> {
            started = true;
            setStream(StreamState.STARTING, null);
            setRemote(RemoteState.STARTING);
            kick();
        });
    }

    /** Nothing new is sent until {@link #resume()}. */
    public void pause() {
        post(() -> {
            paused = true;
            stopRemote();
            cancel(streamRetry);
            streamRetry = null;
        });
    }

    public void resume() {
        post(() -> {
            paused = false;
            kick();
        });
    }

    /** A fresh code: the remote asks with it at once, the stream from its next attempt. */
    public void setCode(final String newCode) {
        post(() -> {
            code = newCode;
            if (!remotePaired && client != null) {
                stopRemote();
                kick();
            }
            if (streamPaired == null && streamRetry != null) {
                // Waiting to retry; go now, with the new code.
                cancel(streamRetry);
                streamRetry = null;
                kick();
            }
        });
    }

    /** Stops everything. A Moonlight request waiting on the host is left to finish by itself. */
    public void stop() {
        post(this::halt);
        control.shutdown();
        workers.shutdown();
    }

    private void halt() {
        stopped = true;
        stopRemote();
        cancel(streamRetry);
        streamRetry = null;
    }

    private void post(Runnable task) {
        try {
            control.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    private ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        try {
            return control.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean active() {
        return started && !paused && !stopped;
    }

    private void kick() {
        if (!active()) {
            return;
        }
        kickStream();
        kickRemote();
    }

    // The stream half

    private void kickStream() {
        if (streamPaired != null || streamInFlight || streamRetry != null) {
            return;
        }
        streamInFlight = true;
        final int attempt = ++streamAttempt;
        final String pin = code;
        try {
            workers.execute(() -> {
                StreamPairer.Outcome outcome;
                try {
                    outcome = pairer.pair(target.host, target.sunshinePort, pin);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcome = StreamPairer.Outcome.of(StreamPairer.Result.UNREACHABLE, "Interrupted");
                } catch (RuntimeException e) {
                    LimeLog.warning("StrmQt stream pairing: " + e);
                    outcome = StreamPairer.Outcome.of(StreamPairer.Result.REFUSED, e.toString());
                }
                if (outcome == null) {
                    outcome = StreamPairer.Outcome.of(StreamPairer.Result.REFUSED, null);
                }
                final StreamPairer.Outcome result = outcome;
                post(() -> streamAnswered(result));
            });
        } catch (RejectedExecutionException e) {
            streamInFlight = false;
            return;
        }
        schedule(() -> {
            if (streamInFlight && attempt == streamAttempt && streamPaired == null && !stopped) {
                setStream(StreamState.WAITING, null);
            }
        }, waitingAfterMs);
    }

    private void streamAnswered(StreamPairer.Outcome outcome) {
        streamInFlight = false;
        LimeLog.info("StrmQt stream pairing: " + outcome);
        if (stopped) {
            return;
        }
        if (outcome.paired()) {
            streamPaired = outcome;
            setStream(StreamState.PAIRED, null);
            checkDone();
            return;
        }
        long delay = streamRetryMs;
        switch (outcome.result) {
            case SESSION_PENDING:
                setStream(StreamState.SESSION_PENDING, outcome.detail);
                break;
            case WRONG_PIN:
                setStream(StreamState.WRONG_PIN, outcome.detail);
                delay = wrongPinRetryMs;
                break;
            case UNREACHABLE:
                setStream(StreamState.UNREACHABLE, outcome.detail);
                break;
            case REFUSED:
            default:
                setStream(StreamState.REFUSED, outcome.detail);
                break;
        }
        if (!paused) {
            streamRetry = schedule(() -> {
                streamRetry = null;
                kick();
            }, delay);
        }
    }

    private void setStream(StreamState next, String detail) {
        // The same failure again is not news; WAITING after it is.
        if (next != streamState) {
            streamState = next;
            listener.onStream(next, detail);
        }
    }

    // The remote half

    private void kickRemote() {
        if (remotePaired || client != null || fetching || remoteRetry != null) {
            return;
        }
        if (fingerprint == null) {
            fetchCertificate();
        } else {
            startClient();
        }
    }

    /** Pins whatever certificate the Web Remote presents now: pairing is where a new one is accepted. */
    private void fetchCertificate() {
        fetching = true;
        final int generation = remoteGeneration;
        try {
            workers.execute(() -> {
                String found = null;
                try {
                    X509Certificate cert = CertPin.fetch(target.host, target.remotePort, FETCH_TIMEOUT_MS);
                    found = CertPin.fingerprint(cert);
                } catch (Exception e) {
                    LimeLog.warning("StrmQt pairing: " + e);
                }
                final String presented = found;
                post(() -> certificateFetched(generation, presented));
            });
        } catch (RejectedExecutionException e) {
            fetching = false;
        }
    }

    private void certificateFetched(int generation, String presented) {
        fetching = false;
        if (stopped) {
            return;
        }
        if (presented == null) {
            setRemote(RemoteState.UNREACHABLE);
            if (!paused) {
                remoteRetry = schedule(() -> {
                    remoteRetry = null;
                    kick();
                }, remoteRetryMs);
            }
            return;
        }
        if (target.announcedFingerprint != null && !target.announcedFingerprint.equalsIgnoreCase(presented)) {
            LimeLog.warning("StrmQt pairing: the Web Remote presents " + presented
                    + ", the host announced " + target.announcedFingerprint);
            remoteFailed(RemoteState.CERT_NOT_ANNOUNCED);
            return;
        }
        fingerprint = presented;
        if (generation == remoteGeneration && active()) {
            startClient();
        } else {
            kick();
        }
    }

    private void startClient() {
        String held = fingerprint.equalsIgnoreCase(target.knownFingerprint) ? target.knownToken : null;
        final StrmQtClient c;
        try {
            c = new StrmQtClient(target.host, target.remotePort, fingerprint, held, null);
        } catch (Exception e) {
            LimeLog.warning("StrmQt pairing: " + e);
            setRemote(RemoteState.UNREACHABLE);
            remoteRetry = schedule(() -> {
                remoteRetry = null;
                kick();
            }, remoteRetryMs);
            return;
        }
        c.pairPollMs = pairPollMs;
        c.pairLockoutMs = pairLockoutMs;
        client = c;
        c.startPairing(code, deviceName, new StrmQtClient.PairingListener() {
            @Override
            public void onPairing(final StrmQtClient.Pairing status) {
                post(() -> {
                    if (c == client) {
                        remotePairing(status);
                    }
                });
            }

            @Override
            public void onPaired(final String newToken) {
                post(() -> {
                    if (c == client) {
                        remotePaired(newToken);
                    }
                });
            }
        });
    }

    private void remotePairing(StrmQtClient.Pairing status) {
        switch (status) {
            case WAITING:
                setRemote(RemoteState.WAITING);
                break;
            case WRONG_CODE:
                setRemote(RemoteState.WRONG_CODE);
                break;
            case LOCKED_OUT:
                setRemote(RemoteState.LOCKED_OUT);
                break;
            case UNREACHABLE:
                setRemote(RemoteState.UNREACHABLE);
                break;
            case CERT_CHANGED:
                remoteFailed(RemoteState.CERT_CHANGED);
                break;
            case UNSUPPORTED:
                remoteFailed(RemoteState.UNSUPPORTED);
                break;
        }
    }

    private void remotePaired(String newToken) {
        remotePaired = true;
        token = newToken;
        stopRemote();
        setRemote(RemoteState.PAIRED);
        checkDone();
    }

    private void remoteFailed(RemoteState why) {
        setRemote(why);
        halt();
        listener.onStopped(why);
    }

    private void stopRemote() {
        remoteGeneration++;
        if (client != null) {
            client.stop();
            client = null;
        }
        cancel(remoteRetry);
        remoteRetry = null;
    }

    private void setRemote(RemoteState next) {
        if (next != remoteState) {
            remoteState = next;
            listener.onRemote(next);
        }
    }

    private void checkDone() {
        if (streamPaired != null && remotePaired && !stopped) {
            halt();
            listener.onDone(new Result(streamPaired.uuid, streamPaired.name, token, fingerprint));
        }
    }
}
