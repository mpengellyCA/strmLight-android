package com.limelight.strmqt;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.utils.UiHelper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Setting up StrmQt: find the headless StrmQt host on the network (or type
 * its address), then pair both the stream and the remote with one code this
 * screen shows, which someone types into StrmQt or into StrmQt's phone
 * remote. StrmQt arms its Web Remote with the code and enters it into its
 * Sunshine, so one entry pairs both. Everything here can be done with a D-pad.
 *
 * Nothing is sent while this screen is not in front, except a Moonlight
 * pairing request already waiting on the host, which is one request, not
 * polling.
 */
public class SetupActivity extends Activity {
    /** Start pairing as soon as the screen opens, at the stored address. */
    public static final String EXTRA_PAIR_NOW = "StrmQtPairNow";

    /**
     * A code stays on offer across visits to this screen for this long, so it
     * can be read here, typed into StrmQt elsewhere, and picked up again here.
     */
    private static final long CODE_KEPT_MS = 10 * 60 * 1000;
    /** How long the outcome stays on screen before the stream starts. */
    private static final long START_AFTER_PAIRING_MS = 2500;

    private static final SecureRandom random = new SecureRandom();
    private static String offeredCode;
    private static long offeredAt;

    /** What pairing was started with. */
    private static final class Choice {
        final String host;
        final String name;
        final int remotePort;
        final int sunshinePort;

        Choice(String host, String name, int remotePort, int sunshinePort) {
            this.host = host;
            this.name = name;
            this.remotePort = remotePort;
            this.sunshinePort = sunshinePort;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<StrmQtDiscovery.Host> found = new ArrayList<>();

    private StrmQtPrefs prefs;
    private RadioGroup hostGroup;
    private TextView discoveryView;
    private Button searchButton;
    private EditText addressView;
    private EditText portView;
    private EditText sunshinePortView;
    private View pairHint;
    private View codePanel;
    private TextView codeView;
    private TextView statusView;
    private TextView streamView;
    private TextView remoteView;
    private Button pairButton;
    private Button newCodeButton;

    private boolean pairNow;
    private boolean resumed;
    private boolean destroyed;
    // Set while a list of found hosts is being filled in, so that does not count as a choice.
    private boolean filling;
    // Bumped per search, so a late answer from an older one is dropped.
    private int searchId;
    private boolean searching;
    // The first search has answered. Pairing by itself waits for it, so a
    // port it corrects (8337 to 8338) is the one paired with.
    private boolean searched;
    // Nothing was configured when the screen opened: one usable host found starts pairing by itself.
    private boolean firstSetup;

    private ComputerManagerService.ComputerManagerBinder binder;
    private boolean bound;
    // Pair was chosen before Moonlight's computer service was ready.
    private boolean pairWhenBound;

    private Choice choice;
    private OneCodePairing pairing;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (ComputerManagerService.ComputerManagerBinder) service;
            if (pairWhenBound) {
                pairWhenBound = false;
                pair();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_strmqt_setup);
        UiHelper.notifyNewRootView(this);

        prefs = new StrmQtPrefs(this);
        hostGroup = findViewById(R.id.strmqtHostGroup);
        discoveryView = findViewById(R.id.strmqtDiscoveryStatus);
        searchButton = findViewById(R.id.strmqtSearch);
        addressView = findViewById(R.id.strmqtAddress);
        portView = findViewById(R.id.strmqtPort);
        sunshinePortView = findViewById(R.id.strmqtSunshinePort);
        pairHint = findViewById(R.id.strmqtPairHint);
        codePanel = findViewById(R.id.strmqtCodePanel);
        codeView = findViewById(R.id.strmqtCode);
        statusView = findViewById(R.id.strmqtSetupStatus);
        streamView = findViewById(R.id.strmqtStreamStatus);
        remoteView = findViewById(R.id.strmqtRemoteStatus);
        pairButton = findViewById(R.id.strmqtPair);
        newCodeButton = findViewById(R.id.strmqtNewCode);

        firstSetup = prefs.remoteHost() == null;
        if (prefs.remoteHost() != null) {
            addressView.setText(prefs.remoteHost());
        }
        portView.setText(String.valueOf(prefs.remotePort()));
        sunshinePortView.setText(String.valueOf(prefs.sunshinePort()));

        hostGroup.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            if (checked != null && checked.getTag() instanceof StrmQtDiscovery.Host) {
                fillFrom((StrmQtDiscovery.Host) checked.getTag());
            }
        });
        searchButton.setOnClickListener(v -> search());
        pairButton.setOnClickListener(v -> {
            if (pairing != null || pairWhenBound) {
                stopPairing(getString(R.string.strmqt_setup_stopped));
            } else {
                pair();
            }
        });
        newCodeButton.setOnClickListener(v -> newCode());

