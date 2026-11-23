package org.fogofwarlabs.sc4j.engine.resource.audio;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * Holds one decoded sound: signed {@code 16}-bit PCM samples, interleaved by channel, at a fixed sample rate.
 */
public final class SoundClip {
    private final int sampleRate;
    private final int channelCount;

    private final short @NotNull [] samples;

    /**
     * Creates a {@link SoundClip} instance.
     *
     * @param sampleRate   the number of sample frames per second, at least {@code 1}
     * @param channelCount the number of channels, at least {@code 1}
     * @param samples      the samples, interleaved by channel, a whole number of sample frames, copied
     * @throws IllegalArgumentException if the rate or the channel count is below {@code 1}, or the samples hold no whole number of sample frames
     */
    public SoundClip(int sampleRate, int channelCount, short @NotNull [] samples) {
        if (sampleRate < 1 || channelCount < 1) {
            throw Exceptions.newIllegalArgumentException("A sound has a positive rate and channel count: {0} Hz, {1} channels",
                                                         sampleRate, channelCount);
        }

        if (samples.length % channelCount != 0) {
            throw Exceptions.newIllegalArgumentException("{0} samples hold no whole number of {1}-channel frames.", samples.length, channelCount);
        }

        this.sampleRate   = sampleRate;
        this.channelCount = channelCount;

        this.samples = samples.clone();
    }

    /**
     * @return the number of sample frames per second
     */
    public int getSampleRate() {
        return this.sampleRate;
    }

    /**
     * @return the number of channels
     */
    public int getChannelCount() {
        return this.channelCount;
    }

    /**
     * @return the number of sample frames: the samples of one point in time, one per channel
     */
    public int getFrameCount() {
        return this.samples.length / this.channelCount;
    }

    /**
     * Returns one sample.
     *
     * @param index the sample index in the interleaved samples
     * @return the signed {@code 16}-bit sample
     * @throws IndexOutOfBoundsException if the index names no sample
     */
    public short getSample(int index) {
        return this.samples[index];
    }

    /**
     * Copies a run of the interleaved samples.
     *
     * @param offset            the index of the first sample
     * @param destination       receives the samples
     * @param destinationOffset the index of the first sample in the destination
     * @param length            the number of samples
     * @throws IndexOutOfBoundsException if the run is outside the clip or the destination
     */
    public void copySamples(int offset, short @NotNull [] destination, int destinationOffset, int length) {
        System.arraycopy(this.samples, offset, destination, destinationOffset, length);
    }
}
