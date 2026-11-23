package org.fogofwarlabs.sc4j.engine.client.render;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the engine default glyph atlas of distance field text on a {@link GpuCanvas}: one-byte signed distance field pages of
 * {@code 1024x1024} texels, filled by shelves, drawn with {@link GpuCanvas#drawSdfTexture}.
 * <p>
 * A glyph goes into the first page that takes it; a new page is made only when none does. Within a page the first shelf whose height is at least
 * {@code ceil(h) + 2} and whose free width is at least {@code ceil(w) + 2} takes the glyph at the shelf's cursor, which then moves by {@code w} (the
 * {@code 2}-texel margin is only tested, not kept horizontally). Without such a shelf a new one of height {@code ceil(h) + 2} opens below the last
 * when it still fits the page. The page keeps a dirty rectangle that is uploaded before drawing.
 */
public final class GlyphAtlas implements AutoCloseable {
    /**
     * The page width and height in texels.
     */
    public static final int PAGE_SIZE = 0x400;
    /**
     * The margin a shelf must leave beyond a glyph's size.
     */
    private static final int MARGIN = 2;

    /**
     * Holds a placed glyph: its page texture and texel rectangle.
     */
    public static final class Slot {
        private final int texture;

        private final int x;
        private final int y;

        private final int width;
        private final int height;

        private Slot(int texture, int x, int y, int width, int height) {
            this.texture = texture;

            this.x = x;
            this.y = y;

            this.width  = width;
            this.height = height;
        }

        /**
         * Returns the canvas name of the page texture, for drawing through {@link GpuCanvas#drawSdfTexture}.
         *
         * @return the page texture name
         */
        public int getTexture() {
            return this.texture;
        }

        /**
         * @return the left texel
         */
        public int getX() {
            return this.x;
        }

        /**
         * @return the top texel
         */
        public int getY() {
            return this.y;
        }

        /**
         * @return the width in texels
         */
        public int getWidth() {
            return this.width;
        }

        /**
         * @return the height in texels
         */
        public int getHeight() {
            return this.height;
        }
    }

    /**
     * Holds one shelf of a page: its top, height and cursor.
     */
    private static final class Shelf {
        private final int top;
        private final int height;

        private int cursor;

        private Shelf(int top, int height) {
            this.top    = top;
            this.height = height;

            this.cursor = 0;
        }
    }

    /**
     * Holds one page: its texture, texels, shelves, next shelf top and dirty rectangle.
     */
    private static final class Page {
        private final int texture;
        private final byte @NotNull [] texels;

        private final @NotNull List<Shelf> shelves;

        private int nextTop;

        private int dirtyLeft;
        private int dirtyTop;
        private int dirtyRight;
        private int dirtyBottom;

        private Page(int texture) {
            this.texture = texture;
            this.texels  = new byte[PAGE_SIZE * PAGE_SIZE];

            this.shelves = new ArrayList<>();

            this.nextTop = 0;

            this.dirtyLeft   = PAGE_SIZE;
            this.dirtyTop    = PAGE_SIZE;
            this.dirtyRight  = 0;
            this.dirtyBottom = 0;
        }

        private @Nullable Shelf findOrOpenShelf(int width, int height) {
            for (Shelf shelf : this.shelves) {
                if (height + MARGIN <= shelf.height && width + MARGIN <= PAGE_SIZE - shelf.cursor) {
                    return shelf;
                }
            }

            int shelfHeight = height + MARGIN;

            if (this.nextTop + shelfHeight > PAGE_SIZE) {
                return null;
            }

            Shelf shelf = new Shelf(this.nextTop, shelfHeight);

            this.nextTop += shelfHeight;
            this.shelves.add(shelf);

            return shelf;
        }
    }

    private final @NotNull GpuCanvas canvas;

    private final @NotNull List<Page> pages;

    /**
     * Creates a {@link GlyphAtlas} instance.
     *
     * @param canvas the canvas that owns the page textures
     */
    public GlyphAtlas(@NotNull GpuCanvas canvas) {
        this.canvas = canvas;

        this.pages = new ArrayList<>();
    }

    /**
     * Places a glyph's distance field.
     *
     * @param texels the row-major texels, {@code width * height} bytes
     * @param width  the width in texels
     * @param height the height in texels
     * @return the slot, or {@code null} without a new page when the glyph and its margin are larger than a page
     */
    public @Nullable Slot add(byte @NotNull [] texels, int width, int height) {
        if (width + MARGIN > PAGE_SIZE || height + MARGIN > PAGE_SIZE) {
            return null;
        }

        for (Page page : this.pages) {
            Shelf shelf = page.findOrOpenShelf(width, height);

            if (shelf != null) {
                return this.place(page, shelf, texels, width, height);
            }
        }

        Page page = new Page(this.canvas.createSdfTexture(PAGE_SIZE, PAGE_SIZE));

        this.pages.add(page);

        Shelf shelf = page.findOrOpenShelf(width, height);

        return shelf == null ? null : this.place(page, shelf, texels, width, height);
    }

    private @NotNull Slot place(@NotNull Page page, @NotNull Shelf shelf, byte @NotNull [] texels, int width, int height) {
        int left = shelf.cursor;
        int top = shelf.top;

        for (int row = 0; row < height; row++) {
            System.arraycopy(texels, row * width, page.texels, (top + row) * PAGE_SIZE + left, width);
        }

        shelf.cursor += width;

        page.dirtyLeft   = Math.min(page.dirtyLeft, left);
        page.dirtyTop    = Math.min(page.dirtyTop, top);
        page.dirtyRight  = Math.max(page.dirtyRight, left + width);
        page.dirtyBottom = Math.max(page.dirtyBottom, top + height);

        return new Slot(page.texture, left, top, width, height);
    }


    /**
     * Uploads the changed texels of every page.
     */
    public void upload() {
        for (Page page : this.pages) {
            if (page.dirtyRight <= page.dirtyLeft || page.dirtyBottom <= page.dirtyTop) {
                continue;
            }

            int width = page.dirtyRight - page.dirtyLeft;
            int height = page.dirtyBottom - page.dirtyTop;
            ByteBuffer buffer = MemoryUtil.memAlloc(width * height);

            try {
                for (int row = 0; row < height; row++) {
                    buffer.put(page.texels, (page.dirtyTop + row) * PAGE_SIZE + page.dirtyLeft, width);
                }

                buffer.flip();
                this.canvas.updateSdfTexture(page.texture, page.dirtyLeft, page.dirtyTop, width, height, buffer);
            } finally {
                MemoryUtil.memFree(buffer);
            }

            page.dirtyLeft   = PAGE_SIZE;
            page.dirtyTop    = PAGE_SIZE;
            page.dirtyRight  = 0;
            page.dirtyBottom = 0;
        }
    }


    /**
     * Disposes resources: releases the page textures.
     */
    @Override
    public void close() {
        for (Page page : this.pages) {
            this.canvas.deleteTexture(page.texture);
        }

        this.pages.clear();
    }
}
