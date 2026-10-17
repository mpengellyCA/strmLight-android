package com.limelight.strmqt;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.preference.PreferenceManager;

import com.limelight.LimeLog;

/**
 * Stream settings for watching StrmQt rather than playing a game: 4K at
 * 30 fps in HEVC, paced for smooth video. Written once into Moonlight's own
 * settings, where every value stays the user's to change.
 */
public final class StreamPreset {
    private static final int BITRATE_4K_KBPS = 40000;
    private static final int BITRATE_1080P_KBPS = 15000;

    private StreamPreset() {}

    /** @return true when the device decodes 4K HEVC and the preset is 4K; false for the 1080p fallback */
    public static boolean apply(Context context) {
        boolean uhd = canDecodeHevc(3840, 2160, 30);
        // Keys and values as in res/xml/preferences.xml.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("list_resolution", uhd ? "3840x2160" : "1920x1080")
                .putString("list_fps", "30")
                .putInt("seekbar_bitrate_kbps", uhd ? BITRATE_4K_KBPS : BITRATE_1080P_KBPS)
                .putString("video_format", "forceh265")
                .putString("frame_pacing", "balanced")
                .apply();
        new StrmQtPrefs(context).setPresetApplied();
        return uhd;
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
}
