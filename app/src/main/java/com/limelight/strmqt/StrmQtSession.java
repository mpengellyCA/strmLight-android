package com.limelight.strmqt;

import android.app.Activity;
import android.os.SystemClock;

/**
 * What the streaming activity tells the launcher about a stream the launcher
 * started, so the launcher can decide between reconnecting and its menu.
 *
 * A stream started any other way (from Moonlight's own host list) is not
 * supervised, and behaves exactly as upstream.
 */
public final class StrmQtSession {
    /** Set on the start intent by LaunchActivity. */
    public static final String EXTRA_SUPERVISED = "StrmQtSupervised";

    public enum End {
        /** The activity went away with no word: the user left the app, or the device slept. */
        STOPPED,
        /** Disconnect was chosen in the fork's menu. */
        USER_DISCONNECT,
        /** The connection could not be made, or dropped. */
        FAILED,
        /** The host ended the session. */
        HOST_ENDED
    }

    private static End end = End.STOPPED;
    private static String message;
    private static long startedAt;
    private static long upMs;

    private StrmQtSession() {}

    public static boolean isSupervised(Activity game) {
        return game.getIntent().getBooleanExtra(EXTRA_SUPERVISED, false);
    }

    /** The launcher is about to start a stream. */
    static synchronized void launching() {
        end = End.STOPPED;
        message = null;
        startedAt = 0;
        upMs = 0;
    }

    /** Called by Game when the stream is up. */
    public static synchronized void streamStarted() {
        startedAt = SystemClock.elapsedRealtime();
    }

    /**
     * Called by Game when the stream failed to start or was terminated.
     *
     * @return true when the launcher takes it from here: Game should finish
     * without showing its own error dialog
     */
    public static synchronized boolean streamEnded(Activity game, boolean byHost, String why) {
        if (!isSupervised(game)) {
            return false;
        }
        if (end != End.USER_DISCONNECT) {
            end = byHost ? End.HOST_ENDED : End.FAILED;
            message = why;
        }
        return true;
    }

    static synchronized void userDisconnected() {
        end = End.USER_DISCONNECT;
    }

    /** Called by the launcher when it is back in front. */
    static synchronized End takeEnd() {
        if (startedAt != 0) {
            upMs = SystemClock.elapsedRealtime() - startedAt;
            startedAt = 0;
        }
        End result = end;
        end = End.STOPPED;
        return result;
    }

    static synchronized String message() {
        return message;
    }

    /** How long the last stream was up; 0 when it never started. */
    static synchronized long lastStreamUpMs() {
        return upMs;
    }
}
