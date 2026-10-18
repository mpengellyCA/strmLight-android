package com.limelight.strmqt;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Following StrmQt's Sunshine to a new port or address: which discovery
 * reply, if any, is the paired StrmQt somewhere else.
 */
public class HostMoveTest {
    private static final String PAIRED =
            "5f1e0c4b9a7d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f";
    private static final String OTHER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ADDRESS = "192.168.2.10";
    private static final int OLD_PORT = 48989;

    private static StrmQtDiscovery.Host reply(String address, int sunshinePort, String fingerprint) {
        return new StrmQtDiscovery.Host(address, "den-pc", 8338, sunshinePort, fingerprint);
    }

    private static StrmQtDiscovery.Host follow(StrmQtDiscovery.Host... replies) {
        return HostMove.follow(ADDRESS, OLD_PORT, PAIRED, Arrays.asList(replies));
    }

    @Test
    public void theSameStrmQtOnANewPortIsFollowed() {
        StrmQtDiscovery.Host moved = reply(ADDRESS, 8335, PAIRED);
        assertSame(moved, follow(moved));
        StrmQtDiscovery.Host stepped = reply(ADDRESS, 8435, PAIRED);
        assertSame("a base StrmQt stepped to", stepped, follow(stepped));
    }

    @Test
    public void theSameStrmQtAtANewAddressIsFollowed() {
        StrmQtDiscovery.Host moved = reply("192.168.2.77", OLD_PORT, PAIRED);
        assertSame(moved, follow(moved));
        StrmQtDiscovery.Host both = reply("192.168.2.77", 8335, PAIRED);
        assertSame("a new address and a new port", both, follow(both));
    }

    @Test
    public void theFingerprintMatchesHoweverItIsWritten() {
        StrmQtDiscovery.Host moved = reply(ADDRESS, 8335, CertPin.display(PAIRED));
        assertSame(moved, follow(moved));
        StrmQtDiscovery.Host upper = reply(ADDRESS, 8335, PAIRED.toUpperCase(Locale.ROOT));
        assertSame(upper, follow(upper));
    }

    @Test
    public void nothingMovedIsNothingToFollow() {
        assertNull(follow(reply(ADDRESS, OLD_PORT, PAIRED)));
    }

    @Test
    public void anotherStrmQtIsIgnored() {
        assertNull(follow(reply(ADDRESS, 8335, OTHER)));
        assertNull(follow(reply("192.168.2.77", 8335, OTHER)));
    }

    @Test
    public void aReplyWithoutAFingerprintIsIgnored() {
        assertNull(follow(reply(ADDRESS, 8335, null)));
        assertNull("not a fingerprint", follow(reply(ADDRESS, 8335, "abc")));
    }

    @Test
    public void theMatchingReplyIsFollowedAmongOthers() {
        StrmQtDiscovery.Host moved = reply("192.168.2.77", 8335, PAIRED);
        assertSame(moved, follow(
                reply("192.168.2.11", 8335, OTHER),
                reply("192.168.2.12", 8335, null),
                moved,
                reply("192.168.2.13", 8435, OTHER)));
    }

    @Test
    public void aNewPortAtTheSameAddressWinsOverANewAddress() {
        StrmQtDiscovery.Host samePlace = reply(ADDRESS, 8335, PAIRED);
        assertSame(samePlace, follow(reply("192.168.2.77", 8335, PAIRED), samePlace));
        assertSame(samePlace, follow(samePlace, reply("192.168.2.77", 8335, PAIRED)));
    }

    @Test
    public void theStrmQtStillWhereItWasPairedIsNotFollowedElsewhere() {
        // One machine answering on two interfaces, one of them the paired address.
        assertNull(follow(reply("192.168.2.77", 8335, PAIRED), reply(ADDRESS, OLD_PORT, PAIRED)));
    }

    @Test
    public void aStrmQtThatCannotStreamIsNotFollowed() {
        assertNull(follow(reply(ADDRESS, 0, PAIRED)));
    }

    @Test
    public void withoutAPinnedCertificateNothingIsFollowed() {
        List<StrmQtDiscovery.Host> replies = Collections.singletonList(reply(ADDRESS, 8335, PAIRED));
        assertNull(HostMove.follow(ADDRESS, OLD_PORT, null, replies));
        assertNull(HostMove.follow(ADDRESS, OLD_PORT, "", replies));
    }

    @Test
    public void noRepliesIsNothingToFollow() {
        assertNull(follow());
        assertNull(HostMove.follow(ADDRESS, OLD_PORT, PAIRED, null));
    }
}
