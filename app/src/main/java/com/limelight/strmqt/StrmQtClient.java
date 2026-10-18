package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Collections;

/**
 * The control channel to StrmQt's Web Remote: one kept-alive HTTPS connection
 * to a pinned certificate, with requests sent in order on a thread of its own.
 *
 * A button press is only worth sending while it is fresh, so nothing here
 * retries late: a request that fails takes everything queued behind it with it.
 *
 * Pairing: this device shows a code of its own and asks StrmQt with it every
 * 2 s until someone types it into StrmQt; StrmQt then hands out a token that
 * stays good until the device is removed in StrmQt.
 */
public final class StrmQtClient {
    public enum State {
        /** Nothing has been tried yet. */
        CONNECTING,
        UP,
        /** StrmQt cannot be reached. */
        DOWN,
        /**
         * StrmQt does not accept this device: it was removed in StrmQt, or was
         * never paired. Nothing more is sent until it is paired again.
         */
        UNPAIRED,
        /** StrmQt presents a different certificate than the one that was paired. */
        CERT_CHANGED
    }

    public interface Listener {
        /** Called on the client's own thread, only when the state changes. */
        void onState(State state);
    }

    /** How pairing is going; see {@link #startPairing}. */
    public enum Pairing {
        /** StrmQt is waiting for the code to be typed in. The code belongs on screen. */
        WAITING,
        /** A code was typed into StrmQt, but not this one. Still asking with the same code. */
        WRONG_CODE,
        /** Too many wrong codes from this address; asking again in 30 s. */
        LOCKED_OUT,
        /** StrmQt stopped answering. Still asking. */
        UNREACHABLE,
        /** StrmQt presents a different certificate. Pairing has stopped. */
        CERT_CHANGED,
        /** This StrmQt does not know StrmLight pairing (it is older). Pairing has stopped. */
        UNSUPPORTED
    }

    public interface PairingListener {
        /** Called on the client's own thread, only when the status changes. */
        void onPairing(Pairing status);

        /**
         * Paired, and pairing has stopped.
         *
         * @param token               the token to keep, or null when StrmQt asks for no pairing
         * @param sunshineFingerprint the certificate StrmQt vouches for as its Sunshine's, from
         *                            the answer to the code; null when that answer did not say
         *                            (or there was none: a held token was still good, or StrmQt
         *                            asks for no pairing), and {@link #askSunshine(String, int,
         *                            String, String)} has to ask
         */
        void onPaired(String token, String sunshineFingerprint);
    }

    /**
     * What StrmQt says about its Sunshine (GET /api/strmlight/host), over the
     * pinned certificate and with the token: the Sunshine StrmLight may stream
     * from is the one StrmQt vouches for here, and no other.
     */
    public static final class Vouch {
        public enum Kind {
            /** StrmQt vouches for the Sunshine with this certificate. */
            VOUCHED,
            /** StrmQt does not say (it could not read the certificate, or it is older than this). */
            NONE,
            /** StrmQt does not know this device's token. */
            UNPAIRED,
            /** StrmQt did not answer. */
            UNREACHABLE,
            /** StrmQt presents a different certificate than the one that was paired. */
            CERT_CHANGED
        }

        public final Kind kind;
        /** Lowercase hex, as {@link CertPin#fingerprint} gives it; set when VOUCHED. */
        public final String fingerprint;
        /**
         * The stream's picture and sound as StrmQt sets them; null when the
         * answer had none (a StrmQt from before this, or no answer).
         */
        public final HostStream stream;
        /** The UDP port of the host's clock; 0 when the answer gave none. */
        public final int timePort;

        Vouch(Kind kind, String fingerprint) {
            this(kind, fingerprint, null, 0);
        }

        Vouch(Kind kind, String fingerprint, HostStream stream, int timePort) {
            this.kind = kind;
            this.fingerprint = fingerprint;
            this.stream = stream;
            this.timePort = timePort;
        }