        pairNow = savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_PAIR_NOW, false)
                && prefs.remoteHost() != null;

        bound = bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
        search();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pairNow && searched && pairing == null) {
            pairNow = false;
            pair();
        } else if (pairing != null) {
            pairing.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Not a single new request while the screen is not in front.
        if (pairing != null) {
            pairing.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (pairing != null) {
            pairing.stop();
            pairing = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // Finding hosts

    private void search() {
        if (searching) {
            return;
        }
        searching = true;
        final int id = ++searchId;
        discoveryView.setText(R.string.strmqt_setup_searching);
        searchButton.setEnabled(false);
        new Thread("StrmQt discovery") {
            @Override
            public void run() {
                List<StrmQtDiscovery.Host> hosts = null;
                String error = null;
                try {
                    hosts = StrmQtDiscovery.scan(SetupActivity.this);
                } catch (Exception e) {
                    LimeLog.warning("StrmQt discovery: " + e);
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final List<StrmQtDiscovery.Host> result = hosts;
                final String failure = error;
                handler.post(() -> {
                    if (destroyed || id != searchId) {
                        return;
                    }
                    searching = false;
                    searched = true;
                    searchButton.setEnabled(true);
                    if (result == null) {
                        discoveryView.setText(getString(R.string.strmqt_setup_search_failed, failure));
                    } else {
                        showHosts(result);
                    }
                    if (pairNow && resumed && pairing == null && !pairWhenBound) {
                        pairNow = false;
                        pair();
                    }
                });
            }
        }.start();
    }

    private void showHosts(List<StrmQtDiscovery.Host> hosts) {
        found.clear();
        found.addAll(hosts);
        View focused = getCurrentFocus();
        boolean listHadFocus = focused != null && focused.getParent() == hostGroup;

        filling = true;
        hostGroup.removeAllViews();
        RadioButton toCheck = null;
        int usable = 0;
        String stored = prefs.remoteHost();
        for (StrmQtDiscovery.Host host : hosts) {
            // A device set up before discovery stored the desktop remote's port.
            if (prefs.migrateRemotePort(host.address, host.remotePort)) {
                LimeLog.info("StrmQt: Web Remote port for " + host.address + " is now " + host.remotePort);
                if (host.address.equals(addressView.getText().toString().trim())) {
                    portView.setText(String.valueOf(host.remotePort));
                }
            }
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(getString(host.canStream() ? R.string.strmqt_setup_host_entry
                    : R.string.strmqt_setup_host_entry_no_stream, host.name, host.address));
            button.setTag(host);
            button.setEnabled(host.canStream());
            hostGroup.addView(button, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (host.canStream()) {
                usable++;
                if (host.address.equals(stored) || toCheck == null && stored == null) {
                    toCheck = button;
                }
            }
        }
        if (toCheck != null && pairing == null) {
            toCheck.setChecked(true);
            fillFrom((StrmQtDiscovery.Host) toCheck.getTag());
        }
        filling = false;

        discoveryView.setText(hosts.isEmpty() ? R.string.strmqt_setup_found_none : R.string.strmqt_setup_found);
        if (listHadFocus) {
            (toCheck != null ? toCheck : searchButton).requestFocus();
        }

        // First setup, and exactly one host to pair with: show the code at once.
        if (firstSetup && !pairNow && usable == 1 && toCheck != null && pairing == null && !pairWhenBound
                && resumed) {
            firstSetup = false;
            pair();
        }
    }

    private void fillFrom(StrmQtDiscovery.Host host) {
        if (pairing != null && !filling) {
            // Choosing another host while pairing starts over there.
            stopPairing(null);
        }
        addressView.setText(host.address);
        portView.setText(String.valueOf(host.remotePort));
        sunshinePortView.setText(String.valueOf(host.sunshinePort));
    }

    /** The found host at this address and these ports, if any: its name and announced certificate. */
    private StrmQtDiscovery.Host foundAt(String address, int remotePort) {
        for (StrmQtDiscovery.Host host : found) {
            if (host.address.equals(address) && host.remotePort == remotePort) {
                return host;
            }
        }
        return null;
    }

    // Pairing

    /** The code on offer: the one shown before, while it is fresh, or a new one. */
    private static String currentCode() {
        if (offeredCode == null || SystemClock.elapsedRealtime() - offeredAt > CODE_KEPT_MS) {
            makeCode();
        }
        return offeredCode;
    }

    private static void makeCode() {
        offeredCode = StrmQtClient.newPairingCode(random);
        offeredAt = SystemClock.elapsedRealtime();
    }

    /** How StrmQt lists this device: its name in the system settings, or its model. */
    private String deviceName() {
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        }
        if (name == null || name.trim().isEmpty()) {
            name = Build.MANUFACTURER + " " + Build.MODEL;
        }
        return name;
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void pair() {
        final String host = addressView.getText().toString().trim();
        final int remotePort = parsePort(portView.getText().toString());
        final int sunshinePort = parsePort(sunshinePortView.getText().toString());
        if (host.isEmpty()) {
            statusView.setText(R.string.strmqt_setup_no_address);
            return;
        }
        if (remotePort < 0 || sunshinePort < 0) {
            statusView.setText(R.string.strmqt_setup_bad_port);
            return;
        }
        pairButton.setText(R.string.strmqt_setup_stop);
        if (binder == null) {
            // Moonlight's computer service is still starting; onServiceConnected carries on.
            pairWhenBound = true;
            statusView.setText(getString(R.string.strmqt_setup_pairing, host));
            return;
        }

        StrmQtDiscovery.Host announced = foundAt(host, remotePort);
        String name = announced != null ? announced.name
                : host.equals(prefs.remoteHost()) ? prefs.hostName() : null;
        choice = new Choice(host, name, remotePort, sunshinePort);
        OneCodePairing.Target target = new OneCodePairing.Target(host, remotePort, sunshinePort,
                announced != null ? announced.fingerprint : null,
                prefs.token(), prefs.certFingerprint());

        final Choice c = choice;
        pairing = new OneCodePairing(target, currentCode(), deviceName(),
                new MoonlightPairer(this, binder), new OneCodePairing.Listener() {
            @Override
            public void onStream(final OneCodePairing.StreamState state, String detail) {
                runIfCurrent(c, () -> showStream(state, c));
            }

            @Override
            public void onRemote(final OneCodePairing.RemoteState state) {
                runIfCurrent(c, () -> showRemote(state, c));
            }

            @Override
            public void onDone(final OneCodePairing.Result result) {
                runIfCurrent(c, () -> paired(c, result));
            }

            @Override
            public void onStopped(final OneCodePairing.RemoteState why) {
                runIfCurrent(c, () -> stopPairing(stoppedMessage(why, c)));
            }
        });
        statusView.setText(getString(R.string.strmqt_setup_pairing, host));
        showCode(true);
        pairing.start();
        if (!resumed) {
            pairing.pause();
        }
    }

    private void runIfCurrent(final Choice c, final Runnable action) {
        handler.post(() -> {
            if (!destroyed && choice == c && pairing != null) {
                action.run();
            }
        });
    }

    private void showStream(OneCodePairing.StreamState state, Choice c) {
        switch (state) {
            case STARTING:
                streamView.setText(getString(R.string.strmqt_stream_starting, c.host, c.sunshinePort));
                break;
            case WAITING:
                streamView.setText(R.string.strmqt_stream_waiting);
                break;
            case SESSION_PENDING:
                streamView.setText(R.string.strmqt_stream_session_pending);
                break;
            case WRONG_PIN:
                streamView.setText(R.string.strmqt_stream_wrong_pin);
                break;
            case REFUSED:
                streamView.setText(R.string.strmqt_stream_refused);
                break;
            case UNREACHABLE:
                streamView.setText(getString(R.string.strmqt_stream_unreachable, c.host, c.sunshinePort));
                break;
            case PAIRED:
                streamView.setText(R.string.strmqt_stream_paired);
                break;
        }
    }

    private void showRemote(OneCodePairing.RemoteState state, Choice c) {
        switch (state) {
            case STARTING:
                remoteView.setText(getString(R.string.strmqt_remote_starting, c.host, c.remotePort));
                break;
            case WAITING:
                remoteView.setText(R.string.strmqt_remote_waiting);
                break;
            case WRONG_CODE:
                remoteView.setText(R.string.strmqt_remote_wrong_code);
                break;
            case LOCKED_OUT:
                remoteView.setText(getString(R.string.strmqt_remote_locked_out,
                        StrmQtClient.PAIR_LOCKOUT_MS / 1000));
                break;
            case UNREACHABLE:
                remoteView.setText(getString(R.string.strmqt_remote_unreachable, c.host, c.remotePort));
                break;
            case PAIRED:
                remoteView.setText(R.string.strmqt_remote_paired);
                break;
            default:
                // The ones that stop pairing arrive through onStopped.
                break;
        }
    }

    private String stoppedMessage(OneCodePairing.RemoteState why, Choice c) {
        switch (why) {
            case CERT_CHANGED:
                return getString(R.string.strmqt_setup_cert_changed);
            case CERT_NOT_ANNOUNCED:
                return getString(R.string.strmqt_setup_cert_not_announced, c.host, c.remotePort);
            case UNSUPPORTED:
            default:
                return getString(R.string.strmqt_setup_unsupported);
        }
    }

    private void showCode(boolean show) {
        if (show) {
            codeView.setText(currentCode());
            streamView.setText("");
            remoteView.setText("");
        }
        codePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        pairHint.setVisibility(show ? View.GONE : View.VISIBLE);
        newCodeButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** A fresh code, for when the shown one was mistyped or seen by someone it should not have been. */
    private void newCode() {
        makeCode();
        codeView.setText(offeredCode);
        if (pairing != null) {
            pairing.setCode(offeredCode);
        }
    }

    private void paired(Choice c, OneCodePairing.Result result) {
        prefs.setHost(c.host, c.name, c.remotePort, c.sunshinePort);
        prefs.setRemotePairing(c.host, c.remotePort, result.token, result.fingerprint);
        prefs.setRemoteMode(true);
        prefs.setComputer(result.computerUuid, c.name != null ? c.name : result.computerName);
        // One code, one device: the next pairing shows a new one.
        offeredCode = null;

        String message = getString(result.token != null ? R.string.strmqt_setup_paired
                : R.string.strmqt_setup_paired_open, CertPin.display(result.fingerprint));
        if (!prefs.presetApplied()) {
            message += "\n" + getString(StreamPreset.apply(this)
                    ? R.string.strmqt_preset_applied_4k : R.string.strmqt_preset_applied_1080p);
        }
        stopPairing(message);
        handler.postDelayed(() -> {
            if (destroyed) {
                return;
            }
            startActivity(new Intent(this, LaunchActivity.class)
                    .putExtra(LaunchActivity.EXTRA_CONNECT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }, START_AFTER_PAIRING_MS);
    }

    private void stopPairing(String message) {
        pairWhenBound = false;
        if (pairing != null) {
            pairing.stop();
            pairing = null;
        }
        choice = null;
        boolean hadFocus = newCodeButton.hasFocus();
        showCode(false);
        pairButton.setText(R.string.strmqt_setup_pair);
        if (hadFocus) {
            pairButton.requestFocus();
        }
        if (message != null) {
            statusView.setText(message);
        }
    }
}
