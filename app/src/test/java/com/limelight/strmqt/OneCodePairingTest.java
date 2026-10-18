package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The one-code flow: a stand-in Sunshine for the stream half (paired,
 * a stale session then paired, wrong PIN, unreachable) and the stand-in Web
 * Remote for the remote half, which vouches for StrmQt's Sunshine.
 */
public class OneCodePairingTest {
    private static final String CODE = "4821";
    private static final String NAME = "Fire TV Stick";
    private static final String UUID = "SUNSHINE-STRMQT-UUID";
    private static final int SUNSHINE_PORT = 48989;
    /** The certificate of StrmQt's Sunshine, which StrmQt vouches for. */
    private static final String SUNSHINE_CERT =
            "5f1e0c4b9a7d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f";
    /** Another Sunshine's: a plain one, or the desktop's on the same machine. */
    private static final String OTHER_CERT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static FakeStrmQt.Identity identity;
    private static FakeStrmQt.Identity otherIdentity;

    private FakeStrmQt server;
    private FakeSunshine sunshine;
    private OneCodePairing pairing;

    private final List<OneCodePairing.StreamState> streamStates =
            Collections.synchronizedList(new ArrayList<OneCodePairing.StreamState>());
    private final List<OneCodePairing.RemoteState> remoteStates =
            Collections.synchronizedList(new ArrayList<OneCodePairing.RemoteState>());
    private volatile OneCodePairing.Result done;
    private volatile OneCodePairing.RemoteState stopped;

    /**
     * Sunshine as Moonlight's pairing meets it: each attempt either gets a
     * scripted answer at once, or waits until a PIN is entered on the host
     * and is then paired (right PIN) or not (wrong PIN).
     */
    static final class FakeSunshine implements StreamPairer {
        final Deque<StreamPairer.Result> script = new ArrayDeque<>();
        final List<String> pins = Collections.synchronizedList(new ArrayList<String>());
        final List<Long> attemptTimes = Collections.synchronizedList(new ArrayList<Long>());
        final List<String> addresses = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean alreadyPaired;
        /** The certificate this Sunshine presents, as Moonlight pins it. */
        volatile String cert = SUNSHINE_CERT;
        private String entered;
        private int waiting;
        private boolean released;

        synchronized void answerNext(StreamPairer.Result... results) {
            Collections.addAll(script, results);
        }

        /** Someone (StrmQt) enters a PIN into this Sunshine. */
        synchronized boolean enter(String pin) {
            if (waiting == 0) {
                return false;
            }
            entered = pin;
            notifyAll();
            return true;
        }

        synchronized int waiting() {
            return waiting;
        }

        synchronized void release() {
            released = true;
            notifyAll();
        }

        @Override
        public Outcome pair(String host, int port, String pin) throws InterruptedException {
            pins.add(pin);
            addresses.add(host + ":" + port);
            attemptTimes.add(System.nanoTime());
            synchronized (this) {
                if (alreadyPaired) {
                    return new Outcome(Result.ALREADY_PAIRED, UUID, "cachyos-minipc", cert, null);
                }
                Result scripted = script.poll();
                if (scripted != null) {
                    return scripted == Result.PAIRED || scripted == Result.ALREADY_PAIRED
                            ? new Outcome(scripted, UUID, "cachyos-minipc", cert, null)
                            : Outcome.of(scripted, "scripted");
                }
                waiting++;
                try {
                    while (entered == null && !released) {
                        wait();
                    }
                    if (released) {
                        return Outcome.of(Result.UNREACHABLE, "released");
                    }
                    String typed = entered;
                    entered = null;
                    return typed.equals(pin) ? new Outcome(Result.PAIRED, UUID, "cachyos-minipc", cert, null)
                            : Outcome.of(Result.WRONG_PIN, null);
                } finally {
                    waiting--;
                }
            }
        }
    }

