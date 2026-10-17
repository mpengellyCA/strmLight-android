package com.limelight.strmqt;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.UiHelper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Setting up StrmQt: find the headless StrmQt host on the network, then pair
 * both the stream and the remote with one code this screen shows, which
 * someone types into StrmQt or into StrmQt's phone remote. StrmQt arms its Web
 * Remote with the code and enters it into its Sunshine, so one entry pairs
 * both. Everything here can be done with a D-pad.
 *
 * The screen has two faces. The simple one, the default, shows one thing at a
 * time: looking for StrmQt, the code (with a tick for the picture and one for
 * the remote), a chooser when several StrmQts answer, "Can't find StrmQt", or
 * "Connected"; {@link FirstRun} decides which. The advanced one, behind
 * "Advanced setup", is everything else: the found hosts, the address and both
 * ports by hand, the raw pairing states, the pinned certificate, and
 * Moonlight's own hosts and settings.
 *
 * Nothing is sent while this screen is not in front, except a Moonlight
 * pairing request already waiting on the host, which is one request, not
 * polling.
 */
public class SetupActivity extends Activity {
    /** Start pairing as soon as the screen opens, at the stored address. */
    public static final String EXTRA_PAIR_NOW = "StrmQtPairNow";
    /**
     * Debug builds: open as a preview that stores nothing and pairs with
     * nothing. The value picks what discovery and pairing pretend to do:
     * searching, one, several, none, nostream, connected, trouble, problem;
     * or live, which searches the network for real and pretends only to pair.
     */
    public static final String EXTRA_PREVIEW = "StrmQtPreview";

    /**
     * A code stays on offer across visits to this screen for this long, so it
     * can be read here, typed into StrmQt elsewhere, and picked up again here.
     */
    private static final long CODE_KEPT_MS = 10 * 60 * 1000;
    /** How long "Connected" stays on screen before the stream starts. */
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

