package org.fogofwarlabs.sc4j.engine.animation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Holds one flattened animation program: its instructions in position order and its labels. Several animation sets and
 * kinds may enter the same program at different positions, so code that animations share exists once.
 * <p>
 * After an instruction the program goes on at the next position, which is the following position unless the program has
 * a successor table: then the table names the next position of each position, so that instructions of different lengths
 * in a binary script format can keep their byte addresses as positions ({@link #getNextPosition}).
 * <p>
 * A program is checked when it is created: every label, every branch target ({@link BranchInstruction}) and every
 * successor lies inside the program (a successor may also be the program length, the end), and label names are unique. A
 * program has the index of its position in its library.
 */
public final class AnimationProgram {
    private final int index;
    private final @NotNull String name;

    private final @NotNull AnimationInstruction @NotNull [] instructions;
    private final @NotNull List<@NotNull AnimationLabel> labels;
    private final int @Nullable [] successors;

    /**
     * Creates an {@link AnimationProgram} instance whose instructions go on at the following position.
     *
     * @param index        the index of the program in its library, at least {@code 0}
     * @param name         the program name, for diagnostics
     * @param instructions the instructions in position order, at least one, copied
     * @param labels       the labels, copied
     * @throws IllegalArgumentException if the index is negative, there is no instruction, a label or branch target lies outside the program or a label name is used twice
     */
    public AnimationProgram(int index, @NotNull String name, @NotNull List<@NotNull AnimationInstruction> instructions,
                            @NotNull List<@NotNull AnimationLabel> labels) {
        this(index, name, instructions, labels, null);
    }

    /**
     * Creates an {@link AnimationProgram} instance with an optional successor table. The program is checked as the class description says,
     * successors included.
     *
     * @param index        the index of the program in its library, at least {@code 0}
     * @param name         the program name, for diagnostics
     * @param instructions the instructions in position order, at least one, copied
     * @param labels       the labels, copied
     * @param successors   the next position of each position, one per instruction, copied, or {@code null} for the following position
     * @throws IllegalArgumentException if the index is negative, there is no instruction, the successor table has another length than the program or a check fails
     */
    public AnimationProgram(int index, @NotNull String name, @NotNull List<@NotNull AnimationInstruction> instructions,
                            @NotNull List<@NotNull AnimationLabel> labels, int @Nullable [] successors) {
        if (index < 0 || instructions.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("The program {0} at index {1} needs instructions.", name, index);
        }

        this.index = index;
        this.name  = name;

        this.instructions = instructions.toArray(new AnimationInstruction[0]);
        this.labels       = List.copyOf(labels);
        this.successors   = successors == null ? null : successors.clone();

        if (this.successors != null) {
            if (this.successors.length != this.instructions.length) {
                throw Exceptions.newIllegalArgumentException("The successor table of program {0} has {1} entries for {2} instructions.",
                                                             name, this.successors.length, this.instructions.length);
            }

            for (int position = 0; position < this.successors.length; position++) {
                if (this.successors[position] < 0 || this.successors[position] > this.instructions.length) {
                    throw Exceptions.newIllegalArgumentException("The instruction at {0} of program {1} goes on outside the program: {2}",
                                                                 position, name, this.successors[position]);
                }
            }
        }

        for (int position = 0; position < this.instructions.length; position++) {
            if (this.instructions[position] instanceof BranchInstruction branch && !this.contains(branch.getTargetPosition())) {
                throw Exceptions.newIllegalArgumentException("The instruction at {0} of program {1} branches outside the program: {2}",
                                                             position, name, branch.getTargetPosition());
            }
        }

        for (int labelIndex = 0; labelIndex < this.labels.size(); labelIndex++) {
            AnimationLabel label = this.labels.get(labelIndex);

            if (!this.contains(label.getPosition())) {
                throw Exceptions.newIllegalArgumentException("The label {0} of program {1} lies outside the program.", label.getName(), name);
            }

            for (int earlier = 0; earlier < labelIndex; earlier++) {
                if (this.labels.get(earlier).getName().equals(label.getName())) {
                    throw Exceptions.newIllegalArgumentException("The label {0} of program {1} is defined twice.", label.getName(), name);
                }
            }
        }
    }

    /**
     * @return the index of the program in its library
     */
    public int getIndex() {
        return this.index;
    }

    /**
     * @return the program name, for diagnostics
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the number of instructions
     */
    public int getLength() {
        return this.instructions.length;
    }

    /**
     * Returns one instruction.
     *
     * @param position the position, {@code 0} up to {@link #getLength()} minus one
     * @return the instruction
     * @throws IndexOutOfBoundsException if the position is outside the program
     */
    public @NotNull AnimationInstruction get(int position) {
        return this.instructions[position];
    }

    /**
     * Returns the position the program goes on at after an instruction that neither jumps nor ends.
     *
     * @param position the position of the instruction, {@code 0} up to {@link #getLength()} minus one
     * @return the next position, {@code 0} up to {@link #getLength()}; the length is the end of the program
     * @throws IndexOutOfBoundsException if the position is outside the program
     */
    public int getNextPosition(int position) {
        int[] table = this.successors;

        if (table == null) {
            Objects.checkIndex(position, this.instructions.length);

            return position + 1;
        }

        return table[position];
    }

    /**
     * @return {@code true} if a successor table names the next positions, {@code false} if every instruction goes on at the following position
     */
    public boolean hasSuccessorTable() {
        return this.successors != null;
    }

    /**
     * @return the labels in their given order, unmodifiable
     */
    public @NotNull List<@NotNull AnimationLabel> getLabels() {
        return this.labels;
    }

    /**
     * Finds a label by name.
     *
     * @param labelName the label name
     * @return the label, or {@code null} if the program has none of the name
     */
    public @Nullable AnimationLabel findLabel(@NotNull String labelName) {
        for (AnimationLabel label : this.labels) {
            if (label.getName().equals(labelName)) {
                return label;
            }
        }

        return null;
    }

    /**
     * Checks whether a position lies inside the program.
     *
     * @param position the position
     * @return {@code true} if the position names an instruction, {@code false} otherwise
     */
    public boolean contains(int position) {
        return position >= 0 && position < this.instructions.length;
    }


    /**
     * @return the program name and index, for diagnostics
     */
    @Override
    public @NotNull String toString() {
        return this.name + " (" + this.index + ")";
    }
}
