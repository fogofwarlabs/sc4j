package org.fogofwarlabs.sc4j.engine.client.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.*;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * The engine default {@link VideoStream}: decodes in-memory WebM clips with FFmpeg. The libvpx decoders read the alpha plane of VP8 and VP9
 * clips. Shared read and seek callbacks locate each stream through its opaque identifier.
 */
final class FfmpegVideoStream implements VideoStream {
    private static final int INPUT_BUFFER_SIZE = 4096;
    /**
     * The {@code write_flag} of a read-only FFmpeg input context.
     */
    private static final int READ_ONLY_INPUT = 0;
    /**
     * FFmpeg {@code AVFMT_FLAG_CUSTOM_IO}: the caller owns {@code pb}.
     */
    private static final int CUSTOM_INPUT_FLAG = 0x0080;
    /**
     * The {@code av_find_best_stream} stream number that lets FFmpeg select the stream.
     */
    private static final int AUTOMATIC_STREAM = -1;
    /**
     * The {@code av_find_best_stream} related stream number that relates the search to no stream.
     */
    private static final int NO_RELATED_STREAM = -1;
    /**
     * The row alignment of the RGBA image buffer in bytes: whole blocks of eight pixels. The scaler's fast YUV to RGB conversion converts a row
     * in blocks of eight pixels and skips the last partial block when the destination row has no room for a whole block, which would leave
     * the last pixels of a row whose width is not a multiple of eight unwritten.
     */
    private static final int IMAGE_ALIGNMENT = 32;
    /**
     * The bytes of one RGBA pixel.
     */
    private static final int RGBA_BYTES = 4;
    /**
     * Extra RGBA rows reserved for the scaler's writes past the last visible row.
     */
    private static final int SCALER_PADDING_ROWS = 1;
    private static final long FALLBACK_FRAME_MILLISECONDS = 67;
    private static final double MILLISECONDS_PER_SECOND = 1000.0;

    private static final int ERROR_TEXT_SIZE = 256;
    private static final int MAXIMUM_AUDIO_CHANNELS = 2;
    private static final float MINIMUM_FLOAT_SAMPLE = -1f;
    private static final float MAXIMUM_FLOAT_SAMPLE = 1f;
    private static final int SEEK_FROM_BEGINNING = 0;
    private static final int SEEK_FROM_CURRENT = 1;
    private static final int SEEK_FROM_END = 2;
    private static final long FIRST_STREAM_IDENTIFIER = 1L;

    /**
     * The open streams by the identifier their {@code opaque} pointer holds.
     */
    private static final @NotNull Map<Long, FfmpegVideoStream> STREAMS = new ConcurrentHashMap<>();
    private static final @NotNull AtomicLong NEXT_STREAM_IDENTIFIER = new AtomicLong(FIRST_STREAM_IDENTIFIER);

    /**
     * The shared read callback: copies the next bytes of the stream named by {@code opaque}.
     */
    private static final @NotNull Read_packet_Pointer_BytePointer_int READER = new Read_packet_Pointer_BytePointer_int() {
        /**
         * Reads bytes for the stream identified by the callback context.
         *
         * @param opaque the retained stream identifier
         * @param target the FFmpeg input buffer
         * @param size   the requested byte count
         * @return the copied count, or the EOF error
         */
        @Override
        public int call(@NotNull Pointer opaque, @NotNull BytePointer target, int size) {
            FfmpegVideoStream stream = STREAMS.get(new LongPointer(opaque).get());

            return stream == null ? AVERROR_EOF : stream.read(target, size);
        }
    };

    /**
     * The shared seek callback: resolves a seek or size query of the stream named by {@code opaque}.
     */
    private static final @NotNull Seek_Pointer_long_int SEEKER = new Seek_Pointer_long_int() {
        /**
         * Resolves a seek or size query for the stream identified by the callback context.
         *
         * @param opaque the retained stream identifier
         * @param offset the signed byte offset
         * @param whence the seek origin and flags
         * @return the resulting position or size, or {@code -1} for an invalid stream or seek
         */
        @Override
        public long call(@NotNull Pointer opaque, long offset, int whence) {
            FfmpegVideoStream stream = STREAMS.get(new LongPointer(opaque).get());

            return stream == null ? -1 : stream.seek(offset, whence);
        }
    };

