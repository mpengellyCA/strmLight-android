package com.limelight.strmqt;

import java.util.EnumMap;

/**
 * Turns button presses and releases into StrmQt actions: key repeat for the
 * D-pad, long presses, and the hold-Back gesture that opens the fork's menu.
 *
 * Not thread safe: call everything, and run the scheduler, on one thread.
 */
public final class RemoteGestures {
    public static final long REPEAT_DELAY_MS = 400;
    public static final long REPEAT_INTERVAL_MS = 90;
    public static final long LONG_PRESS_MS = 500;
    public static final long MENU_HOLD_MS = 2000;

    public interface Scheduler {
        /** Runs `task` once after `delayMs`; returns a handle for cancel(). */
        Object schedule(Runnable task, long delayMs);

        void cancel(Object handle);
    }

    public interface Sink {
        /** @param repeat true for a repeat of a held button, which may be skipped when behind. */
        void action(String id, boolean repeat);

        /** Back was held long enough: open the fork's menu. */
        void menu();
    }

    private static final class Press {
        Object timer;
        boolean consumed;
    }

    private final Scheduler scheduler;
    private final Sink sink;
    private final EnumMap<RemoteButton, Press> held = new EnumMap<>(RemoteButton.class);

    public RemoteGestures(Scheduler scheduler, Sink sink) {
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public void down(final RemoteButton button) {
        if (held.containsKey(button)) {
            // The system's own key repeat; ours runs on a timer.
            return;
        }
        final Press press = new Press();
        held.put(button, press);

        switch (button.kind) {
            case TAP:
                sink.action(button.action, false);
                break;
            case REPEAT:
                // One direction repeats at a time, like a keyboard.
                for (RemoteButton other : RemoteButton.values()) {
                    if (other != button && other.kind == RemoteButton.Kind.REPEAT) {
                        cancelTimer(held.get(other));
                    }
                }
                sink.action(button.action, false);
                press.timer = scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        sink.action(button.action, true);
                        press.timer = scheduler.schedule(this, REPEAT_INTERVAL_MS);
                    }
                }, REPEAT_DELAY_MS);
                break;
            case LONG:
                press.timer = scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        press.timer = null;
                        press.consumed = true;
                        sink.action(button.longAction, false);
                    }
                }, LONG_PRESS_MS);
                break;
            case BACK:
                press.timer = scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        press.timer = null;
                        press.consumed = true;
                        sink.menu();
                    }
                }, MENU_HOLD_MS);
                break;
        }
    }

    public void up(RemoteButton button) {
        Press press = held.remove(button);
        if (press == null) {
            // Pressed before we were listening, or already cancelled.
            return;
        }
        cancelTimer(press);
        if (!press.consumed
                && (button.kind == RemoteButton.Kind.LONG || button.kind == RemoteButton.Kind.BACK)) {
            sink.action(button.action, false);
        }
    }

    /** Forgets every held button without sending anything (focus loss, menu opened). */
    public void cancelAll() {
        for (Press press : held.values()) {
            cancelTimer(press);
        }
        held.clear();
    }

    public boolean isHeld(RemoteButton button) {
        return held.containsKey(button);
    }

    private void cancelTimer(Press press) {
        if (press != null && press.timer != null) {
            scheduler.cancel(press.timer);
            press.timer = null;
        }
    }
}
