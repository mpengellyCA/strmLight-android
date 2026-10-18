package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers time requests from other TVs while this one streams, exactly as the
 * StrmQt host does ({@link TimePacket}), so two TVs can time each other: with
 * each one's offset to the host, that closes a triangle whose sum shows how
 * far the host's figures can be trusted.
 *
 * This TV's clock is System.nanoTime. Its clockId is new every time the app
 * starts; a TV timing this one then simply starts its estimate over.
 *
 * Only this network is answered, at most {@link #MAX_PER_SENDER_PER_SECOND}
 * times a second per sender, and a reply is never larger than its request.
 */
public final class TimeResponder {
    public static final int PORT = 8339;
    static final int MAX_PER_SENDER_PER_SECOND = 100;
    private static final long CLOCK_ID = new SecureRandom().nextLong();

    private final DatagramSocket socket;
    private final Thread thread;
    private volatile boolean stopped;

    private TimeResponder(DatagramSocket socket) {
        this.socket = socket;
        this.thread = new Thread(this::run, "StrmQt time answers");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Answers on UDP `port` (0 for any); null when it cannot listen there. */
    public static TimeResponder start(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            socket.setSoTimeout(250);
            TimeResponder responder = new TimeResponder(socket);
            responder.thread.start();
            return responder;
        } catch (SocketException e) {
            LimeLog.warning("StrmQt time answers: cannot listen on UDP " + port + ": " + e);
            return null;
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    public static long clockId() {
        return CLOCK_ID;
    }

    public void stop() {
        stopped = true;
        socket.close();
    }

    static boolean isNearby(InetAddress address) {
        return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress();
    }

    private void run() {
        byte[] in = new byte[TimePacket.SIZE + 1];
        Map<InetAddress, long[]> perSender = new HashMap<>(); // sender -> {second, replies}
        while (!stopped) {
            DatagramPacket packet = new DatagramPacket(in, in.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!stopped) {
                    LimeLog.warning("StrmQt time answers: " + e);
                }
                return;
            }
            long t2 = System.nanoTime();
            TimePacket request = TimePacket.parse(packet.getData(), packet.getOffset(), packet.getLength());
            InetAddress sender = packet.getAddress();
            if (request == null || request.reply || !isNearby(sender)) {
                continue;
            }
            long second = t2 / 1_000_000_000L;
            if (perSender.size() > 256 && !perSender.containsKey(sender)) {
                perSender.clear();
            }
            long[] count = perSender.get(sender);
            if (count == null) {
                count = new long[2];
                perSender.put(sender, count);
            }
            if (count[0] != second) {
                count[0] = second;
                count[1] = 0;
            }
            if (count[1]++ >= MAX_PER_SENDER_PER_SECOND) {
                continue;
            }
            try {
                byte[] reply = TimePacket.reply(request, t2, System.nanoTime(), CLOCK_ID);
                socket.send(new DatagramPacket(reply, reply.length, sender, packet.getPort()));
            } catch (IOException e) {
                if (!stopped) {
                    LimeLog.warning("StrmQt time answers: " + e);
                }
            }
        }
    }
}