    @BeforeClass
    public static void makeCertificates() throws Exception {
        identity = new FakeStrmQt.Identity();
        otherIdentity = new FakeStrmQt.Identity();
    }

    @Before
    public void start() throws Exception {
        server = new FakeStrmQt(identity, 0);
        server.sunshineFingerprint = SUNSHINE_CERT;
        sunshine = new FakeSunshine();
    }

    @After
    public void stop() {
        if (pairing != null) {
            pairing.stop();
        }
        sunshine.release();
        server.stop();
    }

    private OneCodePairing.Target target(String announced, String knownToken, String knownFingerprint) {
        return new OneCodePairing.Target("127.0.0.1", server.port(), SUNSHINE_PORT, announced,
                knownToken, knownFingerprint);
    }

    private OneCodePairing pairing(OneCodePairing.Target target) {
        pairing = new OneCodePairing(target, CODE, NAME, sunshine, new OneCodePairing.Listener() {
            @Override
            public void onStream(OneCodePairing.StreamState state, String detail) {
                streamStates.add(state);
            }

            @Override
            public void onRemote(OneCodePairing.RemoteState state) {
                remoteStates.add(state);
            }

            @Override
            public void onDone(OneCodePairing.Result result) {
                done = result;
            }

            @Override
            public void onStopped(OneCodePairing.RemoteState why) {
                stopped = why;
            }
        });
        pairing.streamRetryMs = 150;
        pairing.wrongPinRetryMs = 50;
        pairing.waitingAfterMs = 100;
        pairing.remoteRetryMs = 100;
        pairing.pairPollMs = 100;
        pairing.pairLockoutMs = 300;
        return pairing;
    }

    private OneCodePairing pairing() {
        return pairing(target(null, null, null));
    }