        @Override
        public String toString() {
            return kind + (fingerprint != null ? " " + fingerprint : "")
                    + (stream != null ? ", stream " + stream : "");
        }
    }

    /** A press older than this when its turn comes is dropped, not sent. */
    static final long MAX_ACTION_AGE_MS = 700;
    /** StrmQt closes a connection idle for 60 s; this keeps ours open. */
    static final long KEEPALIVE_MS = 20000;
    static final long RETRY_WHEN_DOWN_MS = 2000;
    /** How often pairing asks StrmQt whether the code has been typed in. */
    static final long PAIR_POLL_MS = 2000;
    /** How long pairing waits after a 429; StrmQt sends no Retry-After. */
    static final long PAIR_LOCKOUT_MS = 30000;
    /** StrmQt shows at most this much of the device name. */
    static final int MAX_DEVICE_NAME = 40;

    private static final MediaType JSON = MediaType.get("application/json");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AUTHORIZED = Pattern.compile("\"authorized\"\\s*:\\s*true");
    private static final Pattern REQUIRED = Pattern.compile("\"required\"\\s*:\\s*true");
    private static final Pattern PAIRING = Pattern.compile("\"pairing\"\\s*:");
    private static final Pattern SUNSHINE = Pattern.compile("\"sunshineFingerprint\"\\s*:\\s*\"([^\"]*)\"");

    /** What GET /api/auth/pin says about this device. */
    private enum Auth { OPEN, AUTHORIZED, NEEDS_PAIRING, OLD_STRMQT }

    private final String baseUrl;
    private final Listener listener;
    private final OkHttpClient http;
    private final ScheduledExecutorService executor;
    private final AtomicInteger pending = new AtomicInteger();

    // Set by tests before startPairing.
    long pairPollMs = PAIR_POLL_MS;
    long pairLockoutMs = PAIR_LOCKOUT_MS;

    // Everything below is touched on the executor thread only.
    private String token;
    private State state = State.CONNECTING;
    private ScheduledFuture<?> keepAlive;
    private boolean stopped;
    // Whatever was queued before the last failure is stale and is dropped.
    private boolean hasFailed;
    private long failedAt;
    private Pairing pairingStatus;

