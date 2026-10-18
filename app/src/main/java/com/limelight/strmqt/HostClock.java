package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Another clock as this TV sees it, kept up to date over UDP for as long as
 * it runs: the StrmQt host's (StrmQt's TimeService), which TVs playing
 * together line up on, or another TV's ({@link TimeResponder}).
 *
 * A burst of exchanges first, so there is an estimate within a second, then
 * {@link #GAP_MS} apart. t1 is read just before the request goes and t4 as
 * soon as the reply is in, on a thread of its own, so the round trip holds as
 * little of this TV as possible. Replies to an earlier request are not taken
 * for later ones: the sequence number must match. A host that does not
 * answer, or is not there yet (a restart), is simply asked again: only
 * {@link #stop} ends it.
 */
public final class HostClock {
    static final int BURST = 20;
    static final long BURST_GAP_MS = 50;
    static final long GAP_MS = 250;
    static final int TIMEOUT_MS = 200;

    private static volatile HostClock current;

    private final String host;
    private final int port;
    private final ClockEstimate estimate = new ClockEstimate();
    private final Thread thread;
    private volatile boolean stopped;
    private volatile DatagramSocket socket;

    private HostClock(String host, int port) {
        this.host = host;
        this.port = port;
        this.thread = new Thread(this::run, "StrmQt clock");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Starts following the clock at host:port. */
    public static HostClock start(String host, int port) {
        HostClock clock = new HostClock(host, port);
        clock.thread.start();
        return clock;
    }

    /**
     * Starts following the StrmQt host's clock for this stream, and makes it
     * {@link #current}; null when StrmQt gave no time port (one from before
     * TimeService) or no host is set.
     */
    public static HostClock forStream(StrmQtPrefs prefs) {
        int port = prefs.timePort();
        String host = prefs.remoteHost();
        if (port <= 0 || host == null) {
            return null;
        }
        HostClock clock = start(host, port);
        current = clock;
        return clock;
    }

    /** The stream's host clock, while there is one. */
    public static HostClock current() {
        return current;
    }

    public void stop() {
        stopped = true;
        if (current == this) {
            current = null;
        }
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    /** The estimate so far; null until there are enough exchanges. */
    public ClockEstimate.Result estimate() {
        return estimate.estimate();
    }

    /** The host's clock now, or null while it is not known yet. */
    public Long hostNow() {
        ClockEstimate.Result result = estimate();
        return result != null ? result.at(System.nanoTime()) : null;
    }

    /** One line for the performance overlay. */
    public String describe() {
        ClockEstimate.Result r = estimate();
        if (r == null) {
            return "StrmQt clock: measuring";
        }
        return String.format(Locale.ROOT, "StrmQt clock: ±%.2f ms (RTT %.2f ms, scatter %.2f ms, %+.1f ppm)",
                r.minDelayNs / 2e6, r.minDelayNs / 1e6, r.scatterNs / 1e6, r.skewPpm);
    }

    private void run() {
        int sequence = 0;
        int answered = 0;
        boolean warned = false;
        byte[] in = new byte[TimePacket.SIZE + 1];
        try (DatagramSocket s = new DatagramSocket()) {
            socket = s;
            s.setSoTimeout(TIMEOUT_MS);
            InetAddress address = null;
            while (!stopped) {
                sequence++;
                try {
                    if (address == null) {
                        address = InetAddress.getByName(host);
                        s.connect(address, port);
                    }
                    byte[] request = TimePacket.request(sequence, 0);
                    long t1 = System.nanoTime();
                    s.send(new DatagramPacket(request, request.length));
                    if (exchange(s, in, sequence, t1)) {
                        answered++;
                    }
                } catch (IOException e) {
                    // Nothing listening there (yet), or no route: ask again later.
                    if (!warned && !stopped) {
                        LimeLog.warning("StrmQt clock " + host + ":" + port + ": " + e);
                        warned = true;
                    }
                }
                Thread.sleep(answered < BURST ? BURST_GAP_MS : GAP_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!stopped) {
                LimeLog.warning("StrmQt clock: no socket: " + e);
            }
        }
    }

    /** Waits for the reply to `sequence`; whether one came. */
    private boolean exchange(DatagramSocket s, byte[] in, int sequence, long t1) throws IOException {
        long deadline = t1 + TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            DatagramPacket packet = new DatagramPacket(in, in.length);
            try {
                s.receive(packet);
            } catch (SocketTimeoutException e) {
                return false;
            }
            long t4 = System.nanoTime();
            TimePacket reply = TimePacket.parse(packet.getData(), packet.getOffset(), packet.getLength());
            if (reply != null && reply.reply && reply.sequence == sequence) {
                return estimate.add(t1, reply.t2, reply.t3, t4, reply.clockId);
            }
        }
        return false;
    }
}
