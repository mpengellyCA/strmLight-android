package com.limelight.strmqt;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * The fork's settings. The switches the settings screen shows live in the
 * default preferences beside Moonlight's own; what pairing stores (address,
 * token, certificate pin, the Sunshine StrmQt vouched for) lives in a
 * private file of its own.
 */
public final class StrmQtPrefs {
    public static final String KEY_REMOTE_MODE = "strmqt_remote_mode";
    public static final String KEY_TRANSPORT = "strmqt_transport";
    public static final String KEY_CONNECT_ON_LAUNCH = "strmqt_connect_on_launch";
    public static final String KEY_START_ON_BOOT = "strmqt_start_on_boot";
    public static final String KEY_RETURN_ON_WAKE = "strmqt_return_on_wake";

    public static final String TRANSPORT_WEB = "web";
    public static final String TRANSPORT_GAMEPAD = "gamepad";

    /** The headless StrmQt host's Web Remote. 8337 is a desktop StrmQt's phone remote. */
    public static final int DEFAULT_REMOTE_PORT = StrmQtDiscovery.DEFAULT_REMOTE_PORT;
    /** What builds before discovery stored for the Web Remote. */
    static final int OLD_REMOTE_PORT = 8337;
    public static final int DEFAULT_SUNSHINE_PORT = StrmQtDiscovery.DEFAULT_SUNSHINE_PORT;
    public static final String DEFAULT_APP_NAME = "StrmQt";

    private static final String FILE = "strmqt";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String SUNSHINE_PORT = "sunshine_port";
    /** The host's name as discovery gave it; shown on the setup screen. */
    private static final String HOST_NAME = "host_name";
    /** Left by builds that signed in with a fixed PIN; removed on sight. */
    private static final String OLD_PIN = "pin";
    private static final String TOKEN = "token";
    /** StrmQt asked for no pairing when this device was set up, so there is no token. */
    private static final String OPEN = "open";
    private static final String CERT = "cert_sha256";
    /** The certificate StrmQt vouched for as its Sunshine's; nothing else is streamed from. */
    private static final String SUNSHINE_CERT = "sunshine_cert_sha256";
    private static final String COMPUTER_UUID = "computer_uuid";
    private static final String COMPUTER_NAME = "computer_name";
    private static final String APP_NAME = "app_name";
    private static final String HINT_SHOWN = "hold_back_hint_shown";
    private static final String PRESET_APPLIED = "preset_applied";

    private final SharedPreferences switches;
    private final SharedPreferences store;

    public StrmQtPrefs(Context context) {
        Context app = context.getApplicationContext();
        switches = PreferenceManager.getDefaultSharedPreferences(app);
        store = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (store.contains(OLD_PIN)) {
            // StrmQt has no standing PIN any more; such a device pairs again.
            store.edit().remove(OLD_PIN).apply();
        }
    }

    public boolean remoteMode() {
        return switches.getBoolean(KEY_REMOTE_MODE, false);
    }

    public void setRemoteMode(boolean on) {
        switches.edit().putBoolean(KEY_REMOTE_MODE, on).apply();
    }

    public String transport() {
        return switches.getString(KEY_TRANSPORT, TRANSPORT_WEB);
    }

    public boolean connectOnLaunch() {
        return switches.getBoolean(KEY_CONNECT_ON_LAUNCH, true);
    }

    public boolean startOnBoot() {
        return switches.getBoolean(KEY_START_ON_BOOT, false);
    }

    public boolean returnOnWake() {
        return switches.getBoolean(KEY_RETURN_ON_WAKE, false);
    }

    /** The Sunshine host the app connects to by itself; null until one is chosen. */
    public String computerUuid() {
        return store.getString(COMPUTER_UUID, null);
    }

    public String computerName() {
        return store.getString(COMPUTER_NAME, null);
    }

    public void setComputer(String uuid, String name) {
        store.edit().putString(COMPUTER_UUID, uuid).putString(COMPUTER_NAME, name).apply();
    }

