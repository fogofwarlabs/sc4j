package org.fogofwarlabs.sc4j.engine.client.display;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Holds a resource with its detail versions ({@link DetailUtils}): the same resource drawn at whole multiples of its size, smallest multiple
 * first. A drawer lays out and measures with the {@link #getResource resource} and draws from {@link #find}, the detail of the physical
 * pixels per resource pixel of the drawing; a set without details always draws the resource.
 *
 * @param <T> the type of the resource, for example a picture, a sprite sheet or tile pictures
 */
public final class DetailSet<T> {
    /**
     * Creates a {@link DetailSet} instance of a resource without details.
     *
     * @param resource the resource
     * @param <T>      the type of the resource
     * @return the created {@link DetailSet} instance
     */
    public static <T> @NotNull DetailSet<T> of(@NotNull T resource) {
        return new DetailSet<>(resource, List.of(), new int[0]);
    }


    private final @NotNull T resource;
    private final @NotNull List<@NotNull T> details;
    private final int @NotNull [] multiples;

    /**
     * Creates a {@link DetailSet} instance. Each multiple lies in {@value DetailUtils#MINIMUM_MULTIPLE} up to
     * {@value DetailUtils#MAXIMUM_MULTIPLE} and is larger than the one before.
     *
     * @param resource  the resource
     * @param details   the detail versions, smallest multiple first, copied
     * @param multiples the multiple of each detail version, copied
     * @throws IllegalArgumentException if the counts differ or a multiple is out of range or not larger than the one before
     */
    public DetailSet(@NotNull T resource, @NotNull List<@NotNull T> details, int @NotNull [] multiples) {
        if (details.size() != multiples.length) {
            throw Exceptions.newIllegalArgumentException("{0} details with {1} multiples.", details.size(), multiples.length);
        }

        for (int index = 0; index < multiples.length; index++) {
            int multiple = multiples[index];

            if (multiple < DetailUtils.MINIMUM_MULTIPLE || multiple > DetailUtils.MAXIMUM_MULTIPLE || index > 0 && multiple <= multiples[index - 1]) {
                throw Exceptions.newIllegalArgumentException("Invalid detail multiple {0} at {1}.", multiple, index);
            }
        }

        this.resource  = resource;
        this.details   = List.copyOf(details);
        this.multiples = multiples.clone();
    }

    /**
     * @return the resource, at its own size
     */
    public @NotNull T getResource() {
        return this.resource;
    }

    /**
     * @return the number of detail versions
     */
    public int getDetailCount() {
        return this.details.size();
    }

    /**
     * Returns one detail version.
     *
     * @param index the index of the detail, {@code 0} up to {@link #getDetailCount()} minus one, smallest multiple first
     * @return the detail version
     * @throws IndexOutOfBoundsException if the index names no detail
     */
    public @NotNull T getDetail(int index) {
        return this.details.get(index);
    }

    /**
     * Returns the multiple of one detail version.
     *
     * @param index the index of the detail, {@code 0} up to {@link #getDetailCount()} minus one, smallest multiple first
     * @return the multiple of the detail's size
     * @throws IndexOutOfBoundsException if the index names no detail
     */
    public int getMultiple(int index) {
        if (index < 0 || index >= this.multiples.length) {
            throw Exceptions.newIndexOutOfBoundsException("The set has no detail {0}.", index);
        }

        return this.multiples[index];
    }


    /**
     * Finds the detail a drawing uses ({@link DetailUtils#findDetail}).
     *
     * @param pixelsPerUnit the physical pixels per resource pixel of the drawing
     * @return the index of the detail, or {@code -1} for the resource itself
     */
    public int findDetail(float pixelsPerUnit) {
        return DetailUtils.findDetail(this.multiples, pixelsPerUnit);
    }

    /**
     * Finds the version a drawing uses: the detail of {@link #findDetail}, or the resource itself.
     *
     * @param pixelsPerUnit the physical pixels per resource pixel of the drawing
     * @return the resource or one of its details
     */
    public @NotNull T find(float pixelsPerUnit) {
        int index = this.findDetail(pixelsPerUnit);

        return index < 0 ? this.resource : this.details.get(index);
    }

    /**
     * Finds the multiple of the version a drawing uses.
     *
     * @param pixelsPerUnit the physical pixels per resource pixel of the drawing
     * @return {@code 1} for the resource itself, or the multiple of the picked detail
     */
    public int findMultiple(float pixelsPerUnit) {
        int index = this.findDetail(pixelsPerUnit);

        return index < 0 ? 1 : this.multiples[index];
    }
}
