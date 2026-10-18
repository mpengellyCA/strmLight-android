package com.limelight.strmqt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Reading the "stream" of StrmQt's GET /api/strmlight/host: what StrmQt
 * sets, read tolerantly, with nonsense left out field by field.
 */
public class HostStreamTest {
    private static HostStream stream(String json) {
        return HostStream.fromAnswer("{\"sunshineFingerprint\":\"ab\",\"stream\":" + json + "}");
    }

    @Test
    public void aCompleteStream() {
        HostStream s = HostStream.fromAnswer("{\"sunshineFingerprint\":\""
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"stream\":"
                + FakeStrmQt.STREAM + "}");
        assertEquals(Integer.valueOf(3840), s.width);
        assertEquals(Integer.valueOf(2160), s.height);
        assertEquals("3840x2160", s.resolution());
        assertEquals(Integer.valueOf(30), s.fps);
        assertEquals(Integer.valueOf(40000), s.bitrateKbps);
        assertEquals(HostStream.CODEC_AUTO, s.codec);
        assertEquals(HostStream.AUDIO_STEREO, s.audio);
    }

    @Test
    public void everyOfferedValue() {
        for (int[] r : HostStream.RESOLUTIONS) {
            assertEquals(r[0] + "x" + r[1],
                    stream("{\"width\":" + r[0] + ",\"height\":" + r[1] + "}").resolution());
        }
        assertEquals(Integer.valueOf(60), stream("{\"fps\":60}").fps);
        for (String codec : new String[]{"auto", "hevc", "av1", "h264"}) {
            assertEquals(codec, stream("{\"codec\":\"" + codec + "\"}").codec);
        }
        for (String audio : new String[]{"2", "51", "71"}) {
            assertEquals(audio, stream("{\"audio\":\"" + audio + "\"}").audio);
        }
        assertEquals(Integer.valueOf(1000), stream("{\"bitrateKbps\":1000}").bitrateKbps);
        assertEquals(Integer.valueOf(150000), stream("{\"bitrateKbps\":150000}").bitrateKbps);
    }

    @Test
    public void unknownFieldsAreIgnored() {
        HostStream s = stream("{\"width\":1920,\"height\":1080,\"hdr\":true,\"extra\":{\"a\":[1,2]},"
                + "\"list\":[{\"x\":1}],\"fps\":60}");
        assertEquals("1920x1080", s.resolution());
        assertEquals(Integer.valueOf(60), s.fps);
    }

    @Test
    public void missingFieldsAreNull() {
        HostStream s = stream("{}");
        assertNotNull(s);
        assertNull(s.width);
        assertNull(s.height);
        assertNull(s.resolution());
        assertNull(s.fps);
        assertNull(s.bitrateKbps);
        assertNull(s.codec);
        assertNull(s.audio);
    }

    @Test
    public void aResolutionStrmQtDoesNotOfferIsIgnored() {
        assertNull("not offered", stream("{\"width\":1366,\"height\":768}").resolution());
        assertNull("swapped", stream("{\"width\":2160,\"height\":3840}").resolution());
        assertNull("half of one", stream("{\"width\":3840}").resolution());
        assertNull("mismatched", stream("{\"width\":3840,\"height\":1080}").resolution());
        assertNull("zero", stream("{\"width\":0,\"height\":0}").resolution());
        // The rest still counts.
        assertEquals(Integer.valueOf(60), stream("{\"width\":1366,\"height\":768,\"fps\":60}").fps);
    }

    @Test
    public void nonsenseIsIgnoredFieldByField() {
        HostStream s = stream("{\"width\":3840,\"height\":2160,\"fps\":144,\"bitrateKbps\":999999,"
                + "\"codec\":\"vp9\",\"audio\":\"91\"}");
        assertEquals("3840x2160", s.resolution());
        assertNull(s.fps);
        assertNull(s.bitrateKbps);
        assertNull(s.codec);
        assertNull(s.audio);
        assertNull("too low", stream("{\"bitrateKbps\":999}").bitrateKbps);
        assertNull("negative", stream("{\"bitrateKbps\":-40000}").bitrateKbps);
        assertNull("not whole", stream("{\"fps\":29.97}").fps);
        assertNull("not a number", stream("{\"fps\":true}").fps);
        assertNull("null", stream("{\"codec\":null}").codec);
        assertNull("empty", stream("{\"codec\":\"\"}").codec);
        assertNull("an object", stream("{\"width\":{\"px\":3840},\"height\":2160}").resolution());
    }

    @Test
    public void writtenALittleDifferently() {
        assertEquals("a number as a string", Integer.valueOf(30), stream("{\"fps\":\"30\"}").fps);
        assertEquals("a whole double", Integer.valueOf(40000), stream("{\"bitrateKbps\":40000.0}").bitrateKbps);
        assertEquals("upper case", HostStream.CODEC_HEVC, stream("{\"codec\":\" HEVC \"}").codec);
        assertEquals("audio as a number", HostStream.AUDIO_51, stream("{\"audio\":51}").audio);
    }

    @Test
    public void noStreamIsNull() {
        assertNull("absent", HostStream.fromAnswer("{\"sunshineFingerprint\":\"ab\"}"));
        assertNull("null", stream("null"));
        assertNull("a string", stream("\"4K\""));
        assertNull("an array", stream("[3840,2160]"));
        assertNull("a number", stream("1"));
        assertNull("not JSON", HostStream.fromAnswer("<html>"));
        assertNull("cut short", HostStream.fromAnswer("{\"stream\":{\"width\":3840"));
        assertNull("empty", HostStream.fromAnswer(""));
        assertNull("nothing", HostStream.fromAnswer(null));
    }
}
