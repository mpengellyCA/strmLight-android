package com.limelight.strmqt;

import java.util.List;

/**
 * StrmQt's Sunshine may not be where StrmLight paired it: StrmQt moved its
 * base port (48989 before, 8335 now), or picked another because that one was
 * busy (8435, 8535…), or the machine has a new address. When the paired
 * Sunshine does not answer, the launcher searches the network, and this
 * decides whether a reply is the paired StrmQt somewhere else.
 *
 * The paired StrmQt is the one announcing the Web Remote certificate pinned
 * at pairing. A reply is unauthenticated, so this only says where to look:
 * the stream still starts only from the Sunshine StrmQt vouched for
 * ({@link SunshineVouch}), checked against the certificate Moonlight pinned.
 */
public final class HostMove {
    private HostMove() {}

    /**
     * @param storedAddress where StrmQt was paired
     * @param storedSunshinePort the Sunshine base port it was paired at
     * @param certFingerprint the Web Remote certificate pinned at pairing
     * @param replies what a search found
     * @return the reply to follow, or null when nothing moved or no reply is
     *         the paired StrmQt with a Sunshine
     */
    public static StrmQtDiscovery.Host follow(String storedAddress, int storedSunshinePort,
                                              String certFingerprint, List<StrmQtDiscovery.Host> replies) {
        if (replies == null || StrmQtDiscovery.normalizeFingerprint(certFingerprint) == null) {
            return null;
        }
        StrmQtDiscovery.Host moved = null;
        for (StrmQtDiscovery.Host host : replies) {
            if (host == null || !host.canStream() || !SunshineVouch.matches(certFingerprint, host.fingerprint)) {
                continue;
            }
            boolean sameAddress = host.address.equals(storedAddress);
            if (sameAddress && host.sunshinePort == storedSunshinePort) {
                // It is where it was paired; its Sunshine is down, not moved.
                return null;
            }
            // A new port at the same address before a new address.
            if (moved == null || (sameAddress && !moved.address.equals(storedAddress))) {
                moved = host;
            }
        }
        return moved;
    }
}
