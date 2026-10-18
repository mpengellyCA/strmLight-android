package com.limelight.strmqt;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stream settings for watching StrmQt rather than playing a game.
 *
 * A StrmQt that sets the stream (Settings › StrmLight) sends its picture and
 * sound with every launch's GET /api/strmlight/host; {@link #prefsFor} turns
 * them into Moonlight's own settings, stepped down to what this device can
 * decode, and {@link #write} writes them before the stream starts. With an
 * older StrmQt, the preset is written once instead ({@link #apply}): 4K at
 * 30 fps in HEVC, paced for smooth video, where every value stays the user's
 * to change.
 */
public final class StreamPreset {
    private static final int BITRATE_4K_KBPS = 40000;
    private static final int BITRATE_1080P_KBPS = 15000;

    // Keys and values as in res/xml/preferences.xml and PreferenceConfiguration.
    static final String KEY_RESOLUTION = "list_resolution";
    static final String KEY_FPS = "list_fps";
    static final String KEY_BITRATE = "seekbar_bitrate_kbps";
    static final String KEY_VIDEO_FORMAT = "video_format";
    static final String KEY_AUDIO = "list_audio_config";
    static final String KEY_HDR = "checkbox_enable_hdr";
    static final String KEY_FRAME_PACING = "frame_pacing";
    /** The settings row that applies the preset. */
    static final String KEY_PRESET_ROW = "strmqt_preset";

    /** Moonlight's settings StrmQt sets when it sends the stream. */
    static final List<String> STREAM_KEYS = Collections.unmodifiableList(Arrays.asList(
            KEY_RESOLUTION, KEY_FPS, KEY_BITRATE, KEY_FRAME_PACING, KEY_AUDIO, KEY_VIDEO_FORMAT, KEY_HDR));

    /** What a device can decode; {@link #deviceDecoders()} asks MediaCodecList. */
    public interface Decoders {
        /** @param codec {@link HostStream#CODEC_HEVC}, {@link HostStream#CODEC_AV1} or {@link HostStream#CODEC_H264} */
        boolean canDecode(String codec, int width, int height, int fps);
    }

    private StreamPreset() {}

    /** @return true when the device decodes 4K HEVC and the preset is 4K; false for the 1080p fallback */
    public static boolean apply(Context context) {
        boolean uhd = canDecodeHevc(3840, 2160, 30);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY_RESOLUTION, uhd ? "3840x2160" : "1920x1080")
                .putString(KEY_FPS, "30")
                .putInt(KEY_BITRATE, uhd ? BITRATE_4K_KBPS : BITRATE_1080P_KBPS)
                .putString(KEY_VIDEO_FORMAT, "forceh265")
                .putString(KEY_FRAME_PACING, "balanced")
                .apply();
        new StrmQtPrefs(context).setPresetApplied();
        return uhd;
    }

    // The stream as StrmQt sets it

    /**
     * What to write into Moonlight's settings for the stream StrmQt sends,
     * on this device. Blocks on codec queries, so off the main thread;
     * {@link #write} then writes it.
     */
    public static Map<String, Object> prefsFor(Context context, HostStream stream) {
        return prefsFor(stream, current(PreferenceManager.getDefaultSharedPreferences(context)), deviceDecoders());
    }

    /** Writes what {@link #prefsFor} gave into Moonlight's settings. */
    public static void write(Context context, Map<String, Object> prefs) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (Map.Entry<String, Object> e : prefs.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Integer) {
                editor.putInt(e.getKey(), (Integer) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(e.getKey(), (Boolean) value);
            } else {
                editor.putString(e.getKey(), String.valueOf(value));
            }
        }
        editor.apply();
    }

    /** Moonlight's current resolution, frame rate and bitrate; a field that cannot be read is null. */
    static HostStream current(SharedPreferences prefs) {
        Integer width = null;
        Integer height = null;
        Integer fps = null;
        Integer bitrate = null;
        try {
            String resolution = prefs.getString(KEY_RESOLUTION, null);
            if (resolution != null) {
                String[] parts = resolution.toLowerCase(Locale.ROOT).split("x");
                if (parts.length == 2) {
                    width = Integer.parseInt(parts[0].trim());
                    height = Integer.parseInt(parts[1].trim());
                }
            }
        } catch (NumberFormatException | ClassCastException e) {
            width = null;
            height = null;
        }
        try {
            String value = prefs.getString(KEY_FPS, null);
            fps = value != null ? Integer.valueOf(value.trim()) : null;
        } catch (NumberFormatException | ClassCastException e) {
            fps = null;
        }
        try {
            bitrate = prefs.contains(KEY_BITRATE) ? prefs.getInt(KEY_BITRATE, 0) : null;
        } catch (ClassCastException e) {
            bitrate = null;
        }
        return new HostStream(width, height, fps, bitrate, null, null);
    }

    /**
     * The pure part of {@link #prefsFor(Context, HostStream)}: Moonlight's
     * settings for the stream StrmQt sends, stepped down to what the device
     * decodes.
     *
     * Each setting StrmQt sends is written; one it leaves out (or sent as
     * nonsense) stays as Moonlight has it, in {@code current}. HDR is always
     * off (KWin's virtual screen cannot be HDR) and frame pacing "balanced".
     *
     * When the device cannot decode the codec at that size and rate, the next
     * codec is tried (AV1, then HEVC, then H.264), then 30 fps instead of 60,
     * then the next smaller resolution (4K, 1440p, 1080p, 720p). A stream made
     * smaller or slower has its bitrate capped at what StrmQt would choose by
     * itself for it. When nothing decodes at all, the codec query cannot be
     * trusted: at most 1080p, and Moonlight picks the codec.
     *
     * @param current Moonlight's settings now (only size, rate and bitrate are read); may be null
     */
    static Map<String, Object> prefsFor(HostStream stream, HostStream current, Decoders decoders) {
        int wantWidth = stream.width != null ? stream.width
                : current != null && current.width != null ? current.width : 1920;
        int wantHeight = stream.height != null ? stream.height
                : current != null && current.height != null ? current.height : 1080;
        int wantFps = stream.fps != null ? stream.fps
                : current != null && current.fps != null && current.fps > 0 ? current.fps : 30;
        String wantCodec = stream.codec != null ? stream.codec : HostStream.CODEC_AUTO;

        int[] size = null;
        int fps = wantFps;
        String codec = wantCodec;
        search:
        for (int[] s : sizesFrom(wantWidth, wantHeight)) {
            for (int f : ratesFrom(wantFps)) {
                for (String c : codecsFrom(wantCodec)) {
                    if (decodes(decoders, c, s[0], s[1], f)) {
                        size = s;
                        fps = f;
                        codec = c;
                        break search;
                    }
                }
            }
        }
        if (size == null) {
            size = wantHeight > 1080 ? new int[]{1920, 1080} : new int[]{wantWidth, wantHeight};
            codec = HostStream.CODEC_AUTO;
            LimeLog.warning("StrmQt stream: no decoder reports " + wantCodec + " at any size; streaming "
                    + size[0] + "x" + size[1] + " and letting Moonlight pick the codec");
        }

        boolean smaller = size[0] != wantWidth || size[1] != wantHeight;
        boolean slower = fps != wantFps;
        if (smaller || slower || !codec.equals(wantCodec)) {
            LimeLog.info("StrmQt stream: this device cannot decode " + wantCodec + " at " + wantWidth + "x"
                    + wantHeight + " " + wantFps + " fps; streaming " + codec + " at " + size[0] + "x"
                    + size[1] + " " + fps + " fps");
        }

        Map<String, Object> prefs = new LinkedHashMap<>();
        if (stream.width != null || smaller) {
            prefs.put(KEY_RESOLUTION, size[0] + "x" + size[1]);
        }
        if (stream.fps != null || slower) {
            prefs.put(KEY_FPS, Integer.toString(fps));
        }
        Integer bitrate = stream.bitrateKbps != null ? stream.bitrateKbps
                : current != null ? current.bitrateKbps : null;
        if (smaller || slower) {
            int cap = automaticBitrate(size[1], fps);
            bitrate = bitrate == null ? cap : Math.min(bitrate, cap);
        }
        if (bitrate != null && (stream.bitrateKbps != null || smaller || slower)) {
            prefs.put(KEY_BITRATE, bitrate);
        }
        if (stream.codec != null || !codec.equals(wantCodec)) {
            prefs.put(KEY_VIDEO_FORMAT, videoFormat(codec));
        }
        if (stream.audio != null) {
            prefs.put(KEY_AUDIO, stream.audio);
        }
        prefs.put(KEY_HDR, false);
        prefs.put(KEY_FRAME_PACING, "balanced");
        return prefs;
    }

    /** Moonlight's video_format for a codec StrmQt names. */
    static String videoFormat(String codec) {
        switch (codec) {
            case HostStream.CODEC_HEVC:
                return "forceh265";
            case HostStream.CODEC_AV1:
                return "forceav1";
            case HostStream.CODEC_H264:
                return "neverh265";
            case HostStream.CODEC_AUTO:
            default:
                return "auto";
        }
    }

    /** As StrmQt's automaticBitrate (HostFiles.cpp): what it chooses for a size and rate by itself. */
    static int automaticBitrate(int height, int fps) {
        int kbps = height >= 2160 ? 40000 : height >= 1440 ? 25000 : height >= 1080 ? 15000 : 8000;
        return fps > 30 ? kbps * 3 / 2 : kbps;
    }

    /** The size asked for, then each smaller one StrmQt offers. */
    private static List<int[]> sizesFrom(int width, int height) {
        List<int[]> sizes = new ArrayList<>();
        sizes.add(new int[]{width, height});
        for (int[] r : HostStream.RESOLUTIONS) {
            if ((long) r[0] * r[1] < (long) width * height) {
                sizes.add(r);
            }
        }
        return sizes;
    }

    private static List<Integer> ratesFrom(int fps) {
        return fps > 30 ? Arrays.asList(fps, 30) : Collections.singletonList(fps);
    }

    private static List<String> codecsFrom(String codec) {
        switch (codec) {
            case HostStream.CODEC_AV1:
                return Arrays.asList(HostStream.CODEC_AV1, HostStream.CODEC_HEVC, HostStream.CODEC_H264);
            case HostStream.CODEC_HEVC:
                return Arrays.asList(HostStream.CODEC_HEVC, HostStream.CODEC_H264);
            case HostStream.CODEC_H264:
                return Collections.singletonList(HostStream.CODEC_H264);
            case HostStream.CODEC_AUTO:
            default:
                return Collections.singletonList(HostStream.CODEC_AUTO);
        }
    }

    /** "auto" decodes when any codec Moonlight may choose does. */
    private static boolean decodes(Decoders decoders, String codec, int width, int height, int fps) {
        if (HostStream.CODEC_AUTO.equals(codec)) {
            return decoders.canDecode(HostStream.CODEC_HEVC, width, height, fps)
                    || decoders.canDecode(HostStream.CODEC_H264, width, height, fps)
                    || decoders.canDecode(HostStream.CODEC_AV1, width, height, fps);
        }
        return decoders.canDecode(codec, width, height, fps);
    }

    // What this device decodes

    static String mimeType(String codec) {
        switch (codec) {
            case HostStream.CODEC_HEVC:
                return "video/hevc";
            case HostStream.CODEC_AV1:
                return "video/av01";
            case HostStream.CODEC_H264:
            default:
                return "video/avc";
        }
    }

    /**
     * The device's decoders, read once. Software decoders are left out, as
     * Moonlight leaves them out (except on emulators, which have no others):
     * they cannot keep up with a stream.
     */
    public static Decoders deviceDecoders() {
        final List<MediaCodecInfo> decoders = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() && !isSoftware(info)) {
                    decoders.add(info);
                }
            }
        } catch (RuntimeException e) {
            // Some devices throw from codec queries; treat that as "cannot".
            LimeLog.warning("Codec query failed: " + e);
        }
        return (codec, width, height, fps) -> canDecode(decoders, mimeType(codec), width, height, fps);
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        if (MediaCodecHelper.SHOULD_BYPASS_SOFTWARE_BLOCK) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly();
        }
        String name = info.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.") || name.startsWith("c2.android.");
    }

    private static boolean canDecode(List<MediaCodecInfo> decoders, String mime, int width, int height, int fps) {
        for (MediaCodecInfo info : decoders) {
            try {
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) {
                        continue;
                    }
                    MediaCodecInfo.VideoCapabilities video =
                            info.getCapabilitiesForType(type).getVideoCapabilities();
                    if (video != null && video.areSizeAndRateSupported(width, height, fps)) {
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                LimeLog.warning("Codec query failed for " + info.getName() + ": " + e);
            }
        }
        return false;
    }

    static boolean canDecodeHevc(int width, int height, int fps) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase("video/hevc")) {
                        continue;
                    }
                    MediaCodecInfo.VideoCapabilities video =
                            info.getCapabilitiesForType(type).getVideoCapabilities();
                    if (video != null && video.areSizeAndRateSupported(width, height, fps)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // Some devices throw from codec queries; treat that as "cannot".
            LimeLog.warning("Codec query failed: " + e);
        }
        return false;
    }

    // Settings

    /**
     * On Moonlight's settings screen: when StrmQt sends the stream, the
     * settings it sets would be written over at the next launch, so they are
     * shown greyed out with where to change them (StrmQt, Settings ›
     * StrmLight), and so is the preset row.
     */
    public static void markSetInStrmQt(PreferenceFragment fragment) {
        if (!new StrmQtPrefs(fragment.getActivity()).streamFromStrmQt()) {
            return;
        }
        for (String key : STREAM_KEYS) {
            Preference pref = fragment.findPreference(key);
            if (pref == null) {
                // Upstream removes some (HDR on a screen without it).
                continue;
            }
            pref.setEnabled(false);
            // A list shows its value in place of %s.
            pref.setSummary(fragment.getString(pref instanceof ListPreference
                    ? R.string.strmqt_pref_set_in_strmqt_value : R.string.strmqt_pref_set_in_strmqt));
        }
        Preference preset = fragment.findPreference(KEY_PRESET_ROW);
        if (preset != null) {
            preset.setEnabled(false);
            preset.setSummary(R.string.strmqt_pref_set_in_strmqt);
        }
    }
}
