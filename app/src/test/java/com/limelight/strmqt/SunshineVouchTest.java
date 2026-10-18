package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * The comparisons behind "StrmLight streams only from the Sunshine its StrmQt
 * vouches for": at pairing, and before every stream.
 */
public class SunshineVouchTest {
    private static final String VOUCHED =
            "5f1e0c4b9a7d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f";
    private static final String OTHER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static StrmQtClient.Vouch answer(StrmQtClient.Vouch.Kind kind, String fingerprint) {
        return new StrmQtClient.Vouch(kind, fingerprint);
    }

    @Test
    public void theSameCertificateMatches() {
        assertTrue(SunshineVouch.matches(VOUCHED, VOUCHED));
        // However it is written.
        assertTrue(SunshineVouch.matches(VOUCHED, VOUCHED.toUpperCase(Locale.ROOT)));
        assertTrue(SunshineVouch.matches(CertPin.display(VOUCHED), VOUCHED));
    }

    @Test
    public void nothingElseMatches() {
        assertFalse(SunshineVouch.matches(VOUCHED, OTHER));
        assertFalse("no certificate", SunshineVouch.matches(VOUCHED, null));
        assertFalse("nothing vouched for", SunshineVouch.matches(null, VOUCHED));
        assertFalse(SunshineVouch.matches(null, null));
        assertFalse(SunshineVouch.matches("", ""));
        assertFalse("not a fingerprint", SunshineVouch.matches("abc", "abc"));
    }

    @Test
    public void theFingerprintIsCertPins() throws Exception {
        FakeStrmQt.Identity identity = new FakeStrmQt.Identity();
        assertEquals(CertPin.fingerprint(identity.cert), SunshineVouch.fingerprint(identity.cert));
        assertNull(SunshineVouch.fingerprint(null));
    }

    @Test
    public void atLaunchTheVouchedSunshineStreams() {
        assertEquals(SunshineVouch.Launch.STREAM, SunshineVouch.atLaunch(VOUCHED, VOUCHED));
    }

