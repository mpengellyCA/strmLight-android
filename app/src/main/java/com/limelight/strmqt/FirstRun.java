package com.limelight.strmqt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The decisions behind the simple setup flow, kept out of SetupActivity so
 * they can be tested: which screen to show for what the search found and what
 * is stored, what the chooser lists, and how pairing's states read as two
 * ticks (picture, remote) and one line of trouble.
 *
 * Nothing here touches Android or the network.
 */
final class FirstRun {
    enum Screen {
        /** Looking for StrmQt. */
        SEARCHING,
        /** The code is shown and both halves are pairing. */
        CODE,
        /** More than one StrmQt answered (or the user is choosing again). */
        CHOOSE,
        /** Nothing that can stream answered, after a few searches. */
        NOT_FOUND,
        /** Pairing stopped for a reason a code cannot fix. */
        PROBLEM,
        /** Both halves paired; the stream starts in a moment. */
        CONNECTED
    }

    /** One tick on the code screen: the picture (Sunshine) or the remote (Web Remote). */
    enum Step { WAITING, TROUBLE, DONE }

    /** The one line of trouble the code screen shows; the details are on the advanced screen. */
    enum Trouble { NONE, WRONG_CODE, LOCKED_OUT, UNREACHABLE, REFUSED }

    /**
     * How many searches come back empty before "Can't find StrmQt". One search
     * is three probes over about 2 s, so this is some 7 s of looking.
     */
    static final int SEARCHES_BEFORE_NOT_FOUND = 3;
    /** On "Can't find StrmQt", the network is searched again this often while the screen is in front. */
    static final long QUIET_SEARCH_MS = 10000;

    /** What to do once a search has answered. */
    static final class Decision {
        final Screen screen;
        /** CODE: the host to pair with. */
        final StrmQtDiscovery.Host pairWith;
        /** SEARCHING: search again at once. */
        final boolean searchAgain;
        /** NOT_FOUND: a StrmQt answered, but has no streaming host (no Sunshine). */
        final boolean noStreamingHost;

        private Decision(Screen screen, StrmQtDiscovery.Host pairWith, boolean searchAgain,
                         boolean noStreamingHost) {
            this.screen = screen;
            this.pairWith = pairWith;
            this.searchAgain = searchAgain;
            this.noStreamingHost = noStreamingHost;
        }

        @Override
        public String toString() {
            return screen + (pairWith != null ? " " + pairWith.address : "")
                    + (searchAgain ? " (search again)" : "") + (noStreamingHost ? " (no streaming host)" : "");
        }
    }

    /** What opening the app does. */
    enum Launch {
        /** Nothing is set up: the simple setup, not the menu. */
        FIRST_RUN,
        /** Set up, but the remote is no longer paired: pairing with the stored host. */
        PAIR_AGAIN,
        /** Set up: straight to the stream. */
        CONNECT,
        /** Set up, with Connect on launch off: the fork's menu. */
        MENU
    }

    private FirstRun() {
    }

    /**
     * @param remoteNeedsPairing remote mode is on, StrmQt's address is known, and there is no pairing
     * @param hostChosen         a Sunshine host to connect to has been chosen (setup finished once)
     */
    static Launch onLaunch(boolean remoteNeedsPairing, boolean hostChosen, boolean connectOnLaunch) {
        if (remoteNeedsPairing) {
            return Launch.PAIR_AGAIN;
        }
        if (!hostChosen) {
            return Launch.FIRST_RUN;
        }
        return connectOnLaunch ? Launch.CONNECT : Launch.MENU;
    }

    /**
     * Which screen follows a search.
     *
     * @param found         every host that answered, usable or not
     * @param searchesDone  how many searches have answered since the user last asked, this one included
     * @param searchFailed  the last search could not run at all (no network)
     * @param autoPair      pairing may start by itself; false once the user has stopped or
     *                      left pairing, so the screen does not start it again behind their back
     * @param stored        the host stored by an earlier setup (address and ports), or null
     * @param pairNow       pair with the stored host at once (Pair again, a removed device)
     */
    static Decision afterSearch(List<StrmQtDiscovery.Host> found, int searchesDone, boolean searchFailed,
                                boolean autoPair, StrmQtDiscovery.Host stored, boolean pairNow) {
        List<StrmQtDiscovery.Host> usable = usable(found);

        if (pairNow && stored != null) {
            // The stored host, at the ports it answered with when it did (they may
            // have moved, as the Web Remote did from 8337); at the stored ones if not.
            StrmQtDiscovery.Host here = byAddress(usable, stored.address);
            return new Decision(Screen.CODE, here != null ? here : stored, false, false);
        }
        if (usable.size() == 1 && autoPair) {
            return new Decision(Screen.CODE, usable.get(0), false, false);
        }
        if (!usable.isEmpty()) {
            return new Decision(Screen.CHOOSE, null, false, false);
        }
        if (!searchFailed && searchesDone < SEARCHES_BEFORE_NOT_FOUND) {
            return new Decision(Screen.SEARCHING, null, true, false);
        }
        return new Decision(Screen.NOT_FOUND, null, false, found != null && !found.isEmpty());
    }

