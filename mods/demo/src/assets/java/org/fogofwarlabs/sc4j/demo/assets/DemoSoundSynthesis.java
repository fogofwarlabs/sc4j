package org.fogofwarlabs.sc4j.demo.assets;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Synthesizes the demo's sound effects at build time as 16-bit mono PCM WAV files {@code sounds/<id>.wav} of its pack, and its music
 * ({@link DemoMusicSynthesis}): simple sine, triangle and noise voices under decaying envelopes. The noise comes from a fixed linear congruential
 * sequence, so every run writes the same bytes. Every recipe value (length, frequency, envelope rate, level) is a named constant of its sound.
 */
public final class DemoSoundSynthesis {
    /**
     * The sample rate of every sound.
     */
    static final int SAMPLE_RATE = 22050;
    /**
     * The largest sample value the voices reach, below the 16-bit limit.
     */
    private static final double PEAK = 0.8 * Short.MAX_VALUE;
    /**
     * The length of the fade in and out that keeps a sound free of clicks, in seconds.
     */
    private static final double FADE_SECONDS = 0.004;
    /**
     * The number of channels of every sound: mono.
     */
    private static final int CHANNELS = 1;
    /**
     * The multiplier of the noise sequence.
     */
    static final long NOISE_MULTIPLIER = 6364136223846793005L;
    /**
     * The increment of the noise sequence.
     */
    static final long NOISE_INCREMENT = 1442695040888963407L;
    /**
     * The shift that takes the top bits of a noise state as one value.
     */
    private static final int NOISE_SHIFT = 40;
    /**
     * The number of distinct noise values.
     */
    private static final double NOISE_RANGE = 1 << (Long.SIZE - NOISE_SHIFT);
    /**
     * The first state of the noise sequence.
     */
    private static final long NOISE_SEED = 0x5C4A_D3E0L;
    /**
     * The angle of one full cycle of a voice.
     */
    private static final double FULL_CYCLE = 2 * Math.PI;

    /**
     * The swing of a weapon: a short rising swish of filtered noise.
     */
    private static final @NotNull String ATTACK = "attack";
    /**
     * A weapon striking: a falling low thump with a click of noise.
     */
    private static final @NotNull String HIT = "hit";
    /**
     * A unit falling: a slow falling tone with a tremble.
     */
    private static final @NotNull String DEATH = "death";
    /**
     * A building finished: three rising bell notes.
     */
    private static final @NotNull String CONSTRUCTION_DONE = "construction-done";
    /**
     * A unit trained: two notes of a soft horn.
     */
    private static final @NotNull String UNIT_READY = "unit-ready";
    /**
     * Own units under attack: three pulses of a two-tone alarm.
     */
    private static final @NotNull String UNDER_ATTACK = "under-attack";
    /**
     * A HUD button pressed: a short soft tick.
     */
    private static final @NotNull String CLICK = "click";
    /**
     * An action refused: two short low falling notes.
     */
    private static final @NotNull String REFUSED = "refused";
    /**
     * A unit selected: two quick soft wooden notes.
     */
    private static final @NotNull String SELECT = "select";
    /**
     * A building selected: a low wooden knock.
     */
    private static final @NotNull String SELECT_BUILDING = "select-building";
    /**
     * A menu button under the pointer: a short high tick, played quietly.
     */
    private static final @NotNull String HOVER = "hover";
    /**
     * A popup opened: a soft rising sweep of filtered noise over a low wooden tone.
     */
    private static final @NotNull String OPEN = "open";

    private static final double ATTACK_SECONDS = 0.2;
    private static final double ATTACK_START_CUTOFF = 0.05;
    private static final double ATTACK_CUTOFF_RISE = 0.5;
    private static final double ATTACK_LEVEL = 2.2;

    private static final double HIT_SECONDS = 0.16;
    private static final long HIT_NOISE_SEED_OFFSET = 1;
    private static final double HIT_LOW_FREQUENCY = 60;
    private static final double HIT_FREQUENCY_DROP = 90;
    private static final double HIT_FREQUENCY_DECAY = 30;
    private static final double HIT_TONE_DECAY = 22;
    private static final double HIT_CLICK_LEVEL = 0.6;
    private static final double HIT_CLICK_DECAY = 90;

