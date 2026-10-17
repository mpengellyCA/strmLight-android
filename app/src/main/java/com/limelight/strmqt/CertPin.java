package com.limelight.strmqt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Trust on first use for StrmQt's self-signed certificate: the certificate's
 * SHA-256 is recorded at pairing, and afterwards only that certificate is
 * accepted. Its name is not checked: the certificate lists the addresses the
 * host had when it was made, and the pin identifies it exactly.
 */
public final class CertPin {
    private CertPin() {}

    /** Thrown when the server presents a certificate other than the pinned one. */
    public static final class MismatchException extends CertificateException {
        public MismatchException(String message) {
            super(message);
        }
    }

    /** Lowercase hex SHA-256 of the certificate's DER encoding. */
    public static String fingerprint(Certificate cert) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** AA:BB:CC:… as StrmQt shows it under Settings, Web Remote. */
    public static String display(String fingerprint) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(fingerprint, i, Math.min(i + 2, fingerprint.length()));
        }
        return out.toString().toUpperCase();
    }

    /** Accepts exactly the certificate with this fingerprint. */
    public static X509TrustManager trustManager(final String fingerprint) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("No certificate");
                }
                String presented;
                try {
                    presented = fingerprint(chain[0]);
                } catch (GeneralSecurityException e) {
                    throw new CertificateException(e);
                }
                if (!presented.equalsIgnoreCase(fingerprint)) {
                    throw new MismatchException("StrmQt's certificate is not the one that was paired");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    public static SSLSocketFactory socketFactory(X509TrustManager trustManager) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, null);
        return context.getSocketFactory();
    }

    /**
     * Connects once to read the certificate the server presents, for pairing.
     * Nothing is sent on this connection; the caller pins what it returns and
     * talks to the server only through the pin.
     */
    public static X509Certificate fetch(String host, int port, int timeoutMs)
            throws IOException, GeneralSecurityException {
        final X509Certificate[] seen = new X509Certificate[1];
        X509TrustManager recorder = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("No certificate");
                }
                seen[0] = chain[0];
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        try (SSLSocket socket = (SSLSocket) socketFactory(recorder).createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
        }
        if (seen[0] == null) {
            throw new IOException("The server presented no certificate");
        }
        return seen[0];
    }

    /** Whether `error`, or anything that caused it, is a pin mismatch. */
    public static boolean isMismatch(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MismatchException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