    public String appName() {
        return store.getString(APP_NAME, DEFAULT_APP_NAME);
    }

    public String remoteHost() {
        return store.getString(HOST, null);
    }

    public int remotePort() {
        return store.getInt(PORT, DEFAULT_REMOTE_PORT);
    }

    /** The base port of the StrmQt host's Sunshine. */
    public int sunshinePort() {
        return store.getInt(SUNSHINE_PORT, DEFAULT_SUNSHINE_PORT);
    }

    public String hostName() {
        return store.getString(HOST_NAME, null);
    }

    /**
     * Where the StrmQt host is, chosen on the setup screen. A token stays: it
     * is sent only over the certificate it was paired over.
     */
    public void setHost(String host, String name, int remotePort, int sunshinePort) {
        store.edit()
                .putString(HOST, host)
                .putString(HOST_NAME, name)
                .putInt(PORT, remotePort)
                .putInt(SUNSHINE_PORT, sunshinePort)
                .apply();
    }

    /**
     * A device set up before discovery stored the desktop phone remote's port,
     * 8337. When the same host is found with its Web Remote on another port,
     * that port is stored instead.
     *
     * @return whether the stored port changed
     */
    public boolean migrateRemotePort(String host, int discoveredPort) {
        if (host == null || !host.equals(remoteHost()) || !store.contains(PORT)
                || store.getInt(PORT, DEFAULT_REMOTE_PORT) != OLD_REMOTE_PORT
                || discoveredPort == OLD_REMOTE_PORT) {
            return false;
        }
        store.edit().putInt(PORT, discoveredPort).apply();
        return true;
    }

    /** The token pairing gave; null when there is none. */
    public String token() {
        return store.getString(TOKEN, null);
    }

    public String certFingerprint() {
        return store.getString(CERT, null);
    }

    public boolean remotePaired() {
        return remoteHost() != null && certFingerprint() != null
                && (token() != null || store.getBoolean(OPEN, false));
    }

    /**
     * Remote mode over the Web Remote is on and StrmQt's address is known, but
     * this device is not paired: it was removed in StrmQt, or it signed in
     * with a PIN before.
     */
    public boolean remoteNeedsPairing() {
        return remoteMode() && TRANSPORT_WEB.equals(transport()) && remoteHost() != null && !remotePaired();
    }

    /** @param token null when StrmQt asks for no pairing */
    public void setRemotePairing(String host, int port, String token, String certFingerprint) {
        SharedPreferences.Editor editor = store.edit()
                .putString(HOST, host)
                .putInt(PORT, port)
                .putString(CERT, certFingerprint)
                .putBoolean(OPEN, token == null);
        if (token != null) {
            editor.putString(TOKEN, token);
        } else {
            editor.remove(TOKEN);
        }
        editor.apply();
    }

    /**
     * The certificate StrmQt vouches for as its Sunshine's, lowercase hex;
     * null when none is stored (a device paired before StrmQt vouched).
     */
    public String sunshineFingerprint() {
        return store.getString(SUNSHINE_CERT, null);
    }

    /** @param fingerprint null forgets it */
    public void setSunshineFingerprint(String fingerprint) {
        if (fingerprint != null) {
            store.edit().putString(SUNSHINE_CERT, fingerprint).apply();
        } else {
            store.edit().remove(SUNSHINE_CERT).apply();
        }
    }

    /** Forgets the token, so the device pairs again. The address and certificate stay. */
    public void forgetToken() {
        store.edit().remove(TOKEN).remove(OPEN).apply();
    }

    public boolean takeHoldBackHint() {
        if (store.getBoolean(HINT_SHOWN, false)) {
            return false;
        }
        store.edit().putBoolean(HINT_SHOWN, true).apply();
        return true;
    }

    public boolean presetApplied() {
        return store.getBoolean(PRESET_APPLIED, false);
    }

    public void setPresetApplied() {
        store.edit().putBoolean(PRESET_APPLIED, true).apply();
    }
}
