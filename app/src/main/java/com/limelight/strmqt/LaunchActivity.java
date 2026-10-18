package com.limelight.strmqt;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The app's front door, and the fork's own menu.
 *
 * With a host configured and "connect on launch" on, opening the app goes
 * straight to the stream, and a stream that ends unexpectedly is started
 * again with a growing wait, up to a bounded number of tries. With nothing
 * set up it opens the simple setup instead. Otherwise this is a short menu:
 * connect, set up, Moonlight's host list, settings.
 *
 * The connection itself is Moonlight's: this finds the host through
 * ComputerManagerService, as ShortcutTrampoline does, and hands over to Game.
 * It hands over only a Sunshine StrmQt vouches for; see {@link SunshineVouch}.
 * StrmQt is asked before every stream, and its answer also carries the
 * stream's picture and sound, written into Moonlight's settings first; see
 * {@link StreamPreset#prefsFor(Context, HostStream)}.
 */
public class LaunchActivity extends Activity {
    /** Connect now, whatever this was doing: pairing has just finished. */
    public static final String EXTRA_CONNECT = "StrmQtConnect";

    private enum Mode { START, MENU, CONNECTING, WAITING, STREAMING }

    private static final int WAKE_TRIES = 10;
    /** How long a host may stay unanswered before the attempt counts as failed. */
    private static final long ATTEMPT_TIMEOUT_MS = 20000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Backoff backoff = new Backoff();

    private StrmQtPrefs prefs;
    private Mode mode = Mode.START;
    // The user wants to be in the stream: set by connecting, cleared by disconnecting.
    private boolean wantStream;
    private boolean resumed;
    // Set by EXTRA_CONNECT; onResume connects.
    private boolean connectRequested;

    private TextView statusView;
    private Button connectButton;
    private Button cancelButton;
    private View menuButtons;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean bound;
    // Bumped whenever an attempt is abandoned, so its late callbacks are ignored.
    // Volatile so a worker can stop early; callbacks still check it on the main thread.
    private volatile int attemptId;
    private int wakeTries;
    // This attempt has searched for StrmQt at another port or address; it does not again.
    private boolean followTried;
    // That search is running: offline reports wait for its answer.
    private boolean following;

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_strmqt_launch);
        UiHelper.notifyNewRootView(this);
        ((TextView) findViewById(R.id.strmqtWordmark)).setText(Brand.wordmark(this));
        Brand.applyFonts(getWindow().getDecorView());
        Brand.unclip(findViewById(android.R.id.content));

        prefs = new StrmQtPrefs(this);
        statusView = findViewById(R.id.strmqtStatus);
        menuButtons = findViewById(R.id.strmqtMenuButtons);
        connectButton = findViewById(R.id.strmqtConnect);
        cancelButton = findViewById(R.id.strmqtCancel);

        connectButton.setOnClickListener(v -> {
            backoff.reset();
            connect();
        });
        cancelButton.setOnClickListener(v -> showMenu(null));
        findViewById(R.id.strmqtSetup).setOnClickListener(
                v -> startActivity(new Intent(this, SetupActivity.class)));
        findViewById(R.id.strmqtSettings).setOnClickListener(
                v -> startActivity(new Intent(this, StreamSettings.class)));
        connectRequested = getIntent().getBooleanExtra(EXTRA_CONNECT, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            connectRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        WakeService.sync(this);

        if (connectRequested) {
            connectRequested = false;
            // Whatever ended before pairing is not a reason to wait.
            StrmQtSession.takeEnd();
            backoff.reset();
            connect();
            return;
        }

        switch (mode) {
            case START:
                switch (FirstRun.onLaunch(prefs.remoteNeedsPairing(), prefs.computerUuid() != null,
                        prefs.connectOnLaunch())) {
                    case PAIR_AGAIN:
                        // The remote would be dead in the stream. Back from pairing
                        // lands in the menu, not here again.
                        showMenu(null);
                        startActivity(new Intent(this, SetupActivity.class)
                                .putExtra(SetupActivity.EXTRA_PAIR_NOW, true));
                        break;
                    case FIRST_RUN:
                        // Nothing set up: the simple setup, not the menu. Back from it
                        // leaves the app; pairing starts the stream through a new launcher.
                        startActivity(new Intent(this, SetupActivity.class));
                        overridePendingTransition(0, 0);
                        finish();
                        break;
                    case CONNECT:
                        connect();
                        break;
                    case MENU:
                        showMenu(null);
                        break;
                }
                break;
            case STREAMING:
                streamEnded();
                break;
            case CONNECTING:
            case WAITING:
                // Interrupted by something in front of us; pick it up again.
                connect();
                break;
            case MENU:
                showMenu(null);
                break;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Nothing is started from behind another activity.
        abandonAttempt();
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.CONNECTING || mode == Mode.WAITING) {
            showMenu(null);
        } else {
            super.onBackPressed();
        }
    }

    private void streamEnded() {
        StrmQtSession.End end = StrmQtSession.takeEnd();
        if (StrmQtSession.lastStreamUpMs() >= Backoff.STABLE_STREAM_MS) {
            backoff.reset();
        }
        switch (end) {
            case USER_DISCONNECT:
                showMenu(null);
                break;
            case FAILED:
            case HOST_ENDED:
                attemptFailed(StrmQtSession.message());
                break;
            case STOPPED:
            default:
                // The user left the app, or the device slept, and now we are back.
                backoff.reset();
                if (wantStream) {
                    connect();
                } else {
                    showMenu(null);
                }
                break;
        }
    }

    private void showMenu(String message) {
        abandonAttempt();
        mode = Mode.MENU;
        wantStream = false;

        String host = prefs.computerName();
        connectButton.setEnabled(prefs.computerUuid() != null);
        connectButton.setText(host != null
                ? getString(R.string.strmqt_menu_connect_to, host)
                : getString(R.string.strmqt_menu_connect));
        if (message != null) {
            statusView.setText(message);
        } else if (prefs.computerUuid() == null) {
            statusView.setText(R.string.strmqt_status_not_set_up);
        } else if (prefs.remoteNeedsPairing()) {
            statusView.setText(R.string.strmqt_status_pair_again);
        } else {
            statusView.setText("");
        }
        menuButtons.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.GONE);
        (connectButton.isEnabled() ? connectButton : findViewById(R.id.strmqtSetup)).requestFocus();
    }

    private void showProgress(String message) {
        statusView.setText(message);
        menuButtons.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.requestFocus();
    }

    private void connect() {
        connect(false);
    }

    /** @param followed StrmQt was just followed to where it is now: no second search */
    private void connect(boolean followed) {
        abandonAttempt();
        followTried = followed;
        if (!resumed) {
            // onResume carries on from here.
            mode = Mode.CONNECTING;
            return;
        }
        if (prefs.computerUuid() == null) {
            showMenu(null);
            return;
        }
        mode = Mode.CONNECTING;
        wantStream = true;
        wakeTries = WAKE_TRIES;
        showProgress(getString(R.string.strmqt_status_connecting, hostLabel()));

        final int attempt = attemptId;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (attempt == attemptId && mode == Mode.CONNECTING) {
                    attemptFailed(getString(R.string.strmqt_status_host_offline, hostLabel()));
                }
            }
        }, ATTEMPT_TIMEOUT_MS);

        bound = bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private String hostLabel() {
        String name = prefs.computerName();
        return name != null ? name : getString(R.string.strmqt_the_host);
    }

    /** Stops whatever attempt is running; anything it still reports is ignored. */
    private void abandonAttempt() {
        attemptId++;
        following = false;
        handler.removeCallbacksAndMessages(null);
        if (managerBinder != null) {
            managerBinder.stopPolling();
            managerBinder = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    private void attemptFailed(String why) {
        abandonAttempt();
        long delay = backoff.nextDelayMs();
        if (delay < 0) {
            showMenu(getString(R.string.strmqt_status_gave_up,
                    why != null ? why : getString(R.string.strmqt_status_stream_ended)));
            backoff.reset();
            return;
        }
        mode = Mode.WAITING;
        wantStream = true;
        showProgress(getString(R.string.strmqt_status_retrying,
                why != null ? why : getString(R.string.strmqt_status_stream_ended),
                Math.max(1, delay / 1000), backoff.attempt(), backoff.maxAttempts()));
        handler.postDelayed(retry, delay);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    (ComputerManagerService.ComputerManagerBinder) binder;
            final int attempt = attemptId;
            new Thread("StrmQt connect") {
                @Override
                public void run() {
                    localBinder.waitForReady();
                    handler.post(() -> {
                        if (attempt == attemptId) {
                            startPolling(localBinder, attempt);
                        }
                    });
                }
            }.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    private void startPolling(final ComputerManagerService.ComputerManagerBinder binder, final int attempt) {
        managerBinder = binder;
        final String uuid = prefs.computerUuid();
        final ComputerDetails computer = binder.getComputer(uuid);
        if (computer == null) {
            showMenu(getString(R.string.strmqt_status_host_forgotten));
            return;
        }

        binder.invalidateStateForComputer(uuid);
        binder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (!uuid.equalsIgnoreCase(details.uuid)) {
                    return;
                }
                handler.post(() -> {
                    if (attempt == attemptId) {
                        computerUpdated(binder, details, attempt);
                    }
                });
            }
        });
    }

    private void computerUpdated(final ComputerManagerService.ComputerManagerBinder binder,
                                 final ComputerDetails details, final int attempt) {
        if (details.state == ComputerDetails.State.UNKNOWN) {
            return;
        }

        if (details.state == ComputerDetails.State.OFFLINE) {
            if (following) {
                // The search decides what comes next.
                return;
            }
            if (!followTried) {
                followTried = true;
                followStrmQt(binder, details, attempt);
                return;
            }
            hostOffline(binder, details);
            return;
        }

        // Answering where it was paired; a search still running is not needed.
        following = false;

        if (details.pairState != PairingManager.PairState.PAIRED) {
            // The stream half of pairing is gone (Sunshine forgot this device):
            // pair again with one code. Back from there lands in the menu.
            pairAgain(getString(R.string.strmqt_status_not_paired, hostLabel()));
            return;
        }

        // Online and paired. One answer is enough.
        binder.stopPolling();

        // Only the Sunshine StrmQt vouches for is streamed from, and StrmQt sets
        // the stream: it is asked every time.
        final String presented = SunshineVouch.fingerprint(details.serverCert);
        askStrmQt(binder, details, presented, attempt);
    }

    /** Wakes the host while tries are left; then the attempt fails. */
    private void hostOffline(final ComputerManagerService.ComputerManagerBinder binder,
                             final ComputerDetails details) {
        if (details.macAddress != null && wakeTries-- > 0) {
            // Best effort, off the main thread; the attempt's timeout bounds the wait.
            new Thread("StrmQt wake") {
                @Override
                public void run() {
                    try {
                        WakeOnLanSender.sendWolPacket(details);
                    } catch (IOException e) {
                        LimeLog.warning("Wake-on-LAN failed: " + e);
                    }
                    binder.invalidateStateForComputer(details.uuid);
                }
            }.start();
            showProgress(getString(R.string.strmqt_status_waking, hostLabel()));
            return;
        }
        attemptFailed(getString(R.string.strmqt_status_host_offline, hostLabel()));
    }

    /**
     * The paired Sunshine does not answer where it was paired. StrmQt may have
     * moved it to another base port (48989 before, 8335 now, or the next free
     * one), or the machine may have a new address: searches once, and when the
     * StrmQt announcing the pinned Web Remote certificate answers somewhere
     * else, points Moonlight's computer there and tries again. A reply is only
     * a hint: the stream still starts only after the vouch check.
     */
    private void followStrmQt(final ComputerManagerService.ComputerManagerBinder binder,
                              final ComputerDetails details, final int attempt) {
        following = true;
        showProgress(getString(R.string.strmqt_status_finding, hostLabel()));
        // Where Moonlight looks is what did not answer; the prefs only when it has no manual address.
        final ComputerDetails.AddressTuple paired = details.manualAddress;
        final String storedAddress = paired != null ? paired.address : prefs.remoteHost();
        final int storedPort = paired != null ? paired.port : prefs.sunshinePort();
        final String cert = prefs.certFingerprint();
        final String uuid = details.uuid;
        new Thread("StrmQt follow") {
            @Override
            public void run() {
                StrmQtDiscovery.Host moved = null;
                try {
                    moved = HostMove.follow(storedAddress, storedPort, cert,
                            StrmQtDiscovery.scan(LaunchActivity.this));
                } catch (IOException e) {
                    LimeLog.warning("StrmQt: cannot search for the host: " + e);
                }
                // Moonlight's computer is not touched for an attempt already abandoned.
                final boolean followed = moved != null && attempt == attemptId
                        && pointAt(binder, uuid, moved);
                handler.post(() -> {
                    if (attempt != attemptId || !following) {
                        return;
                    }
                    following = false;
                    if (followed) {
                        connect(true);
                    } else {
                        hostOffline(binder, details);
                    }
                });
            }
        }.start();
    }

    /**
     * Points Moonlight's stored computer at where StrmQt's Sunshine is now, as
     * pairing added it, and stores the new place. Moonlight takes the address
     * only from the Sunshine with the paired uuid, and keeps the certificate
     * it pinned. Blocks; off the main thread.
     *
     * @return whether the paired Sunshine answers there
     */
    private boolean pointAt(ComputerManagerService.ComputerManagerBinder binder, String uuid,
                            StrmQtDiscovery.Host moved) {
        LimeLog.info("StrmQt moved its Sunshine to " + moved.address + ":" + moved.sunshinePort);
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = uuid;
        computer.manualAddress = new ComputerDetails.AddressTuple(moved.address, moved.sunshinePort);
        try {
            if (!binder.addComputerBlocking(computer)) {
                LimeLog.warning("StrmQt: the paired Sunshine does not answer at "
                        + moved.address + ":" + moved.sunshinePort);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IllegalArgumentException e) {
            // OkHttp refuses an address it cannot make a URL of.
            LimeLog.warning("StrmQt: " + e);
            return false;
        }
        // The token, the pinned certificate and the vouched Sunshine stay.
        String name = prefs.hostName();
        prefs.setHost(moved.address, name != null ? name : moved.name, moved.remotePort, moved.sunshinePort);
        return true;
    }

    /** Opens pairing with one code, over the menu; Back from there lands in the menu. */
    private void pairAgain(String message) {
        showMenu(message);
        if (prefs.remoteHost() != null) {
            startActivity(new Intent(this, SetupActivity.class)
                    .putExtra(SetupActivity.EXTRA_PAIR_NOW, true));
        }
    }

    /**
     * Before every stream: asks StrmQt, over the pinned Web Remote with the
     * token (one request, at most 3 s), which Sunshine is its own and how it
     * sets the stream. A device paired before StrmQt vouched has no
     * fingerprint stored and keeps the answer; with one stored, an answer
     * naming another Sunshine is refused, and no answer streams as before,
     * with the settings last written. See {@link SunshineVouch#atEveryLaunch}.
     */
    private void askStrmQt(final ComputerManagerService.ComputerManagerBinder binder,
                           final ComputerDetails details, final String presented, final int attempt) {
        final String stored = prefs.sunshineFingerprint();
        final String host = prefs.remoteHost();
        final int port = prefs.remotePort();
        final String cert = prefs.certFingerprint();
        final String token = prefs.token();
        if (host == null || cert == null) {
            // No StrmQt to ask: only what is stored can decide.
            SunshineVouch.Launch decision = SunshineVouch.atLaunch(stored, presented);
            vouchDecided(decision == SunshineVouch.Launch.ASK ? SunshineVouch.Launch.NOT_STRMQT : decision,
                    binder, details, attempt);
            return;
        }
        final Context app = getApplicationContext();
        new Thread("StrmQt vouch") {
            @Override
            public void run() {
                final StrmQtClient.Vouch answer = StrmQtClient.askSunshine(host, port, cert, token);
                LimeLog.info("StrmQt vouches for: " + answer);
                // Codec queries block, so the settings are worked out here; written
                // only when the stream is going to start.
                final Map<String, Object> streamPrefs = answer.stream != null && attempt == attemptId
                        ? StreamPreset.prefsFor(app, answer.stream) : null;
                handler.post(() -> {
                    if (attempt != attemptId) {
                        return;
                    }
                    if (StrmQtDiscovery.normalizeFingerprint(stored) == null
                            && answer.kind == StrmQtClient.Vouch.Kind.VOUCHED) {
                        prefs.setSunshineFingerprint(answer.fingerprint);
                    }
                    if (answer.kind == StrmQtClient.Vouch.Kind.VOUCHED
                            || answer.kind == StrmQtClient.Vouch.Kind.NONE) {
                        // StrmQt answered: whether it sets the stream is now known.
                        prefs.setStreamFromStrmQt(answer.stream != null);
                        prefs.setTimePort(answer.timePort);
                    }
                    SunshineVouch.Launch decision = SunshineVouch.atEveryLaunch(stored, answer, presented);
                    if (decision == SunshineVouch.Launch.STREAM && streamPrefs != null) {
                        LimeLog.info("StrmQt sets the stream: " + streamPrefs);
                        StreamPreset.write(LaunchActivity.this, streamPrefs);
                    }
                    vouchDecided(decision, binder, details, attempt);
                });
            }
        }.start();
    }

    private void vouchDecided(SunshineVouch.Launch decision, ComputerManagerService.ComputerManagerBinder binder,
                              ComputerDetails details, int attempt) {
        switch (decision) {
            case STREAM:
                findApp(binder, details, attempt);
                break;
            case NOT_STRMQT:
                LimeLog.warning("StrmQt does not vouch for the Sunshine of " + details.uuid + "; not streaming");
                pairAgain(getString(R.string.strmqt_status_not_strmqt_sunshine, hostLabel()));
                break;
            case UNVOUCHED:
                // Pairing again would stop for the same reason; StrmQt needs updating.
                showMenu(getString(R.string.strmqt_status_sunshine_unvouched));
                break;
            case UNPAIRED:
                // Removed in StrmQt, as the remote finds out in the stream.
                prefs.forgetToken();
                pairAgain(getString(R.string.strmqt_status_not_paired, hostLabel()));
                break;
            case CERT_CHANGED:
                pairAgain(getString(R.string.strmqt_status_strmqt_cert_changed));
                break;
            case RETRY:
            case ASK:
            default:
                attemptFailed(getString(R.string.strmqt_status_strmqt_unreachable));
                break;
        }
    }

    private void findApp(final ComputerManagerService.ComputerManagerBinder binder,
                         final ComputerDetails details, final int attempt) {
        final String uniqueId = binder.getUniqueId();
        final String wanted = prefs.appName();
        new Thread("StrmQt app list") {
            @Override
            public void run() {
                NvApp found = null;
                String error = null;
                try {
                    NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(details),
                            details.httpsPort, uniqueId, details.serverCert,
                            PlatformBinding.getCryptoProvider(LaunchActivity.this));
                    found = pickApp(http.getAppList(), wanted);
                    if (found == null) {
                        error = getString(R.string.strmqt_status_no_app, wanted, hostLabel());
                    }
                } catch (Exception e) {
                    LimeLog.warning("Could not read the app list: " + e);
                    error = getString(R.string.strmqt_status_host_offline, hostLabel());
                }
                final NvApp app = found;
                final String failure = error;
                handler.post(() -> {
                    if (attempt != attemptId) {
                        return;
                    }
                    if (app == null) {
                        attemptFailed(failure);
                    } else {
                        startStream(binder, details, app);
                    }
                });
            }
        }.start();
    }

    /** The app by name; failing that, the only app there is. */
    static NvApp pickApp(List<NvApp> apps, String wanted) {
        for (NvApp app : apps) {
            if (app.getAppName() != null && app.getAppName().equalsIgnoreCase(wanted)) {
                return app;
            }
        }
        return apps.size() == 1 ? apps.get(0) : null;
    }

    private void startStream(ComputerManagerService.ComputerManagerBinder binder,
                             ComputerDetails details, NvApp app) {
        Intent intent = ServerHelper.createStartIntent(this, app, details, binder);
        intent.putExtra(StrmQtSession.EXTRA_SUPERVISED, true);
        abandonAttempt();
        mode = Mode.STREAMING;
        StrmQtSession.launching();
        startActivity(intent);
    }
}
