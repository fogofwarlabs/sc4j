package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the chat notification sound: two short rising sine notes under decaying envelopes, synthesized once as 16-bit mono PCM and played on
 * a thread of its own through the default audio output. A sound that still plays is not started again; without an audio output nothing plays.
 */
public final class ChatTone {
    /**
     * The sample rate of the sound.
     */
    static final float SAMPLE_RATE = 44_100f;
    /**
     * The length of the first note, in seconds.
     */
    static final double FIRST_NOTE_SECONDS = 0.09;
    /**
     * The length of the second note, in seconds.
     */
    static final double SECOND_NOTE_SECONDS = 0.18;

    private static final @NotNull Logger LOGGER = Logger.of(ChatTone.class);
    private static final @NotNull String THREAD_NAME = "launcher-chat-tone";
    private static final int SAMPLE_BITS = 16;
    private static final int SAMPLE_BYTES = 2;
    private static final int CHANNELS = 1;
    private static final int BYTE_BITS = 8;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The frequency of the first note, A5.
     */
    private static final double FIRST_FREQUENCY = 880.0;
    /**
     * The frequency of the second note, E6.
     */
    private static final double SECOND_FREQUENCY = 1318.51;
    /**
     * The largest sample value of the notes, a quiet level below the 16-bit limit.
     */
    private static final double PEAK = 0.22 * Short.MAX_VALUE;
    /**
     * How fast a note fades, per second.
     */
    private static final double DECAY_PER_SECOND = 16.0;
    /**
     * The length of the fade in and out that keeps a note free of clicks, in seconds.
     */
    private static final double FADE_SECONDS = 0.004;
    private static final double FULL_CYCLE = 2 * Math.PI;

    /**
     * Creates the samples of the sound: 16-bit signed little-endian mono PCM at the sample rate {@link #SAMPLE_RATE}.
     *
     * @return the created samples
     */
    static byte @NotNull [] createSamples() {
        int firstCount = (int) (SAMPLE_RATE * FIRST_NOTE_SECONDS);
        int secondCount = (int) (SAMPLE_RATE * SECOND_NOTE_SECONDS);
        byte[] samples = new byte[(firstCount + secondCount) * SAMPLE_BYTES];

        writeNote(samples, 0, firstCount, FIRST_FREQUENCY);
        writeNote(samples, firstCount, secondCount, SECOND_FREQUENCY);

        return samples;
    }

    private static void writeNote(byte @NotNull [] samples, int start, int count, double frequency) {
        int fadeCount = (int) (SAMPLE_RATE * FADE_SECONDS);

        for (int index = 0; index < count; index++) {
            double seconds = index / (double) SAMPLE_RATE;
            double fade = Math.min(1.0, Math.min(index, count - 1 - index) / (double) fadeCount);
            double level = PEAK * fade * Math.exp(-DECAY_PER_SECOND * seconds);
            int value = (int) Math.round(level * Math.sin(FULL_CYCLE * frequency * seconds));
            int offset = (start + index) * SAMPLE_BYTES;

            samples[offset]     = (byte) (value & BYTE_MASK);
            samples[offset + 1] = (byte) ((value >> BYTE_BITS) & BYTE_MASK);
        }
    }


    private final byte @NotNull [] samples;
    private final @NotNull AtomicBoolean playing;

    /**
     * Creates a {@link ChatTone} instance with the synthesized sound.
     */
    public ChatTone() {
        this.samples = createSamples();
        this.playing = new AtomicBoolean(false);
    }

    /**
     * Plays the sound on a thread of its own and returns at once; does nothing while the sound still plays.
     */
    public void play() {
        if (!this.playing.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                this.writeSamples();
            } finally {
                this.playing.set(false);
            }
        }, THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    private void writeSamples() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);

        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(this.samples, 0, this.samples.length);
            line.drain();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException exception) {
            LOGGER.debug("The chat sound cannot play: {0}", exception.getMessage());
        }
    }
}