    /**
     * Formats the error number and its zero-terminated FFmpeg message.
     *
     * @param error the FFmpeg error code
     * @return the diagnostic text
     */
    private static @NotNull String describeError(int error) {
        byte[] text = new byte[ERROR_TEXT_SIZE];

        av_strerror(error, text, text.length);

        int length = 0;

        while (length < text.length && text[length] != 0) {
            length++;
        }

        return error + " (" + new String(text, 0, length, StandardCharsets.UTF_8) + ")";
    }

    private @Nullable AVIOContext input;
    private @Nullable AVFormatContext format;
    private @Nullable AVCodecContext codec;
    private @Nullable SwsContext scaler;
    private @Nullable BytePointer buffer;
    private @Nullable AVCodecContext audioCodec;

    private final byte @NotNull [] data;
    private int position;
    private final long streamIdentifier;
    private final @NotNull LongPointer opaque;

    private final @NotNull Deque<AVPacket> videoPackets;
    private final @NotNull ByteArrayOutputStream audioSamples;
    private long audioMilliseconds;
    private long frameTime;
    private long lastFrameMilliseconds;
    private byte @Nullable [] pendingPixels;
    private boolean inputEnded;
    private boolean videoEnded;
    private boolean videoDrained;

    private final @NotNull AVFrame frame;
    private final @NotNull AVFrame converted;
    private final @NotNull AVPacket packet;

    private final int streamIndex;
    private final int width;
    private final int height;
    private final long frameMilliseconds;
    private final int audioIndex;

    /**
     * Creates a {@link FfmpegVideoStream} instance over a clip, optionally with its audio track.
     *
     * @param clip      the clip bytes
     * @param withAudio whether the audio track is decoded ({@link #readAudio})
     * @throws IOException if FFmpeg cannot open or decode the clip
     */
    FfmpegVideoStream(byte @NotNull [] clip, boolean withAudio) throws IOException {
        this.input      = null;
        this.format     = null;
        this.codec      = null;
        this.scaler     = null;
        this.buffer     = null;
        this.audioCodec = null;

        this.data             = clip;
        this.position         = 0;
        this.streamIdentifier = NEXT_STREAM_IDENTIFIER.getAndIncrement();
        this.opaque           = new LongPointer(1);
        this.opaque.put(this.streamIdentifier);

        STREAMS.put(this.streamIdentifier, this);

        this.videoPackets          = new ArrayDeque<>();
        this.audioSamples          = new ByteArrayOutputStream();
        this.audioMilliseconds     = 0;
        this.frameTime             = -1;
        this.lastFrameMilliseconds = -1;
        this.pendingPixels         = null;
        this.inputEnded            = false;
        this.videoEnded            = false;
        this.videoDrained          = false;

        this.frame     = av_frame_alloc();
        this.converted = av_frame_alloc();
        this.packet    = av_packet_alloc();

        BytePointer inputBuffer = new BytePointer(av_malloc(INPUT_BUFFER_SIZE));

        this.input = avio_alloc_context(inputBuffer, INPUT_BUFFER_SIZE, READ_ONLY_INPUT, this.opaque, READER, null, SEEKER);

        AVFormatContext opened = avformat_alloc_context();

        opened.pb(this.input);
        opened.flags(opened.flags() | CUSTOM_INPUT_FLAG);

        int opening = avformat_open_input(opened, (String) null, null, null);

        if (opening < 0) {
            this.close();

            throw Exceptions.newIOException("FFmpeg cannot open the video: {0}", describeError(opening));
        }

        this.format = opened;

        if (avformat_find_stream_info(opened, (PointerPointer<?>) null) < 0) {
            this.close();

            throw Exceptions.newIOException("FFmpeg cannot read the video stream information.");
        }

        this.streamIndex = av_find_best_stream(opened, AVMEDIA_TYPE_VIDEO, AUTOMATIC_STREAM, NO_RELATED_STREAM, (AVCodec) null, 0);

        if (this.streamIndex < 0) {
            this.close();

            throw Exceptions.newIOException("The clip has no video stream.");
        }

        AVStream stream = opened.streams(this.streamIndex);
        // The libvpx decoders read the alpha plane that WebM stores beside the color frames; the built-in ones drop it.
        int codecIdentifier = stream.codecpar().codec_id();
        AVCodec decoder = codecIdentifier == AV_CODEC_ID_VP8 ? avcodec_find_decoder_by_name("libvpx")
                                                             : codecIdentifier == AV_CODEC_ID_VP9 ? avcodec_find_decoder_by_name("libvpx-vp9") : null;

        if (decoder == null || decoder.isNull()) {
            decoder = avcodec_find_decoder(codecIdentifier);
        }

        if (decoder == null) {
            this.close();

            throw Exceptions.newIOException("FFmpeg has no decoder for the clip.");
        }

        AVCodecContext decoderContext = avcodec_alloc_context3(decoder);

        if (decoderContext == null || decoderContext.isNull()) {
            this.close();

            throw Exceptions.newIOException("FFmpeg cannot allocate the video decoder.");
        }

        this.codec = decoderContext;
        avcodec_parameters_to_context(decoderContext, stream.codecpar());

        if (avcodec_open2(decoderContext, decoder, (PointerPointer<?>) null) < 0) {
            this.close();

            throw Exceptions.newIOException("FFmpeg cannot open the video decoder.");
        }

        this.width  = decoderContext.width();
        this.height = decoderContext.height();

        int size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, this.width, this.height, IMAGE_ALIGNMENT);
        int rowStride = size / this.height;
        int paddedSize = size + SCALER_PADDING_ROWS * rowStride;