    /**
     * @param token the token pairing gave, or null when there is none (yet)
     */
    public StrmQtClient(String host, int port, String certFingerprint, String token, Listener listener)
            throws GeneralSecurityException {
        this.baseUrl = "https://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + ":" + port;
        this.token = token == null || token.isEmpty() ? null : token;
        this.listener = listener;

        X509TrustManager trustManager = CertPin.trustManager(certFingerprint);
        this.http = new OkHttpClient.Builder()
                .sslSocketFactory(CertPin.socketFactory(trustManager), trustManager)
                // The pin names the certificate exactly; see CertPin.
                .hostnameVerifier((hostname, session) -> true)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(1, 5, TimeUnit.MINUTES))
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2000, TimeUnit.MILLISECONDS)
                .writeTimeout(2000, TimeUnit.MILLISECONDS)
                .callTimeout(3000, TimeUnit.MILLISECONDS)
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StrmQt remote");
            t.setDaemon(true);
            return t;
        });
    }

    /** A fresh pairing code: four digits, ASCII whatever the locale. */
    public static String newPairingCode(Random random) {
        return String.format(Locale.ROOT, "%04d", random.nextInt(10000));
    }

    /**
     * The device name as sent in X-StrmLight: printable, on one line, at most
     * 40 characters, and never empty (an empty header would make the request
     * a phone's).
     */
    public static String deviceHeader(String name) {
        StringBuilder out = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                out.append(Character.isISOControl(c) || Character.isWhitespace(c) ? ' ' : c);
            }
        }
        String tidy = out.toString().trim().replaceAll(" {2,}", " ");
        if (tidy.length() > MAX_DEVICE_NAME) {
            int end = MAX_DEVICE_NAME;
            if (Character.isHighSurrogate(tidy.charAt(end - 1))) {
                end--;
            }
            tidy = tidy.substring(0, end).trim();
        }
        return tidy.isEmpty() ? "StrmLight" : tidy;
    }

    /** Opens the connection and checks the token, so the first press does not pay for it. */
    public void start() {
        submit(this::keepAliveTick);
    }

    /** Stops everything, pairing included. Nothing is sent afterwards. */
    public void stop() {
        submit(() -> {
            stopped = true;
            if (keepAlive != null) {
                keepAlive.cancel(false);
            }
            http.connectionPool().evictAll();
        });
        executor.shutdown();
    }

    /** Requests queued or in flight. */
    public int pending() {
        return pending.get();
    }

    /**
     * Queues a button press.
     *
     * @param repeat a repeat of a held button: skipped when anything is still
     *               queued, so repeats never pile up and run on after release
     */
    public void send(final String action, boolean repeat) {
        if (repeat && pending.get() > 0) {
            return;
        }
        final long queuedAt = System.nanoTime();
        pending.incrementAndGet();
        final Runnable task = () -> {
            try {
                long ageMs = (System.nanoTime() - queuedAt) / 1000000;
                if (!stopped && state != State.UNPAIRED && queuedAfterLastFailure(queuedAt)
                        && ageMs <= MAX_ACTION_AGE_MS) {
                    doAction(action);
                }
            } finally {
                pending.decrementAndGet();
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
        }
    }

    private boolean queuedAfterLastFailure(long queuedAt) {
        return !hasFailed || queuedAt - failedAt > 0;
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    private void doAction(String action) {
        try {
            int code = postAction(action);
            if (code == 200) {
                setState(State.UP);
            } else if (code == 400) {
                // The channel is fine; this StrmQt has no such action.
                LimeLog.warning("StrmQt does not know the action " + action);
                setState(State.UP);
            } else if (code == 401) {
                // Tokens outlive StrmQt's restarts: this device was removed.
                unpaired();
            } else {
                LimeLog.warning("StrmQt answered " + code + " to " + action);
            }
        } catch (IOException e) {
            failed(e);
        }
        scheduleKeepAlive();
    }

    private int postAction(String action) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(baseUrl + "/api/action")
                .post(RequestBody.create("{\"action\":\"" + escape(action) + "\"}", JSON));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (Response response = http.newCall(request.build()).execute()) {
            return response.code();
        }
    }

    private Auth probe() throws IOException {
        Request.Builder request = new Request.Builder().url(baseUrl + "/api/auth/pin");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (Response response = http.newCall(request.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.code() != 200) {
                throw new IOException("Unexpected answer " + response.code());
            }
            if (!REQUIRED.matcher(body).find()) {
                return Auth.OPEN;
            }
            if (token != null && AUTHORIZED.matcher(body).find()) {
                return Auth.AUTHORIZED;
            }
            return PAIRING.matcher(body).find() ? Auth.NEEDS_PAIRING : Auth.OLD_STRMQT;
        }
    }

    private void unpaired() {
        token = null;
        setState(State.UNPAIRED);
    }

    private void failed(IOException e) {
        hasFailed = true;
        failedAt = System.nanoTime();
        http.connectionPool().evictAll();
        if (CertPin.isMismatch(e)) {
            setState(State.CERT_CHANGED);
        } else {
            LimeLog.warning("StrmQt remote: " + e);
            setState(State.DOWN);
        }
    }

    private void keepAliveTick() {
        if (stopped || state == State.UNPAIRED) {
            return;
        }
        try {
            Auth auth = probe();
            if (auth == Auth.OPEN || auth == Auth.AUTHORIZED) {
                setState(State.UP);
            } else {
                unpaired();
            }
        } catch (IOException e) {
            failed(e);
        }
        scheduleKeepAlive();
    }

    private void scheduleKeepAlive() {
        if (stopped || state == State.UNPAIRED) {
            return;
        }
        if (keepAlive != null) {
            keepAlive.cancel(false);
        }
        long delay = state == State.UP ? KEEPALIVE_MS : RETRY_WHEN_DOWN_MS;
        try {
            keepAlive = executor.schedule(this::keepAliveTick, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    // Pairing

    /**
     * Pairs by showing a code: first asks whether pairing is needed at all
     * (a token this client already holds may still be good), then asks StrmQt
     * with {@code code} every 2 s until someone types it in. Runs until it
     * succeeds, until it cannot go on (a changed certificate, an older
     * StrmQt), or until {@link #stop()}.
     *
     * @param code       the code on screen, kept for as long as this runs
     * @param deviceName how StrmQt lists this device; see {@link #deviceHeader}
     */
    public void startPairing(final String code, String deviceName, final PairingListener pairing) {
        final String header = deviceHeader(deviceName);
        submit(() -> pairTick(code, header, pairing, true));
    }

    private void pairTick(String code, String header, PairingListener pairing, boolean probeFirst) {
        if (stopped) {
            return;
        }
        long next = pairPollMs;
        boolean probeNext = false;
        try {
            if (probeFirst) {
                Auth auth = probe();
                if (auth == Auth.OPEN || auth == Auth.AUTHORIZED) {
                    paired(pairing, auth == Auth.OPEN ? null : token, null);
                    return;
                }
                if (auth == Auth.OLD_STRMQT) {
                    setPairing(pairing, Pairing.UNSUPPORTED);
                    return;
                }
                token = null;
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/auth/pin")
                    // The name may hold any character; StrmQt reads it as UTF-8.
                    .headers(new Headers.Builder().addUnsafeNonAscii("X-StrmLight", header).build())
                    .post(RequestBody.create("{\"pin\":\"" + escape(code) + "\"}", JSON))
                    .build();
            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                switch (response.code()) {
                    case 202:
                        setPairing(pairing, Pairing.WAITING);
                        break;
                    case 200:
                        Matcher m = TOKEN.matcher(body);
                        if (!m.find()) {
                            throw new IOException("No token in the pairing answer");
                        }
                        paired(pairing, m.group(1), sunshineFingerprint(body));
                        return;
                    case 403:
                        setPairing(pairing, Pairing.WRONG_CODE);
                        break;
                    case 429:
                        setPairing(pairing, Pairing.LOCKED_OUT);
                        next = pairLockoutMs;
                        break;
                    default:
                        throw new IOException("Unexpected answer " + response.code());
                }
            }
        } catch (IOException e) {
            failed(e);
            if (CertPin.isMismatch(e)) {
                setPairing(pairing, Pairing.CERT_CHANGED);
                return;
            }
            setPairing(pairing, Pairing.UNREACHABLE);
            // StrmQt may have come back with its settings changed.
            probeNext = true;
        }

        final boolean probe = probeNext;
        try {
            executor.schedule(() -> pairTick(code, header, pairing, probe), next, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    private void paired(PairingListener pairing, String newToken, String sunshineFingerprint) {
        token = newToken;
        setState(State.UP);
        if (!stopped) {
            pairing.onPaired(newToken, sunshineFingerprint);
        }
    }

    /** The "timePort" of a StrmQt answer: a UDP port, or 0 when there is none. */
    static int timePort(String body) {
        Map<String, Object> answer = body != null ? StrmQtDiscovery.Json.parseObject(body.trim()) : null;
        Object port = answer != null ? answer.get("timePort") : null;
        if (port instanceof Double) {
            double d = (Double) port;
            if (d == Math.rint(d) && d > 0 && d <= 65535) {
                return (int) d;
            }
        }
        return 0;
    }

    /** The vouched fingerprint in a StrmQt answer; null when it is absent, empty or not one. */
    static String sunshineFingerprint(String body) {
        Matcher m = SUNSHINE.matcher(body);
        return m.find() ? StrmQtDiscovery.normalizeFingerprint(m.group(1)) : null;
    }

    // The Sunshine StrmQt vouches for

    /**
     * Asks StrmQt which Sunshine is its own (GET /api/strmlight/host, with
     * the token when there is one), on the calling thread. Only on a client
     * that was never started: the token is read here, not on the executor.
     */
    private Vouch askSunshine() {
        Request.Builder request = new Request.Builder().url(baseUrl + "/api/strmlight/host");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (Response response = http.newCall(request.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            switch (response.code()) {
                case 200:
                    String fingerprint = sunshineFingerprint(body);
                    return new Vouch(fingerprint != null ? Vouch.Kind.VOUCHED : Vouch.Kind.NONE, fingerprint,
                            HostStream.fromAnswer(body), timePort(body));
                case 401:
                    return new Vouch(Vouch.Kind.UNPAIRED, null);
                case 404:
                    // A StrmQt from before this does not know the request.
                    return new Vouch(Vouch.Kind.NONE, null);
                default:
                    LimeLog.warning("StrmQt answered " + response.code() + " to /api/strmlight/host");
                    return new Vouch(Vouch.Kind.UNREACHABLE, null);
            }
        } catch (IOException e) {
            if (CertPin.isMismatch(e)) {
                return new Vouch(Vouch.Kind.CERT_CHANGED, null);
            }
            LimeLog.warning("StrmQt remote: " + e);
            return new Vouch(Vouch.Kind.UNREACHABLE, null);
        }
    }

    /**
     * Asks the StrmQt at host:port, over the pinned certificate, which
     * Sunshine is its own, and how it sets the stream. Blocks, so never on
     * the main thread; one request, no retries, at most 3 s, and it never
     * throws.
     *
     * @param token the paired token, or null when StrmQt asks for no pairing
     */
    public static Vouch askSunshine(String host, int port, String certFingerprint, String token) {
        StrmQtClient client;
        try {
            client = new StrmQtClient(host, port, certFingerprint, token, null);
        } catch (GeneralSecurityException | RuntimeException e) {
            LimeLog.warning("StrmQt remote: " + e);
            return new Vouch(Vouch.Kind.UNREACHABLE, null);
        }
        try {
            return client.askSunshine();
        } finally {
            client.stop();
        }
    }

    /** What POST /api/strmlight/sync was answered. */
    public static final class SyncAnswer {
        /** The host does not know the request: a StrmQt from before it. */
        public final boolean unknown;
        /** The answer's body; null unless it was a 200. */
        public final String body;

        SyncAnswer(boolean unknown, String body) {
            this.unknown = unknown;
            this.body = body;
        }
    }

    /**
     * Tells StrmQt this TV's own latency while it streams (POST
     * /api/strmlight/sync, see {@link PlayTogether}), on the calling thread.
     * Never throws.
     *
     * @param latencyMs negative while it is not measured yet
     */
    public SyncAnswer reportSync(double latencyMs) {
        String report = latencyMs >= 0 ? String.format(java.util.Locale.ROOT, "{\"latencyMs\":%.1f}", latencyMs) : "{}";
        Request.Builder request = new Request.Builder().url(baseUrl + "/api/strmlight/sync")
                .post(RequestBody.create(report, JSON));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (Response response = http.newCall(request.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.code() == 404) {
                return new SyncAnswer(true, null);
            }
            return new SyncAnswer(false, response.code() == 200 ? body : null);
        } catch (IOException e) {
            return new SyncAnswer(false, null);
        }
    }

    private void setPairing(PairingListener pairing, Pairing next) {
        if (next != pairingStatus) {
            pairingStatus = next;
            if (!stopped) {
                pairing.onPairing(next);
            }
        }
    }

    private void setState(State next) {
        if (next != state) {
            state = next;
            if (listener != null && !stopped) {
                listener.onState(next);
            }
        }
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
