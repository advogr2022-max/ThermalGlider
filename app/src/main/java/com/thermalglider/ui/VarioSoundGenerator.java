package com.thermalglider.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * VarioSoundGenerator — тональный PCM-генератор звука варио.
 * AudioTrack, 44100 Гц, 16-bit mono.
 *
 * Раздел 21.3 ТЗ.
 */
public class VarioSoundGenerator {

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 2048;

    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private float lastFreq = 0;
    private float lastAmp = 0;

    public VarioSoundGenerator() {
        int minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(BUFFER_SIZE, minBufSize);

        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .build();
    }

    /** Обновление звука (вызов из FlightManager.tick) */
    public void update(float varioMs, long nowMs) {
        float frequency;
        float amplitude;

        if (varioMs > -0.3f) {
            // Climb or weak sink
            if (varioMs < 0) {
                frequency = 400;  // нейтральная
                amplitude = 0.05f;
            } else {
                frequency = 400 + varioMs * 300;  // 400-3400 Гц
                amplitude = Math.min(0.05f + varioMs * 0.07f, 0.8f);
            }
        } else {
            // Strong sink — прерывистый
            frequency = 200;
            double phase = (nowMs / 1000.0 * 0.5) % 1.0;
            if (phase < 0.4) {
                amplitude = Math.min(0.1f + Math.abs(varioMs) * 0.05f, 0.4f);
            } else {
                amplitude = 0;
            }
        }

        if (frequency != lastFreq || Math.abs(amplitude - lastAmp) > 0.01f) {
            generateAndPlay(frequency, amplitude);
            lastFreq = frequency;
            lastAmp = amplitude;
        }
    }

    private void generateAndPlay(float freqHz, float amplitude) {
        if (!isPlaying) return;
        if (amplitude <= 0) return;

        int nSamples = SAMPLE_RATE * 100 / 1000; // 100ms буфер
        byte[] buffer = new byte[nSamples * 2];

        for (int i = 0; i < nSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            short sample = (short) (amplitude * 32767 * Math.sin(2 * Math.PI * freqHz * t));
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.write(buffer, 0, buffer.length);
            } catch (Exception ignored) {}
        }
    }

    public void start() {
        if (audioTrack != null) {
            audioTrack.play();
            isPlaying = true;
        }
    }

    public void stop() {
        isPlaying = false;
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.flush();
            } catch (Exception ignored) {}
        }
    }

    public boolean isPlaying() { return isPlaying; }

    public void release() {
        stop();
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
    }
}
