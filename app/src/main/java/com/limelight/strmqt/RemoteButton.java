package com.limelight.strmqt;

import android.view.KeyEvent;

/**
 * The buttons of a TV remote that StrmQt understands, with the StrmQt action
 * each one sends (see moonlight-android-fork-plan.md, section 4).
 */
public enum RemoteButton {
    UP(Kind.REPEAT, "nav.up", null),
    DOWN(Kind.REPEAT, "nav.down", null),
    LEFT(Kind.REPEAT, "nav.left", null),
    RIGHT(Kind.REPEAT, "nav.right", null),
    SELECT(Kind.TAP, "nav.select", null),
    BACK(Kind.BACK, "nav.back", null),
    MENU(Kind.LONG, "app.toggleMenu", "nav.contextMenu"),
    PLAY_PAUSE(Kind.TAP, "player.togglePause", null),
    REWIND(Kind.LONG, "player.seekBackward", "player.seekBackwardLong"),
    FAST_FORWARD(Kind.LONG, "player.seekForward", "player.seekForwardLong");

    public enum Kind {
        /** Sent on press, then again on a timer while held. */
        REPEAT,
        /** Sent once on press. */
        TAP,
        /** Sent on release, unless held long enough to send the long action instead. */
        LONG,
        /** Sent on release, unless held long enough to open the fork's own menu. */
        BACK
    }

    public final Kind kind;
    public final String action;
    public final String longAction;

    RemoteButton(Kind kind, String action, String longAction) {
        this.kind = kind;
        this.action = action;
        this.longAction = longAction;
    }

    /** The button for an Android key code, or null when the remote mode leaves the key alone. */
    public static RemoteButton fromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return SELECT;
            case KeyEvent.KEYCODE_BACK:
                return BACK;
            case KeyEvent.KEYCODE_MENU:
                return MENU;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return PLAY_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return REWIND;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return FAST_FORWARD;
            default:
                return null;
        }
    }

    /**
     * Whether a key event's device is a remote, as opposed to a game controller
     * or a real keyboard, which keep working as in upstream Moonlight.
     *
     * @param virtual the event has no physical device (injected, or a phone remote app)
     */
    public static boolean isRemoteDevice(boolean virtual, boolean gamepadButtons,
                                         boolean joystickAxes, boolean alphabeticKeyboard) {
        if (virtual) {
            return true;
        }
        return !gamepadButtons && !joystickAxes && !alphabeticKeyboard;
    }
}