    private static void await(String what, long timeoutMs, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.call()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static <T> T last(List<T> list) {
        synchronized (list) {
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }
    }

    private void awaitStream(OneCodePairing.StreamState wanted) throws Exception {
        await("stream " + wanted + " (is " + streamStates + ")", 5000, () -> last(streamStates) == wanted);
    }

    private void awaitRemote(OneCodePairing.RemoteState wanted) throws Exception {
        await("remote " + wanted + " (is " + remoteStates + ")", 5000, () -> last(remoteStates) == wanted);
    }

    /** For states that pass quickly: seen at some point. */
    private void awaitStreamSeen(OneCodePairing.StreamState wanted) throws Exception {
        await("stream " + wanted + " (was " + streamStates + ")", 5000, () -> streamStates.contains(wanted));
    }

    private void awaitRemoteSeen(OneCodePairing.RemoteState wanted) throws Exception {
        await("remote " + wanted + " (was " + remoteStates + ")", 5000, () -> remoteStates.contains(wanted));
    }

    /** StrmQt, once the code is typed into it: arms its Web Remote and enters the PIN into its Sunshine. */
    private void typeIntoStrmQt(String code) throws Exception {
        server.typeCode(code);
        await("Sunshine to wait for the PIN", 5000, () -> sunshine.enter(code));
    }

    private String fingerprint() throws Exception {
        return CertPin.fingerprint(identity.cert);
    }

    @Test
    public void oneEntryPairsTheStreamAndTheRemote() throws Exception {
        pairing().start();
        awaitStream(OneCodePairing.StreamState.WAITING);
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        assertNull(done);

        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
        assertEquals(UUID, done.computerUuid);
        assertTrue("the token is one StrmQt keeps", server.tokens.contains(done.token));
        assertEquals(fingerprint(), done.fingerprint);
        assertEquals("the Sunshine StrmQt vouched for", SUNSHINE_CERT, done.sunshineFingerprint);
        assertEquals("the answer to the code named it", 0, server.hostRequests.get());
        assertEquals(OneCodePairing.StreamState.PAIRED, last(streamStates));
        assertEquals(OneCodePairing.RemoteState.PAIRED, last(remoteStates));

        // Moonlight pairing went to the StrmQt host's Sunshine, with the code on screen as the PIN.
        assertEquals(Collections.singletonList("127.0.0.1:" + SUNSHINE_PORT), sunshine.addresses);
        assertEquals(Collections.singletonList(CODE), sunshine.pins);
        for (String body : server.pairBodies) {
            assertEquals("{\"pin\":\"" + CODE + "\"}", body);
        }
        for (String name : server.pairNames) {
            assertEquals(NAME, name);
        }

        // Nothing more is sent once both are paired.
        int posts = server.pairPosts.get();
        Thread.sleep(400);
        assertEquals(posts, server.pairPosts.get());
        assertEquals(1, sunshine.pins.size());
    }

    @Test
    public void aStaleSunshineSessionIsRetriedEveryFewSecondsUntilItClears() throws Exception {
        sunshine.answerNext(StreamPairer.Result.SESSION_PENDING, StreamPairer.Result.SESSION_PENDING,
                StreamPairer.Result.SESSION_PENDING);
        pairing().start();
        awaitStreamSeen(OneCodePairing.StreamState.SESSION_PENDING);
        await("the stale session to clear", 5000, () -> sunshine.waiting() == 1);
        awaitStream(OneCodePairing.StreamState.WAITING);

        assertEquals(4, sunshine.attemptTimes.size());
        for (int i = 1; i < 4; i++) {
            long gapMs = (sunshine.attemptTimes.get(i) - sunshine.attemptTimes.get(i - 1)) / 1000000;
            assertTrue("retries wait (" + gapMs + " ms)", gapMs >= 140);
        }
        // The same failure three times is reported once.
        assertEquals(1, Collections.frequency(streamStates, OneCodePairing.StreamState.SESSION_PENDING));

        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
        assertEquals(Collections.nCopies(4, CODE), sunshine.pins);
    }

    @Test
    public void aWrongPinStartsTheStreamHalfAgainWithTheSameCode() throws Exception {
        pairing().start();
        await("Sunshine to wait", 5000, () -> sunshine.waiting() == 1);
        // StrmQt was given another code; its Sunshine gets that one.
        server.typeCode("1111");
        assertTrue(sunshine.enter("1111"));
        awaitStreamSeen(OneCodePairing.StreamState.WRONG_PIN);
        awaitRemoteSeen(OneCodePairing.RemoteState.WRONG_CODE);
        await("a new Moonlight attempt", 5000, () -> sunshine.waiting() == 1);
        assertEquals(CODE, last(sunshine.pins));
        assertNull(done);

        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
        assertEquals(UUID, done.computerUuid);
    }

    @Test
    public void anUnreachableSunshineIsRetriedAndTheRemoteWaitsForIt() throws Exception {
        sunshine.answerNext(StreamPairer.Result.UNREACHABLE, StreamPairer.Result.UNREACHABLE,
                StreamPairer.Result.REFUSED, StreamPairer.Result.UNREACHABLE);
        pairing().start();
        awaitStreamSeen(OneCodePairing.StreamState.UNREACHABLE);
        server.typeCode(CODE);
        awaitRemote(OneCodePairing.RemoteState.PAIRED);
        assertNull("the remote alone is not done", done);

        await("Sunshine to come back", 5000, () -> sunshine.waiting() == 1);
        assertTrue(streamStates.contains(OneCodePairing.StreamState.REFUSED));
        assertTrue(sunshine.enter(CODE));
        await("both halves", 5000, () -> done != null);
        // The remote was not asked again while the stream caught up.
        assertEquals(OneCodePairing.RemoteState.PAIRED, last(remoteStates));
    }

    @Test
    public void alreadyPairedHalvesNeedNoCode() throws Exception {
        // Pair again: Sunshine still knows this device, and StrmQt still knows its token.
        sunshine.alreadyPaired = true;
        String token = server.pairDevice();
        pairing(target(fingerprint(), token, fingerprint())).start();
        await("both halves", 5000, () -> done != null);
        assertEquals(UUID, done.computerUuid);
        assertEquals(token, done.token);
        assertEquals("no code was asked for", 0, server.pairPosts.get());
        // No code, so StrmQt was asked which Sunshine is its own.
        assertTrue(server.hostRequests.get() > 0);
        assertEquals(SUNSHINE_CERT, done.sunshineFingerprint);
    }

    @Test
    public void aTokenGoesOnlyToTheCertificateItWasPairedOver() throws Exception {
        sunshine.alreadyPaired = true;
        String token = server.pairDevice();
        pairing(target(null, token, CertPin.fingerprint(otherIdentity.cert))).start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        assertNull(done);
        server.typeCode(CODE);
        await("both halves", 5000, () -> done != null);
        assertFalse("a new token, by code", token.equals(done.token));
    }

    @Test
    public void nothingNewIsSentWhilePaused() throws Exception {
        sunshine.answerNext(StreamPairer.Result.SESSION_PENDING);
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        awaitStream(OneCodePairing.StreamState.WAITING);
        pairing.pause();
        Thread.sleep(200);
        int posts = server.pairPosts.get();
        int attempts = sunshine.pins.size();

        // The Moonlight request already waiting is left alone; it fails while paused...
        sunshine.enter("0000");
        Thread.sleep(500);
        assertEquals("no polling while paused", posts, server.pairPosts.get());
        assertEquals("no new Moonlight attempt while paused", attempts, sunshine.pins.size());

        // ...and everything carries on when resumed, with the same code.
        pairing.resume();
        await("a new Moonlight attempt", 5000, () -> sunshine.waiting() == 1);
        await("polling again", 5000, () -> server.pairPosts.get() > posts);
        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
    }

    @Test
    public void aStreamPairingThatFinishesWhilePausedCounts() throws Exception {
        pairing().start();
        await("Sunshine to wait", 5000, () -> sunshine.waiting() == 1);
        pairing.pause();
        // The code was typed into StrmQt from inside the stream, say.
        assertTrue(sunshine.enter(CODE));
        awaitStream(OneCodePairing.StreamState.PAIRED);
        server.typeCode(CODE);
        Thread.sleep(300);
        assertNull("the remote is not asked while paused", done);
        pairing.resume();
        await("both halves", 5000, () -> done != null);
        assertEquals(1, sunshine.pins.size());
    }

    @Test
    public void aCertificateOtherThanTheAnnouncedOneStopsPairing() throws Exception {
        pairing(target(CertPin.fingerprint(otherIdentity.cert), null, null)).start();
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.CERT_NOT_ANNOUNCED, stopped);
        assertEquals("no code is sent to it", 0, server.pairPosts.get());
        assertNull(done);
    }

