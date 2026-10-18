package com.limelight.strmqt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;

public class TimeExchangeTest {
    @Test
    public void aRequestIsStrmQtsFortyEightBytes() {
        byte[] request = TimePacket.request(7, 12345);
        ByteBuffer expected = ByteBuffer.allocate(48);
        expected.put(new byte[]{'S', 'T', 'Q', 'T', 1, 1, 0, 0}).putInt(7).putInt(0).putLong(12345)
                .putLong(0).putLong(0).putLong(0);
        assertArrayEquals("the layout of StrmQt's remote/TimeService.h", expected.array(), request);
    }

    @Test
    public void aReplyEchoesTheRequestAndAddsItsTimes() {
        TimePacket request = TimePacket.parse(TimePacket.request(9, 111), 0, 48);
        assertNotNull(request);
        byte[] bytes = TimePacket.reply(request, 222, 333, 444);
        assertEquals(TimePacket.SIZE, bytes.length);
        TimePacket reply = TimePacket.parse(bytes, 0, bytes.length);
        assertTrue(reply.reply);
        assertEquals(9, reply.sequence);
        assertEquals(111, reply.t1);
        assertEquals(222, reply.t2);
        assertEquals(333, reply.t3);
        assertEquals(444, reply.clockId);
    }

    @Test
    public void anythingElseIsNotAPacket() {
        byte[] good = TimePacket.request(1, 2);
        assertNull(TimePacket.parse(good, 0, 47));
        assertNull(TimePacket.parse(new byte[49], 0, 49));
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertNull(TimePacket.parse(magic, 0, 48));
        byte[] version = good.clone();
        version[4] = 2;
        assertNull(TimePacket.parse(version, 0, 48));
        byte[] kind = good.clone();
        kind[5] = 3;
        assertNull(TimePacket.parse(kind, 0, 48));
    }

    @Test
    public void theTimePortIsReadFromStrmQtsAnswer() {
        assertEquals(8339, StrmQtClient.timePort("{\"sunshineFingerprint\":\"ab\",\"timePort\":8339}"));
        assertEquals("a StrmQt from before TimeService", 0, StrmQtClient.timePort("{\"sunshineFingerprint\":\"ab\"}"));
        assertEquals(0, StrmQtClient.timePort("{\"timePort\":\"8339\"}"));
        assertEquals(0, StrmQtClient.timePort("{\"timePort\":70000}"));
        assertEquals(0, StrmQtClient.timePort("not json"));
        assertEquals(0, StrmQtClient.timePort(null));
    }

    @Test
    public void onlyThisNetworkIsAnswered() throws Exception {
        assertTrue(TimeResponder.isNearby(InetAddress.getByName("192.168.2.158")));
        assertTrue(TimeResponder.isNearby(InetAddress.getByName("10.0.0.7")));
        assertTrue(TimeResponder.isNearby(InetAddress.getByName("169.254.1.1")));
        assertTrue(TimeResponder.isNearby(InetAddress.getLoopbackAddress()));
        assertTrue(!TimeResponder.isNearby(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    public void aClockThatIsNotThereYetIsAskedAgain() throws Exception {
        // Nothing listens on the port at first, as while a host restarts.
        TimeResponder placeholder = TimeResponder.start(0);
        int port = placeholder.port();
        placeholder.stop();
        HostClock clock = HostClock.start("127.0.0.1", port);
        try {
            Thread.sleep(500);
            TimeResponder responder = TimeResponder.start(port);
            assertNotNull(responder);
            try {
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (clock.estimate() == null && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertNotNull("the clock gave up on a host that came back", clock.estimate());
            } finally {
                responder.stop();
            }
        } finally {
            clock.stop();
        }
    }

    @Test
    public void aTvFollowsAnotherTvsClock() throws Exception {
        // Both on this machine's clock: the offset is the error.
        TimeResponder responder = TimeResponder.start(0);
        assertNotNull(responder);
        HostClock clock = HostClock.start("127.0.0.1", responder.port());
        try {
            ClockEstimate.Result result = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (result == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
                result = clock.estimate();
            }
            assertNotNull("no estimate within 5 s", result);
            long now = System.nanoTime();
            long error = Math.abs(result.at(now) - now);
            assertTrue("off by " + error + " ns", error < 1_000_000);
            assertTrue(result.minDelayNs < 5_000_000);
        } finally {
            clock.stop();
            responder.stop();
        }
    }
}