    private static final double DEATH_SECONDS = 0.6;
    private static final double DEATH_START_FREQUENCY = 330;
    private static final double DEATH_FREQUENCY_FALL = 220;
    private static final double DEATH_TREMBLE_DEPTH = 0.03;
    private static final double DEATH_TREMBLE_FREQUENCY = 9;
    private static final double DEATH_LEVEL = 0.7;

    private static final double @NotNull [] CHIME_NOTES = {523.25, 659.25, 783.99};
    private static final double CHIME_NOTE_SECONDS = 0.14;
    private static final double CHIME_SECONDS = 0.95;
    private static final double CHIME_SECOND_HARMONIC = 0.3;
    private static final double CHIME_THIRD_HARMONIC = 0.1;
    private static final double CHIME_LEVEL = 0.35;
    private static final double CHIME_DECAY = 4;

    private static final double @NotNull [] HORN_NOTES = {392.0, 523.25};
    private static final double HORN_NOTE_SECONDS = 0.24;
    private static final double HORN_ATTACK_SECONDS = 0.03;
    private static final double HORN_DECAY = 3;
    private static final double HORN_SECOND_HARMONIC = 0.45;
    private static final double HORN_THIRD_HARMONIC = 0.25;
    private static final double HORN_LEVEL = 0.5;

    private static final double ALARM_PULSE_SECONDS = 0.3;
    private static final int ALARM_PULSES = 3;
    private static final double ALARM_HIGH_FREQUENCY = 880;
    private static final double ALARM_LOW_FREQUENCY = 660;
    private static final double ALARM_LEVEL = 0.6;
    private static final double ALARM_TONE_SHARE = 0.9;
    private static final double ALARM_TAIL_LEVEL = 0.2;

    private static final double CLICK_SECONDS = 0.12;
    private static final double CLICK_FREQUENCY = 1800;
    private static final double CLICK_DECAY = 70;
    private static final double CLICK_NOISE_LEVEL = 0.25;
    private static final long CLICK_NOISE_SEED_OFFSET = 2;
    private static final double CLICK_LEVEL = 0.45;

    private static final double @NotNull [] REFUSED_NOTES = {311.13, 233.08};
    private static final double REFUSED_NOTE_SECONDS = 0.09;
    private static final double REFUSED_DECAY = 18;
    private static final double REFUSED_LEVEL = 0.45;

    private static final double @NotNull [] SELECT_NOTES = {659.25, 880.0};
    private static final double SELECT_NOTE_SECONDS = 0.06;
    private static final double SELECT_TAIL_SECONDS = 0.1;
    private static final double SELECT_DECAY = 30;
    private static final double SELECT_SECOND_HARMONIC = 0.3;
    private static final double SELECT_LEVEL = 0.4;

    private static final double KNOCK_SECONDS = 0.2;
    private static final double KNOCK_FREQUENCY = 170;
    private static final double KNOCK_DECAY = 28;
    private static final double KNOCK_OVERTONE = 2.7;
    private static final double KNOCK_OVERTONE_LEVEL = 0.35;
    private static final double KNOCK_OVERTONE_DECAY = 60;
    private static final double KNOCK_NOISE_LEVEL = 0.3;
    private static final double KNOCK_NOISE_DECAY = 120;
    private static final long KNOCK_NOISE_SEED_OFFSET = 3;
    private static final double KNOCK_LEVEL = 0.7;

    private static final double HOVER_SECONDS = 0.12;
    private static final double HOVER_FREQUENCY = 2600;
    private static final double HOVER_DECAY = 60;
    private static final double HOVER_LEVEL = 0.45;

    private static final double OPEN_SECONDS = 0.22;
    private static final double OPEN_START_CUTOFF = 0.02;
    private static final double OPEN_CUTOFF_RISE = 0.25;
    private static final double OPEN_NOISE_LEVEL = 0.9;
    private static final double OPEN_TONE_FREQUENCY = 220;
    private static final double OPEN_TONE_LEVEL = 0.35;
    private static final double OPEN_TONE_DECAY = 18;
    private static final long OPEN_NOISE_SEED_OFFSET = 4;
    private static final double OPEN_LEVEL = 0.75;

