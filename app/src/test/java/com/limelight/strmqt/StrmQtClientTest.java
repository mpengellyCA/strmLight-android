package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;

public class StrmQtClientTest {
    private static final String CODE = "4821";
    private static final String NAME = "Mike’s Fire TV";
    /** Fast polling for the tests; the real intervals are checked on their own. */
    private static final long POLL_MS = 100;

    private static FakeStrmQt.Identity identity;
    private static FakeStrmQt.Identity otherIdentity;

    private FakeStrmQt server;
    private StrmQtClient client;
    private volatile StrmQtClient.State state = StrmQtClient.State.CONNECTING;
    private final List<StrmQtClient.Pairing> pairing =
            Collections.synchronizedList(new ArrayList<StrmQtClient.Pairing>());
    private volatile boolean paired;
    private volatile String pairedToken;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        identity = new FakeStrmQt.Identity();
        otherIdentity = new FakeStrmQt.Identity();
    }

    @Before
    public void startServer() throws Exception {
        server = new FakeStrmQt(identity, 0);
    }

    @After
    public void stop() {
        if (client != null) {
            client.stop();
        }
        server.stop();
    }

    private String fingerprint() throws Exception {
        return CertPin.fingerprint(CertPin.fetch("127.0.0.1", server.port(), 2000));
    }

    private StrmQtClient connect(String token) throws Exception {
        return connect(token, fingerprint());
    }

    private StrmQtClient connect(String token, String fingerprint) throws Exception {
        client = new StrmQtClient("127.0.0.1", server.port(), fingerprint, token, s -> state = s);
        client.pairPollMs = POLL_MS;
        return client;
    }

    private void startPairing(String code) {
        client.startPairing(code, NAME, new StrmQtClient.PairingListener() {
            @Override
            public void onPairing(StrmQtClient.Pairing status) {
                pairing.add(status);
            }

            @Override
            public void onPaired(String token) {
                pairedToken = token;
                paired = true;
            }
        });
    }

    private StrmQtClient.Pairing lastPairing() {
        synchronized (pairing) {
            return pairing.isEmpty() ? null : pairing.get(pairing.size() - 1);
        }
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

    private void awaitState(StrmQtClient.State wanted, long timeoutMs) throws Exception {
        await("state " + wanted + " (is " + state + ")", timeoutMs, () -> state == wanted);
    }

    private void awaitPairing(StrmQtClient.Pairing wanted, long timeoutMs) throws Exception {
        await("pairing " + wanted + " (is " + lastPairing() + ")", timeoutMs, () -> lastPairing() == wanted);
    }

    private void awaitPaired(long timeoutMs) throws Exception {
        await("pairing to finish (is " + lastPairing() + ")", timeoutMs, () -> paired);
    }

    private void awaitIdle() throws Exception {
        await("the queue to drain", 5000, () -> client.pending() == 0);
    }

    /** A client holding a token StrmQt knows, with the channel up. */
    private void connectPaired() throws Exception {
        connect(server.pairDevice()).start();
        awaitState(StrmQtClient.State.UP, 5000);
    }

    @Test
    public void pairingReadsTheCertificateTheServerPresents() throws Exception {
        assertEquals(CertPin.fingerprint(identity.cert), fingerprint());
        assertEquals("fetching the certificate sends no request", 0, server.requests);
    }

    @Test
    public void pollsUntilTheCodeIsTypedInThenKeepsTheToken() throws Exception {
        connect(null);
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.WAITING, 5000);
        await("a few polls", 5000, () -> server.pairPosts.get() >= 3);
        assertTrue("nothing paired before the code is typed in", !paired);

        server.typeCode(CODE);
        awaitPaired(5000);
        assertTrue("the token is one StrmQt keeps", server.tokens.contains(pairedToken));
        assertEquals(StrmQtClient.State.UP, state);

        // Polling stops once paired.
        int posts = server.pairPosts.get();
        Thread.sleep(POLL_MS * 5);
        assertEquals(posts, server.pairPosts.get());

        // Every poll names the device and carries the same code.
        for (String body : server.pairBodies) {
            assertEquals("{\"pin\":\"" + CODE + "\"}", body);
        }
        for (String name : server.pairNames) {
            assertEquals(NAME, name);
        }
        assertEquals(Collections.singletonList(StrmQtClient.Pairing.WAITING), pairing);

        // The token works for buttons.
        client.send("nav.select", false);
        awaitIdle();
        assertEquals(Collections.singletonList("nav.select"), server.actions);
    }

    @Test
    public void aWrongCodeKeepsTheSameCodeAndKeepsPolling() throws Exception {
        connect(null);
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.WAITING, 5000);

        server.typeCode("1111"); // a typo
        awaitPairing(StrmQtClient.Pairing.WRONG_CODE, 5000);
        // StrmQt drops the typed code after five misses; the client is back to waiting.
        awaitPairing(StrmQtClient.Pairing.WAITING, 5000);
        assertNull(server.typedCode());
        assertTrue(!paired);

        server.typeCode(CODE); // typed again, right this time
        awaitPaired(5000);
        assertTrue(server.tokens.contains(pairedToken));
        for (String body : server.pairBodies) {
            assertEquals("the code on screen never changes by itself", "{\"pin\":\"" + CODE + "\"}", body);
        }
        assertEquals(Arrays.asList(StrmQtClient.Pairing.WAITING, StrmQtClient.Pairing.WRONG_CODE,
                StrmQtClient.Pairing.WAITING), pairing);
    }

    @Test
    public void waitsOutA429() throws Exception {
        connect(null);
        client.pairLockoutMs = 1500;
        server.lockedOut = true;
        server.typeCode(CODE);
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.LOCKED_OUT, 5000);
        int posts = server.pairPosts.get();
        Thread.sleep(1000);
        assertEquals("nothing is asked while locked out", posts, server.pairPosts.get());

        server.lockedOut = false;
        awaitPaired(3000);
        assertEquals(posts + 1, server.pairPosts.get());
        assertTrue(server.tokens.contains(pairedToken));
    }

    @Test
    public void theRealIntervalsAreTheOnesStrmQtExpects() {
        assertEquals(2000, StrmQtClient.PAIR_POLL_MS);
        assertEquals(30000, StrmQtClient.PAIR_LOCKOUT_MS);
    }

    @Test
    public void withPairingOffNoCodeIsNeeded() throws Exception {
        server.requirePin = false;
        connect(null);
        startPairing(CODE);
        awaitPaired(5000);
        assertNull("no token when StrmQt asks for none", pairedToken);
        assertEquals("not a single pairing request", 0, server.pairPosts.get());
        assertTrue(pairing.isEmpty());

        client.send("nav.up", false);
        awaitIdle();
        assertEquals(Collections.singletonList("nav.up"), server.actions);
    }

    @Test
    public void aTokenStillGoodNeedsNoCode() throws Exception {
        String token = server.pairDevice();
        connect(token);
        startPairing(CODE);
        awaitPaired(5000);
        assertEquals(token, pairedToken);
        assertEquals(0, server.pairPosts.get());
    }

    @Test
    public void aTokenNoLongerGoodPairsByCode() throws Exception {
        connect("removed-in-strmqt");
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.WAITING, 5000);
        server.typeCode(CODE);
        awaitPaired(5000);
        assertTrue(server.tokens.contains(pairedToken));
    }

    @Test
    public void theDeviceNameHeaderIsAlwaysSent() throws Exception {
        connect(null);
        client.startPairing(CODE, "  \n\t ", new StrmQtClient.PairingListener() {
            @Override
            public void onPairing(StrmQtClient.Pairing status) {
                pairing.add(status);
            }

            @Override
            public void onPaired(String token) {
                paired = true;
            }
        });
        awaitPairing(StrmQtClient.Pairing.WAITING, 5000);
        // Without the header StrmQt would take it for a phone, and count it as a guess.
        assertEquals("StrmLight", server.pairNames.get(0));
    }

    @Test
    public void deviceNamesAreTidied() {
        assertEquals("Mike’s Fire TV", StrmQtClient.deviceHeader("  Mike’s\tFire\r\n  TV "));
        assertEquals("StrmLight", StrmQtClient.deviceHeader(null));
        assertEquals("StrmLight", StrmQtClient.deviceHeader(" \u0007 "));
        String longName = "Fire TV Stick 4K Max in the living room upstairs";
        assertEquals(longName.substring(0, 40).trim(), StrmQtClient.deviceHeader(longName));
        assertTrue(StrmQtClient.deviceHeader(longName).length() <= StrmQtClient.MAX_DEVICE_NAME);
    }

    @Test
    public void codesAreFourAsciiDigits() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")); // formats with Arabic-Indic digits
            Random random = new Random(1);
            for (int i = 0; i < 200; i++) {
                assertTrue(StrmQtClient.newPairingCode(random).matches("[0-9]{4}"));
            }
            assertEquals("0007", StrmQtClient.newPairingCode(new Random() {
                @Override
                public int nextInt(int bound) {
                    return 7;
                }
            }));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void anOlderStrmQtIsReportedNotGuessedAt() throws Exception {
        server.oldStrmQt = true;
        connect(null);
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.UNSUPPORTED, 5000);
        Thread.sleep(POLL_MS * 5);
        assertEquals("no code is sent to a StrmQt that would count it as a wrong PIN",
                0, server.pairPosts.get());
    }

    @Test
    public void aRemovedDeviceGoesBackToPairingWithoutLooping() throws Exception {
        connectPaired();
        server.tokens.clear(); // removed in StrmQt's Settings
        client.send("player.togglePause", false);
        awaitState(StrmQtClient.State.UNPAIRED, 5000);
        for (int i = 0; i < 5; i++) {
            client.send("nav.down", false);
        }
        awaitIdle();
        Thread.sleep(StrmQtClient.RETRY_WHEN_DOWN_MS + 500); // past a keep-alive retry
        assertEquals("one 401, then nothing more is sent", 1, server.actionPosts.get());
        assertEquals("no pairing is tried on its own", 0, server.pairPosts.get());
        assertTrue(server.actions.isEmpty());
        assertEquals(StrmQtClient.State.UNPAIRED, state);
    }

    @Test
    public void aTokenRemovedWhileAwayIsFoundAtStart() throws Exception {
        connect("removed-in-strmqt").start();
        awaitState(StrmQtClient.State.UNPAIRED, 5000);
        int requests = server.requests;
        client.send("nav.down", false);
        awaitIdle();
        Thread.sleep(StrmQtClient.RETRY_WHEN_DOWN_MS + 500);
        assertEquals("no request after StrmQt said the device is not paired", requests, server.requests);
    }

    @Test
    public void sendsButtonsInOrder() throws Exception {
        connectPaired();
        client.send("nav.down", false);
        client.send("nav.right", false);
        client.send("nav.select", false);
        awaitIdle();
        assertEquals(Arrays.asList("nav.down", "nav.right", "nav.select"), server.actions);
        assertEquals(0, server.pairPosts.get());
    }

    @Test
    public void staysPairedAndRecoversByItselfAfterStrmQtRestarts() throws Exception {
        connectPaired();
        int port = server.port();

        server.stop();
        client.send("nav.down", false);
        awaitState(StrmQtClient.State.DOWN, 5000);

        long restartedAt = System.currentTimeMillis();
        server = new FakeStrmQt(identity, port, server.tokens); // tokens are kept across restarts
        awaitState(StrmQtClient.State.UP, 6000);
        assertTrue("recovered within a few seconds", System.currentTimeMillis() - restartedAt < 5000);

        client.send("nav.up", false);
        awaitIdle();
        assertEquals(Collections.singletonList("nav.up"), server.actions);
        assertEquals("no pairing again", 0, server.pairPosts.get());
    }

    @Test
    public void pressesMadeWhileDownAreDroppedNotReplayed() throws Exception {
        connectPaired();
        int port = server.port();
        server.stop();

        for (int i = 0; i < 6; i++) {
            client.send("nav.down", false);
        }
        awaitIdle();
        server = new FakeStrmQt(identity, port, server.tokens);
        awaitState(StrmQtClient.State.UP, 6000);
        Thread.sleep(500);
        assertTrue("nothing is replayed late: " + server.actions, server.actions.isEmpty());
    }

    @Test
    public void repeatsNeverPileUpBehindASlowRequest() throws Exception {
        connectPaired();
        server.actionDelayMs = 300;
        client.send("nav.down", false);
        for (int i = 0; i < 20; i++) {
            client.send("nav.down", true);
            Thread.sleep(5);
        }
        awaitIdle();
        Thread.sleep(400);
        assertEquals(Collections.singletonList("nav.down"), server.actions);
    }

    @Test
    public void aStalePressIsDropped() throws Exception {
        connectPaired();
        server.actionDelayMs = StrmQtClient.MAX_ACTION_AGE_MS + 300;
        client.send("nav.down", false);
        client.send("nav.select", false); // waits behind the slow one for too long
        awaitIdle();
        assertEquals(Collections.singletonList("nav.down"), server.actions);
    }

    @Test
    public void anUnknownActionDoesNotBreakTheChannel() throws Exception {
        connectPaired();
        client.send("no.such.action", false);
        client.send("nav.back", false);
        awaitIdle();
        assertEquals(Collections.singletonList("nav.back"), server.actions);
        assertEquals(StrmQtClient.State.UP, state);
    }

    @Test
    public void aDifferentCertificateIsRefusedBeforeAnythingIsSent() throws Exception {
        String paired = CertPin.fingerprint(identity.cert);
        String token = server.pairDevice();
        int port = server.port();
        server.stop();
        server = new FakeStrmQt(otherIdentity, port);

        connect(token, paired).start();
        awaitState(StrmQtClient.State.CERT_CHANGED, 5000);
        client.send("nav.down", false);
        awaitIdle();
        startPairing(CODE);
        awaitPairing(StrmQtClient.Pairing.CERT_CHANGED, 5000);
        assertEquals("neither the token nor the code reaches a server that is not the paired one",
                0, server.requests);
    }

    @Test
    public void jsonEscaping() {
        assertEquals("12\\\"34\\\\", StrmQtClient.escape("12\"34\\"));
    }
}