        String label() {
            return name != null && !name.isEmpty() ? name : host;
        }
    }

    /** One pairing run: OneCodePairing, or the preview's pretence of it. */
    private interface Session {
        void start();

        void pause();

        void resume();

        void setCode(String code);

        void stop();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<StrmQtDiscovery.Host> found = new ArrayList<>();

    private StrmQtPrefs prefs;
    // Debug builds only: the preview's scenario, or null.
    private String preview;

    // The advanced face
    private View advancedView;
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
    private TextView certView;
    private Button pairButton;
    private Button newCodeButton;

    // The simple face
    private View simpleView;
    private View searchingScreen;
    private View codeScreen;
    private View chooseScreen;
    private View messageScreen;
    private View connectedScreen;
    private View searchingSymbol;
    private ObjectAnimator searchingPulse;
    private TextView firstCodeHost;
    private TextView firstCode;
    private TextView stepPicture;
    private TextView stepRemote;
    private TextView firstTrouble;
    private LinearLayout chooseList;
    private TextView messageTitle;
    private TextView messageBody;
    private TextView messageDetail;
    private TextView connectedBody;
    private TextView connectedNote;

    private boolean advanced;
    private FirstRun.Screen screen = FirstRun.Screen.SEARCHING;
    // While a screen is being shown: whether it takes the D-pad's focus.
    private boolean moveFocus = true;
    private boolean noStreamingHost;
    // Why pairing stopped, on the PROBLEM screen.
    private String problem;

    private boolean pairNow;
    private boolean pairNowAsked;
    // Pairing may start by itself; off once the user has stopped or left it.
    private boolean autoPair = true;
    // A search answered while the screen was not in front; decide when it is.
    private boolean decideOnResume;
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
    // Searches answered since the user last asked; FirstRun gives up after a few.
    private int searchesDone;
    private String searchError;

    private ComputerManagerService.ComputerManagerBinder binder;
    private boolean bound;
    // Pair was chosen before Moonlight's computer service was ready.
    private boolean pairWhenBound;

    private Choice choice;
    private Session pairing;
    private OneCodePairing.StreamState streamState;
    private OneCodePairing.RemoteState remoteState;
    // Both halves paired: "Connected" until the stream starts.
    private Choice connected;

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

    private final Runnable quietSearch = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && resumed && screen == FirstRun.Screen.NOT_FOUND) {
                search();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_strmqt_setup);
        UiHelper.notifyNewRootView(this);

        prefs = new StrmQtPrefs(this);
        if (BuildConfig.DEBUG && getIntent().hasExtra(EXTRA_PREVIEW)) {
            String scenario = getIntent().getStringExtra(EXTRA_PREVIEW);
            preview = scenario != null && !scenario.isEmpty() ? scenario : "live";
            LimeLog.info("StrmQt: setup preview, " + preview + "; nothing is stored or paired");
        }

        advancedView = findViewById(R.id.strmqtAdvanced);
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
        certView = findViewById(R.id.strmqtCert);
        pairButton = findViewById(R.id.strmqtPair);
        newCodeButton = findViewById(R.id.strmqtNewCode);

        simpleView = findViewById(R.id.strmqtSimple);
        searchingScreen = findViewById(R.id.strmqtScreenSearching);
        codeScreen = findViewById(R.id.strmqtScreenCode);
        chooseScreen = findViewById(R.id.strmqtScreenChoose);
        messageScreen = findViewById(R.id.strmqtScreenMessage);
        connectedScreen = findViewById(R.id.strmqtScreenConnected);
        searchingSymbol = findViewById(R.id.strmqtSearchingSymbol);
        firstCodeHost = findViewById(R.id.strmqtFirstCodeHost);
        firstCode = findViewById(R.id.strmqtFirstCode);
        stepPicture = findViewById(R.id.strmqtStepPicture);
        stepRemote = findViewById(R.id.strmqtStepRemote);
        firstTrouble = findViewById(R.id.strmqtFirstTrouble);
        chooseList = findViewById(R.id.strmqtChooseList);
        messageTitle = findViewById(R.id.strmqtMessageTitle);
        messageBody = findViewById(R.id.strmqtMessageBody);
        messageDetail = findViewById(R.id.strmqtMessageDetail);
        connectedBody = findViewById(R.id.strmqtConnectedBody);
        connectedNote = findViewById(R.id.strmqtConnectedNote);

        for (int header : new int[] {R.id.strmqtSimpleHeader, R.id.strmqtAdvancedHeader}) {
            View view = findViewById(header);
            ((TextView) view.findViewById(R.id.strmqtWordmark)).setText(Brand.wordmark(this));
            view.findViewById(R.id.strmqtPreviewBadge).setVisibility(preview != null ? View.VISIBLE : View.GONE);
        }
        Brand.applyFonts(getWindow().getDecorView());
        Brand.unclip(findViewById(android.R.id.content));

        searchingPulse = ObjectAnimator.ofFloat(searchingSymbol, View.ALPHA, 1f, 0.4f);
        searchingPulse.setDuration(1200);
        searchingPulse.setRepeatMode(ValueAnimator.REVERSE);
        searchingPulse.setRepeatCount(ValueAnimator.INFINITE);
        searchingPulse.setInterpolator(new AccelerateDecelerateInterpolator());

        String storedHost = storedHost();
        if (storedHost != null) {
            addressView.setText(storedHost);
        }
        portView.setText(String.valueOf(preview != null ? StrmQtPrefs.DEFAULT_REMOTE_PORT : prefs.remotePort()));
        sunshinePortView.setText(String.valueOf(
                preview != null ? StrmQtPrefs.DEFAULT_SUNSHINE_PORT : prefs.sunshinePort()));
        showCert(preview != null ? null : prefs.certFingerprint());

        // The advanced face
        hostGroup.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            if (checked != null && checked.getTag() instanceof StrmQtDiscovery.Host) {
                fillFrom((StrmQtDiscovery.Host) checked.getTag());
            }
        });
        searchButton.setOnClickListener(v -> searchAsked());
        pairButton.setOnClickListener(v -> {
            if (pairing != null || pairWhenBound) {
                userStopped(getString(R.string.strmqt_setup_stopped));
            } else {
                problem = null;
                pair();
            }
        });
        newCodeButton.setOnClickListener(v -> newCode());
        findViewById(R.id.strmqtAdvancedHosts).setOnClickListener(
                v -> startActivity(new Intent(this, PcView.class)));
        findViewById(R.id.strmqtAdvancedSettings).setOnClickListener(
                v -> startActivity(new Intent(this, StreamSettings.class)));
        findViewById(R.id.strmqtAdvancedSimple).setOnClickListener(v -> showAdvanced(false));

        // The simple face
        for (int id : new int[] {R.id.strmqtSearchingAdvanced, R.id.strmqtCodeAdvanced,
                R.id.strmqtChooseAdvanced, R.id.strmqtMessageAdvanced}) {
            findViewById(id).setOnClickListener(v -> showAdvanced(true));
        }
        findViewById(R.id.strmqtFirstNewCode).setOnClickListener(v -> newCode());
        findViewById(R.id.strmqtChooseSearch).setOnClickListener(v -> searchAsked());
        findViewById(R.id.strmqtMessageRetry).setOnClickListener(v -> tryAgain());

        pairNowAsked = preview == null && getIntent().getBooleanExtra(EXTRA_PAIR_NOW, false);
        pairNow = savedInstanceState == null && pairNowAsked && storedHost != null;

        if (preview == null) {
            bound = bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                    Service.BIND_AUTO_CREATE);
        }
        showScreen(FirstRun.Screen.SEARCHING);
        showAdvanced(false);
        search();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pairing != null) {
            pairing.resume();
        } else if (decideOnResume) {
            decideOnResume = false;
            decide();
        }
        if (screen == FirstRun.Screen.NOT_FOUND) {
            handler.removeCallbacks(quietSearch);
            handler.postDelayed(quietSearch, FirstRun.QUIET_SEARCH_MS);
        }
        if (screen == FirstRun.Screen.SEARCHING && !advanced) {
            searchingPulse.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(quietSearch);
        searchingPulse.cancel();
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
        searchingPulse.cancel();
        if (pairing != null) {
            pairing.stop();
            pairing = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (advanced) {
            showAdvanced(false);
            return;
        }
        if (screen == FirstRun.Screen.CODE && pairing != null
                && FirstRun.usable(found).size() > 1) {
            // Back to the hosts the code was for.
            userStopped(null);
            return;
        }
        super.onBackPressed();
    }

    /** The stored host's address; none in a preview, which behaves as a device never set up. */
    private String storedHost() {
        return preview != null ? null : prefs.remoteHost();
    }

    // The two faces

    private void showAdvanced(boolean show) {
        advanced = show;
        advancedView.setVisibility(show ? View.VISIBLE : View.GONE);
        simpleView.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) {
            searchingPulse.cancel();
            (pairing != null ? pairButton : searchButton).requestFocus();
        } else {
            showScreen(screen, true);
        }
    }

    private void showScreen(FirstRun.Screen next) {
        showScreen(next, next != screen);
    }

    /** @param moveFocus put the D-pad on the new screen's first button; not when only its content changed */
    private void showScreen(FirstRun.Screen next, boolean moveFocus) {
        screen = next;
        boolean focusWas = this.moveFocus;
        this.moveFocus = moveFocus;
        searchingScreen.setVisibility(next == FirstRun.Screen.SEARCHING ? View.VISIBLE : View.GONE);
        codeScreen.setVisibility(next == FirstRun.Screen.CODE ? View.VISIBLE : View.GONE);
        chooseScreen.setVisibility(next == FirstRun.Screen.CHOOSE ? View.VISIBLE : View.GONE);
        messageScreen.setVisibility(next == FirstRun.Screen.NOT_FOUND || next == FirstRun.Screen.PROBLEM
                ? View.VISIBLE : View.GONE);
        connectedScreen.setVisibility(next == FirstRun.Screen.CONNECTED ? View.VISIBLE : View.GONE);

        handler.removeCallbacks(quietSearch);
        if (next == FirstRun.Screen.SEARCHING && resumed && !advanced) {
            searchingPulse.start();
        } else {
            searchingPulse.cancel();
            searchingSymbol.setAlpha(1f);
        }

        switch (next) {
            case SEARCHING:
                focus(R.id.strmqtSearchingAdvanced);
                break;
            case CODE:
                renderCode();
                focus(R.id.strmqtFirstNewCode);
                break;
            case CHOOSE:
                renderChooser();
                break;
            case NOT_FOUND:
                messageTitle.setText(noStreamingHost ? R.string.strmqt_first_no_stream_title
                        : R.string.strmqt_first_not_found_title);
                messageBody.setText(noStreamingHost ? R.string.strmqt_first_no_stream_body
                        : R.string.strmqt_first_not_found_body);
                setDetail(searchError != null ? getString(R.string.strmqt_first_search_failed, searchError) : null);
                focus(R.id.strmqtMessageRetry);
                if (resumed) {
                    handler.postDelayed(quietSearch, FirstRun.QUIET_SEARCH_MS);
                }
                break;
            case PROBLEM:
                messageTitle.setText(R.string.strmqt_first_problem_title);
                messageBody.setText(problem);
                setDetail(null);
                focus(R.id.strmqtMessageRetry);
                break;
            case CONNECTED:
                View focused = getCurrentFocus();
                if (focused != null) {
                    focused.clearFocus();
                }
                break;
        }
        this.moveFocus = focusWas;
    }

    private void focus(int id) {
        if (!advanced && moveFocus) {
            findViewById(id).requestFocus();
        }
    }

    private void setDetail(String text) {
        messageDetail.setText(text);
        messageDetail.setVisibility(text != null ? View.VISIBLE : View.GONE);
    }

    private void renderCode() {
        firstCode.setText(currentCode());
        Choice c = choice;
        firstCodeHost.setText(c != null ? getString(R.string.strmqt_first_code_host, c.label()) : "");
        renderStep(stepPicture, R.string.strmqt_first_step_picture, FirstRun.step(streamState));
        renderStep(stepRemote, R.string.strmqt_first_step_remote, FirstRun.step(remoteState));
        String trouble = null;
        switch (FirstRun.trouble(streamState, remoteState)) {
            case WRONG_CODE:
                trouble = getString(R.string.strmqt_first_trouble_wrong_code);
                break;
            case LOCKED_OUT:
                trouble = getString(R.string.strmqt_first_trouble_locked_out, StrmQtClient.PAIR_LOCKOUT_MS / 1000);
                break;
            case UNREACHABLE:
                trouble = getString(R.string.strmqt_first_trouble_unreachable);
                break;
            case REFUSED:
                trouble = getString(R.string.strmqt_first_trouble_refused);
                break;
            case NONE:
                break;
        }
        firstTrouble.setText(trouble);
    }

    private void renderStep(TextView view, int label, FirstRun.Step step) {
        boolean done = step == FirstRun.Step.DONE;
        view.setText(label);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(done ? R.drawable.strmqt_check
                : step == FirstRun.Step.TROUBLE ? R.drawable.strmqt_trouble : R.drawable.strmqt_waiting, 0, 0, 0);
        view.setBackgroundResource(done ? R.drawable.strmqt_pill_done : R.drawable.strmqt_pill);
        view.setTextColor(getResources().getColor(done ? R.color.strmqt_accent
                : step == FirstRun.Step.TROUBLE ? R.color.strmqt_warning : R.color.strmqt_text_secondary));
    }

    private void renderChooser() {
        View focused = getCurrentFocus();
        Object focusedHost = focused != null && focused.getParent() == chooseList ? focused.getTag() : null;
        chooseList.removeAllViews();
        List<StrmQtDiscovery.Host> hosts = FirstRun.forChooser(found, storedHost());
        LayoutInflater inflater = LayoutInflater.from(this);
        View toFocus = null;
        for (final StrmQtDiscovery.Host host : hosts) {
            View card = inflater.inflate(R.layout.strmqt_host_card, chooseList, false);
            ((TextView) card.findViewById(R.id.strmqtHostName)).setText(host.name);
            TextView address = card.findViewById(R.id.strmqtHostAddress);
            if (FirstRun.needsAddress(host, hosts)) {
                address.setText(host.address);
                address.setVisibility(View.VISIBLE);
            }
            card.setTag(host.address);
            card.setOnClickListener(v -> pairWith(host));
            Brand.applyFonts(card);
            Brand.unclip(card);
            chooseList.addView(card);
            if (toFocus == null || host.address.equals(focusedHost)) {
                toFocus = card;
            }
        }
        if (!advanced && (moveFocus || focusedHost != null)) {
            (toFocus != null ? toFocus : findViewById(R.id.strmqtChooseSearch)).requestFocus();
        }
    }

    private void showCert(String fingerprint) {
        certView.setText(fingerprint != null ? CertPin.display(fingerprint)
                : getString(R.string.strmqt_advanced_cert_none));
    }

    // Finding hosts

    /** The user asked to search: start counting again, and pairing may start by itself. */
    private void searchAsked() {
        searchesDone = 0;
        if (!advanced && pairing == null && !pairWhenBound && connected == null) {
            showScreen(FirstRun.Screen.SEARCHING);
        }
        search();
    }

    /** Try again after "Can't find StrmQt" or a stopped pairing. */
    private void tryAgain() {
        problem = null;
        autoPair = true;
        pairNow = pairNowAsked && storedHost() != null;
        searchAsked();
    }

    private void search() {
        if (searching) {
            return;
        }
        searching = true;
        final int id = ++searchId;
        discoveryView.setText(R.string.strmqt_setup_searching);
        searchButton.setEnabled(false);
        if ("searching".equals(preview)) {
            // The preview of looking: it never finishes.
            return;
        }
        new Thread("StrmQt discovery") {
            @Override
            public void run() {
                List<StrmQtDiscovery.Host> hosts = null;
                String error = null;
                try {
                    hosts = preview != null && !"live".equals(preview) ? previewHosts()
                            : StrmQtDiscovery.scan(SetupActivity.this);
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
                    searchesDone++;
                    searchError = failure;
                    searchButton.setEnabled(true);
                    if (result == null) {
                        discoveryView.setText(getString(R.string.strmqt_setup_search_failed, failure));
                        found.clear();
                    } else {
                        showHosts(result);
                    }
                    if (resumed) {
                        decide();
                    } else {
                        decideOnResume = true;
                    }
                });
            }
        }.start();
    }

    /** What the last search means for the simple face; may start pairing. */
    private void decide() {
        if (pairing != null || pairWhenBound || connected != null || problem != null) {
            // Pairing, paired, or stopped for a reason: a search does not change that.
            if (screen == FirstRun.Screen.CHOOSE) {
                renderChooser();
            }
            return;
        }
        StrmQtDiscovery.Host stored = null;
        String storedAddress = storedHost();
        if (storedAddress != null) {
            stored = new StrmQtDiscovery.Host(storedAddress, prefs.hostName(), prefs.remotePort(),
                    prefs.sunshinePort(), null);
        }
        FirstRun.Decision decision = FirstRun.afterSearch(found, searchesDone, searchError != null,
                autoPair && !advanced, stored, pairNow);
        noStreamingHost = decision.noStreamingHost;
        if (decision.screen == FirstRun.Screen.CODE) {
            pairNow = false;
            pairWith(decision.pairWith);
        } else if (decision.searchAgain) {
            if (screen != FirstRun.Screen.SEARCHING && screen != FirstRun.Screen.NOT_FOUND) {
                showScreen(FirstRun.Screen.SEARCHING);
            }
            search();
        } else {
            // A new screen takes the focus; the same one, searched again, keeps it
            // where it was (still nothing found: it keeps looking quietly).
            showScreen(decision.screen);
        }
    }

    private List<StrmQtDiscovery.Host> previewHosts() throws InterruptedException {
        Thread.sleep(1800);
        switch (preview) {
            case "several":
                return new ArrayList<>(Arrays.asList(
                        new StrmQtDiscovery.Host("192.168.2.116", "cachyos-minipc", 8338, 48989, null),
                        new StrmQtDiscovery.Host("192.168.2.40", "living-room", 8338, 48989, null),
                        new StrmQtDiscovery.Host("192.168.2.41", "living-room", 8338, 48989, null)));
            case "none":
                return new ArrayList<>();
            case "nostream":
                return new ArrayList<>(Arrays.asList(
                        new StrmQtDiscovery.Host("192.168.2.116", "cachyos-minipc", 8338, 0, null)));
            default:
                return new ArrayList<>(Arrays.asList(
                        new StrmQtDiscovery.Host("192.168.2.116", "cachyos-minipc", 8338, 48989, null)));
        }
    }

    private void showHosts(List<StrmQtDiscovery.Host> hosts) {
        found.clear();
        found.addAll(hosts);
        View focused = getCurrentFocus();
        boolean listHadFocus = focused != null && focused.getParent() == hostGroup;

        filling = true;
        hostGroup.removeAllViews();
        RadioButton toCheck = null;
        String stored = storedHost();
        for (StrmQtDiscovery.Host host : hosts) {
            // A device set up before discovery stored the desktop remote's port.
            if (preview == null && prefs.migrateRemotePort(host.address, host.remotePort)) {
                LimeLog.info("StrmQt: Web Remote port for " + host.address + " is now " + host.remotePort);
                if (host.address.equals(addressView.getText().toString().trim())) {
                    portView.setText(String.valueOf(host.remotePort));
                }
            }
            RadioButton button = new RadioButton(this, null, 0, R.style.StrmQt_Radio);
            button.setId(View.generateViewId());
            button.setText(getString(host.canStream() ? R.string.strmqt_setup_host_entry
                    : R.string.strmqt_setup_host_entry_no_stream, host.name, host.address));
            button.setTag(host);
            button.setEnabled(host.canStream());
            button.setTypeface(Brand.typeface(this, null));
            hostGroup.addView(button, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (host.canStream() && (host.address.equals(stored) || toCheck == null && stored == null)) {
                toCheck = button;
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
    }

    private void fillFrom(StrmQtDiscovery.Host host) {
        if (pairing != null && !filling) {
            // Choosing another host while pairing starts over there.
            userStopped(null);
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

    /** Pairs with a host from the chooser or the search, as choosing it on the advanced face and Pair would. */
    private void pairWith(StrmQtDiscovery.Host host) {
        filling = true;
        for (int i = 0; i < hostGroup.getChildCount(); i++) {
            View child = hostGroup.getChildAt(i);
            if (child.getTag() instanceof StrmQtDiscovery.Host
                    && ((StrmQtDiscovery.Host) child.getTag()).address.equals(host.address)) {
                ((RadioButton) child).setChecked(true);
            }
        }
        fillFrom(host);
        filling = false;
        problem = null;
        pair();
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
        StrmQtDiscovery.Host announced = foundAt(host, remotePort);
        String name = announced != null ? announced.name
                : host.equals(storedHost()) ? prefs.hostName() : null;
        if (binder == null && preview == null) {
            // Moonlight's computer service is still starting; onServiceConnected carries on.
            pairWhenBound = true;
            choice = new Choice(host, name, remotePort, sunshinePort);
            streamState = null;
            remoteState = null;
            statusView.setText(getString(R.string.strmqt_setup_pairing, host));
            showScreen(FirstRun.Screen.CODE);
            return;
        }

        choice = new Choice(host, name, remotePort, sunshinePort);
        streamState = null;
        remoteState = null;
        final Choice c = choice;
        OneCodePairing.Listener listener = new OneCodePairing.Listener() {
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
                runIfCurrent(c, () -> pairingStopped(why, c));
            }
        };
        if (preview != null) {
            pairing = new PreviewSession(listener);
        } else {
            OneCodePairing.Target target = new OneCodePairing.Target(host, remotePort, sunshinePort,
                    announced != null ? announced.fingerprint : null,
                    prefs.token(), prefs.certFingerprint());
            final OneCodePairing real = new OneCodePairing(target, currentCode(), deviceName(),
                    new MoonlightPairer(this, binder), listener);
            pairing = new Session() {
                @Override
                public void start() {
                    real.start();
                }

                @Override
                public void pause() {
                    real.pause();
                }

                @Override
                public void resume() {
                    real.resume();
                }

                @Override
                public void setCode(String code) {
                    real.setCode(code);
                }

                @Override
                public void stop() {
                    real.stop();
                }
            };
        }
        statusView.setText(getString(R.string.strmqt_setup_pairing, host));
        showCode(true);
        showScreen(FirstRun.Screen.CODE);
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
        streamState = state;
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
        if (screen == FirstRun.Screen.CODE) {
            renderCode();
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
                return;
        }
        remoteState = state;
        if (screen == FirstRun.Screen.CODE) {
            renderCode();
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

    /** Pairing cannot go on (see OneCodePairing.RemoteState); a code will not fix it. */
    private void pairingStopped(OneCodePairing.RemoteState why, Choice c) {
        stopPairing(stoppedMessage(why, c));
        problem = why == OneCodePairing.RemoteState.CERT_CHANGED
                ? getString(R.string.strmqt_first_problem_cert_changed) : stoppedMessage(why, c);
        autoPair = false;
        showScreen(FirstRun.Screen.PROBLEM);
    }

    /** The user stopped pairing (Stop, Back, another host): it does not start again by itself. */
    private void userStopped(String message) {
        stopPairing(message);
        autoPair = false;
        pairNow = false;
        decide();
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
        firstCode.setText(offeredCode);
        if (pairing != null) {
            pairing.setCode(offeredCode);
        }
    }

    private void paired(Choice c, OneCodePairing.Result result) {
        // One code, one device: the next pairing shows a new one.
        offeredCode = null;
        connected = c;

        if (preview != null) {
            stopPairing(getString(R.string.strmqt_first_connected_preview));
            connectedBody.setText(getString(R.string.strmqt_first_connected_body, c.label()));
            connectedNote.setText(R.string.strmqt_first_connected_preview);
            connectedNote.setVisibility(View.VISIBLE);
            showScreen(FirstRun.Screen.CONNECTED);
            return;
        }

        prefs.setHost(c.host, c.name, c.remotePort, c.sunshinePort);
        prefs.setRemotePairing(c.host, c.remotePort, result.token, result.fingerprint);
        prefs.setRemoteMode(true);
        prefs.setComputer(result.computerUuid, c.name != null ? c.name : result.computerName);
        showCert(result.fingerprint);

        String message = getString(result.token != null ? R.string.strmqt_setup_paired
                : R.string.strmqt_setup_paired_open, CertPin.display(result.fingerprint));
        String note = null;
        if (!prefs.presetApplied()) {
            note = getString(StreamPreset.apply(this)
                    ? R.string.strmqt_preset_applied_4k : R.string.strmqt_preset_applied_1080p);
            message += "\n" + note;
        }
        stopPairing(message);
        connectedBody.setText(getString(R.string.strmqt_first_connected_body,
                c.name != null ? c.name : result.computerName != null ? result.computerName : c.host));
        connectedNote.setText(note);
        connectedNote.setVisibility(note != null ? View.VISIBLE : View.GONE);
        showScreen(FirstRun.Screen.CONNECTED);
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

    /**
     * The preview's pairing: it sends nothing and pretends, by the scenario,
     * to wait for the code, to pair, to meet a wrong code, or to stop.
     */
    private final class PreviewSession implements Session {
        private final OneCodePairing.Listener listener;
        private final Object token = new Object();

        PreviewSession(OneCodePairing.Listener listener) {
            this.listener = listener;
        }

        private void later(long delayMs, Runnable action) {
            handler.postAtTime(action, token, SystemClock.uptimeMillis() + delayMs);
        }

        @Override
        public void start() {
            later(300, () -> {
                listener.onStream(OneCodePairing.StreamState.STARTING, null);
                listener.onRemote(OneCodePairing.RemoteState.STARTING);
            });
            later(900, () -> {
                listener.onStream(OneCodePairing.StreamState.WAITING, null);
                listener.onRemote(OneCodePairing.RemoteState.WAITING);
            });
            switch (preview) {
                case "connected":
                    later(3000, () -> listener.onStream(OneCodePairing.StreamState.PAIRED, null));
                    later(4500, () -> listener.onRemote(OneCodePairing.RemoteState.PAIRED));
                    later(5500, () -> listener.onDone(null));
                    break;
                case "trouble":
                    later(2500, () -> listener.onStream(OneCodePairing.StreamState.PAIRED, null));
                    later(3000, () -> listener.onRemote(OneCodePairing.RemoteState.WRONG_CODE));
                    break;
                case "problem":
                    later(2000, () -> listener.onStopped(OneCodePairing.RemoteState.UNSUPPORTED));
                    break;
                default:
                    // Waits for a code that will never come.
                    break;
            }
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void setCode(String code) {
        }

        @Override
        public void stop() {
            handler.removeCallbacksAndMessages(token);
        }
    }
}
