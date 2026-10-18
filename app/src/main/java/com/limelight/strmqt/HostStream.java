package com.limelight.strmqt;

import java.util.Locale;
import java.util.Map;

/**
 * The stream's picture and sound as StrmQt sets them (Settings › StrmLight),
 * from the "stream" object of GET /api/strmlight/host:
 * {@code {"width":3840,"height":2160,"fps":30,"bitrateKbps":40000,"codec":"auto","audio":"2"}}.
 *
 * Read tolerantly: unknown fields are ignored, and a field that is missing or
 * makes no sense is null, so the setting it would have made stays as it is.
 * A resolution counts only as one of the four StrmQt offers.
 */
public final class HostStream {
    public static final String CODEC_AUTO = "auto";
    public static final String CODEC_HEVC = "hevc";
    public static final String CODEC_AV1 = "av1";
    public static final String CODEC_H264 = "h264";

    public static final String AUDIO_STEREO = "2";
    public static final String AUDIO_51 = "51";
    public static final String AUDIO_71 = "71";

    static final int MIN_BITRATE_KBPS = 1000;
    static final int MAX_BITRATE_KBPS = 150000;

    /** The resolutions StrmQt offers, largest first: width, height. */
    static final int[][] RESOLUTIONS = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};

    /** Both set or both null. */
    public final Integer width;
    public final Integer height;
    /** 30 or 60; null when not set. */
    public final Integer fps;
    public final Integer bitrateKbps;
    /** One of the CODEC_ constants; null when not set. */
    public final String codec;
    /** One of the AUDIO_ constants, as Moonlight's list_audio_config stores it; null when not set. */
    public final String audio;

    HostStream(Integer width, Integer height, Integer fps, Integer bitrateKbps, String codec, String audio) {
        boolean resolution = width != null && height != null;
        this.width = resolution ? width : null;
        this.height = resolution ? height : null;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.codec = codec;
        this.audio = audio;
    }

    /**
     * The "stream" of a StrmQt answer; null when there is none (a StrmQt from
     * before StrmQt set the stream), or the answer is not JSON.
     */
    public static HostStream fromAnswer(String body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> answer = StrmQtDiscovery.Json.parseObject(body.trim());
        return answer != null ? from(answer.get("stream")) : null;
    }

    /** @param stream the "stream" member; anything but an object gives null */
    @SuppressWarnings("unchecked")
    static HostStream from(Object stream) {
        if (!(stream instanceof Map)) {
            return null;
        }
        Map<String, Object> fields = (Map<String, Object>) stream;
        Integer width = integer(fields.get("width"));
        Integer height = integer(fields.get("height"));
        if (!offered(width, height)) {
            width = null;
            height = null;
        }
        Integer fps = integer(fields.get("fps"));
        if (fps != null && fps != 30 && fps != 60) {
            fps = null;
        }
        Integer bitrate = integer(fields.get("bitrateKbps"));
        if (bitrate != null && (bitrate < MIN_BITRATE_KBPS || bitrate > MAX_BITRATE_KBPS)) {
            bitrate = null;
        }
        String codec = word(fields.get("codec"));
        if (codec != null && !codec.equals(CODEC_AUTO) && !codec.equals(CODEC_HEVC)
                && !codec.equals(CODEC_AV1) && !codec.equals(CODEC_H264)) {
            codec = null;
        }
        String audio = word(fields.get("audio"));
        if (audio != null && !audio.equals(AUDIO_STEREO) && !audio.equals(AUDIO_51) && !audio.equals(AUDIO_71)) {
            audio = null;
        }
        return new HostStream(width, height, fps, bitrate, codec, audio);
    }

    /** Whether width x height is one of the resolutions StrmQt offers. */
    static boolean offered(Integer width, Integer height) {
        if (width == null || height == null) {
            return false;
        }
        for (int[] r : RESOLUTIONS) {
            if (r[0] == width && r[1] == height) {
                return true;
            }
        }
        return false;
    }

    /** A whole number, written as a number or as a string of digits; null for anything else. */
    private static Integer integer(Object value) {
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                return (int) d;
            }
            return null;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A string, trimmed and lowercase; a whole number as its digits (audio may come as 51). */
    private static String word(Object value) {
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase(Locale.ROOT);
            return s.isEmpty() ? null : s;
        }
        Integer n = integer(value);
        return n != null ? Integer.toString(n) : null;
    }

    /** "WxH" as Moonlight's list_resolution stores it; null when not set. */
    public String resolution() {
        return width != null ? width + "x" + height : null;
    }

    @Override
    public String toString() {
        return (resolution() != null ? resolution() : "-") + " " + (fps != null ? fps : "-") + "fps "
                + (bitrateKbps != null ? bitrateKbps : "-") + "kbps " + (codec != null ? codec : "-")
                + " audio " + (audio != null ? audio : "-");
    }
}
