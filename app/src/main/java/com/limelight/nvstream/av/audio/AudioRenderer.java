package com.limelight.nvstream.av.audio;

import com.limelight.nvstream.jni.MoonBridge;

public interface AudioRenderer {
    int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame);

    void start();

    void stop();
    
    void playDecodedAudio(short[] audioData);

    /**
     * The same, with the host's timestamp of the audio (ms) and when its packet
     * arrived (System.nanoTime; 0 for a lost packet's concealment), for a
     * renderer that schedules it.
     */
    default void playDecodedAudio(short[] audioData, int rtpTimestamp, long receiveTimeNs) {
        playDecodedAudio(audioData);
    }
    
    void cleanup();
}
