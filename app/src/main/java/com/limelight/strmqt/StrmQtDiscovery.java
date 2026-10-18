package com.limelight.strmqt;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds headless StrmQt hosts on the local network with StrmQt's own UDP
 * broadcast: the datagram {@code STRMQT-DISCOVER 1} goes to port 8338 on
 * 255.255.255.255 and on every IPv4 interface's own broadcast address, and a
 * host answers by unicast with a JSON object naming itself and its ports.
 *
 * Moonlight's mDNS cannot do this job: the host may have mDNS turned off, and
 * the StrmQt host's Sunshine shares its machine with another Sunshine.
 *
 * What a reply says is a hint for the user to pick from. The Web Remote's
 * certificate is still pinned on first use, and the pairing code is what
 * authenticates either side.
 */
public final class StrmQtDiscovery {
    public static final int PORT = 8338;
    public static final String PROBE = "STRMQT-DISCOVER 1";
    public static final int DEFAULT_REMOTE_PORT = 8338;
    public static final int DEFAULT_SUNSHINE_PORT = 8335;

    static final int SENDS = 3;
    static final long SEND_INTERVAL_MS = 500;
    static final long COLLECT_MS = 2000;
    private static final int MAX_REPLY = 4096;

    private StrmQtDiscovery() {}

    /** One StrmQt host, as its reply described it. */
    public static final class Host {
        /** The reply's source address: where the host is. */
        public final String address;
        public final String name;
        public final int remotePort;
        /** The base port of the host's Sunshine; 0 when the reply named none, and it cannot stream. */
        public final int sunshinePort;
        /** The Web Remote certificate's SHA-256 as lowercase hex, or null when the reply had none. */
        public final String fingerprint;

        public Host(String address, String name, int remotePort, int sunshinePort, String fingerprint) {
            this.address = address;
            this.name = name;
            this.remotePort = remotePort;
            this.sunshinePort = sunshinePort;
            this.fingerprint = fingerprint;
        }

        public boolean canStream() {
            return sunshinePort > 0;
        }

        @Override
        public String toString() {
            return name + " (" + address + ", remote " + remotePort + ", Sunshine " + sunshinePort + ")";
        }
    }

    /**
     * Reads one reply. Unknown fields are ignored; missing or unusable ports
     * fall back to the default (the Web Remote) or to none (Sunshine).
     *
     * @param address the reply's source address
     * @return the host, or null when this is not a StrmQt reply
     */
    public static Host parseReply(byte[] data, int length, String address) {
        if (data == null || address == null || address.isEmpty()) {
            return null;
        }
        Map<String, Object> fields = Json.parseObject(new String(data, 0, length, StandardCharsets.UTF_8));
        if (fields == null || !"strmqt".equals(fields.get("service"))) {
            return null;
        }
        int version = number(fields.get("version"), 1);
        if (version < 1) {
            return null;
        }
        Object nameField = fields.get("name");
        String name = nameField instanceof String ? oneLine((String) nameField) : "";
        if (name.isEmpty()) {
            name = address;
        }
        int remotePort = port(fields.get("remotePort"), DEFAULT_REMOTE_PORT);
        int sunshinePort = port(fields.get("sunshinePort"), 0);
        Object fp = fields.get("fingerprint");
        return new Host(address, name, remotePort, sunshinePort,
                fp instanceof String ? normalizeFingerprint((String) fp) : null);
    }

