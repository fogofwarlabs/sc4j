package org.fogofwarlabs.sc4j.engine.resource.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Holds the pixels of one layer of a picture in one primitive array: unsigned bytes for {@link PixelFormat#INDEXED_8} and
 * {@link PixelFormat#ALPHA_8}, packed {@code int} colours for {@link PixelFormat#RGBA_8888}. The owner of the layer decides
 * where each frame or tile starts; the layer itself is a flat pixel sequence. The values are copied in and out, so a
 * layer never changes.
 */
public final class PixelLayer {
    /**
     * The mask of an unsigned byte value.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Creates a {@link PixelLayer} instance of palette indices.
     *
     * @param indices the palette index of each pixel, copied
     * @return the created {@link PixelLayer} instance
     */
    public static @NotNull PixelLayer ofIndexed(byte @NotNull [] indices) {
        return new PixelLayer(PixelFormat.INDEXED_8, indices.clone(), null);
    }

    /**
     * Creates a {@link PixelLayer} instance of alpha values.
     *
     * @param alphas the alpha value of each pixel, copied
     * @return the created {@link PixelLayer} instance
     */
    public static @NotNull PixelLayer ofAlpha(byte @NotNull [] alphas) {
        return new PixelLayer(PixelFormat.ALPHA_8, alphas.clone(), null);
    }

    /**
     * Creates a {@link PixelLayer} instance of packed colours.
     *
     * @param colors the colour of each pixel as {@code 0xAARRGGBB} with straight alpha, copied
     * @return the created {@link PixelLayer} instance
     */
    public static @NotNull PixelLayer ofRgba(int @NotNull [] colors) {
        return new PixelLayer(PixelFormat.RGBA_8888, null, colors.clone());
    }


    private final @NotNull PixelFormat format;
    private final byte @Nullable [] bytes;
    private final int @Nullable [] colors;

    private PixelLayer(@NotNull PixelFormat format, byte @Nullable [] bytes, int @Nullable [] colors) {
        this.format = format;
        this.bytes  = bytes;
        this.colors = colors;
    }

    /**
     * @return how a pixel is stored
     */
    public @NotNull PixelFormat getFormat() {
        return this.format;
    }

    /**
     * @return the number of pixels
     */
    public int getLength() {
        byte[] byteValues = this.bytes;

        return byteValues != null ? byteValues.length : this.requireColors().length;
    }

    /**
     * Returns the value of one pixel.
     *
     * @param offset the pixel offset in the layer
     * @return the unsigned byte value ({@code 0} up to {@code 255}) of an 8-bit layer, or the packed {@code 0xAARRGGBB} colour of an RGBA layer
     * @throws IndexOutOfBoundsException if the offset is outside the layer
     */
    public int getValue(int offset) {
        byte[] byteValues = this.bytes;

        return byteValues != null ? byteValues[offset] & BYTE_MASK : this.requireColors()[offset];
    }

    /**
     * Copies a run of pixel values, each as {@link #getValue} returns it.
     *
     * @param offset            the offset of the first pixel in the layer
     * @param destination       receives the values
     * @param destinationOffset the offset of the first value in the destination
     * @param length            the number of pixels
     * @throws IndexOutOfBoundsException if the run is outside the layer or the destination
     */
    public void copyValues(int offset, int @NotNull [] destination, int destinationOffset, int length) {
        byte[] byteValues = this.bytes;

        if (byteValues == null) {
            System.arraycopy(this.requireColors(), offset, destination, destinationOffset, length);

            return;
        }

        Objects.checkFromIndexSize(offset, length, byteValues.length);
        Objects.checkFromIndexSize(destinationOffset, length, destination.length);

        for (int index = 0; index < length; index++) {
            destination[destinationOffset + index] = byteValues[offset + index] & BYTE_MASK;
        }
    }

    /**
     * Copies a run of the raw bytes of an 8-bit layer.
     *
     * @param offset            the offset of the first pixel in the layer
     * @param destination       receives the bytes
     * @param destinationOffset the offset of the first byte in the destination
     * @param length            the number of pixels
     * @throws IllegalStateException     if the layer stores packed colours
     * @throws IndexOutOfBoundsException if the run is outside the layer or the destination
     */
    public void copyBytes(int offset, byte @NotNull [] destination, int destinationOffset, int length) {
        byte[] byteValues = this.bytes;

        if (byteValues == null) {
            throw Exceptions.newIllegalStateException("The layer stores packed colours, not bytes.");
        }

        System.arraycopy(byteValues, offset, destination, destinationOffset, length);
    }

    private int @NotNull [] requireColors() {
        int[] colorValues = this.colors;

        if (colorValues == null) {
            throw Exceptions.newIllegalStateException("The layer stores no colours.");
        }

        return colorValues;
    }
}
