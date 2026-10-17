package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class RemoteButtonTest {
    @Test
    public void fireTvRemoteKeys() {
        assertEquals(RemoteButton.UP, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(RemoteButton.DOWN, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(RemoteButton.LEFT, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(RemoteButton.RIGHT, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(RemoteButton.SELECT, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(RemoteButton.BACK, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_BACK));
        assertEquals(RemoteButton.MENU, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_MENU));
        assertEquals(RemoteButton.PLAY_PAUSE, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertEquals(RemoteButton.REWIND, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertEquals(RemoteButton.FAST_FORWARD, RemoteButton.fromKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
    }

    @Test
    public void otherKeysAreLeftAlone() {
        assertNull(RemoteButton.fromKeyCode(KeyEvent.KEYCODE_A));
        assertNull(RemoteButton.fromKeyCode(KeyEvent.KEYCODE_BUTTON_A));
        assertNull(RemoteButton.fromKeyCode(KeyEvent.KEYCODE_VOLUME_UP));
        assertNull(RemoteButton.fromKeyCode(KeyEvent.KEYCODE_HOME));
    }

    @Test
    public void actionsMatchThePlan() {
        assertEquals("nav.up", RemoteButton.UP.action);
        assertEquals("nav.select", RemoteButton.SELECT.action);
        assertEquals("nav.back", RemoteButton.BACK.action);
        assertEquals("app.toggleMenu", RemoteButton.MENU.action);
        assertEquals("nav.contextMenu", RemoteButton.MENU.longAction);
        assertEquals("player.togglePause", RemoteButton.PLAY_PAUSE.action);
        assertEquals("player.seekBackward", RemoteButton.REWIND.action);
        assertEquals("player.seekBackwardLong", RemoteButton.REWIND.longAction);
        assertEquals("player.seekForward", RemoteButton.FAST_FORWARD.action);
        assertEquals("player.seekForwardLong", RemoteButton.FAST_FORWARD.longAction);
    }

    @Test
    public void onlyRemotesAreRemotes() {
        // A Fire TV remote: a non-alphabetic keyboard with a D-pad.
        assertTrue(RemoteButton.isRemoteDevice(false, false, false, false));
        // Injected keys and phone remote apps have no physical device.
        assertTrue(RemoteButton.isRemoteDevice(true, false, false, false));
        // A game controller.
        assertFalse(RemoteButton.isRemoteDevice(false, true, true, false));
        assertFalse(RemoteButton.isRemoteDevice(false, true, false, false));
        assertFalse(RemoteButton.isRemoteDevice(false, false, true, false));
        // A real keyboard: its arrows and Enter stay keyboard input.
        assertFalse(RemoteButton.isRemoteDevice(false, false, false, true));
    }

    @Test
    public void backoffIsBounded() {
        Backoff backoff = new Backoff();
        long last = 0;
        int tries = 0;
        for (long delay = backoff.nextDelayMs(); delay >= 0; delay = backoff.nextDelayMs()) {
            assertTrue(delay >= last);
            last = delay;
            tries++;
        }
        assertEquals(backoff.maxAttempts(), tries);
        assertEquals(-1, backoff.nextDelayMs());
        backoff.reset();
        assertEquals(1000, backoff.nextDelayMs());
    }
}