    private static final int SECOND_HARMONIC = 2;
    private static final int THIRD_HARMONIC = 3;

    /**
     * Writes every sound of the demo.
     *
     * @param arguments the output pack folder
     * @throws IOException if a file cannot be written
     */
    public static void main(@NotNull String @NotNull [] arguments) throws IOException {
        if (arguments.length != 1) {
            throw Exceptions.newIllegalArgumentException("Usage: DemoSoundSynthesis <output pack folder>");
        }

        Path folder = Path.of(arguments[0]).resolve("sounds");

        Files.createDirectories(folder);

        for (String id : new String[]{ATTACK, HIT, DEATH, CONSTRUCTION_DONE, UNIT_READY, UNDER_ATTACK, CLICK, REFUSED, SELECT, SELECT_BUILDING,
                                      HOVER, OPEN}) {
            writeWave(folder.resolve(id + ".wav"), synthesize(id), true);
        }

        DemoMusicSynthesis.writePieces(Path.of(arguments[0]));
    }

    private static double @NotNull [] synthesize(@NotNull String id) {
        return switch (id) {
            case ATTACK -> synthesizeAttack();
            case HIT -> synthesizeHit();
            case DEATH -> synthesizeDeath();
            case CONSTRUCTION_DONE -> synthesizeChime();
            case UNIT_READY -> synthesizeHorn();
            case UNDER_ATTACK -> synthesizeAlarm();
            case CLICK -> synthesizeClick();
            case REFUSED -> synthesizeRefused();
            case SELECT -> synthesizeSelect();
            case SELECT_BUILDING -> synthesizeKnock();
            case HOVER -> synthesizeHover();
            case OPEN -> synthesizeOpen();
            default -> throw Exceptions.newIllegalArgumentException("Unknown sound {0}.", id);
        };
    }

