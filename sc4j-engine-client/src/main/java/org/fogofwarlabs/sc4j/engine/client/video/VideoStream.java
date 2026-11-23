package org.fogofwarlabs.sc4j.engine.client.video;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Decodes an in-memory video clip to RGBA frames: {@code width * height * 4} bytes per frame, rows top to bottom, with the clip's alpha plane
 * where it has one. {@link #readNextLoopedFrame()} loops; {@link #readNextFrame()} stops at the end. Timed playback uses
 * {@link #readFrameAt(long)} and the optional signed {@code 16}-bit audio from {@link #readAudio(long)}.
 * <p>
 * The engine default ({@link #open}) decodes WebM clips (VP8 or VP9 with an alpha plane, any audio codec FFmpeg reads) with FFmpeg.
 */
public interface VideoStream extends AutoCloseable {
    /**
     * Opens a clip with the engine default decoder.
     *
     * @param clip      the clip bytes
     * @param withAudio whether the audio track is decoded ({@link #readAudio})
     * @return the open stream, positioned before its first frame
     * @throws IOException if the FFmpeg libraries cannot be loaded, or the clip cannot be opened or has no decodable video stream
     */
    static @NotNull VideoStream open(byte @NotNull [] clip, boolean withAudio) throws IOException {
        try {
            return new FfmpegVideoStream(clip, withAudio);
        } catch (LinkageError error) {
            throw Exceptions.newIOException(error, "The FFmpeg libraries cannot be loaded: {0}", error);
        }
    }

    /**
     * @return the frame width in pixels
     */
    int getWidth();

    /**
     * @return the frame height in pixels
     */
    int getHeight();

    /**
     * @return the frame period in milliseconds from the stream frame rate
     */
    long getFrameMilliseconds();

    /**
     * @return the presentation time of the frame last returned by {@link #readNextFrame} or {@link #readNextLoopedFrame} in milliseconds, or {@code -1} if the frame has none
     */
    long getLastFrameMilliseconds();

    /**
     * @return the audio channels of {@link #readAudio} ({@code 1} or {@code 2}), or {@code 0} without audio
     */
    int getAudioChannels();

    /**
     * @return the audio sample rate in hertz, or {@code 0} without audio
     */
    int getAudioSampleRate();

    /**
     * @return {@code true} if every frame has been returned by {@link #readFrameAt}, {@code false} otherwise
     */
    boolean isFinished();

    /**
     * Reads ahead until the audio is decoded up to a time and returns the PCM decoded since the last call.
     *
     * @param untilMilliseconds the time the audio should reach
     * @return little-endian signed {@code 16}-bit interleaved samples, possibly empty
     */
    byte @NotNull [] readAudio(long untilMilliseconds);

    /**
     * Decodes the frame that is due at a time: the last frame whose presentation time is not later.
     *
     * @param milliseconds the playback time
     * @return the RGBA pixels of a new frame, or {@code null} if the shown frame stays
     */
    byte @Nullable [] readFrameAt(long milliseconds);

    /**
     * Decodes the next frame, starting over at the end of the clip.
     *
     * @return the RGBA pixels, or {@code null} if the clip has no decodable frame
     */
    byte @Nullable [] readNextLoopedFrame();

    /**
     * Decodes the next frame without starting over (whole-clip decoding).
     *
     * @return the RGBA pixels, or {@code null} at the end of the clip
     */
    byte @Nullable [] readNextFrame();

    /**
     * Closes the stream and releases its decoder resources. A stream is closed once.
     */
    @Override
    void close();
}
