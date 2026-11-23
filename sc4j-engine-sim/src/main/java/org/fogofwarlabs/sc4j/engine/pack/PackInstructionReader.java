package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.engine.content.type.SoundType;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Gives a {@link PackInstructionType} one instruction object of a pack animation: the value of its keyword, its other
 * fields, and the names the pack resolves (labels of the same program, sounds, sprite sheets). Every failure is an
 * {@link IOException} that names the instruction.
 */
public final class PackInstructionReader {
    private final @NotNull JsonObjectReader instruction;
    private final @NotNull String keyword;

    private final @NotNull Map<@NotNull String, @NotNull Integer> labels;
    private final @NotNull List<@NotNull SoundType> soundTypes;
    private final @NotNull List<@NotNull ImageType> imageTypes;
    private final int frameLimit;

    /**
     * Creates a {@link PackInstructionReader} instance.
     *
     * @param instruction the instruction object
     * @param keyword     the keyword of the instruction
     * @param labels      the label positions of the program
     * @param soundTypes  the sound types of the pack in index order, named by their sound ids
     * @param imageTypes  the image types of the pack in index order, one per sprite sheet, named by their sprite sheet ids
     * @param frameLimit  the number of frame numbers the sprite sheet of the program has
     */
    PackInstructionReader(@NotNull JsonObjectReader instruction, @NotNull String keyword,
                          @NotNull Map<@NotNull String, @NotNull Integer> labels, @NotNull List<@NotNull SoundType> soundTypes,
                          @NotNull List<@NotNull ImageType> imageTypes, int frameLimit) {
        this.instruction = instruction;
        this.keyword     = keyword;

        this.labels     = labels;
        this.soundTypes = soundTypes;
        this.imageTypes = imageTypes;
        this.frameLimit = frameLimit;
    }

    /**
     * @return the location of the instruction, for diagnostics
     */
    public @NotNull String getLocation() {
        return this.instruction.getLocation();
    }

    /**
     * @return the number of frame numbers the sprite sheet of the program has: a frame number is {@code 0} up to this number minus one
     */
    public int getFrameLimit() {
        return this.frameLimit;
    }

    /**
     * @return the value of the keyword field as it was parsed: a {@link Long}, {@link Double}, {@link String}, {@link Boolean}, {@link List} or {@link Map}
     */
    public @Nullable Object getValue() {
        return this.instruction.get(this.keyword);
    }

    /**
     * @return the value of the keyword field as an integer
     * @throws IOException if the value is not an integer of the {@code int} range
     */
    public int getIntValue() throws IOException {
        return this.instruction.getInt(this.keyword);
    }

    /**
     * @return the value of the keyword field as a string
     * @throws IOException if the value is not a string
     */
    public @NotNull String getTextValue() throws IOException {
        return this.instruction.getText(this.keyword);
    }

    /**
     * Returns the value of the keyword field as an array of integers.
     *
     * @param length the number of integers the array must have
     * @return the integers
     * @throws IOException if the value is not an array of {@code length} integers
     */
    public int @NotNull [] getIntValues(int length) throws IOException {
        List<?> array = this.instruction.getArray(this.keyword);

        if (array.size() != length) {
            throw this.createException("expected {0} integers, found {1}", length, array.size());
        }

        int[] values = new int[length];

        for (int index = 0; index < length; index++) {
            values[index] = JsonObjectReader.toInt(array.get(index), this.instruction.getLocation(this.keyword) + "[" + index + "]");
        }

        return values;
    }

    /**
     * Checks that the value of the keyword field is {@code true}, for an instruction without operand such as
     * {@code {"end": true}}.
     *
     * @throws IOException if the value is not {@code true}
     */
    public void requireTrueValue() throws IOException {
        if (!Boolean.TRUE.equals(this.getValue())) {
            throw this.createException("expected true, found {0}", this.getValue());
        }
    }

    /**
     * Returns another integer field of the instruction that must be present.
     *
     * @param name the field name
     * @return the value
     * @throws IOException if the field is absent or not an integer
     */
    public int getInt(@NotNull String name) throws IOException {
        return this.instruction.getInt(name);
    }

    /**
     * Returns another integer field of the instruction.
     *
     * @param name         the field name
     * @param defaultValue the value of an absent field
     * @return the value
     * @throws IOException if the field is present but not an integer
     */
    public int getInt(@NotNull String name, int defaultValue) throws IOException {
        return this.instruction.getInt(name, defaultValue);
    }

    /**
     * Resolves a label of the program: the name of an animation of the sprite sheet or an inline label.
     *
     * @param label the label name
     * @return the position the label names
     * @throws IOException if the program has no such label
     */
    public int resolveLabel(@NotNull String label) throws IOException {
        Integer position = this.labels.get(label);

        if (position == null) {
            throw this.createException("no animation or label {0} in this sprite sheet", label);
        }

        return position;
    }

    /**
     * Resolves a sound id of the pack.
     *
     * @param soundId the sound id
     * @return the sound type of the sound
     * @throws IOException if the pack has no such sound
     */
    public @NotNull SoundType resolveSound(@NotNull String soundId) throws IOException {
        for (SoundType sound : this.soundTypes) {
            if (sound.getDebugName().equals(soundId)) {
                return sound;
            }
        }

        throw this.createException("no sound {0} in the pack", soundId);
    }

    /**
     * Resolves a sprite sheet id of the pack.
     *
     * @param spriteSheetId the sprite sheet id
     * @return the image type of the sprite sheet
     * @throws IOException if the pack has no such sprite sheet
     */
    public @NotNull ImageType resolveSpriteSheet(@NotNull String spriteSheetId) throws IOException {
        for (ImageType imageType : this.imageTypes) {
            if (imageType.getDebugName().equals(spriteSheetId)) {
                return imageType;
            }
        }

        throw this.createException("no sprite sheet {0} in the pack", spriteSheetId);
    }

    /**
     * Creates the exception of an invalid instruction, with its location.
     *
     * @param message   the message format with {@code {index}} placeholders
     * @param arguments the placeholder arguments
     * @return the created exception
     */
    public @NotNull IOException createException(@NotNull String message, @Nullable Object @NotNull ... arguments) {
        return Exceptions.newIOException("{0} ({1}): {2}", this.getLocation(), this.keyword, Exceptions.format(message, arguments));
    }
}
