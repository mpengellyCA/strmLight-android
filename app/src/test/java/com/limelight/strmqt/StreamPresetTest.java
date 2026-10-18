package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Moonlight's settings for the stream StrmQt sends, and stepping down to what
 * the device decodes. Pure: the device is a stand-in.
 */
public class StreamPresetTest {
    /** A device that decodes the listed codecs up to a size and rate. */
    private static StreamPreset.Decoders decodes(final int maxHeight, final int maxFps, String... codecs) {
        final Set<String> set = new HashSet<>(Arrays.asList(codecs));
        return (codec, width, height, fps) -> set.contains(codec) && height <= maxHeight && fps <= maxFps;
    }

    private static final StreamPreset.Decoders EVERYTHING = decodes(2160, 60, "hevc", "av1", "h264");
    private static final StreamPreset.Decoders NOTHING = (codec, width, height, fps) -> false;

    /** Moonlight's settings before: 720p, 60 fps, 10 Mbps. */
    private static final HostStream CURRENT = new HostStream(1280, 720, 60, 10000, null, null);

    private static HostStream stream(Integer w, Integer h, Integer fps, Integer kbps, String codec, String audio) {
        return new HostStream(w, h, fps, kbps, codec, audio);
    }

    private static Map<String, Object> expected(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    public void everythingStrmQtSetsIsWritten() {
        Map<String, Object> prefs = StreamPreset.prefsFor(
                stream(3840, 2160, 30, 40000, "auto", "2"), CURRENT, EVERYTHING);
        assertEquals(expected(
                "list_resolution", "3840x2160",
                "list_fps", "30",
                "seekbar_bitrate_kbps", 40000,
                "video_format", "auto",
                "list_audio_config", "2",
                "checkbox_enable_hdr", false,
                "frame_pacing", "balanced"), prefs);
    }

    @Test
    public void codecsAndSoundMapToMoonlightsValues() {
        assertEquals("forceh265", StreamPreset.prefsFor(stream(null, null, null, null, "hevc", null),
                CURRENT, EVERYTHING).get("video_format"));
        assertEquals("forceav1", StreamPreset.prefsFor(stream(null, null, null, null, "av1", null),
                CURRENT, EVERYTHING).get("video_format"));
        assertEquals("neverh265", StreamPreset.prefsFor(stream(null, null, null, null, "h264", null),
                CURRENT, EVERYTHING).get("video_format"));
        assertEquals("auto", StreamPreset.prefsFor(stream(null, null, null, null, "auto", null),
                CURRENT, EVERYTHING).get("video_format"));
        assertEquals("51", StreamPreset.prefsFor(stream(null, null, null, null, null, "51"),
                CURRENT, EVERYTHING).get("list_audio_config"));
        assertEquals("71", StreamPreset.prefsFor(stream(null, null, null, null, null, "71"),
                CURRENT, EVERYTHING).get("list_audio_config"));
        assertEquals("60", StreamPreset.prefsFor(stream(1920, 1080, 60, null, null, null),
                CURRENT, EVERYTHING).get("list_fps"));
    }

    @Test
    public void whatStrmQtLeavesOutStaysAsItIs() {
        // Only HDR off and the pacing: KWin's screen is not HDR, video is paced.
        assertEquals(expected("checkbox_enable_hdr", false, "frame_pacing", "balanced"),
                StreamPreset.prefsFor(stream(null, null, null, null, null, null), CURRENT, EVERYTHING));
        assertEquals(expected("list_fps", "30", "checkbox_enable_hdr", false, "frame_pacing", "balanced"),
                StreamPreset.prefsFor(stream(null, null, 30, null, null, null), CURRENT, EVERYTHING));
        // Nothing known about Moonlight's settings either.
        assertEquals(expected("checkbox_enable_hdr", false, "frame_pacing", "balanced"),
                StreamPreset.prefsFor(stream(null, null, null, null, null, null), null, EVERYTHING));
    }

    @Test
    public void av1FallsBackToHevcThenH264() {
        assertEquals("forceh265", StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "av1", null),
                CURRENT, decodes(2160, 60, "hevc", "h264")).get("video_format"));
        Map<String, Object> h264 = StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "av1", null),
                CURRENT, decodes(2160, 60, "h264"));
        assertEquals("neverh265", h264.get("video_format"));
        assertEquals("the size stays when a codec decodes it", "3840x2160", h264.get("list_resolution"));
        assertEquals(40000, h264.get("seekbar_bitrate_kbps"));
    }

    @Test
    public void hevcFallsBackToH264() {
        assertEquals("neverh265", StreamPreset.prefsFor(stream(1920, 1080, 30, 15000, "hevc", null),
                CURRENT, decodes(2160, 60, "h264")).get("video_format"));
    }

    @Test
    public void aCodecIsKeptOverASize() {
        // 4K HEVC is gone, 1080p HEVC is not: a smaller size in HEVC, not 4K in H.264 ...
        // unless H.264 decodes 4K, which comes first.
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "hevc", null),
                CURRENT, (codec, w, h, fps) -> codec.equals("hevc") ? h <= 1080 : h <= 2160);
        assertEquals("3840x2160", prefs.get("list_resolution"));
        assertEquals("neverh265", prefs.get("video_format"));
    }

    @Test
    public void fourKFallsBackToWhatTheDeviceDecodes() {
        // A 1080p Fire TV Stick: 4K HEVC asked for.
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "hevc", "2"),
                CURRENT, decodes(1080, 60, "hevc", "h264"));
        assertEquals(expected(
                "list_resolution", "1920x1080",
                "list_fps", "30",
                "seekbar_bitrate_kbps", 15000,
                "video_format", "forceh265",
                "list_audio_config", "2",
                "checkbox_enable_hdr", false,
                "frame_pacing", "balanced"), prefs);
    }

    @Test
    public void theNextSmallerSizeComesFirst() {
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "auto", null),
                CURRENT, decodes(1440, 30, "h264"));
        assertEquals("2560x1440", prefs.get("list_resolution"));
        assertEquals("auto stays auto", "auto", prefs.get("video_format"));
        assertEquals(25000, prefs.get("seekbar_bitrate_kbps"));
    }

    @Test
    public void sixtyFallsBackToThirtyBeforeTheSize() {
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 60, 60000, "hevc", null),
                CURRENT, decodes(2160, 30, "hevc"));
        assertEquals("3840x2160", prefs.get("list_resolution"));
        assertEquals("30", prefs.get("list_fps"));
        assertEquals("capped at StrmQt's own choice for 4K30", 40000, prefs.get("seekbar_bitrate_kbps"));
        assertEquals("forceh265", prefs.get("video_format"));
    }

    @Test
    public void aSmallerBitrateIsNotRaised() {
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 30, 8000, "hevc", null),
                CURRENT, decodes(1080, 30, "hevc"));
        assertEquals("1920x1080", prefs.get("list_resolution"));
        assertEquals(8000, prefs.get("seekbar_bitrate_kbps"));
    }

    @Test
    public void aStepDownWithoutABitrateCapsMoonlightsOwn() {
        HostStream current = new HostStream(1920, 1080, 30, 80000, null, null);
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, null, null, "hevc", null),
                current, decodes(1080, 30, "hevc"));
        assertEquals("1920x1080", prefs.get("list_resolution"));
        assertEquals(15000, prefs.get("seekbar_bitrate_kbps"));
        assertFalse("the rate was not stepped down", prefs.containsKey("list_fps"));
    }

    @Test
    public void whatIsLeftOutIsCheckedAtMoonlightsSettings() {
        // No size sent, Moonlight has 4K, the device decodes 1080p: 1080p is written.
        HostStream current = new HostStream(3840, 2160, 30, 40000, null, null);
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(null, null, null, null, "hevc", null),
                current, decodes(1080, 60, "hevc"));
        assertEquals("1920x1080", prefs.get("list_resolution"));
        assertEquals(15000, prefs.get("seekbar_bitrate_kbps"));
    }

    @Test
    public void whenNothingDecodesMoonlightPicksAt1080pAtMost() {
        Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 30, 40000, "hevc", null),
                CURRENT, NOTHING);
        assertEquals("1920x1080", prefs.get("list_resolution"));
        assertEquals("auto", prefs.get("video_format"));
        assertEquals(15000, prefs.get("seekbar_bitrate_kbps"));
        Map<String, Object> small = StreamPreset.prefsFor(stream(1280, 720, 30, 5000, "h264", null),
                CURRENT, NOTHING);
        assertEquals("1280x720", small.get("list_resolution"));
        assertEquals(5000, small.get("seekbar_bitrate_kbps"));
    }

    @Test
    public void hdrIsAlwaysOffAndThePacingBalanced() {
        for (String codec : new String[]{null, "auto", "hevc", "av1", "h264"}) {
            Map<String, Object> prefs = StreamPreset.prefsFor(stream(3840, 2160, 60, 40000, codec, "71"),
                    CURRENT, NOTHING);
            assertEquals(false, prefs.get("checkbox_enable_hdr"));
            assertEquals("balanced", prefs.get("frame_pacing"));
        }
    }

    @Test
    public void theBitrateCapIsStrmQts() {
        assertEquals(40000, StreamPreset.automaticBitrate(2160, 30));
        assertEquals(60000, StreamPreset.automaticBitrate(2160, 60));
        assertEquals(25000, StreamPreset.automaticBitrate(1440, 30));
        assertEquals(15000, StreamPreset.automaticBitrate(1080, 30));
        assertEquals(22500, StreamPreset.automaticBitrate(1080, 60));
        assertEquals(8000, StreamPreset.automaticBitrate(720, 30));
    }

    @Test
    public void mimeTypes() {
        assertEquals("video/hevc", StreamPreset.mimeType("hevc"));
        assertEquals("video/av01", StreamPreset.mimeType("av1"));
        assertEquals("video/avc", StreamPreset.mimeType("h264"));
    }
}
