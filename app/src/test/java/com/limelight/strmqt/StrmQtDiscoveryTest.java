package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StrmQtDiscoveryTest {
    private static final String FP_DISPLAY =
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89";
    private static final String FP_HEX = FP_DISPLAY.replace(":", "").toLowerCase();
    private static final String GOOD = "{\"service\":\"strmqt\",\"version\":1,\"name\":\"cachyos-minipc\","
            + "\"remotePort\":8338,\"sunshinePort\":48989,\"fingerprint\":\"" + FP_DISPLAY + "\"}";

    @Test
    public void readsAGoodReply() {
        StrmQtDiscovery.Host host = StrmQtDiscovery.parseReply(GOOD, "192.168.2.116");
        assertNotNull(host);
        assertEquals("192.168.2.116", host.address);
        assertEquals("cachyos-minipc", host.name);
        assertEquals(8338, host.remotePort);
        assertEquals(48989, host.sunshinePort);
        assertTrue(host.canStream());
        assertEquals("the fingerprint is kept as CertPin gives it", FP_HEX, host.fingerprint);
    }

    @Test
    public void ignoresUnknownFieldsOfAnyKind() {
        String reply = "{ \"service\" : \"strmqt\", \"version\": 1, \"future\": {\"a\": [1, 2, {\"b\": null}]},"
                + " \"list\": [\"x\", true, false, null, -1.5e3], \"flag\": true, \"none\": null,"
                + " \"name\": \"Living room \\u00e9\", \"remotePort\": 9000, \"sunshinePort\": 50000 }\n";
        StrmQtDiscovery.Host host = StrmQtDiscovery.parseReply(reply, "10.0.0.5");
        assertNotNull(host);
        assertEquals("Living room é", host.name);
        assertEquals(9000, host.remotePort);
        assertEquals(50000, host.sunshinePort);
        assertNull(host.fingerprint);
    }

    @Test
    public void missingPortsAreDefaultedOrMarkTheHostAsUnableToStream() {
        StrmQtDiscovery.Host host = StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"version\":1}", "10.0.0.7");
        assertNotNull(host);
        assertEquals("no name: the address stands in", "10.0.0.7", host.name);
        assertEquals(StrmQtDiscovery.DEFAULT_REMOTE_PORT, host.remotePort);
        assertEquals(0, host.sunshinePort);
        assertFalse("no Sunshine port, so it cannot stream", host.canStream());
    }

    @Test
    public void unusablePortsAreTreatedAsMissing() {
        StrmQtDiscovery.Host host = StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"remotePort\":70000,"
                + "\"sunshinePort\":0}", "10.0.0.8");
        assertEquals(8338, host.remotePort);
        assertFalse(host.canStream());

        host = StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"remotePort\":8338.5,"
                + "\"sunshinePort\":\"not a port\"}", "10.0.0.8");
        assertEquals(8338, host.remotePort);
        assertFalse(host.canStream());

        host = StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"sunshinePort\":\"48989\"}", "10.0.0.8");
        assertEquals("a port sent as a string is read", 48989, host.sunshinePort);
    }

    @Test
    public void aBadFingerprintIsDroppedNotTrusted() {
        assertNull(StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"fingerprint\":\"AB:CD\"}", "1.2.3.4")
                .fingerprint);
        assertNull(StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"fingerprint\":\""
                + FP_DISPLAY.replace('A', 'Z') + "\"}", "1.2.3.4").fingerprint);
        assertNull(StrmQtDiscovery.parseReply("{\"service\":\"strmqt\",\"fingerprint\":12}", "1.2.3.4")
                .fingerprint);
        assertEquals(FP_HEX, StrmQtDiscovery.normalizeFingerprint(FP_HEX.toUpperCase()));
    }

    @Test
    public void controlCharactersInTheNameAreFlattened() {
        StrmQtDiscovery.Host host = StrmQtDiscovery.parseReply(
                "{\"service\":\"strmqt\",\"name\":\"  two\\nlines\\t \"}", "1.2.3.4");
        assertEquals("two lines", host.name);
    }

    @Test
    public void rejectsGarbage() {
        String[] garbage = {
                "",
                "STRMQT-DISCOVER 1",
                "hello",
                "[]",
                "[{\"service\":\"strmqt\"}]",
                "{\"service\":\"strmqt\"",
                "{\"service\":\"strmqt\"} trailing",
                "{\"service\":\"sunshine\",\"sunshinePort\":47989}",
                "{\"name\":\"no service\",\"sunshinePort\":48989}",
                "{\"service\":\"strmqt\",\"version\":0}",
                "{\"service\":strmqt}",
                "{\"service\":\"strmqt\",}",
                "{\"service\":\"str\\qmqt\"}",
                "{\"service\":\"strmqt\",\"x\":\"\\u12\"}",
                "\u0000\u0001\u0002",
        };
        for (String text : garbage) {
            assertNull("not a StrmQt reply: " + text, StrmQtDiscovery.parseReply(text, "1.2.3.4"));
        }
        byte[] binary = {(byte) 0xff, (byte) 0xfe, '{', '}'};
        assertNull(StrmQtDiscovery.parseReply(binary, binary.length, "1.2.3.4"));
        assertNull("no address, no host", StrmQtDiscovery.parseReply(GOOD, ""));
    }

    @Test
    public void readsOnlyTheReceivedLength() {
        byte[] buffer = new byte[4096];
        byte[] reply = GOOD.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(reply, 0, buffer, 0, reply.length);
        buffer[reply.length] = 'x';
        assertNotNull(StrmQtDiscovery.parseReply(buffer, reply.length, "1.2.3.4"));
    }

    @Test
    public void deduplicatesByAddressKeepingTheLatestReply() {
        List<StrmQtDiscovery.Host> hosts = new ArrayList<>();
        StrmQtDiscovery.merge(hosts, new StrmQtDiscovery.Host("10.0.0.1", "a", 8338, 48989, null));
        StrmQtDiscovery.merge(hosts, new StrmQtDiscovery.Host("10.0.0.2", "b", 8338, 48989, null));
        StrmQtDiscovery.merge(hosts, new StrmQtDiscovery.Host("10.0.0.1", "a renamed", 8338, 49000, null));
        StrmQtDiscovery.merge(hosts, null);
        assertEquals(2, hosts.size());
        assertEquals("a renamed", hosts.get(0).name);
        assertEquals(49000, hosts.get(0).sunshinePort);
        assertEquals("b", hosts.get(1).name);
    }

    /** The whole exchange over loopback, against a stand-in host on a port of its own. */
    @Test
    public void scanSendsTheProbeAndCollectsDeduplicatedReplies() throws Exception {
        final AtomicInteger probes = new AtomicInteger();
        final List<String> payloads = Collections.synchronizedList(new ArrayList<String>());
        try (final DatagramSocket host = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                byte[] buffer = new byte[512];
                try {
                    while (true) {
                        DatagramPacket probe = new DatagramPacket(buffer, buffer.length);
                        host.receive(probe);
                        probes.incrementAndGet();
                        payloads.add(new String(probe.getData(), 0, probe.getLength(), StandardCharsets.US_ASCII));
                        byte[] reply = GOOD.getBytes(StandardCharsets.UTF_8);
                        // Answer each probe, so the scanner sees the same host three times.
                        host.send(new DatagramPacket(reply, reply.length, probe.getSocketAddress()));
                        byte[] junk = "not json".getBytes(StandardCharsets.US_ASCII);
                        host.send(new DatagramPacket(junk, junk.length, probe.getSocketAddress()));
                    }
                } catch (Exception ignored) {
                    // Closed.
                }
            });
            responder.setDaemon(true);
            responder.start();

            List<StrmQtDiscovery.Host> found = StrmQtDiscovery.scan(
                    Collections.singletonList(InetAddress.getByName("127.0.0.1")), host.getLocalPort(),
                    3, 60, 500);
            assertEquals(1, found.size());
            assertEquals("127.0.0.1", found.get(0).address);
            assertEquals(48989, found.get(0).sunshinePort);
            assertEquals("three probes", 3, probes.get());
            for (String payload : payloads) {
                assertEquals(StrmQtDiscovery.PROBE, payload);
            }
        }
    }

    @Test
    public void scanWithNobodyThereFindsNothing() throws Exception {
        int port;
        try (DatagramSocket unused = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        long start = System.nanoTime();
        List<StrmQtDiscovery.Host> found = StrmQtDiscovery.scan(
                Collections.singletonList(InetAddress.getByName("127.0.0.1")), port, 3, 50, 300);
        long tookMs = (System.nanoTime() - start) / 1000000;
        assertTrue(found.isEmpty());
        assertTrue("collects for the time it was given (" + tookMs + " ms)", tookMs >= 280 && tookMs < 2000);
    }

    @Test
    public void theRealScanUsesTheAgreedProbeAndPort() {
        assertEquals("STRMQT-DISCOVER 1", StrmQtDiscovery.PROBE);
        assertEquals(8338, StrmQtDiscovery.PORT);
        assertEquals(3, StrmQtDiscovery.SENDS);
        assertEquals(500, StrmQtDiscovery.SEND_INTERVAL_MS);
        assertEquals(2000, StrmQtDiscovery.COLLECT_MS);
        assertEquals(8335, StrmQtDiscovery.DEFAULT_SUNSHINE_PORT);
    }
}