    private static double @NotNull [] synthesizeAttack() {
        double[] samples = new double[(int) (SAMPLE_RATE * ATTACK_SECONDS)];
        long noise = NOISE_SEED;
        double low = 0;

        for (int index = 0; index < samples.length; index++) {
            double time = (double) index / samples.length;
            double cutoff = ATTACK_START_CUTOFF + ATTACK_CUTOFF_RISE * time;

            noise          = NOISE_MULTIPLIER * noise + NOISE_INCREMENT;
            low += cutoff * (toNoise(noise) - low);
            samples[index] = low * ATTACK_LEVEL * Math.sin(Math.PI * time);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeHit() {
        double[] samples = new double[(int) (SAMPLE_RATE * HIT_SECONDS)];
        long noise = NOISE_SEED + HIT_NOISE_SEED_OFFSET;
        double phase = 0;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;
            double frequency = HIT_LOW_FREQUENCY + HIT_FREQUENCY_DROP * Math.exp(-seconds * HIT_FREQUENCY_DECAY);

            noise          = NOISE_MULTIPLIER * noise + NOISE_INCREMENT;
            phase += FULL_CYCLE * frequency / SAMPLE_RATE;
            samples[index] = Math.sin(phase) * Math.exp(-seconds * HIT_TONE_DECAY) +
                             toNoise(noise) * HIT_CLICK_LEVEL * Math.exp(-seconds * HIT_CLICK_DECAY);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeDeath() {
        double[] samples = new double[(int) (SAMPLE_RATE * DEATH_SECONDS)];
        double phase = 0;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;
            double time = (double) index / samples.length;
            double frequency = (DEATH_START_FREQUENCY - DEATH_FREQUENCY_FALL * time) *
                               (1 + DEATH_TREMBLE_DEPTH * Math.sin(FULL_CYCLE * DEATH_TREMBLE_FREQUENCY * seconds));

            phase += FULL_CYCLE * frequency / SAMPLE_RATE;
            samples[index] = triangle(phase) * DEATH_LEVEL * (1 - time);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeChime() {
        double[] samples = new double[(int) (SAMPLE_RATE * CHIME_SECONDS)];

        for (int note = 0; note < CHIME_NOTES.length; note++) {
            int start = (int) (SAMPLE_RATE * CHIME_NOTE_SECONDS * note);

            for (int index = start; index < samples.length; index++) {
                double seconds = (double) (index - start) / SAMPLE_RATE;
                double angle = FULL_CYCLE * CHIME_NOTES[note] * seconds;

                samples[index] += (Math.sin(angle) + CHIME_SECOND_HARMONIC * Math.sin(SECOND_HARMONIC * angle) +
                                   CHIME_THIRD_HARMONIC * Math.sin(THIRD_HARMONIC * angle)) * CHIME_LEVEL * Math.exp(-seconds * CHIME_DECAY);
            }
        }

        return samples;
    }

    private static double @NotNull [] synthesizeHorn() {
        double[] samples = new double[(int) (SAMPLE_RATE * HORN_NOTE_SECONDS * HORN_NOTES.length)];

        for (int index = 0; index < samples.length; index++) {
            int note = Math.min(index / (int) (SAMPLE_RATE * HORN_NOTE_SECONDS), HORN_NOTES.length - 1);
            double seconds = (double) index / SAMPLE_RATE - HORN_NOTE_SECONDS * note;
            double angle = FULL_CYCLE * HORN_NOTES[note] * seconds;
            double envelope = Math.min(1, seconds / HORN_ATTACK_SECONDS) * Math.exp(-seconds * HORN_DECAY);

            samples[index] = (Math.sin(angle) + HORN_SECOND_HARMONIC * Math.sin(SECOND_HARMONIC * angle) +
                              HORN_THIRD_HARMONIC * Math.sin(THIRD_HARMONIC * angle)) * HORN_LEVEL * envelope;
        }

        return samples;
    }

    private static double @NotNull [] synthesizeAlarm() {
        double[] samples = new double[(int) (SAMPLE_RATE * ALARM_PULSE_SECONDS * ALARM_PULSES)];
        double phase = 0;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;
            double inPulse = seconds % ALARM_PULSE_SECONDS;
            double frequency = inPulse < ALARM_PULSE_SECONDS / 2 ? ALARM_HIGH_FREQUENCY : ALARM_LOW_FREQUENCY;

            phase += FULL_CYCLE * frequency / SAMPLE_RATE;
            samples[index] = triangle(phase) * ALARM_LEVEL * (inPulse < ALARM_PULSE_SECONDS * ALARM_TONE_SHARE ? 1 : ALARM_TAIL_LEVEL);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeClick() {
        double[] samples = new double[(int) (SAMPLE_RATE * CLICK_SECONDS)];
        long noise = NOISE_SEED + CLICK_NOISE_SEED_OFFSET;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;

            noise          = NOISE_MULTIPLIER * noise + NOISE_INCREMENT;
            samples[index] = (Math.sin(FULL_CYCLE * CLICK_FREQUENCY * seconds) + toNoise(noise) * CLICK_NOISE_LEVEL) * CLICK_LEVEL *
                             Math.exp(-seconds * CLICK_DECAY);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeRefused() {
        double[] samples = new double[(int) (SAMPLE_RATE * REFUSED_NOTE_SECONDS * REFUSED_NOTES.length)];
        double phase = 0;

        for (int index = 0; index < samples.length; index++) {
            int note = Math.min(index / (int) (SAMPLE_RATE * REFUSED_NOTE_SECONDS), REFUSED_NOTES.length - 1);
            double seconds = (double) index / SAMPLE_RATE - REFUSED_NOTE_SECONDS * note;

            phase += FULL_CYCLE * REFUSED_NOTES[note] / SAMPLE_RATE;
            samples[index] = triangle(phase) * REFUSED_LEVEL * Math.exp(-seconds * REFUSED_DECAY);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeSelect() {
        double[] samples = new double[(int) (SAMPLE_RATE * (SELECT_NOTE_SECONDS * SELECT_NOTES.length + SELECT_TAIL_SECONDS))];

        for (int note = 0; note < SELECT_NOTES.length; note++) {
            int start = (int) (SAMPLE_RATE * SELECT_NOTE_SECONDS * note);

            for (int index = start; index < samples.length; index++) {
                double seconds = (double) (index - start) / SAMPLE_RATE;
                double angle = FULL_CYCLE * SELECT_NOTES[note] * seconds;

                samples[index] += (Math.sin(angle) + SELECT_SECOND_HARMONIC * Math.sin(SECOND_HARMONIC * angle)) * SELECT_LEVEL *
                                  Math.exp(-seconds * SELECT_DECAY);
            }
        }

        return samples;
    }

    private static double @NotNull [] synthesizeKnock() {
        double[] samples = new double[(int) (SAMPLE_RATE * KNOCK_SECONDS)];
        long noise = NOISE_SEED + KNOCK_NOISE_SEED_OFFSET;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;
            double angle = FULL_CYCLE * KNOCK_FREQUENCY * seconds;

            noise          = NOISE_MULTIPLIER * noise + NOISE_INCREMENT;
            samples[index] = (Math.sin(angle) * Math.exp(-seconds * KNOCK_DECAY) +
                              Math.sin(KNOCK_OVERTONE * angle) * KNOCK_OVERTONE_LEVEL * Math.exp(-seconds * KNOCK_OVERTONE_DECAY) +
                              toNoise(noise) * KNOCK_NOISE_LEVEL * Math.exp(-seconds * KNOCK_NOISE_DECAY)) * KNOCK_LEVEL;
        }

        return samples;
    }

    private static double @NotNull [] synthesizeHover() {
        double[] samples = new double[(int) (SAMPLE_RATE * HOVER_SECONDS)];

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;

            samples[index] = Math.sin(FULL_CYCLE * HOVER_FREQUENCY * seconds) * HOVER_LEVEL * Math.exp(-seconds * HOVER_DECAY);
        }

        return samples;
    }

    private static double @NotNull [] synthesizeOpen() {
        double[] samples = new double[(int) (SAMPLE_RATE * OPEN_SECONDS)];
        long noise = NOISE_SEED + OPEN_NOISE_SEED_OFFSET;
        double low = 0;

        for (int index = 0; index < samples.length; index++) {
            double seconds = (double) index / SAMPLE_RATE;
            double time = (double) index / samples.length;
            double cutoff = OPEN_START_CUTOFF + OPEN_CUTOFF_RISE * time;

            noise          = NOISE_MULTIPLIER * noise + NOISE_INCREMENT;
            low += cutoff * (toNoise(noise) - low);
            samples[index] = (low * OPEN_NOISE_LEVEL * Math.sin(Math.PI * time) +
                              Math.sin(FULL_CYCLE * OPEN_TONE_FREQUENCY * seconds) * OPEN_TONE_LEVEL * Math.exp(-seconds * OPEN_TONE_DECAY)) *
                             OPEN_LEVEL;
        }

        return samples;
    }

    /**
     * Returns the noise value of a state of the noise sequence, from {@code -1} to {@code 1}.
     */
    static double toNoise(long state) {
        return (state >>> NOISE_SHIFT) / NOISE_RANGE * 2 - 1;
    }

    /**
     * Returns the triangle wave of a phase, from {@code -1} to {@code 1}.
     */
    private static double triangle(double phase) {
        double position = phase / FULL_CYCLE % 1;

        return 4 * Math.abs(position - 0.5) - 1;
    }

    /**
     * Writes samples at the sample rate of the demo's sounds as a 16-bit mono PCM WAV file, at {@link #PEAK} for a sample of {@code 1}.
     *
     * @param file    the file
     * @param samples the samples, from {@code -1} to {@code 1}
     * @param faded   whether the first and last milliseconds fade in and out, so a sound starts and ends without a click; a piece that repeats is not faded
     * @throws IOException if the file cannot be written
     */
    static void writeWave(@NotNull Path file, double @NotNull [] samples, boolean faded) throws IOException {
        int fade = (int) (SAMPLE_RATE * FADE_SECONDS);
        byte[] bytes = new byte[samples.length * Short.BYTES];

        for (int index = 0; index < samples.length; index++) {
            double gain = faded ? Math.min(1, Math.min(index, samples.length - 1 - index) / (double) fade) : 1;
            long value = Math.round(Math.clamp(samples[index], -1, 1) * gain * PEAK);

            bytes[index * Short.BYTES]     = (byte) value;
            bytes[index * Short.BYTES + 1] = (byte) (value >> Byte.SIZE);
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, Short.SIZE, CHANNELS, true, false);

        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(bytes), format, samples.length);
             OutputStream output = Files.newOutputStream(file)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
        }
    }

    private DemoSoundSynthesis() {
    }
}
