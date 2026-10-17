package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RemoteGesturesTest {
    /** A scheduler on virtual time. */
    private static final class FakeScheduler implements RemoteGestures.Scheduler {
        private static final class Entry {
            final Runnable task;
            final long at;

            Entry(Runnable task, long at) {
                this.task = task;
                this.at = at;
            }
        }

        private final List<Entry> entries = new ArrayList<>();
        long now;

        @Override
        public Object schedule(Runnable task, long delayMs) {
            Entry entry = new Entry(task, now + delayMs);
            entries.add(entry);
            return entry;
        }

        @Override
        public void cancel(Object handle) {
            entries.remove(handle);
        }

        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Entry next = null;
                for (Entry entry : entries) {
                    if (entry.at <= end && (next == null || entry.at < next.at)) {
                        next = entry;
                    }
                }
                if (next == null) {
                    break;
                }
                entries.remove(next);
                now = next.at;
                next.task.run();
            }
            now = end;
        }
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<String> sent = new ArrayList<>();
    private int menus;
    private final RemoteGestures gestures = new RemoteGestures(scheduler, new RemoteGestures.Sink() {
        @Override
        public void action(String id, boolean repeat) {
            sent.add(repeat ? id + "*" : id);
        }

        @Override
        public void menu() {
            menus++;
        }
    });

    private void tap(RemoteButton button, long heldMs) {
        gestures.down(button);
        scheduler.advance(heldMs);
        gestures.up(button);
    }

    @Test
    public void selectAndPlayPauseSendOnPress() {
        gestures.down(RemoteButton.SELECT);
        assertEquals(Collections.singletonList("nav.select"), sent);
        gestures.up(RemoteButton.SELECT);
        tap(RemoteButton.PLAY_PAUSE, 3000);
        assertEquals(Arrays.asList("nav.select", "player.togglePause"), sent);
    }

    @Test
    public void dpadSendsOnceForAShortPress() {
        tap(RemoteButton.DOWN, 120);
        scheduler.advance(5000);
        assertEquals(Collections.singletonList("nav.down"), sent);
    }

    @Test
    public void dpadRepeatsWhileHeldAndStopsOnRelease() {
        gestures.down(RemoteButton.RIGHT);
        scheduler.advance(399);
        assertEquals(1, sent.size());
        scheduler.advance(1);
        assertEquals(Arrays.asList("nav.right", "nav.right*"), sent);
        // 400 ms, then every 90 ms: 400, 490, 580, 670, 760, 850, 940
        scheduler.advance(540);
        assertEquals(8, sent.size());
        gestures.up(RemoteButton.RIGHT);
        scheduler.advance(10000);
        assertEquals("no run-on after release", 8, sent.size());
    }

    @Test
    public void systemKeyRepeatIsNotANewPress() {
        gestures.down(RemoteButton.UP);
        gestures.down(RemoteButton.UP);
        gestures.down(RemoteButton.UP);
        assertEquals(Collections.singletonList("nav.up"), sent);
    }

    @Test
    public void aSecondDirectionTakesOverTheRepeat() {
        gestures.down(RemoteButton.DOWN);
        scheduler.advance(200);
        gestures.down(RemoteButton.RIGHT);
        scheduler.advance(400);
        assertEquals(Arrays.asList("nav.down", "nav.right", "nav.right*"), sent);
        gestures.up(RemoteButton.DOWN);
        gestures.up(RemoteButton.RIGHT);
        scheduler.advance(5000);
        assertEquals(3, sent.size());
    }

    @Test
    public void menuShortAndLong() {
        tap(RemoteButton.MENU, 100);
        assertEquals(Collections.singletonList("app.toggleMenu"), sent);
        sent.clear();

        gestures.down(RemoteButton.MENU);
        scheduler.advance(500);
        assertEquals(Collections.singletonList("nav.contextMenu"), sent);
        scheduler.advance(5000);
        gestures.up(RemoteButton.MENU);
        assertEquals("a long press sends only the long action", 1, sent.size());
    }

    @Test
    public void seekShortAndLong() {
        tap(RemoteButton.REWIND, 100);
        tap(RemoteButton.FAST_FORWARD, 100);
        tap(RemoteButton.REWIND, 800);
        tap(RemoteButton.FAST_FORWARD, 800);
        assertEquals(Arrays.asList("player.seekBackward", "player.seekForward",
                "player.seekBackwardLong", "player.seekForwardLong"), sent);
    }

    @Test
    public void shortBackGoesBackAndNeverOpensTheMenu() {
        tap(RemoteButton.BACK, 150);
        tap(RemoteButton.BACK, 1999);
        scheduler.advance(10000);
        assertEquals(Arrays.asList("nav.back", "nav.back"), sent);
        assertEquals(0, menus);
    }

    @Test
    public void heldBackOpensTheMenuAndDoesNotGoBack() {
        gestures.down(RemoteButton.BACK);
        scheduler.advance(2000);
        assertEquals(1, menus);
        scheduler.advance(3000);
        gestures.up(RemoteButton.BACK);
        assertEquals(1, menus);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void cancelAllStopsEverythingSilently() {
        gestures.down(RemoteButton.LEFT);
        gestures.down(RemoteButton.BACK);
        gestures.down(RemoteButton.MENU);
        sent.clear();
        gestures.cancelAll();
        scheduler.advance(10000);
        gestures.up(RemoteButton.LEFT);
        gestures.up(RemoteButton.BACK);
        gestures.up(RemoteButton.MENU);
        assertTrue(sent.isEmpty());
        assertEquals(0, menus);
    }

    @Test
    public void releaseWithoutPressIsIgnored() {
        gestures.up(RemoteButton.BACK);
        gestures.up(RemoteButton.MENU);
        assertTrue(sent.isEmpty());
    }
}