    @Test
    public void atLaunchAnotherSunshineIsRefused() {
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.atLaunch(VOUCHED, OTHER));
        assertEquals("no certificate pinned", SunshineVouch.Launch.NOT_STRMQT,
                SunshineVouch.atLaunch(VOUCHED, null));
    }

    @Test
    public void atLaunchNothingStoredAsksStrmQt() {
        // Paired by a StrmLight from before StrmQt vouched.
        assertEquals(SunshineVouch.Launch.ASK, SunshineVouch.atLaunch(null, VOUCHED));
        assertEquals(SunshineVouch.Launch.ASK, SunshineVouch.atLaunch("", VOUCHED));
        assertEquals(SunshineVouch.Launch.ASK, SunshineVouch.atLaunch(null, null));
    }

    @Test
    public void afterAskingOnlyTheVouchedSunshineStreams() {
        StrmQtClient.Vouch vouched = answer(StrmQtClient.Vouch.Kind.VOUCHED, VOUCHED);
        assertEquals(SunshineVouch.Launch.STREAM, SunshineVouch.afterAsking(vouched, VOUCHED));
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.afterAsking(vouched, OTHER));
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.afterAsking(vouched, null));
    }

    @Test
    public void afterAskingEveryOtherAnswerIsNotAStream() {
        assertEquals(SunshineVouch.Launch.UNVOUCHED,
                SunshineVouch.afterAsking(answer(StrmQtClient.Vouch.Kind.NONE, null), VOUCHED));
        assertEquals(SunshineVouch.Launch.UNPAIRED,
                SunshineVouch.afterAsking(answer(StrmQtClient.Vouch.Kind.UNPAIRED, null), VOUCHED));
        assertEquals(SunshineVouch.Launch.CERT_CHANGED,
                SunshineVouch.afterAsking(answer(StrmQtClient.Vouch.Kind.CERT_CHANGED, null), VOUCHED));
        assertEquals(SunshineVouch.Launch.RETRY,
                SunshineVouch.afterAsking(answer(StrmQtClient.Vouch.Kind.UNREACHABLE, null), VOUCHED));
    }
    @Test
    public void everyLaunchWithNothingStoredTheAnswerDecides() {
        StrmQtClient.Vouch vouched = answer(StrmQtClient.Vouch.Kind.VOUCHED, VOUCHED);
        assertEquals(SunshineVouch.Launch.STREAM, SunshineVouch.atEveryLaunch(null, vouched, VOUCHED));
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.atEveryLaunch(null, vouched, OTHER));
        assertEquals(SunshineVouch.Launch.UNVOUCHED,
                SunshineVouch.atEveryLaunch(null, answer(StrmQtClient.Vouch.Kind.NONE, null), VOUCHED));
        assertEquals(SunshineVouch.Launch.UNPAIRED,
                SunshineVouch.atEveryLaunch("", answer(StrmQtClient.Vouch.Kind.UNPAIRED, null), VOUCHED));
        assertEquals(SunshineVouch.Launch.CERT_CHANGED,
                SunshineVouch.atEveryLaunch(null, answer(StrmQtClient.Vouch.Kind.CERT_CHANGED, null), VOUCHED));
        assertEquals("no answer, nothing stored: tried again", SunshineVouch.Launch.RETRY,
                SunshineVouch.atEveryLaunch(null, answer(StrmQtClient.Vouch.Kind.UNREACHABLE, null), VOUCHED));
    }

    @Test
    public void everyLaunchTheSameVouchStreams() {
        assertEquals(SunshineVouch.Launch.STREAM, SunshineVouch.atEveryLaunch(VOUCHED,
                answer(StrmQtClient.Vouch.Kind.VOUCHED, VOUCHED), VOUCHED));
        assertEquals("however it is written", SunshineVouch.Launch.STREAM, SunshineVouch.atEveryLaunch(
                VOUCHED, answer(StrmQtClient.Vouch.Kind.VOUCHED, VOUCHED), VOUCHED.toUpperCase(Locale.ROOT)));
        assertEquals("but not from another Sunshine", SunshineVouch.Launch.NOT_STRMQT,
                SunshineVouch.atEveryLaunch(VOUCHED, answer(StrmQtClient.Vouch.Kind.VOUCHED, VOUCHED), OTHER));
    }

    @Test
    public void everyLaunchAVouchForAnotherSunshineIsRefused() {
        // Even when the Sunshine at hand is the one StrmQt now names: it is not the paired one.
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.atEveryLaunch(VOUCHED,
                answer(StrmQtClient.Vouch.Kind.VOUCHED, OTHER), OTHER));
        assertEquals(SunshineVouch.Launch.NOT_STRMQT, SunshineVouch.atEveryLaunch(VOUCHED,
                answer(StrmQtClient.Vouch.Kind.VOUCHED, OTHER), VOUCHED));
    }

    @Test
    public void everyLaunchWithoutAVouchTheStoredFingerprintDecides() {
        for (StrmQtClient.Vouch.Kind kind : new StrmQtClient.Vouch.Kind[]{StrmQtClient.Vouch.Kind.UNREACHABLE,
                StrmQtClient.Vouch.Kind.NONE, StrmQtClient.Vouch.Kind.UNPAIRED,
                StrmQtClient.Vouch.Kind.CERT_CHANGED}) {
            assertEquals(kind + ": streams as before", SunshineVouch.Launch.STREAM,
                    SunshineVouch.atEveryLaunch(VOUCHED, answer(kind, null), VOUCHED));
            assertEquals(kind + ": another Sunshine still refused", SunshineVouch.Launch.NOT_STRMQT,
                    SunshineVouch.atEveryLaunch(VOUCHED, answer(kind, null), OTHER));
        }
    }
}
