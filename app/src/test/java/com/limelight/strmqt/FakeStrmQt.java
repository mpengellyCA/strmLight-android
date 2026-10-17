package com.limelight.strmqt;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * StrmQt's Web Remote as the client sees it, after src/remote/WebRemoteServer.cpp:
 * a self-signed certificate; StrmLight pairing, where a StrmLight asks with
 * the code it shows and is told to wait (202) until someone types a code in,
 * then gets a token for the right code or 403 for a wrong one (a typed code
 * dies after five misses); 429 while locked out; tokens that are kept until
 * removed (they outlive a restart when the set is handed on); and POSTs that
 * must be JSON.
 */
final class FakeStrmQt {
    /** A certificate and its key, so a "restarted" server can present the same one. */
    static final class Identity {
        final X509Certificate cert;
        final SSLContext context;

        Identity() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=StrmQt");
            long now = System.currentTimeMillis();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                    BigInteger.valueOf(now), new Date(now - 60000), new Date(now + 86400000L),
                    name, pair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
            cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("strmqt", pair.getPrivate(), new char[0], new Certificate[]{cert});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, new char[0]);
            context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
        }
    }

    static final Set<String> KNOWN_ACTIONS = new HashSet<>();

    static {
        for (RemoteButton button : RemoteButton.values()) {
            KNOWN_ACTIONS.add(button.action);
            if (button.longAction != null) {
                KNOWN_ACTIONS.add(button.longAction);
            }
        }
    }

    static final int MAX_CODE_MISSES = 5;

    final List<String> actions = Collections.synchronizedList(new ArrayList<String>());
    final Set<String> tokens;
    /** Every pairing POST's body and X-StrmLight header ("" when there was none), in order. */
    final List<String> pairBodies = Collections.synchronizedList(new ArrayList<String>());
    final List<String> pairNames = Collections.synchronizedList(new ArrayList<String>());
    final AtomicInteger pairPosts = new AtomicInteger();
    final AtomicInteger actionPosts = new AtomicInteger();
    volatile boolean requirePin = true;
    /** A StrmQt from before StrmLight pairing: GET /api/auth/pin has no "pairing". */
    volatile boolean oldStrmQt;
    volatile boolean lockedOut;
    volatile long actionDelayMs;
    volatile int requests;
    /** The StrmLight code someone typed into StrmQt; null when there is none. */
    private String typedCode;
    private int codeMisses;

    private final SSLServerSocket listener;
    private final Set<Socket> connections = Collections.synchronizedSet(new HashSet<Socket>());

    FakeStrmQt(Identity identity, int port) throws IOException {
        this(identity, port, Collections.synchronizedSet(new HashSet<String>()));
    }

    /** @param tokens the paired devices, kept across a restart by passing the old server's set */
    FakeStrmQt(Identity identity, int port, Set<String> tokens) throws IOException {
        this.tokens = tokens;
        listener = (SSLServerSocket) identity.context.getServerSocketFactory()
                .createServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (true) {
                final Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException e) {
                    return; // stopped
                }
                connections.add(socket);
                Thread serve = new Thread(() -> serve(socket), "FakeStrmQt connection");
                serve.setDaemon(true);
                serve.start();
            }
        }, "FakeStrmQt accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return listener.getLocalPort();
    }

    /** Someone types a StrmLight's code into StrmQt (Settings or a phone). */
    synchronized void typeCode(String code) {
        typedCode = code;
        codeMisses = 0;
    }

    synchronized String typedCode() {
        return typedCode;
    }

    /** A token for a device paired some other time. */
    String pairDevice() {
        String token = UUID.randomUUID().toString();
        tokens.add(token);
        return token;
    }

    /** Closes the listener and every open connection, as StrmQt going away does. */
    void stop() {
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        synchronized (connections) {
            for (Socket socket : connections) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            connections.clear();
        }
    }

    private static final class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    // HTTP/1.1 with keep-alive and Content-Length framing only, like the real server.
    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                String line = readLine(in);
                if (line == null) {
                    return;
                }
                Request request = new Request();
                String[] parts = line.split(" ");
                request.method = parts[0];
                request.path = parts[1];
                while (!(line = readLine(in)).isEmpty()) {
                    int colon = line.indexOf(':');
                    request.headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
                int length = Integer.parseInt(request.headers.getOrDefault("content-length", "0"));
                byte[] body = new byte[length];
                for (int read = 0; read < length; ) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) {
                        return;
                    }
                    read += n;
                }
                request.body = new String(body, StandardCharsets.UTF_8);

                requests++;
                String[] answer = request.path.equals("/api/auth/pin") ? authPin(request)
                        : request.path.equals("/api/action") ? action(request)
                        : new String[]{"404", "{\"error\":\"Unknown endpoint\"}"};
                byte[] payload = answer[1].getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 " + answer[0] + " X\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
            }
        } catch (IOException | RuntimeException ignored) {
            // The client went away, or the server was stopped.
        } finally {
            connections.remove(socket);
        }
    }

    // Header values are read as UTF-8, as the real server reads X-StrmLight.
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return line.size() == 0 ? null : line.toString("UTF-8");
            }
            if (c == '\n') {
                return line.toString("UTF-8");
            }
            if (c != '\r') {
                line.write(c);
            }
        }
    }

    private boolean authorized(Request request) {
        String header = request.headers.get("authorization");
        return header != null && header.startsWith("Bearer ") && tokens.contains(header.substring(7).trim());
    }

    private static boolean json(Request request) {
        String type = request.headers.get("content-type");
        return type != null && type.startsWith("application/json");
    }

    private synchronized String[] authPin(Request request) {
        if (request.method.equals("GET")) {
            String pairing = oldStrmQt ? "" : ",\"pairing\":\"" + (typedCode == null ? "none" : "strmlight") + "\"";
            return new String[]{"200", "{\"authorized\":" + (!requirePin || authorized(request))
                    + pairing + ",\"required\":" + requirePin + "}"};
        }
        if (!json(request)) {
            return new String[]{"415", "{\"error\":\"Expected application/json\"}"};
        }
        String name = request.headers.getOrDefault("x-strmlight", "").trim();
        pairPosts.incrementAndGet();
        pairBodies.add(request.body);
        pairNames.add(name);
        boolean strmLight = !name.isEmpty();
        if (strmLight && typedCode == null) {
            // Nothing typed in yet: nothing to guess at, nothing counted.
            return new String[]{"202", "{\"pending\":true}"};
        }
        if (lockedOut) {
            return new String[]{"429", "{\"error\":\"Too many failed attempts. Please wait.\"}"};
        }
        if (!strmLight) {
            // A phone's request, checked against a code shown on the TV; none is here.
            return new String[]{"403", "{\"error\":\"No code is showing on the TV.\"}"};
        }
        if (request.body.equals("{\"pin\":\"" + typedCode + "\"}")) {
            typedCode = null; // one code, one device
            return new String[]{"200", "{\"token\":\"" + pairDevice() + "\"}"};
        }
        if (++codeMisses >= MAX_CODE_MISSES) {
            typedCode = null;
        }
        return new String[]{"403", "{\"error\":\"Invalid PIN\"}"};
    }

    private String[] action(Request request) {
        actionPosts.incrementAndGet();
        if (!json(request)) {
            return new String[]{"415", "{\"error\":\"Expected application/json\"}"};
        }
        if (requirePin && !authorized(request)) {
            return new String[]{"401", "{\"error\":\"Unauthorized\",\"requirePin\":true}"};
        }
        String id = request.body.replaceAll("^\\{\"action\":\"(.*)\"\\}$", "$1");
        if (!KNOWN_ACTIONS.contains(id)) {
            return new String[]{"400", "{\"error\":\"Unknown action\"}"};
        }
        if (actionDelayMs > 0) {
            try {
                Thread.sleep(actionDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        actions.add(id);
        return new String[]{"200", "{\"ok\":true}"};
    }
}
