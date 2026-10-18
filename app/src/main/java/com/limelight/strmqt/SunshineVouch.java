package com.limelight.strmqt;

import com.limelight.LimeLog;

import java.security.GeneralSecurityException;
import java.security.cert.Certificate;

/**
 * StrmLight streams only from the Sunshine its StrmQt vouches for. StrmQt
 * names its Sunshine's certificate (SHA-256 of the DER, as
 * {@link CertPin#fingerprint} gives it) over the Web Remote, which is pinned
 * and needs the token; StrmLight compares that with the certificate Moonlight
 * pinned for the Sunshine it paired with. A plain Sunshine, or StrmQt's
 * desktop Sunshine on the same machine, is refused.
 */
public final class SunshineVouch {
    private SunshineVouch() {}

    /** What the launcher does with the paired, online Sunshine. */
    public enum Launch {
        /** It is the Sunshine StrmQt vouches for: stream. */
        STREAM,
        /** Nothing is stored (paired by a StrmLight before this): StrmQt's answer decides. */
        ASK,
        /** Not the Sunshine StrmQt vouches for: refuse, and offer pairing again. */
        NOT_STRMQT,
        /** StrmQt does not say which Sunshine is its own: refuse; StrmQt needs updating. */
        UNVOUCHED,
        /** StrmQt does not know this device any more: pair again. */
        UNPAIRED,
        /** StrmQt's Web Remote presents another certificate: pair again. */
        CERT_CHANGED,
        /** StrmQt did not answer: the attempt fails, and is tried again as any other. */
        RETRY
    }

    /** {@link CertPin#fingerprint}, or null when there is no certificate or it cannot be read. */
    public static String fingerprint(Certificate cert) {
        if (cert == null) {
            return null;
        }
        try {
            return CertPin.fingerprint(cert);
        } catch (GeneralSecurityException | RuntimeException e) {
            LimeLog.warning("StrmQt: cannot read Sunshine's certificate: " + e);
            return null;
        }
    }

    /** Whether both are the same certificate. Nothing matches a fingerprint that is missing. */
    public static boolean matches(String vouched, String presented) {
        String a = StrmQtDiscovery.normalizeFingerprint(vouched);
        String b = StrmQtDiscovery.normalizeFingerprint(presented);
        return a != null && a.equals(b);
    }

    /**
     * Before streaming, with the fingerprint stored at pairing (null when
     * there is none) and that of the certificate Moonlight pinned for the
     * Sunshine it is about to stream from.
     */
    public static Launch atLaunch(String stored, String presented) {
        if (StrmQtDiscovery.normalizeFingerprint(stored) == null) {
            return Launch.ASK;
        }
        return matches(stored, presented) ? Launch.STREAM : Launch.NOT_STRMQT;
    }

    /**
     * After asking StrmQt, as the launcher does before every stream (the
     * answer also carries the stream settings). With nothing stored, the
     * answer decides, as {@link #afterAsking}. With a fingerprint stored, an
     * answer naming another certificate is refused; any other answer (StrmQt
     * unreachable, no fingerprint in it, a {@code 401}, a changed Web Remote
     * certificate) leaves the decision to the stored fingerprint, as before
     * StrmLight asked every time: the remote finds out about the Web Remote
     * in the stream.
     */
    public static Launch atEveryLaunch(String stored, StrmQtClient.Vouch answer, String presented) {
        if (StrmQtDiscovery.normalizeFingerprint(stored) == null) {
            return afterAsking(answer, presented);
        }
        if (answer.kind == StrmQtClient.Vouch.Kind.VOUCHED && !matches(answer.fingerprint, stored)) {
            return Launch.NOT_STRMQT;
        }
        return atLaunch(stored, presented);
    }

    /** After asking StrmQt, because nothing was stored. */
    public static Launch afterAsking(StrmQtClient.Vouch answer, String presented) {
        switch (answer.kind) {
            case VOUCHED:
                return matches(answer.fingerprint, presented) ? Launch.STREAM : Launch.NOT_STRMQT;
            case NONE:
                return Launch.UNVOUCHED;
            case UNPAIRED:
                return Launch.UNPAIRED;
            case CERT_CHANGED:
                return Launch.CERT_CHANGED;
            case UNREACHABLE:
            default:
                return Launch.RETRY;
        }
    }
}