    /** The hosts that can stream, in the order they answered. */
    static List<StrmQtDiscovery.Host> usable(List<StrmQtDiscovery.Host> found) {
        List<StrmQtDiscovery.Host> usable = new ArrayList<>();
        if (found != null) {
            for (StrmQtDiscovery.Host host : found) {
                if (host != null && host.canStream()) {
                    usable.add(host);
                }
            }
        }
        return usable;
    }

    /** The chooser's list: the hosts that can stream, the stored one first. */
    static List<StrmQtDiscovery.Host> forChooser(List<StrmQtDiscovery.Host> found, String storedAddress) {
        List<StrmQtDiscovery.Host> list = usable(found);
        StrmQtDiscovery.Host stored = byAddress(list, storedAddress);
        if (stored != null) {
            list.remove(stored);
            list.add(0, stored);
        }
        return list;
    }

    /**
     * Whether the chooser has to show `host`'s address: only when another host
     * in the list goes by the same name, so the two can be told apart.
     */
    static boolean needsAddress(StrmQtDiscovery.Host host, List<StrmQtDiscovery.Host> list) {
        String name = key(host.name);
        if (name.isEmpty() || name.equals(key(host.address))) {
            // A host without a name is already listed by its address.
            return false;
        }
        for (StrmQtDiscovery.Host other : list) {
            if (other != host && !other.address.equals(host.address) && key(other.name).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One StrmQt per StrmLight: the computers in Moonlight's list to remove once
     * `keep` (the one just paired) is in it. Several TVs can share a StrmQt, but
     * a TV streams from one.
     */
    static List<String> toForget(List<String> known, String keep) {
        List<String> forget = new ArrayList<>();
        if (known == null || keep == null) {
            return forget;
        }
        for (String uuid : known) {
            if (uuid != null && !uuid.equalsIgnoreCase(keep)) {
                forget.add(uuid);
            }
        }
        return forget;
    }

    static StrmQtDiscovery.Host byAddress(List<StrmQtDiscovery.Host> hosts, String address) {
        if (address == null) {
            return null;
        }
        for (StrmQtDiscovery.Host host : hosts) {
            if (address.equals(host.address)) {
                return host;
            }
        }
        return null;
    }

    private static String key(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    // The code screen

    static Step step(OneCodePairing.StreamState state) {
        if (state == null) {
            return Step.WAITING;
        }
        switch (state) {
            case PAIRED:
                return Step.DONE;
            case WRONG_PIN:
            case REFUSED:
            case UNREACHABLE:
                return Step.TROUBLE;
            default:
                // Starting, waiting for the code, or an earlier attempt still open
                // on the host (which typing the code clears): nothing to do but type.
                return Step.WAITING;
        }
    }

    static Step step(OneCodePairing.RemoteState state) {
        if (state == null) {
            return Step.WAITING;
        }
        switch (state) {
            case PAIRED:
                return Step.DONE;
            case WRONG_CODE:
            case LOCKED_OUT:
            case UNREACHABLE:
                return Step.TROUBLE;
            default:
                return Step.WAITING;
        }
    }

    /**
     * The one line of trouble under the ticks, most useful first: a wrong code
     * is something to do now; a lockout says how long to wait; a host that
     * does not answer is worth knowing; a refusal is retried by itself.
     */
    static Trouble trouble(OneCodePairing.StreamState stream, OneCodePairing.RemoteState remote) {
        if (remote == OneCodePairing.RemoteState.LOCKED_OUT) {
            return Trouble.LOCKED_OUT;
        }
        if (remote == OneCodePairing.RemoteState.WRONG_CODE || stream == OneCodePairing.StreamState.WRONG_PIN) {
            return Trouble.WRONG_CODE;
        }
        if (remote == OneCodePairing.RemoteState.UNREACHABLE || stream == OneCodePairing.StreamState.UNREACHABLE) {
            return Trouble.UNREACHABLE;
        }
        if (stream == OneCodePairing.StreamState.REFUSED) {
            return Trouble.REFUSED;
        }
        return Trouble.NONE;
    }
}