    public static Host parseReply(String text, String address) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return parseReply(data, data.length, address);
    }

    /** Adds `host` to `hosts`, replacing an earlier reply from the same address. */
    public static void merge(List<Host> hosts, Host host) {
        if (host == null) {
            return;
        }
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).address.equals(host.address)) {
                hosts.set(i, host);
                return;
            }
        }
        hosts.add(host);
    }

    /**
     * "AA:BB:…" or "aabb…" to lowercase hex without separators, as
     * {@link CertPin#fingerprint} gives it; null when it is not a SHA-256.
     */
    public static String normalizeFingerprint(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder(64);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':' || c == ' ') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                return null;
            }
            hex.append(Character.toLowerCase(c));
        }
        return hex.length() == 64 ? hex.toString() : null;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Double) {
            double d = (Double) value;
            return d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE ? (int) d : fallback;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int port(Object value, int fallback) {
        int port = number(value, fallback);
        return port >= 1 && port <= 65535 ? port : fallback;
    }

    private static String oneLine(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String tidy = out.toString().trim().replaceAll(" {2,}", " ");
        return tidy.length() > 64 ? tidy.substring(0, 64).trim() : tidy;
    }

    // Sending and receiving

    /** 255.255.255.255 and the broadcast address of every IPv4 interface that is up. */
    public static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
            // A literal; cannot fail.
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (address.getAddress() instanceof Inet4Address && broadcast != null
                            && !targets.contains(broadcast)) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (Exception e) {
            // Some devices throw from getNetworkInterfaces(); the limited broadcast still goes out.
            LimeLog.warning("StrmQt discovery: " + e);
        }
        return targets;
    }

    /**
     * Sends the probe `sends` times, `intervalMs` apart, to each target, and
     * collects replies until `collectMs` after the first send. Blocks; call
     * it off the main thread.
     */
    public static List<Host> scan(List<InetAddress> targets, int port, int sends, long intervalMs, long collectMs)
            throws IOException {
        List<Host> hosts = new ArrayList<>();
        byte[] probe = PROBE.getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[MAX_REPLY];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            long start = System.nanoTime();
            long deadline = start + collectMs * 1000000L;
            int sent = 0;
            long nextSend = start;
            while (true) {
                long now = System.nanoTime();
                if (sent < sends && now - nextSend >= 0) {
                    for (InetAddress target : targets) {
                        try {
                            socket.send(new DatagramPacket(probe, probe.length, target, port));
                        } catch (IOException e) {
                            // One unreachable interface does not stop the others.
                            LimeLog.warning("StrmQt discovery to " + target + ": " + e);
                        }
                    }
                    sent++;
                    nextSend += intervalMs * 1000000L;
                    now = System.nanoTime();
                }
                long until = sent < sends ? Math.min(nextSend, deadline) : deadline;
                long waitMs = (until - now) / 1000000L;
                if (deadline - now <= 0) {
                    break;
                }
                if (waitMs <= 0) {
                    continue;
                }
                socket.setSoTimeout((int) Math.max(1, waitMs));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (reply.getAddress() instanceof Inet4Address) {
                    merge(hosts, parseReply(reply.getData(), reply.getLength(), reply.getAddress().getHostAddress()));
                }
            }
        }
        return hosts;
    }

    /**
     * The scan StrmLight runs: every broadcast target, port 8338, three sends
     * 500 ms apart, replies for 2 s. Holds a Wi-Fi multicast lock meanwhile,
     * since some Wi-Fi drivers drop broadcast traffic without one.
     */
    public static List<Host> scan(Context context) throws IOException {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("StrmQt discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException e) {
            // No permission or no Wi-Fi; an Ethernet device does not need it.
            LimeLog.info("StrmQt discovery without a multicast lock: " + e);
            lock = null;
        }
        try {
            List<Host> hosts = scan(broadcastTargets(), PORT, SENDS, SEND_INTERVAL_MS, COLLECT_MS);
            LimeLog.info("StrmQt discovery found " + hosts);
            return hosts;
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (RuntimeException ignored) {
                    // Already released.
                }
            }
        }
    }

    /**
     * Just enough JSON for a discovery reply: one object, whose string,
     * number and boolean members are kept and whose nested values are
     * skipped. Anything malformed gives null.
     */
    static final class Json {
        private final String text;
        private int pos;

        private Json(String text) {
            this.text = text;
        }

        static Map<String, Object> parseObject(String text) {
            Json json = new Json(text);
            try {
                json.skipSpace();
                Map<String, Object> object = json.object(0);
                json.skipSpace();
                return json.pos == text.length() ? object : null;
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                return null;
            }
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> members = new HashMap<>();
            skipSpace();
            if (peek() == '}') {
                pos++;
                return members;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                Object value = value(depth);
                // The first of a duplicated name wins, as it is the least surprising.
                if (!members.containsKey(key)) {
                    members.put(key, value);
                }
                skipSpace();
                char c = next();
                if (c == '}') {
                    return members;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or }");
                }
            }
        }

        private Object value(int depth) {
            if (depth > 16) {
                throw new IllegalArgumentException("Too deep");
            }
            char c = peek();
            switch (c) {
                case '"':
                    return string();
                case '{':
                    object(depth + 1);
                    return null;
                case '[':
                    array(depth + 1);
                    return null;
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    return number();
            }
        }

        private void array(int depth) {
            expect('[');
            skipSpace();
            if (peek() == ']') {
                pos++;
                return;
            }
            while (true) {
                skipSpace();
                value(depth);
                skipSpace();
                char c = next();
                if (c == ']') {
                    return;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or ]");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("Control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"': case '\\': case '/':
                        out.append(e);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("Short \\u escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad escape");
                }
            }
        }

        private Double number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Unexpected character");
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private void literal(String word) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalArgumentException("Expected " + word);
            }
            pos += word.length();
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException(String.format(Locale.ROOT, "Expected %c", c));
            }
        }

        private char peek() {
            return text.charAt(pos);
        }

        private char next() {
            return text.charAt(pos++);
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
