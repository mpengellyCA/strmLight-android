package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.strmqt.FirstRun.Decision;
import com.limelight.strmqt.FirstRun.Screen;
import com.limelight.strmqt.FirstRun.Step;
import com.limelight.strmqt.FirstRun.Trouble;
import com.limelight.strmqt.OneCodePairing.RemoteState;
import com.limelight.strmqt.OneCodePairing.StreamState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FirstRunTest {
    private static final StrmQtDiscovery.Host DEN =
            new StrmQtDiscovery.Host("192.168.2.10", "den-pc", 8338, 48989, null);
    private static final StrmQtDiscovery.Host LOFT =
            new StrmQtDiscovery.Host("192.168.2.11", "loft", 8338, 48989, null);
    /** Answers discovery but has no Sunshine. */
    private static final StrmQtDiscovery.Host NO_STREAM =
            new StrmQtDiscovery.Host("192.168.2.12", "office", 8338, 0, null);

    private static List<StrmQtDiscovery.Host> hosts(StrmQtDiscovery.Host... hosts) {
        return new ArrayList<>(Arrays.asList(hosts));
    }

    private static Decision first(List<StrmQtDiscovery.Host> found, int searches) {
        return FirstRun.afterSearch(found, searches, false, true, null, false);
    }

    // Opening the app

    @Test
    public void nothingSetUpOpensTheSimpleSetupNotTheMenu() {
        assertEquals(FirstRun.Launch.FIRST_RUN, FirstRun.onLaunch(false, false, true));
        assertEquals("whatever Connect on launch says", FirstRun.Launch.FIRST_RUN,
                FirstRun.onLaunch(false, false, false));
    }

    @Test
    public void setUpGoesStraightToTheStreamAsBefore() {
        assertEquals(FirstRun.Launch.CONNECT, FirstRun.onLaunch(false, true, true));
        assertEquals(FirstRun.Launch.MENU, FirstRun.onLaunch(false, true, false));
    }

    @Test
    public void aRemoteThatLostItsPairingPairsAgainFirst() {
        assertEquals(FirstRun.Launch.PAIR_AGAIN, FirstRun.onLaunch(true, true, true));
        assertEquals(FirstRun.Launch.PAIR_AGAIN, FirstRun.onLaunch(true, false, true));
    }

    // Which screen

    @Test
    public void exactlyOneHostGoesStraightToTheCode() {
        Decision d = first(hosts(DEN), 1);
        assertEquals(Screen.CODE, d.screen);
        assertSame(DEN, d.pairWith);
    }

    @Test
    public void aHostThatCannotStreamDoesNotCountTowardsExactlyOne() {
        Decision d = first(hosts(NO_STREAM, DEN), 1);
        assertEquals(Screen.CODE, d.screen);
        assertSame(DEN, d.pairWith);
    }

    @Test
    public void severalHostsAreOfferedToChoose() {
        Decision d = first(hosts(DEN, LOFT), 1);
        assertEquals(Screen.CHOOSE, d.screen);
        assertNull(d.pairWith);
    }

    @Test
    public void oneHostIsOfferedToChooseOnceTheUserHasLeftPairing() {
        Decision d = FirstRun.afterSearch(hosts(DEN), 1, false, false, null, false);
        assertEquals("pairing is not started again behind the user's back", Screen.CHOOSE, d.screen);
    }

    @Test
    public void nothingFoundKeepsLookingForAFewSearches() {
        for (int searches = 1; searches < FirstRun.SEARCHES_BEFORE_NOT_FOUND; searches++) {
            Decision d = first(hosts(), searches);
            assertEquals(Screen.SEARCHING, d.screen);
            assertTrue(d.searchAgain);
        }
    }

    @Test
    public void nothingFoundAfterAWhileIsNotFound() {
        Decision d = first(hosts(), FirstRun.SEARCHES_BEFORE_NOT_FOUND);
        assertEquals(Screen.NOT_FOUND, d.screen);
        assertFalse(d.searchAgain);
        assertFalse(d.noStreamingHost);
    }

    @Test
    public void aSearchThatCannotRunIsNotFoundAtOnce() {
        Decision d = FirstRun.afterSearch(null, 1, true, true, null, false);
        assertEquals(Screen.NOT_FOUND, d.screen);
        assertFalse(d.searchAgain);
    }

    @Test
    public void onlyHostsThatCannotStreamSaySo() {
        Decision d = first(hosts(NO_STREAM), FirstRun.SEARCHES_BEFORE_NOT_FOUND);
        assertEquals(Screen.NOT_FOUND, d.screen);
        assertTrue(d.noStreamingHost);
    }

    @Test
    public void aHostFoundOnALaterSearchStillPairsByItself() {
        assertEquals(Screen.SEARCHING, first(hosts(), 1).screen);
        Decision d = first(hosts(DEN), 2);
        assertEquals(Screen.CODE, d.screen);
        assertSame(DEN, d.pairWith);
    }

    @Test
    public void pairNowUsesTheStoredHostAtThePortsItAnswersWith() {
        StrmQtDiscovery.Host stored = new StrmQtDiscovery.Host(DEN.address, "den-pc", 8337, 48989, null);
        Decision d = FirstRun.afterSearch(hosts(LOFT, DEN), 1, false, true, stored, true);
        assertEquals(Screen.CODE, d.screen);
        assertSame("the found host, with its corrected Web Remote port", DEN, d.pairWith);
    }

    @Test
    public void pairNowUsesTheStoredHostWhenItDoesNotAnswer() {
        StrmQtDiscovery.Host stored = new StrmQtDiscovery.Host("10.0.0.5", "far", 8338, 48989, null);
        Decision d = FirstRun.afterSearch(hosts(), 1, false, true, stored, true);
        assertEquals(Screen.CODE, d.screen);
        assertSame(stored, d.pairWith);
        Decision failed = FirstRun.afterSearch(null, 1, true, true, stored, true);
        assertSame("even when the search could not run", stored, failed.pairWith);
    }

    @Test
    public void pairNowWithoutAStoredHostIsAnOrdinarySetup() {
        Decision d = FirstRun.afterSearch(hosts(DEN, LOFT), 1, false, true, null, true);
        assertEquals(Screen.CHOOSE, d.screen);
    }

    @Test
    public void aStoredHostDoesNotSkipTheChooserWhenSeveralAnswer() {
        StrmQtDiscovery.Host stored = new StrmQtDiscovery.Host(LOFT.address, "loft", 8338, 48989, null);
        Decision d = FirstRun.afterSearch(hosts(DEN, LOFT), 1, false, true, stored, false);
        assertEquals(Screen.CHOOSE, d.screen);
    }

    // The chooser

    @Test
    public void theChooserListsUsableHostsWithTheStoredOneFirst() {
        List<StrmQtDiscovery.Host> list = FirstRun.forChooser(hosts(DEN, NO_STREAM, LOFT), LOFT.address);
        assertEquals(Arrays.asList(LOFT, DEN), list);
        assertEquals(Arrays.asList(DEN, LOFT), FirstRun.forChooser(hosts(DEN, LOFT), null));
        assertEquals(Collections.emptyList(), FirstRun.forChooser(null, null));
    }

    @Test
    public void addressesAreShownOnlyToTellTwoOfTheSameNameApart() {
        List<StrmQtDiscovery.Host> distinct = hosts(DEN, LOFT);
        assertFalse(FirstRun.needsAddress(DEN, distinct));
        assertFalse(FirstRun.needsAddress(LOFT, distinct));

        StrmQtDiscovery.Host twin = new StrmQtDiscovery.Host("192.168.2.20", "Den-PC ", 8338, 48989, null);
        List<StrmQtDiscovery.Host> twins = hosts(DEN, twin, LOFT);
        assertTrue(FirstRun.needsAddress(DEN, twins));
        assertTrue("names are compared without case or spaces", FirstRun.needsAddress(twin, twins));
        assertFalse(FirstRun.needsAddress(LOFT, twins));
    }

    @Test
    public void aHostNamedByItsAddressNeedsNoSecondAddress() {
        StrmQtDiscovery.Host unnamed = new StrmQtDiscovery.Host("192.168.2.30", "192.168.2.30", 8338, 48989, null);
        assertFalse(FirstRun.needsAddress(unnamed, hosts(unnamed, DEN)));
    }

    // The code screen

    @Test
    public void streamStatesReadAsOneTick() {
        assertEquals(Step.WAITING, FirstRun.step((StreamState) null));
        assertEquals(Step.WAITING, FirstRun.step(StreamState.STARTING));
        assertEquals(Step.WAITING, FirstRun.step(StreamState.WAITING));
        assertEquals("typing the code clears an earlier attempt", Step.WAITING,
                FirstRun.step(StreamState.SESSION_PENDING));
        assertEquals(Step.TROUBLE, FirstRun.step(StreamState.WRONG_PIN));
        assertEquals(Step.TROUBLE, FirstRun.step(StreamState.REFUSED));
        assertEquals(Step.TROUBLE, FirstRun.step(StreamState.UNREACHABLE));
        assertEquals(Step.DONE, FirstRun.step(StreamState.PAIRED));
    }

    @Test
    public void remoteStatesReadAsOneTick() {
        assertEquals(Step.WAITING, FirstRun.step((RemoteState) null));
        assertEquals(Step.WAITING, FirstRun.step(RemoteState.STARTING));
        assertEquals(Step.WAITING, FirstRun.step(RemoteState.WAITING));
        assertEquals(Step.TROUBLE, FirstRun.step(RemoteState.WRONG_CODE));
        assertEquals(Step.TROUBLE, FirstRun.step(RemoteState.LOCKED_OUT));
        assertEquals(Step.TROUBLE, FirstRun.step(RemoteState.UNREACHABLE));
        assertEquals(Step.DONE, FirstRun.step(RemoteState.PAIRED));
    }

    @Test
    public void troubleShowsTheMostUsefulLine() {
        assertEquals(Trouble.NONE, FirstRun.trouble(null, null));
        assertEquals(Trouble.NONE, FirstRun.trouble(StreamState.WAITING, RemoteState.WAITING));
        assertEquals(Trouble.NONE, FirstRun.trouble(StreamState.PAIRED, RemoteState.PAIRED));
        assertEquals(Trouble.WRONG_CODE, FirstRun.trouble(StreamState.WRONG_PIN, RemoteState.WAITING));
        assertEquals(Trouble.WRONG_CODE, FirstRun.trouble(StreamState.PAIRED, RemoteState.WRONG_CODE));
        assertEquals("a lockout says how long to wait", Trouble.LOCKED_OUT,
                FirstRun.trouble(StreamState.WRONG_PIN, RemoteState.LOCKED_OUT));
        assertEquals("a wrong code beats a host that does not answer", Trouble.WRONG_CODE,
                FirstRun.trouble(StreamState.UNREACHABLE, RemoteState.WRONG_CODE));
        assertEquals(Trouble.UNREACHABLE, FirstRun.trouble(StreamState.UNREACHABLE, RemoteState.PAIRED));
        assertEquals(Trouble.UNREACHABLE, FirstRun.trouble(StreamState.WAITING, RemoteState.UNREACHABLE));
        assertEquals(Trouble.REFUSED, FirstRun.trouble(StreamState.REFUSED, RemoteState.WAITING));
    }
}
