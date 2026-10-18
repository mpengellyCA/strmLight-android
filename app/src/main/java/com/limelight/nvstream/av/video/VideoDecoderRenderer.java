package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeUs, long enqueueTimeUs);

    /**
     * The same, with the host's 90 kHz timestamp of the frame and when its
     * first packet arrived (System.nanoTime), for a renderer that schedules
     * it.
     */
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs, int rtpTimestamp, long receiveTimeNs) {
        return submitDecodeUnit(decodeUnitData, decodeUnitLength, decodeUnitType, frameNumber, frameType,
                frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs);
    }
    
    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
