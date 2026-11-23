package org.fogofwarlabs.sc4j.engine.client.render.gl;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.render.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws RGBA images, solid rectangles, lines and signed distance field glyphs on an OpenGL 3.3 context: the engine default
 * {@link GpuCanvas} ({@link GraphicsDevice#createCanvas}). Coordinates use a top-left framebuffer origin and colors use packed
 * {@code 0xRRGGBBAA}. The quads are batched per texture and submitted when the texture or the shader changes, at a clip change and at
 * {@link #end}.
 * <p>
 * Besides the {@link Texture} objects of the {@link Canvas} contract, the canvas offers the OpenGL texture names directly
 * ({@link #createTexture}, {@link #drawTexture(int, float, float, float, float, int)}): a client whose image caches keep texture
 * names in primitive arrays draws through them without a wrapper object per image.
 */
public final class GlCanvas implements GpuCanvas {
    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "Interface";
    /**
     * The bytes of an RGBA texel and the offsets of its channels.
     */
    private static final int RGBA_BYTES = 4;
    private static final int RGBA_GREEN = 1;
    private static final int RGBA_BLUE = 2;
    private static final int RGBA_ALPHA = 3;
    /**
     * The value of a full color channel, as a byte and as the float a channel is divided by.
     */
    private static final byte FULL_CHANNEL = (byte) 0xFF;
    private static final float CHANNEL_MAXIMUM = 255.0f;
    /**
     * The bit shifts of the channels of a packed {@code 0xRRGGBBAA} color.
     */
    private static final int RGBA_RED_SHIFT = 24;
    private static final int RGBA_GREEN_SHIFT = 16;
    private static final int RGBA_BLUE_SHIFT = 8;
    /**
     * The mask of one channel of a packed {@code 0xRRGGBBAA} color.
     */
    private static final int CHANNEL_MASK = 0xFF;
    /**
     * The bit shifts of the channels of a packed {@code 0xAARRGGBB} color.
     */
    private static final int ARGB_ALPHA_SHIFT = 24;
    private static final int ARGB_RED_SHIFT = 16;
    private static final int ARGB_GREEN_SHIFT = 8;
    /**
     * The divisor of half a line thickness.
     */
    private static final int HALF_DIVISOR = 2;
    /**
     * The vertex layout: position, texture coordinates and color.
     */
    private static final int POSITION_ATTRIBUTE = 0;
    private static final int COORDINATES_ATTRIBUTE = 1;
    private static final int COLOR_ATTRIBUTE = 2;
    private static final int POSITION_COMPONENTS = 2;
    private static final int COORDINATES_COMPONENTS = 2;
    private static final int COLOR_COMPONENTS = 4;
    private static final int COORDINATES_OFFSET = POSITION_COMPONENTS;
    private static final int COLOR_OFFSET = COORDINATES_OFFSET + COORDINATES_COMPONENTS;
    private static final int VERTEX_FLOATS = COLOR_OFFSET + COLOR_COMPONENTS;
    /**
     * The floats of a quad's two triangles, and the quads the vertex buffer holds.
     */
    private static final int QUAD_FLOATS = GraphicsDevice.QUAD_VERTICES * VERTEX_FLOATS;
    private static final int MAXIMUM_QUADS = 4096;
    /**
     * The texture unit of the sampled image.
     */
    private static final int IMAGE_UNIT = 0;
    /**
     * The uniform names of the shaders: the drawing coordinates' size, the sampled image, and the distance-field texel size and drop shadow.
     */
    private static final @NotNull String FRAMEBUFFER_SIZE_UNIFORM = "framebufferSize";
    private static final @NotNull String IMAGE_TEXTURE_UNIFORM = "imageTexture";
    private static final @NotNull String INVERSE_RESOLUTION_UNIFORM = "inverseResolution";
    private static final @NotNull String SHADOW_DATA_UNIFORM = "shadowData";
    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         layout(location = 2) in vec4 color;
                                                         uniform vec2 framebufferSize;
                                                         out vec2 sampleCoordinates;
                                                         out vec4 vertexColor;
                                                         void main() {
                                                             gl_Position = vec4(position.x * 2.0 / framebufferSize.x - 1.0,
                                                                                1.0 - position.y * 2.0 / framebufferSize.y, 0.0, 1.0);
                                                             sampleCoordinates = coordinates;
                                                             vertexColor = color;
                                                         }
                                                         """;
    private static final @NotNull String FRAGMENT_SHADER = """
                                                           #version 330 core
                                                           in vec2 sampleCoordinates;
                                                           in vec4 vertexColor;
                                                           uniform sampler2D imageTexture;
                                                           out vec4 resultColor;
                                                           void main() {
                                                               resultColor = texture(imageTexture, sampleCoordinates) * vertexColor;
                                                           }
                                                           """;

    /**
     * The distance-field glyph shader, including coverage smoothing and an optional drop shadow.
     */
    private static final @NotNull String SDF_FRAGMENT_SHADER = """
                                                               #version 330 core
                                                               in vec2 sampleCoordinates;
                                                               in vec4 vertexColor;
                                                               uniform sampler2D imageTexture;
                                                               uniform vec2 inverseResolution;
                                                               uniform vec4 shadowData;
                                                               out vec4 resultColor;
                                                               float sampleField(vec2 uv) {
                                                                   const float smoothing = 1.0 / 16.0;
                                                                   const float edge = 180.0 / 255.0;
                                                                   float distance = texture(imageTexture, uv).r;
                                                                   return smoothstep(edge - smoothing, edge + smoothing, distance);
                                                               }
                                                               void main() {
                                                                   vec4 fontColor = vertexColor * 1.10;
                                                                   float mainAlpha = sampleField(sampleCoordinates);
                                                                   float shadowAlpha = sampleField(sampleCoordinates - shadowData.xy * inverseResolution) * shadowData.z;
                                                                   shadowAlpha *= fontColor.a;
                                                                   mainAlpha *= fontColor.a;
                                                                   resultColor = vec4(fontColor.rgb * mainAlpha, shadowAlpha + mainAlpha);
                                                               }
                                                               """;

    private final int program;
    private final int framebufferUniform;
    private final int imageUniform;

    private final int sdfProgram;
    private final int sdfFramebufferUniform;
    private final int sdfImageUniform;
    private final int sdfResolutionUniform;
    private final int sdfShadowUniform;
    private final float fieldTexelSize;

    private final int vertexArray;
    private final int vertexBuffer;
    private final @NotNull FloatBuffer vertices;

    /**
     * The staging buffer of {@link #updateTexture}, grown on demand.
     */
    private @Nullable IntBuffer uploadPixels;

    private int batchTexture;
    private boolean batchField;
    private float batchShadowOffset;
    private float batchShadowPower;

    private int framebufferWidth;
    private int framebufferHeight;
    private float clipScaleX;
    private float clipScaleY;
    private int viewportHeight;

    private boolean closed;

    private final int whiteTexture;

    /**
     * Creates a {@link GlCanvas} instance on the current OpenGL 3.3 context.
     *
     * @param fieldPageSize the size in texels of the square distance field pages it draws glyphs from ({@link #createSdfTexture})
     */
    public GlCanvas(int fieldPageSize) {
        // The vertex memory comes first: a failing step closes the renderer, which releases what the steps before it created and the memory.
        this.vertices = MemoryUtil.memAllocFloat(MAXIMUM_QUADS * QUAD_FLOATS);

        try {
            this.program            = GlShaderProgramUtils.linkProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, FRAGMENT_SHADER);
            this.framebufferUniform = glGetUniformLocation(this.program, FRAMEBUFFER_SIZE_UNIFORM);
            this.imageUniform       = glGetUniformLocation(this.program, IMAGE_TEXTURE_UNIFORM);

            this.sdfProgram            = GlShaderProgramUtils.linkProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, SDF_FRAGMENT_SHADER);
            this.sdfFramebufferUniform = glGetUniformLocation(this.sdfProgram, FRAMEBUFFER_SIZE_UNIFORM);
            this.sdfImageUniform       = glGetUniformLocation(this.sdfProgram, IMAGE_TEXTURE_UNIFORM);
            this.sdfResolutionUniform  = glGetUniformLocation(this.sdfProgram, INVERSE_RESOLUTION_UNIFORM);
            this.sdfShadowUniform      = glGetUniformLocation(this.sdfProgram, SHADOW_DATA_UNIFORM);
            this.fieldTexelSize        = 1f / fieldPageSize;

            this.vertexArray  = glGenVertexArrays();
            this.vertexBuffer = glGenBuffers();

            this.uploadPixels = null;

            this.batchTexture      = 0;
            this.batchField        = false;
            this.batchShadowOffset = 0;
            this.batchShadowPower  = 0;

            this.framebufferWidth  = 0;
            this.framebufferHeight = 0;
            this.clipScaleX        = 1;
            this.clipScaleY        = 1;
            this.viewportHeight    = 0;

            this.closed = false;

            glBindVertexArray(this.vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, (long) this.vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
            glEnableVertexAttribArray(POSITION_ATTRIBUTE);
            glEnableVertexAttribArray(COORDINATES_ATTRIBUTE);
            glEnableVertexAttribArray(COLOR_ATTRIBUTE);
            glVertexAttribPointer(POSITION_ATTRIBUTE, POSITION_COMPONENTS, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 0L);
            glVertexAttribPointer(COORDINATES_ATTRIBUTE, COORDINATES_COMPONENTS, GL_FLOAT, false,
                                  VERTEX_FLOATS * Float.BYTES, (long) COORDINATES_OFFSET * Float.BYTES);
            glVertexAttribPointer(COLOR_ATTRIBUTE, COLOR_COMPONENTS, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, (long) COLOR_OFFSET * Float.BYTES);
            glBindVertexArray(0);

            this.whiteTexture = this.uploadRgbaTexture(1, 1, new byte[]{FULL_CHANNEL, FULL_CHANNEL, FULL_CHANNEL, FULL_CHANNEL});
        } catch (RuntimeException exception) {
            this.release();

            throw exception;
        }
    }

    /**
     * @return the width of the drawing coordinates of the current drawing
     */
    @Override
    public int getWidth() {
        return this.framebufferWidth;
    }

    /**
     * @return the height of the drawing coordinates of the current drawing
     */
    @Override
    public int getHeight() {
        return this.framebufferHeight;
    }

    /**
     * Returns the framebuffer pixels one drawing unit covers in the current drawing, so that a drawer can pick pictures of a matching
     * resolution.
     *
     * @return the pixels per drawing unit across
     */
    @Override
    public float getPixelScale() {
        return this.clipScaleX;
    }


    /**
     * Creates an empty one-byte signed distance field page of a glyph atlas, sampled linearly.
     *
     * @param width  the width in texels
     * @param height the height in texels
     * @return the texture name
     */
    @Override
    public int createSdfTexture(int width, int height) {
        this.flush();

        int texture = glGenTextures();
        ByteBuffer pixels = MemoryUtil.memCalloc(width * height);

        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }

        glBindTexture(GL_TEXTURE_2D, this.batchTexture);

        return texture;
    }

    /**
     * Replaces a rectangle of a distance field page.
     *
     * @param texture the page texture
     * @param x       the left texel
     * @param y       the top texel
     * @param width   the width in texels
     * @param height  the height in texels
     * @param texels  the row-major texels
     */
    @Override
    public void updateSdfTexture(int texture, int x, int y, int width, int height, @NotNull ByteBuffer texels) {
        this.flush();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_RED, GL_UNSIGNED_BYTE, texels);
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);
    }

    /**
     * Uploads an RGBA image.
     *
     * @param width      the image width
     * @param height     the image height
     * @param rgbaPixels the row-major RGBA bytes, top row first
     * @return the texture name
     * @throws IllegalArgumentException if a dimension is not positive or the pixel array does not match the dimensions
     */
    @Override
    public int createTexture(int width, int height, byte @NotNull [] rgbaPixels) {
        return this.uploadRgbaTexture(width, height, rgbaPixels);
    }

    /**
     * Uploads an image as a texture of this canvas.
     *
     * @param width  the image width in pixels
     * @param height the image height in pixels
     * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
     * @return the created {@link Texture} instance, closed by the caller
     * @throws IllegalArgumentException if a dimension is not positive or the pixels do not match the dimensions
     */
    @Override
    public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
        if (width <= 0 || height <= 0 || colors.length != width * height) {
            throw Exceptions.newIllegalArgumentException("Pixels do not match {0} by {1}.", width, height);
        }

        byte[] rgbaPixels = new byte[colors.length * RGBA_BYTES];

        for (int index = 0; index < colors.length; index++) {
            int color = colors[index];
            int offset = index * RGBA_BYTES;

            rgbaPixels[offset]              = (byte) (color >>> ARGB_RED_SHIFT);
            rgbaPixels[offset + RGBA_GREEN] = (byte) (color >>> ARGB_GREEN_SHIFT);
            rgbaPixels[offset + RGBA_BLUE]  = (byte) color;
            rgbaPixels[offset + RGBA_ALPHA] = (byte) (color >>> ARGB_ALPHA_SHIFT);
        }

        return new GlTexture(this, this.uploadRgbaTexture(width, height, rgbaPixels), width, height);
    }

    private int uploadRgbaTexture(int width, int height, byte @NotNull [] rgbaPixels) {
        if (width <= 0 || height <= 0 || rgbaPixels.length != width * height * RGBA_BYTES) {
            throw Exceptions.newIllegalArgumentException("RGBA pixels do not match {0} by {1}.", width, height);
        }

        // Pending quads keep their texture: submit them before the texture binding changes.
        this.flush();

        int texture = glGenTextures();
        ByteBuffer pixels = MemoryUtil.memAlloc(rgbaPixels.length);

        try {
            pixels.put(rgbaPixels).flip();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }

        glBindTexture(GL_TEXTURE_2D, this.batchTexture);

        return texture;
    }

    /**
     * Creates an empty RGBA texture for {@link #updateTexture}.
     *
     * @param width               the texture width
     * @param height              the texture height
     * @param linearMagnification whether magnification filters linearly (minification always does)
     * @return the texture name
     * @throws IllegalArgumentException if the size is not positive
     */
    @Override
    public int createDynamicTexture(int width, int height, boolean linearMagnification) {
        if (width <= 0 || height <= 0) {
            throw Exceptions.newIllegalArgumentException("A texture needs a positive size, not {0} by {1}.", width, height);
        }

        this.flush();

        int texture = glGenTextures();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linearMagnification ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);

        return texture;
    }

    /**
     * Replaces the texels of a texture created by {@link #createDynamicTexture} from packed {@code 0xRRGGBBAA} ints, uploaded as
     * {@code GL_UNSIGNED_INT_8_8_8_8} without a byte conversion.
     *
     * @param texture the texture name
     * @param width   the texture width
     * @param height  the texture height
     * @param pixels  the row-major texels, top row first, {@code width * height} of them
     */
    @Override
    public void updateTexture(int texture, int width, int height, int @NotNull [] pixels) {
        this.updateTextureRows(texture, width, 0, height - 1, pixels);
    }

    /**
     * Replaces rows of a texture created by {@link #createDynamicTexture} from packed {@code 0xRRGGBBAA} ints.
     *
     * @param texture the texture name
     * @param width   the texture width
     * @param top     the first row to replace
     * @param bottom  the last row to replace
     * @param pixels  the whole texture's row-major texels, top row first
     * @throws IllegalArgumentException if the texels do not cover the rows
     */
    @Override
    public void updateTextureRows(int texture, int width, int top, int bottom, int @NotNull [] pixels) {
        int rows = bottom - top + 1;

        if (rows <= 0) {
            return;
        }

        if (top < 0 || pixels.length < (bottom + 1) * width) {
            throw Exceptions.newIllegalArgumentException("The texels do not cover rows {0} to {1}.", top, bottom);
        }

        this.flush();

        IntBuffer buffer = this.uploadPixels;

        if (buffer == null || buffer.capacity() < width * rows) {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }

            buffer            = MemoryUtil.memAllocInt(width * rows);
            this.uploadPixels = buffer;
        }

        buffer.clear();
        buffer.put(pixels, top * width, width * rows).flip();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, Integer.BYTES);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, top, width, rows, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8, buffer);
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);
    }

    /**
     * Deletes a texture created by {@link #createTexture}.
     *
     * @param texture the texture name
     */
    @Override
    public void deleteTexture(int texture) {
        this.flush();

        glDeleteTextures(texture);
    }


    /**
     * Begins drawing over the whole window, in window pixel coordinates.
     *
     * @param framebufferWidth  the window framebuffer width in pixels
     * @param framebufferHeight the window framebuffer height in pixels
     */
    @Override
    public void begin(int framebufferWidth, int framebufferHeight) {
        this.begin(0, framebufferWidth, framebufferHeight, framebufferWidth, framebufferHeight);
    }

    /**
     * Begins drawing over the whole window in the logical units of an interface surface: the surface's logical area maps onto the whole
     * framebuffer, and {@link #getWidth} and {@link #getHeight} are its logical size.
     *
     * @param surface the interface surface of the frame
     */
    @Override
    public void begin(@NotNull UiSurface surface) {
        this.begin(0, surface.getFramebufferWidth(), surface.getFramebufferHeight(), surface.getWidth(), surface.getHeight());
    }

    /**
     * Begins interface drawing into a framebuffer whose pixel grid differs from the drawing coordinates: callers keep drawing in coordinates of
     * {@code coordinateWidth} by {@code coordinateHeight} (the window), which map onto the whole viewport (for example a low resolution target that
     * is scaled up to the window afterwards).
     *
     * @param framebuffer      the framebuffer, {@code 0} for the window
     * @param viewportWidth    the framebuffer width in pixels
     * @param viewportHeight   the framebuffer height in pixels
     * @param coordinateWidth  the drawing coordinates' width
     * @param coordinateHeight the drawing coordinates' height
     * @throws IllegalStateException if the renderer is closed
     */
    @Override
    public void begin(int framebuffer, int viewportWidth, int viewportHeight, int coordinateWidth, int coordinateHeight) {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The canvas is closed.");
        }

        this.clipScaleX     = viewportWidth / (float) coordinateWidth;
        this.clipScaleY     = viewportHeight / (float) coordinateHeight;
        this.viewportHeight = viewportHeight;

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, viewportWidth, viewportHeight);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        // The destination alpha accumulates as coverage, so that a transparent layer (an offscreen interface layer composed later) holds
        // premultiplied color for its compose; the window ignores it.
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(this.program);
        glUniform2f(this.framebufferUniform, coordinateWidth, coordinateHeight);

        this.framebufferWidth  = coordinateWidth;
        this.framebufferHeight = coordinateHeight;
        this.batchField        = false;

        glUniform1i(this.imageUniform, IMAGE_UNIT);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(this.vertexArray);

        this.vertices.clear();

        this.batchTexture = 0;
    }

    /**
     * Sets the rectangle the following drawing is limited to, or lifts the limit.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width, or a negative value to draw over the whole target again
     * @param height the height
     */
    @Override
    public void setClip(int x, int y, int width, int height) {
        this.flush();

        if (width < 0) {
            glDisable(GL_SCISSOR_TEST);

            return;
        }

        int left = Math.round(x * this.clipScaleX);
        int right = Math.round((x + width) * this.clipScaleX);
        int top = Math.round(y * this.clipScaleY);
        int bottom = Math.round((y + Math.max(0, height)) * this.clipScaleY);

        glEnable(GL_SCISSOR_TEST);
        glScissor(left, this.viewportHeight - bottom, right - left, bottom - top);
    }


    /**
     * Draws a whole texture into a rectangle.
     *
     * @param texture the texture name
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param color   the packed color multiplied with the texture
     */
    @Override
    public void drawTexture(int texture, float x, float y, float width, float height, int color) {
        this.drawTexture(texture, x, y, width, height, 0, 0, 1, 1, color);
    }

    /**
     * Draws part of a texture into a rectangle.
     *
     * @param texture the texture name
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param left    the left texture coordinate
     * @param top     the top texture coordinate
     * @param right   the right texture coordinate
     * @param bottom  the bottom texture coordinate
     * @param color   the packed color multiplied with the texture
     */
    @Override
    public void drawTexture(int texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        this.selectBatchTexture(texture);
        this.appendQuad(x, y, x + width, y + height, left, top, right, bottom, color);
    }

    /**
     * Draws a whole texture into a rectangle.
     *
     * @param texture the texture of this canvas
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param color   the packed colour multiplied with the texture
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
        this.drawTexture(this.getTextureName(texture), x, y, width, height, color);
    }

    /**
     * Draws part of a texture into a rectangle.
     *
     * @param texture the texture of this canvas
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param left    the left texture coordinate, {@code 0} to {@code 1}
     * @param top     the top texture coordinate
     * @param right   the right texture coordinate
     * @param bottom  the bottom texture coordinate
     * @param color   the packed colour multiplied with the texture
     */
    @Override
    public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                            float left, float top, float right, float bottom,
                            int color) {
        this.drawTexture(this.getTextureName(texture), x, y, width, height, left, top, right, bottom, color);
    }

    private int getTextureName(@NotNull Texture texture) {
        if (!(texture instanceof GlTexture glTexture) || glTexture.getCanvas() != this) {
            throw Exceptions.newIllegalArgumentException("The texture belongs to another canvas.");
        }

        return glTexture.getName();
    }

    /**
     * Draws part of a distance field page as a glyph through the distance-field shader ({@link #SDF_FRAGMENT_SHADER}).
     *
     * @param texture      the page texture
     * @param x            the left coordinate
     * @param y            the top coordinate
     * @param width        the drawn width
     * @param height       the drawn height
     * @param left         the left texture coordinate
     * @param top          the top texture coordinate
     * @param right        the right texture coordinate
     * @param bottom       the bottom texture coordinate
     * @param color        the packed glyph color
     * @param shadowOffset the drop shadow offset in texels right and down, or {@code 0}
     * @param shadowPower  the drop shadow weight, or {@code 0} for none
     */
    @Override
    public void drawSdfTexture(int texture,
                               float x, float y, float width, float height,
                               float left, float top, float right, float bottom,
                               int color,
                               float shadowOffset, float shadowPower) {
        if (this.batchTexture != texture || !this.batchField || this.batchShadowOffset != shadowOffset || this.batchShadowPower != shadowPower) {
            this.flush();

            this.batchTexture      = texture;
            this.batchField        = true;
            this.batchShadowOffset = shadowOffset;
            this.batchShadowPower  = shadowPower;
        }

        this.appendQuad(x, y, x + width, y + height, left, top, right, bottom, color);
    }

    /**
     * Fills a rectangle.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @param color  the packed color
     */
    @Override
    public void fillRectangle(float x, float y, float width, float height, int color) {
        this.drawTexture(this.whiteTexture, x, y, width, height, color);
    }

    /**
     * Draws a rectangle outline inside the rectangle.
     *
     * @param x         the left coordinate
     * @param y         the top coordinate
     * @param width     the width
     * @param height    the height
     * @param thickness the line thickness
     * @param color     the packed color
     */
    @Override
    public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
        this.fillRectangle(x, y, width, thickness, color);
        this.fillRectangle(x, y + height - thickness, width, thickness, color);
        this.fillRectangle(x, y, thickness, height, color);
        this.fillRectangle(x + width - thickness, y, thickness, height, color);
    }

    /**
     * Draws a straight line as a quad of the given thickness.
     *
     * @param sourceX      the start column
     * @param sourceY      the start row
     * @param destinationX the end column
     * @param destinationY the end row
     * @param thickness    the line thickness
     * @param color        the packed color
     */
    @Override
    public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        float length = (float) Math.hypot(destinationX - sourceX, destinationY - sourceY);

        if (length == 0) {
            return;
        }

        float offsetX = -(destinationY - sourceY) / length * thickness / HALF_DIVISOR;
        float offsetY = (destinationX - sourceX) / length * thickness / HALF_DIVISOR;

        this.selectBatchTexture(this.whiteTexture);

        if (this.vertices.remaining() < QUAD_FLOATS) {
            this.flush();
        }

        float red = (color >>> RGBA_RED_SHIFT) / CHANNEL_MAXIMUM;
        float green = (color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float blue = (color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float alpha = (color & CHANNEL_MASK) / CHANNEL_MAXIMUM;

        this.appendVertex(sourceX + offsetX, sourceY + offsetY, 0, 0, red, green, blue, alpha);
        this.appendVertex(destinationX + offsetX, destinationY + offsetY, 1, 0, red, green, blue, alpha);
        this.appendVertex(destinationX - offsetX, destinationY - offsetY, 1, 1, red, green, blue, alpha);
        this.appendVertex(sourceX + offsetX, sourceY + offsetY, 0, 0, red, green, blue, alpha);
        this.appendVertex(destinationX - offsetX, destinationY - offsetY, 1, 1, red, green, blue, alpha);
        this.appendVertex(sourceX - offsetX, sourceY - offsetY, 0, 1, red, green, blue, alpha);
    }

    /**
     * Fills a convex quadrilateral, for example the screen outline of a diamond cell or of a footprint: an axis-aligned rectangle as
     * {@link #fillRectangle}, other outlines as two triangles.
     *
     * @param corners the x and y of the four corners in order around the outline, {@value QuadrilateralUtils#CORNER_VALUES} values
     * @param color   the packed colour
     * @throws IndexOutOfBoundsException if the array is shorter than {@value QuadrilateralUtils#CORNER_VALUES}
     */
    @Override
    public void fillQuadrilateral(float @NotNull [] corners, int color) {
        if (QuadrilateralUtils.isRectangle(corners)) {
            this.fillRectangle(QuadrilateralUtils.getLeft(corners), QuadrilateralUtils.getTop(corners),
                               QuadrilateralUtils.getWidth(corners), QuadrilateralUtils.getHeight(corners), color);

            return;
        }

        this.selectBatchTexture(this.whiteTexture);

        if (this.vertices.remaining() < QUAD_FLOATS) {
            this.flush();
        }

        float red = (color >>> RGBA_RED_SHIFT) / CHANNEL_MAXIMUM;
        float green = (color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float blue = (color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float alpha = (color & CHANNEL_MASK) / CHANNEL_MAXIMUM;

        this.appendVertex(corners[0], corners[1], 0, 0, red, green, blue, alpha);
        this.appendVertex(corners[2], corners[3], 1, 0, red, green, blue, alpha);
        this.appendVertex(corners[4], corners[5], 1, 1, red, green, blue, alpha);
        this.appendVertex(corners[0], corners[1], 0, 0, red, green, blue, alpha);
        this.appendVertex(corners[4], corners[5], 1, 1, red, green, blue, alpha);
        this.appendVertex(corners[6], corners[7], 0, 1, red, green, blue, alpha);
    }

    private void selectBatchTexture(int texture) {
        if (this.batchTexture == texture && !this.batchField) {
            return;
        }

        this.flush();

        this.batchTexture = texture;
        this.batchField   = false;
    }

    private void appendQuad(float left, float top, float right, float bottom,
                            float textureLeft, float textureTop, float textureRight, float textureBottom,
                            int color) {
        if (this.vertices.remaining() < QUAD_FLOATS) {
            this.flush();
        }

        float red = (color >>> RGBA_RED_SHIFT) / CHANNEL_MAXIMUM;
        float green = (color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float blue = (color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK) / CHANNEL_MAXIMUM;
        float alpha = (color & CHANNEL_MASK) / CHANNEL_MAXIMUM;

        this.appendVertex(left, top, textureLeft, textureTop, red, green, blue, alpha);
        this.appendVertex(right, top, textureRight, textureTop, red, green, blue, alpha);
        this.appendVertex(right, bottom, textureRight, textureBottom, red, green, blue, alpha);
        this.appendVertex(left, top, textureLeft, textureTop, red, green, blue, alpha);
        this.appendVertex(right, bottom, textureRight, textureBottom, red, green, blue, alpha);
        this.appendVertex(left, bottom, textureLeft, textureBottom, red, green, blue, alpha);
    }

    private void appendVertex(float x, float y, float textureX, float textureY, float red, float green, float blue, float alpha) {
        this.vertices.put(x).put(y).put(textureX).put(textureY).put(red).put(green).put(blue).put(alpha);
    }


    /**
     * Submits the pending drawing.
     */
    @Override
    public void end() {
        this.flush();

        glDisable(GL_SCISSOR_TEST);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void flush() {
        if (this.vertices.position() == 0) {
            return;
        }

        this.vertices.flip();
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferSubData(GL_ARRAY_BUFFER, 0, this.vertices);

        if (this.batchField) {
            // Distance-field output uses straight-alpha blending, applying its alpha weight again.
            glUseProgram(this.sdfProgram);
            glUniform2f(this.sdfFramebufferUniform, this.framebufferWidth, this.framebufferHeight);
            glUniform1i(this.sdfImageUniform, IMAGE_UNIT);
            glUniform2f(this.sdfResolutionUniform, this.fieldTexelSize, this.fieldTexelSize);
            glUniform4f(this.sdfShadowUniform, this.batchShadowOffset, this.batchShadowOffset, this.batchShadowPower, 0f);
            glDrawArrays(GL_TRIANGLES, 0, this.vertices.remaining() / VERTEX_FLOATS);
            glUseProgram(this.program);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, this.vertices.remaining() / VERTEX_FLOATS);
        }

        this.vertices.clear();
    }

    /**
     * Disposes resources: releases the GPU programs, buffers and textures and the staging memory. Closing again does nothing.
     */
    @Override
    public void close() {
        this.release();
    }

    private void release() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                () -> MemoryUtil.memFree(this.uploadPixels),
                () -> glDeleteTextures(this.whiteTexture),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> glDeleteProgram(this.sdfProgram),
                () -> glDeleteProgram(this.program),
                () -> MemoryUtil.memFree(this.vertices)
        );
    }
}