        this.buffer = new BytePointer(av_malloc(paddedSize)).capacity(paddedSize);
        av_image_fill_arrays(this.converted.data(), this.converted.linesize(), this.buffer,
                             AV_PIX_FMT_RGBA, this.width, this.height, IMAGE_ALIGNMENT);

        AVRational rate = stream.avg_frame_rate().num() > 0 ? stream.avg_frame_rate() : stream.r_frame_rate();

        this.frameMilliseconds = rate.num() > 0 ? Math.round(MILLISECONDS_PER_SECOND * rate.den() / rate.num()) : FALLBACK_FRAME_MILLISECONDS;
        this.audioIndex        = withAudio
                                 ? av_find_best_stream(opened, AVMEDIA_TYPE_AUDIO, AUTOMATIC_STREAM, NO_RELATED_STREAM, (AVCodec) null, 0)
                                 : -1;

        if (this.audioIndex >= 0) {
            AVStream audioStream = opened.streams(this.audioIndex);
            AVCodec audioDecoder = avcodec_find_decoder(audioStream.codecpar().codec_id());
            AVCodecContext audioContext = audioDecoder == null ? null : avcodec_alloc_context3(audioDecoder);

            if (audioContext != null) {
                avcodec_parameters_to_context(audioContext, audioStream.codecpar());

                if (avcodec_open2(audioContext, audioDecoder, (PointerPointer<?>) null) >= 0) {
                    this.audioCodec = audioContext;
                } else {
                    avcodec_free_context(audioContext);
                }
            }
        }
    }

    /**
     * @return the frame width in pixels
     */
    @Override
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the frame height in pixels
     */
    @Override
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the frame period in milliseconds from the stream frame rate
     */
    @Override
    public long getFrameMilliseconds() {
        return this.frameMilliseconds;
    }

    /**
     * @return the presentation time of the frame last returned by {@link #readNextFrame} or {@link #readNextLoopedFrame} in milliseconds, or {@code -1} if the frame has none
     */
    @Override
    public long getLastFrameMilliseconds() {
        return this.lastFrameMilliseconds;
    }

    /**
     * @return the audio channels of {@link #readAudio} ({@code 1} or {@code 2}), or {@code 0} without audio
     */
    @Override
    public int getAudioChannels() {
        return this.audioCodec == null ? 0 : Math.min(MAXIMUM_AUDIO_CHANNELS, this.audioCodec.ch_layout().nb_channels());
    }

    /**
     * @return the audio sample rate in hertz, or {@code 0} without audio
     */
    @Override
    public int getAudioSampleRate() {
        return this.audioCodec == null ? 0 : this.audioCodec.sample_rate();
    }

    /**
     * @return {@code true} if every frame has been returned by {@link #readFrameAt}, {@code false} otherwise
     */
    @Override
    public boolean isFinished() {
        return this.videoDrained && this.pendingPixels == null;
    }


    /**
     * Reads ahead until the audio is decoded up to a time and returns the PCM decoded since the last call.
     *
     * @param untilMilliseconds the time the audio should reach
     * @return little-endian signed {@code 16}-bit interleaved samples, possibly empty
     */
    @Override
    public byte @NotNull [] readAudio(long untilMilliseconds) {
        while (this.audioCodec != null && !this.inputEnded && this.audioMilliseconds < untilMilliseconds) {
            this.readPacket();
        }

        byte[] samples = this.audioSamples.toByteArray();

        this.audioSamples.reset();

        return samples;
    }

    /**
     * Decodes the frame that is due at a time: the last frame whose presentation time is not later.
     *
     * @param milliseconds the playback time
     * @return the RGBA pixels of a new frame, or {@code null} if the shown frame stays
     */
    @Override
    public byte @Nullable [] readFrameAt(long milliseconds) {
        AVCodecContext decoder = this.codec;
        AVFormatContext opened = this.format;

        if (decoder == null || opened == null) {
            return null;
        }

        byte[] latest = null;

        if (this.pendingPixels != null) {
            if (this.frameTime > milliseconds) {
                return null;
            }

            latest             = this.pendingPixels;
            this.pendingPixels = null;
        }

        AVRational timeBase = opened.streams(this.streamIndex).time_base();

        while (true) {
            if (avcodec_receive_frame(decoder, this.frame) >= 0) {
                long time = this.frame.best_effort_timestamp();

                this.frameTime = time == AV_NOPTS_VALUE ? this.frameTime + this.frameMilliseconds
                                                        : Math.round(MILLISECONDS_PER_SECOND * time * timeBase.num() / timeBase.den());

                byte[] pixels = this.convertFramePixels();

                if (this.frameTime > milliseconds) {
                    this.pendingPixels = pixels;

                    return latest;
                }

                latest = pixels;

                continue;
            }

            while (this.videoPackets.isEmpty() && !this.inputEnded) {
                this.readPacket();
            }

            AVPacket next = this.videoPackets.pollFirst();

            if (next == null) {
                if (this.videoEnded) {
                    this.videoDrained = true;

                    return latest;
                }

                avcodec_send_packet(decoder, null);
                this.videoEnded = true;

                continue;
            }

            avcodec_send_packet(decoder, next);
            av_packet_free(next);
        }
    }

    /**
     * Decodes the next frame, starting over at the end of the clip.
     *
     * @return the RGBA pixels, or {@code null} if the clip has no decodable frame
     */
    @Override
    public byte @Nullable [] readNextLoopedFrame() {
        byte[] pixels = this.readNextFrame();

        if (pixels != null) {
            return pixels;
        }

        AVFormatContext opened = this.format;
        AVCodecContext decoder = this.codec;

        if (opened != null && decoder != null) {
            av_seek_frame(opened, this.streamIndex, 0, AVSEEK_FLAG_BACKWARD);
            avcodec_flush_buffers(decoder);
        }

        return this.readNextFrame();
    }

    /**
     * Decodes the next frame without starting over (whole-clip decoding).
     *
     * @return the RGBA pixels, or {@code null} at the end of the clip
     */
    @Override
    public byte @Nullable [] readNextFrame() {
        AVFormatContext opened = this.format;
        AVCodecContext decoder = this.codec;

        if (opened == null || decoder == null) {
            return null;
        }

        while (true) {
            if (avcodec_receive_frame(decoder, this.frame) >= 0) {
                return this.convertFramePixelsWithTimestamp(opened);
            }

            if (av_read_frame(opened, this.packet) < 0) {
                avcodec_send_packet(decoder, null);

                return avcodec_receive_frame(decoder, this.frame) >= 0 ? this.convertFramePixelsWithTimestamp(opened) : null;
            }

            if (this.packet.stream_index() == this.streamIndex) {
                avcodec_send_packet(decoder, this.packet);
            }

            av_packet_unref(this.packet);
        }
    }

    /**
     * Queues one video packet or immediately decodes an audio packet; marks input exhaustion on failure.
     */
    private void readPacket() {
        AVFormatContext opened = this.format;

        if (opened == null || av_read_frame(opened, this.packet) < 0) {
            this.inputEnded = true;

            return;
        }

        if (this.packet.stream_index() == this.streamIndex) {
            this.videoPackets.addLast(av_packet_clone(this.packet));
        } else if (this.packet.stream_index() == this.audioIndex && this.audioCodec != null) {
            this.decodeAudio(this.audioCodec);
        }

        av_packet_unref(this.packet);
    }

    /**
     * Appends received audio frames as little-endian signed {@code 16}-bit samples.
     *
     * @param decoder the audio decoder
     */
    private void decodeAudio(@NotNull AVCodecContext decoder) {
        if (avcodec_send_packet(decoder, this.packet) < 0) {
            return;
        }

        int channels = this.getAudioChannels();

        while (avcodec_receive_frame(decoder, this.frame) >= 0) {
            int sampleCount = this.frame.nb_samples();
            int sampleFormat = this.frame.format();
            boolean planar = sampleFormat == AV_SAMPLE_FMT_FLTP || sampleFormat == AV_SAMPLE_FMT_S16P;
            boolean floating = sampleFormat == AV_SAMPLE_FMT_FLTP || sampleFormat == AV_SAMPLE_FMT_FLT;
            int sourceChannels = this.frame.ch_layout().nb_channels();
            byte[] block = new byte[sampleCount * channels * Short.BYTES];
            int offset = 0;

            for (int sample = 0; sample < sampleCount; sample++) {
                for (int channel = 0; channel < channels; channel++) {
                    int plane = planar ? channel : 0;
                    int sampleIndex = planar ? sample : sample * sourceChannels + channel;
                    BytePointer planeData = this.frame.data(plane);
                    int value;

                    if (floating) {
                        float level = planeData.getFloat((long) sampleIndex * Float.BYTES);

                        value = Math.round(Math.clamp(level, MINIMUM_FLOAT_SAMPLE, MAXIMUM_FLOAT_SAMPLE) * Short.MAX_VALUE);
                    } else {
                        value = planeData.getShort((long) sampleIndex * Short.BYTES);
                    }

                    block[offset++] = (byte) value;
                    block[offset++] = (byte) (value >> Byte.SIZE);
                }
            }

            this.audioSamples.writeBytes(block);
            this.audioMilliseconds += Math.round(MILLISECONDS_PER_SECOND * sampleCount / decoder.sample_rate());
        }
    }

    /**
     * Copies the next available input bytes into the FFmpeg buffer.
     *
     * @param target the destination buffer
     * @param size   the requested byte count
     * @return the copied count, or the EOF error when exhausted
     */
    private int read(@NotNull BytePointer target, int size) {
        int remaining = this.data.length - this.position;

        if (remaining <= 0) {
            return AVERROR_EOF;
        }

        int count = Math.min(size, remaining);

        target.position(0).put(this.data, this.position, count);
        this.position += count;

        return count;
    }

    /**
     * Resolves a seek origin or size query against the in-memory clip.
     *
     * @param offset the signed byte offset
     * @param whence the seek origin and flags
     * @return the new position or input size, or {@code -1} when invalid
     */
    private long seek(long offset, int whence) {
        long target;

        switch (whence & ~AVSEEK_FORCE) {
            case AVSEEK_SIZE -> {
                return this.data.length;
            }
            case SEEK_FROM_BEGINNING -> target = offset;
            case SEEK_FROM_CURRENT -> target = this.position + offset;
            case SEEK_FROM_END -> target = this.data.length + offset;
            default -> {
                return -1;
            }
        }

        if (target < 0 || target > this.data.length) {
            return -1;
        }

        this.position = (int) target;

        return target;
    }

    /**
     * Stores the frame presentation time before converting its pixels.
     *
     * @param opened the open format context
     * @return the RGBA pixels
     */
    private byte @NotNull [] convertFramePixelsWithTimestamp(@NotNull AVFormatContext opened) {
        long time = this.frame.best_effort_timestamp();
        AVRational timeBase = opened.streams(this.streamIndex).time_base();

        this.lastFrameMilliseconds = time == AV_NOPTS_VALUE ? -1 : Math.round(MILLISECONDS_PER_SECOND * time * timeBase.num() / timeBase.den());

        return this.convertFramePixels();
    }

    /**
     * Converts the received frame into a newly allocated visible RGBA byte array.
     *
     * @return the RGBA pixels
     */
    private byte @NotNull [] convertFramePixels() {
        BytePointer convertedPixels = this.requireBuffer();

        if (this.scaler == null) {
            this.scaler = sws_getContext(this.width, this.height, this.frame.format(),
                                         this.width, this.height, AV_PIX_FMT_RGBA,
                                         SWS_POINT, null, null, (DoublePointer) null);
        }

        sws_scale(this.scaler, this.frame.data(), this.frame.linesize(), 0, this.height, this.converted.data(), this.converted.linesize());

        int rowBytes = this.width * RGBA_BYTES;
        int stride = this.converted.linesize(0);
        byte[] pixels = new byte[rowBytes * this.height];

        if (stride == rowBytes) {
            convertedPixels.position(0).get(pixels);
        } else {
            for (int row = 0; row < this.height; row++) {
                convertedPixels.position((long) row * stride).get(pixels, row * rowBytes, rowBytes);
            }
        }

        return pixels;
    }

    /**
     * Returns the RGBA image buffer of an open stream.
     *
     * @return the RGBA image buffer
     * @throws IllegalStateException if the stream is closed
     */
    private @NotNull BytePointer requireBuffer() {
        BytePointer opened = this.buffer;

        if (opened == null) {
            throw Exceptions.newIllegalStateException("The video stream is closed.");
        }

        return opened;
    }


    /**
     * Closes the stream and releases its decoder resources. A stream is closed once.
     * <p>
     * Releases the scaler, buffers, packets, frames and contexts, then removes the callback registration.
     */
    @Override
    public void close() {
        if (this.scaler != null) {
            sws_freeContext(this.scaler);
        }

        this.scaler = null;

        if (this.buffer != null) {
            av_free(this.buffer.position(0));
        }

        this.buffer = null;

        av_packet_free(this.packet);
        av_frame_free(this.frame);
        av_frame_free(this.converted);

        if (this.codec != null) {
            avcodec_free_context(this.codec);
        }

        this.codec = null;

        if (this.audioCodec != null) {
            avcodec_free_context(this.audioCodec);
        }

        this.audioCodec = null;

        for (AVPacket queued : this.videoPackets) {
            av_packet_free(queued);
        }

        this.videoPackets.clear();

        if (this.format != null) {
            avformat_close_input(this.format);
        }

        this.format = null;

        if (this.input != null) {
            av_free(this.input.buffer());
            this.input.buffer(null);
            avio_context_free(this.input);
        }

        this.input = null;

        STREAMS.remove(this.streamIdentifier);
        this.opaque.close();
    }
}
