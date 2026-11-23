package org.fogofwarlabs.sc4j.engine.animation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds every animation of a mod: the kinds, the programs and the animation sets, each in index order (element
 * {@code n} has index {@code n}). The standard animation system uses set {@code n} for the image type of index {@code n};
 * a mod that animates its own image pool may index the sets by its own scheme, for example by script id.
 * <p>
 * The library checks when it is created that every set refers only to its kinds and programs. It is immutable; the
 * games of a session share it.
 */
public final class AnimationLibrary {
    private final @NotNull List<@NotNull AnimationKind> kinds;
    private final @NotNull List<@NotNull AnimationProgram> programs;
    private final @NotNull List<@NotNull AnimationSet> sets;

    /**
     * Creates an {@link AnimationLibrary} instance.
     *
     * @param kinds    the kinds in index order, copied
     * @param programs the programs in index order, copied
     * @param sets     the animation sets in index order, copied
     * @throws IllegalArgumentException if an element has another index than its position, a kind name is used twice, or a set refers to a kind or program of another library
     */
    public AnimationLibrary(@NotNull List<@NotNull AnimationKind> kinds, @NotNull List<@NotNull AnimationProgram> programs,
                            @NotNull List<@NotNull AnimationSet> sets) {
        this.kinds    = List.copyOf(kinds);
        this.programs = List.copyOf(programs);
        this.sets     = List.copyOf(sets);

        for (int index = 0; index < this.kinds.size(); index++) {
            AnimationKind kind = this.kinds.get(index);

            if (kind.getIndex() != index) {
                throw Exceptions.newIllegalArgumentException("The kind {0} is at position {1}.", kind, index);
            }

            for (int earlier = 0; earlier < index; earlier++) {
                if (this.kinds.get(earlier).getName().equals(kind.getName())) {
                    throw Exceptions.newIllegalArgumentException("The kind name {0} is used twice.", kind.getName());
                }
            }
        }

        for (int index = 0; index < this.programs.size(); index++) {
            if (this.programs.get(index).getIndex() != index) {
                throw Exceptions.newIllegalArgumentException("The program {0} is at position {1}.", this.programs.get(index), index);
            }
        }

        for (int index = 0; index < this.sets.size(); index++) {
            AnimationSet set = this.sets.get(index);

            if (set.getIndex() != index) {
                throw Exceptions.newIllegalArgumentException("The animation set {0} is at position {1}.", set, index);
            }

            for (int kindIndex = 0; kindIndex < set.getKindLimit(); kindIndex++) {
                AnimationKind kind = set.getKind(kindIndex);
                AnimationProgram program = set.getProgram(kindIndex);

                if (kind == null || program == null) {
                    continue;
                }

                if (kindIndex >= this.kinds.size() || this.kinds.get(kindIndex) != kind) {
                    throw Exceptions.newIllegalArgumentException("The animation set {0} uses the kind {1} of another library.", set, kind);
                }

                if (program.getIndex() >= this.programs.size() || this.programs.get(program.getIndex()) != program) {
                    throw Exceptions.newIllegalArgumentException("The animation set {0} uses the program {1} of another library.", set, program);
                }
            }
        }
    }

    /**
     * @return the kinds in index order, unmodifiable
     */
    public @NotNull List<@NotNull AnimationKind> getKinds() {
        return this.kinds;
    }

    /**
     * @return the programs in index order, unmodifiable
     */
    public @NotNull List<@NotNull AnimationProgram> getPrograms() {
        return this.programs;
    }

    /**
     * @return the animation sets in index order, unmodifiable
     */
    public @NotNull List<@NotNull AnimationSet> getSets() {
        return this.sets;
    }

    /**
     * Returns the animation set of a set index, such as the one the image type table's animation set column or a mod's own image
     * record stores.
     *
     * @param setIndex the set index
     * @return the set
     * @throws IndexOutOfBoundsException if the index names no set
     */
    public @NotNull AnimationSet getSet(int setIndex) {
        return this.sets.get(setIndex);
    }

    /**
     * Finds a kind by name. Meant for loading, not for the frame.
     *
     * @param name the kind name
     * @return the kind, or {@code null} if the library has none of the name
     */
    public @Nullable AnimationKind findKind(@NotNull String name) {
        for (AnimationKind kind : this.kinds) {
            if (kind.getName().equals(name)) {
                return kind;
            }
        }

        return null;
    }

    /**
     * Finds an animation set by name. Meant for loading, not for the frame.
     *
     * @param name the set name
     * @return the first set of the name, or {@code null} if the library has none
     */
    public @Nullable AnimationSet findSet(@NotNull String name) {
        for (AnimationSet set : this.sets) {
            if (set.getName().equals(name)) {
                return set;
            }
        }

        return null;
    }
}