    @Test
    public void theAnnouncedCertificateIsAccepted() throws Exception {
        // Announced as discovery sends it: uppercase, colon-separated.
        String announced = StrmQtDiscovery.normalizeFingerprint(CertPin.display(fingerprint()));
        pairing(target(announced, null, null)).start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
    }

    @Test
    public void anUnreachableWebRemoteIsRetried() throws Exception {
        int port = server.port();
        server.stop();
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.UNREACHABLE);
        server = new FakeStrmQt(identity, port);
        server.sunshineFingerprint = SUNSHINE_CERT;
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("both halves", 5000, () -> done != null);
    }

    @Test
    public void aNewCodeIsUsedByBothHalves() throws Exception {
        sunshine.answerNext(StreamPairer.Result.REFUSED);
        pairing().streamRetryMs = 3000;
        pairing.start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        awaitStream(OneCodePairing.StreamState.REFUSED);
        pairing.setCode("7777");
        await("a Moonlight attempt with the new code", 5000,
                () -> sunshine.waiting() == 1 && "7777".equals(last(sunshine.pins)));
        int before = server.pairBodies.size();
        await("the remote asking with the new code", 5000,
                () -> server.pairBodies.size() > before
                        && last(server.pairBodies).equals("{\"pin\":\"7777\"}"));
        typeIntoStrmQt("7777");
        await("both halves", 5000, () -> done != null);
    }

    @Test
    public void aLockedOutRemoteWaitsAndCarriesOn() throws Exception {
        server.lockedOut = true;
        server.typeCode("1111");
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.LOCKED_OUT);
        server.lockedOut = false;
        server.typeCode(CODE);
        awaitRemote(OneCodePairing.RemoteState.PAIRED);
        assertTrue(sunshine.enter(CODE));
        await("both halves", 5000, () -> done != null);
    }

    @Test
    public void anOlderStrmQtStopsPairing() throws Exception {
        server.oldStrmQt = true;
        pairing().start();
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.UNSUPPORTED, stopped);
        assertEquals(0, server.pairPosts.get());
    }

    // StrmQt vouching for its Sunshine

    @Test
    public void aSunshineStrmQtDoesNotVouchForIsRefused() throws Exception {
        // The Sunshine port led to a plain Sunshine, or the desktop's own.
        sunshine.cert = OTHER_CERT;
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.SUNSHINE_NOT_STRMQT, stopped);
        assertNull("not paired", done);
    }

    @Test
    public void aStrmQtThatDoesNotVouchIsRefused() throws Exception {
        server.sunshineFingerprint = null;
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.SUNSHINE_UNVOUCHED, stopped);
        assertTrue("StrmQt was asked as well", server.hostRequests.get() > 0);
        assertNull(done);
    }

    @Test
    public void aStrmQtFromBeforeVouchingIsRefused() throws Exception {
        server.noVouching = true;
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.SUNSHINE_UNVOUCHED, stopped);
        assertNull(done);
    }

    @Test
    public void aSunshineWithoutAKnownCertificateIsRefused() throws Exception {
        sunshine.cert = null;
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        typeIntoStrmQt(CODE);
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.SUNSHINE_NOT_STRMQT, stopped);
        assertNull(done);
    }

    @Test
    public void anAlreadyPairedSunshineStrmQtDoesNotVouchForIsRefused() throws Exception {
        sunshine.alreadyPaired = true;
        sunshine.cert = OTHER_CERT;
        String token = server.pairDevice();
        pairing(target(fingerprint(), token, fingerprint())).start();
        await("pairing to stop", 5000, () -> stopped != null);
        assertEquals(OneCodePairing.RemoteState.SUNSHINE_NOT_STRMQT, stopped);
        assertEquals("no code was asked for", 0, server.pairPosts.get());
        assertNull(done);
    }

    @Test
    public void aHeldTokenStrmQtWillNotVouchWithPairsByCode() throws Exception {
        sunshine.alreadyPaired = true;
        server.hostRefusesTokens = true;
        String token = server.pairDevice();
        pairing(target(fingerprint(), token, fingerprint())).start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        assertNull(stopped);
        server.typeCode(CODE);
        await("both halves", 5000, () -> done != null);
        assertFalse("a new token, by code", token.equals(done.token));
        assertEquals(SUNSHINE_CERT, done.sunshineFingerprint);
    }

    @Test
    public void stopEndsBothHalves() throws Exception {
        pairing().start();
        awaitRemote(OneCodePairing.RemoteState.WAITING);
        await("Sunshine to wait", 5000, () -> sunshine.waiting() == 1);
        pairing.stop();
        Thread.sleep(200);
        int posts = server.pairPosts.get();
        Thread.sleep(400);
        assertEquals(posts, server.pairPosts.get());
        // A Moonlight attempt finishing later changes nothing here.
        sunshine.enter(CODE);
        Thread.sleep(200);
        assertNull(done);
    }
}
