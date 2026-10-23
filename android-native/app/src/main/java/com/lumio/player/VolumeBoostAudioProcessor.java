package com.lumio.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Optional PCM gain stage. Gain is deliberately limited to 2x and clips safely to the PCM range.
 * Encoded passthrough formats are not processed by audio processors.
 */
final class VolumeBoostAudioProcessor extends BaseAudioProcessor {
    private volatile float gain = 1.0f;
    private int encoding = C.ENCODING_INVALID;

    void setGain(float factor) {
        gain = Math.max(1.0f, Math.min(2.0f, factor));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        encoding = inputAudioFormat.encoding;
        if (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) {
            return inputAudioFormat;
        }
        return AudioFormat.NOT_SET;
    }

    @Override
    public boolean isActive() {
        return super.isActive()
                && (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) return;
        float multiplier = gain;
        ByteBuffer output = replaceOutputBuffer(inputBuffer.remaining());
        if (multiplier == 1.0f) {
            output.put(inputBuffer);
        } else if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                int sample = inputBuffer.getShort();
                int amplified = Math.round(sample * multiplier);
                amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
                output.putShort((short) amplified);
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                float sample = inputBuffer.getFloat();
                float amplified = sample * multiplier;
                output.putFloat(Math.max(-1.0f, Math.min(1.0f, amplified)));
            }
        } else {
            output.put(inputBuffer);
        }
        output.flip();
    }

    @Override
    protected void onFlush(AudioProcessor.StreamMetadata streamMetadata) {
        // Gain is configured externally and must persist across seeks and track changes.
    }

    @Override
    protected void onReset() {
        encoding = C.ENCODING_INVALID;
    }
}
