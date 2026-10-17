package com.limelight.strmqt;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.StreamSettings;

import java.security.GeneralSecurityException;

/**
 * Remote mode for the streaming activity: key events from a TV remote are
 * taken out of Moonlight's input path and sent to StrmQt instead. Game
 * controllers and real keyboards are not touched.
 *
 * All methods are called on the main thread.
 */
public final class RemoteInput implements RemoteGestures.Sink {
    private static final long BUTTON_PULSE_MS = 60;
    /** How long the one-time hold-Back hint stays over the stream. */
    private static final long HINT_MS = 5000;

    private final Activity activity;
    private final ControllerHandler controllerHandler;
    private final StrmQtPrefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RemoteGestures gestures;
    private final boolean gamepad;

    private StrmQtClient client;
    private TextView indicator;
    private TextView hint;
    private Dialog menu;
    private boolean destroyed;

    /** Returns null when remote mode is off, which leaves Game exactly as upstream. */
    public static RemoteInput create(Activity activity, ControllerHandler controllerHandler) {
        StrmQtPrefs prefs = new StrmQtPrefs(activity);
        if (!prefs.remoteMode()) {
            return null;
        }
        return new RemoteInput(activity, controllerHandler, prefs);
    }

    private RemoteInput(Activity activity, ControllerHandler controllerHandler, StrmQtPrefs prefs) {
        this.activity = activity;
        this.controllerHandler = controllerHandler;
        this.prefs = prefs;
        this.gestures = new RemoteGestures(new RemoteGestures.Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object handle) {
                handler.removeCallbacks((Runnable) handle);
            }
        }, this);

        boolean wantGamepad = StrmQtPrefs.TRANSPORT_GAMEPAD.equals(prefs.transport());
        if (!wantGamepad) {
            if (prefs.remotePaired()) {
                try {
                    client = new StrmQtClient(prefs.remoteHost(), prefs.remotePort(),
                            prefs.certFingerprint(), prefs.token(), new StrmQtClient.Listener() {
                        @Override
                        public void onState(final StrmQtClient.State state) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showState(state);
                                }
                            });
                        }
                    });
                    client.start();
                } catch (GeneralSecurityException e) {
                    LimeLog.severe("StrmQt remote unavailable: " + e);
                }
            }
            if (client == null) {
                showIndicator(activity.getString(R.string.strmqt_indicator_not_paired));
            }
        }
        this.gamepad = wantGamepad;
    }

    /** @return true when the event was a remote button and has been dealt with */
    public boolean handleKey(KeyEvent event) {
        if (destroyed) {
            return false;
        }
        RemoteButton button = RemoteButton.fromKeyCode(event.getKeyCode());
        if (button == null || !isRemote(event)) {
            return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }

        if (gamepad && button != RemoteButton.BACK) {
            // The system's repeats of a held key are not new presses.
            if (!down || event.getRepeatCount() == 0) {
                sendGamepadKey(event, gamepadKeyCode(button, event.getKeyCode()), down);
            }
            return true;
        }

        if (down) {
            gestures.down(button);
        } else {
            gestures.up(button);
        }
        return true;
    }

    private static boolean isRemote(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            return RemoteButton.isRemoteDevice(true, false, false, false);
        }
        int sources = device.getSources();
        boolean gamepadButtons = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean joystickAxes = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean alphabetic = device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
        return RemoteButton.isRemoteDevice(false, gamepadButtons, joystickAxes, alphabetic);
    }

    // RemoteGestures.Sink

    @Override
    public void action(String id, boolean repeat) {
        if (gamepad) {
            // Only Back comes through the gestures in gamepad mode: B, pressed and let go.
            pulseGamepadKey(KeyEvent.KEYCODE_BUTTON_B);
        } else if (client != null) {
            client.send(id, repeat);
        }
    }

    @Override
    public void menu() {
        showMenu();
    }

    // Transport B: the remote as a gamepad, through Moonlight's controller path.

    private static int gamepadKeyCode(RemoteButton button, int original) {
        switch (button) {
            case PLAY_PAUSE:
                return KeyEvent.KEYCODE_BUTTON_X;
            case REWIND:
                return KeyEvent.KEYCODE_BUTTON_L1;
            case FAST_FORWARD:
                return KeyEvent.KEYCODE_BUTTON_R1;
            case SELECT:
                return KeyEvent.KEYCODE_BUTTON_A;
            case MENU:
                return KeyEvent.KEYCODE_BUTTON_START;
            default:
                // The D-pad is already what the controller path expects.
                return original;
        }
    }

    private void sendGamepadKey(KeyEvent source, int keyCode, boolean down) {
        KeyEvent event = new KeyEvent(source.getDownTime(), source.getEventTime(),
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, 0,
                source.getDeviceId(), 0, 0, source.getSource());
        if (down) {
            controllerHandler.handleButtonDown(event);
        } else {
            controllerHandler.handleButtonUp(event);
        }
    }

    private void pulseGamepadKey(final int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        final KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        controllerHandler.handleButtonDown(down);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    controllerHandler.handleButtonUp(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
                }
            }
        }, BUTTON_PULSE_MS);
    }

    // Lifecycle, called by Game

    public void onStreamStarted() {
        if (prefs.takeHoldBackHint()) {
            showHint(activity.getString(R.string.strmqt_hold_back_hint));
        }
    }

    public void onFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            // A held button must not keep repeating into a window that cannot see its release.
            gestures.cancelAll();
        }
    }

    public void destroy() {
        destroyed = true;
        gestures.cancelAll();
        handler.removeCallbacksAndMessages(null);
        if (menu != null) {
            menu.dismiss();
            menu = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    // The fork's menu

    private void showMenu() {
        if (destroyed || activity.isFinishing() || (menu != null && menu.isShowing())) {
            return;
        }
        gestures.cancelAll();
        final Dialog dialog = new Dialog(activity, R.style.StrmQtDialog);
        dialog.setContentView(R.layout.strmqt_menu_dialog);
        dialog.setTitle(R.string.strmqt_menu_title);
        ((TextView) dialog.findViewById(R.id.strmqtWordmark)).setText(Brand.wordmark(activity));
        Brand.applyFonts(dialog.findViewById(android.R.id.content));
        Brand.unclip(dialog.findViewById(android.R.id.content));
        dialog.findViewById(R.id.strmqtMenuResume).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.strmqtMenuDisconnect).setOnClickListener(v -> {
            dialog.dismiss();
            StrmQtSession.userDisconnected();
            activity.finish();
        });
        dialog.findViewById(R.id.strmqtMenuSettings).setOnClickListener(v -> {
            dialog.dismiss();
            // Leaving the stream ends it, as it does in upstream; coming
            // back from the settings reconnects.
            activity.startActivity(new Intent(activity, StreamSettings.class));
        });
        menu = dialog;
        menu.show();
        dialog.findViewById(R.id.strmqtMenuResume).requestFocus();
    }

    // The control channel indicator

    private void showState(StrmQtClient.State state) {
        if (destroyed) {
            return;
        }
        switch (state) {
            case UP:
            case CONNECTING:
                hideIndicator();
                break;
            case DOWN:
                showIndicator(activity.getString(R.string.strmqt_indicator_down));
                break;
            case UNPAIRED:
                // Removed in StrmQt: the token is no good any more, and the
                // remote is no use until this device is paired again.
                prefs.forgetToken();
                showIndicator(activity.getString(R.string.strmqt_indicator_unpaired));
                if (!activity.isFinishing()) {
                    // Leaving the stream ends it, as Settings does; once paired,
                    // the launcher reconnects.
                    activity.startActivity(new Intent(activity, SetupActivity.class)
                            .putExtra(SetupActivity.EXTRA_PAIR_NOW, true));
                }
                break;
            case CERT_CHANGED:
                showIndicator(activity.getString(R.string.strmqt_indicator_cert_changed));
                break;
        }
    }

    private void showIndicator(String text) {
        if (indicator == null) {
            indicator = label(Gravity.BOTTOM | Gravity.END);
        }
        indicator.setText(text);
        indicator.setVisibility(View.VISIBLE);
    }

    /** A label over the stream in StrmQt's look; it takes no focus and blocks nothing. */
    private TextView label(int gravity) {
        TextView view = new TextView(activity);
        view.setTextColor(activity.getResources().getColor(R.color.strmqt_text));
        view.setTextSize(16);
        view.setTypeface(Brand.typeface(activity, null));
        view.setBackgroundResource(R.drawable.strmqt_chip);
        view.setFocusable(false);
        int pad = (int) (8 * activity.getResources().getDisplayMetrics().density);
        view.setPadding(pad * 2, pad + pad / 2, pad * 2, pad + pad / 2);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        params.setMargins(pad * 6, pad * 4, pad * 6, pad * 4);
        activity.addContentView(view, params);
        return view;
    }

    /** The one-time hint, bottom centre, gone after a few seconds. */
    private void showHint(String text) {
        if (hint == null) {
            hint = label(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && hint != null) {
                    hint.animate().alpha(0f).setDuration(420).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (hint != null) {
                                hint.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            }
        }, HINT_MS);
    }

    private void hideIndicator() {
        if (indicator != null) {
            indicator.setVisibility(View.GONE);
        }
    }
}
