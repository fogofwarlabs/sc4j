package org.fogofwarlabs.sc4j.engine.client.audio;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.audio.SoundClip;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Decodes RIFF WAVE PCM and Ogg Vorbis sound data into mono or stereo PCM samples, the engine default sound decoder; a client turns the result
 * into a {@link SoundClip} ({@link #toSoundClip()}) and plays it through an {@link AudioMixer}.
 */
public final class WaveData {
    private static final int RIFF_IDENTIFIER = 0x46464952;
    private static final int WAVE_IDENTIFIER = 0x45564157;
    private static final int FORMAT_CHUNK_IDENTIFIER = 0x20746D66;
    private static final int DATA_CHUNK_IDENTIFIER = 0x61746164;

    private static final int RIFF_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int RIFF_BYTES_OFFSET = 4;
    private static final int WAVE_IDENTIFIER_OFFSET = 8;
    private static final int CHUNK_LENGTH_OFFSET = 4;
    private static final int PCM_FORMAT_BYTES = 16;

    private static final int PCM_FORMAT = 1;
    private static final int MONO_CHANNELS = 1;
    private static final int STEREO_CHANNELS = 2;

    private static final int CHANNELS_OFFSET = 2;
    private static final int SAMPLE_RATE_OFFSET = 4;
    private static final int BYTE_RATE_OFFSET = 8;
    private static final int BLOCK_ALIGNMENT_OFFSET = 12;
    private static final int SAMPLE_BITS_OFFSET = 14;

    private static final int PCM24_SAMPLE_BITS = 24;
    private static final int PCM24_SAMPLE_BYTES = 3;
    private static final int PCM24_MIDDLE_BYTE_OFFSET = 1;
    private static final int PCM24_HIGH_BYTE_OFFSET = 2;

    private static final int WORD_ALIGNMENT_MASK = 1;

    /**
     * The unsigned value of silence in 8-bit PCM.
     */
    private static final int EIGHT_BIT_SILENCE = 0x80;

    /**
     * The length of the Ogg page capture pattern {@code OggS} that starts an Ogg Vorbis file.
     */
    private static final int OGG_CAPTURE_PATTERN_BYTES = 4;

    /**
     * The long block length in maximal frame sizes of {@code stb_vorbis}: one decoded frame holds up to one long block of samples per channel.
     */
    private static final int LONG_BLOCK_FRAME_SIZES = 2;

    /**
     * Creates a {@link WaveData} instance by decoding a sound file: Ogg Vorbis data to 16-bit PCM with {@code stb_vorbis}, any other file as mono or
     * stereo 8-bit, 16-bit or 24-bit PCM of a RIFF WAVE container; 24-bit samples are reduced to {@code 16} bits.
     * <p>
     * One decoder serves every sound of a client: its own files, map sounds and menu sounds.
     *
     * @param contents the complete file
     * @return the created {@link WaveData} instance
     * @throws IOException if the file is neither format, its container or sample format is unsupported or malformed, or it cannot be decoded
     */
    public static @NotNull WaveData decode(byte @NotNull [] contents) throws IOException {
        if (contents.length < OGG_CAPTURE_PATTERN_BYTES || contents[0] != 'O' || contents[1] != 'g' || contents[2] != 'g' || contents[3] != 'S') {
            return parse(contents);
        }

        ByteBuffer input = MemoryUtil.memAlloc(contents.length);

        try {
            input.put(contents).flip();

            return decodeVorbis(input);
        } finally {
            MemoryUtil.memFree(input);
        }
    }

    /**
     * Creates a {@link WaveData} instance by decoding an Ogg Vorbis stream frame by frame up to its end. Frames without samples are skipped;
     * decoding ends when the decoder reports an error or a frame without samples reads no more input.
     *
     * @param input the complete file in native memory
     * @return the created {@link WaveData} instance
     * @throws IOException if the stream cannot be opened, has neither {@code 1} nor {@code 2} channels or holds no samples
     */
    private static @NotNull WaveData decodeVorbis(@NotNull ByteBuffer input) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long decoder = STBVorbis.stb_vorbis_open_memory(input, stack.mallocInt(1), null);

            if (decoder == MemoryUtil.NULL) {
                throw Exceptions.newIOException("Ogg Vorbis sound cannot be decoded.");
            }

            try {
                STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(decoder, STBVorbisInfo.malloc(stack));
                int channels = info.channels();

                if (channels != MONO_CHANNELS && channels != STEREO_CHANNELS) {
                    throw Exceptions.newIOException("Ogg Vorbis sound has {0} channels.", channels);
                }

                ShortBuffer frame = MemoryUtil.memAllocShort(channels * info.max_frame_size() * LONG_BLOCK_FRAME_SIZES);

                try {
                    short[] samples = new short[frame.capacity()];
                    int sampleCount = 0;
                    int inputOffset = STBVorbis.stb_vorbis_get_file_offset(decoder);

                    while (true) {
                        int frameLength = STBVorbis.stb_vorbis_get_frame_short_interleaved(decoder, channels, frame);
                        int previousInputOffset = inputOffset;

                        inputOffset = STBVorbis.stb_vorbis_get_file_offset(decoder);

                        if (frameLength == 0) {
                            if (STBVorbis.stb_vorbis_get_error(decoder) != STBVorbis.VORBIS__no_error || inputOffset == previousInputOffset) {
                                break;
                            }

                            continue;
                        }

                        int frameSamples = frameLength * channels;

                        if (sampleCount + frameSamples > samples.length) {
                            samples = Arrays.copyOf(samples, Math.max(samples.length * 2, sampleCount + frameSamples));
                        }

                        frame.get(0, samples, sampleCount, frameSamples);
                        sampleCount += frameSamples;
                    }

                    if (sampleCount == 0) {
                        throw Exceptions.newIOException("Ogg Vorbis sound has no samples.");
                    }

                    byte[] contents = new byte[sampleCount * Short.BYTES];

                    ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, sampleCount);

                    return new WaveData(channels, info.sample_rate(), Short.SIZE, contents);
                } finally {
                    MemoryUtil.memFree(frame);
                }
            } finally {
                STBVorbis.stb_vorbis_close(decoder);
            }
        }
    }

    /**
     * Creates a {@link WaveData} instance by reading mono or stereo 8-bit, 16-bit or 24-bit PCM from a RIFF WAVE container; 24-bit samples are
     * reduced to {@code 16} bits. Chunks are read in order up to the first {@code data} chunk after the {@code fmt } chunk; the bytes after it are
     * not read.
     *
     * @param contents the complete file
     * @return the created {@link WaveData} instance
     * @throws IOException if the container or sample format is unsupported or malformed
     */
    private static @NotNull WaveData parse(byte @NotNull [] contents) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN);

        if (contents.length < RIFF_HEADER_BYTES || input.getInt(0) != RIFF_IDENTIFIER || input.getInt(WAVE_IDENTIFIER_OFFSET) != WAVE_IDENTIFIER) {
            throw Exceptions.newIOException("Sound is not a RIFF WAVE file.");
        }

        long end = Integer.toUnsignedLong(input.getInt(RIFF_BYTES_OFFSET)) + CHUNK_HEADER_BYTES;

        if (end > contents.length || end < RIFF_HEADER_BYTES) {
            throw Exceptions.newIOException("RIFF extent exceeds the sound file.");
        }

        int channels = 0;
        int sampleRate = 0;
        int sampleBits = 0;
        int blockAlignment = 0;
        int sampleOffset = -1;
        int sampleLength = 0;

        for (int offset = RIFF_HEADER_BYTES; offset + (long) CHUNK_HEADER_BYTES <= end; ) {
            int identifier = input.getInt(offset);
            long length = Integer.toUnsignedLong(input.getInt(offset + CHUNK_LENGTH_OFFSET));

            if (offset + (long) CHUNK_HEADER_BYTES + length > end) {
                throw Exceptions.newIOException("WAVE chunk exceeds the RIFF extent.");
            }

            int payload = offset + CHUNK_HEADER_BYTES;

            if (identifier == FORMAT_CHUNK_IDENTIFIER) {
                if (length < PCM_FORMAT_BYTES || input.getShort(payload) != PCM_FORMAT) {
                    throw Exceptions.newIOException("Sound requires unsupported non-PCM WAVE decoding.");
                }

                channels       = Short.toUnsignedInt(input.getShort(payload + CHANNELS_OFFSET));
                sampleRate     = input.getInt(payload + SAMPLE_RATE_OFFSET);
                blockAlignment = Short.toUnsignedInt(input.getShort(payload + BLOCK_ALIGNMENT_OFFSET));
                sampleBits     = Short.toUnsignedInt(input.getShort(payload + SAMPLE_BITS_OFFSET));

                if ((channels != MONO_CHANNELS && channels != STEREO_CHANNELS) ||
                    (sampleBits != Byte.SIZE && sampleBits != Short.SIZE && sampleBits != PCM24_SAMPLE_BITS) ||
                    sampleRate <= 0 || blockAlignment != channels * sampleBits / Byte.SIZE ||
                    Integer.toUnsignedLong(input.getInt(payload + BYTE_RATE_OFFSET)) != (long) sampleRate * blockAlignment) {
                    throw Exceptions.newIOException("Unsupported or inconsistent PCM sound format.");
                }
            } else if (identifier == DATA_CHUNK_IDENTIFIER && sampleOffset < 0) {
                sampleOffset = payload;
                sampleLength = (int) length;

                // The samples after the format end the reading: later bytes are not read as chunks.
                if (channels != 0) {
                    break;
                }
            }

            offset = (int) (payload + length + (length & WORD_ALIGNMENT_MASK));
        }

        if (channels == 0 || sampleOffset < 0 || sampleLength == 0 || sampleLength % blockAlignment != 0) {
            throw Exceptions.newIOException("WAVE file has missing or incomplete PCM samples.");
        }

        if (sampleBits == PCM24_SAMPLE_BITS) {
            // 24-bit PCM samples are reduced to 16 bits by retaining the upper two little-endian bytes.
            byte[] samples = new byte[sampleLength / PCM24_SAMPLE_BYTES * Short.BYTES];

            for (int sourceIndex = sampleOffset, destinationIndex = 0; destinationIndex < samples.length;
                 sourceIndex += PCM24_SAMPLE_BYTES, destinationIndex += Short.BYTES) {
                samples[destinationIndex]     = contents[sourceIndex + PCM24_MIDDLE_BYTE_OFFSET];
                samples[destinationIndex + 1] = contents[sourceIndex + PCM24_HIGH_BYTE_OFFSET];
            }

            return new WaveData(channels, sampleRate, Short.SIZE, samples);
        }

        return new WaveData(channels, sampleRate, sampleBits, Arrays.copyOfRange(contents, sampleOffset, sampleOffset + sampleLength));
    }


    private final int channels;
    private final int sampleRate;
    private final int sampleBits;
    private final byte @NotNull [] samples;

    /**
     * Creates a {@link WaveData} instance. The sample array is retained directly.
     *
     * @param channels   the channel count
     * @param sampleRate the samples per second
     * @param sampleBits the bits per sample
     * @param samples    the interleaved little-endian PCM samples
     */
    public WaveData(int channels, int sampleRate, int sampleBits, byte @NotNull [] samples) {
        this.channels   = channels;
        this.sampleRate = sampleRate;
        this.sampleBits = sampleBits;
        this.samples    = samples;
    }

    /**
     * @return the channel count
     */
    public int getChannels() {
        return this.channels;
    }

    /**
     * @return the samples per second
     */
    public int getSampleRate() {
        return this.sampleRate;
    }

    /**
     * @return the bits of one sample
     */
    public int getSampleBits() {
        return this.sampleBits;
    }

    /**
     * @return the interleaved little-endian PCM samples
     */
    public byte @NotNull [] getSamples() {
        return this.samples;
    }


    /**
     * Creates a {@link SoundClip} instance of the samples: 8-bit unsigned samples become signed {@code 16}-bit samples ({@code (sample - 128) << 8},
     * the same level), 16-bit little-endian samples are kept.
     *
     * @return the created {@link SoundClip} instance
     * @throws IllegalArgumentException if the rate or the channel count is below {@code 1}, or the samples hold no whole number of sample frames
     */
    public @NotNull SoundClip toSoundClip() {
        short[] pcm;

        if (this.sampleBits == Byte.SIZE) {
            pcm = new short[this.samples.length];

            for (int index = 0; index < pcm.length; index++) {
                pcm[index] = (short) ((Byte.toUnsignedInt(this.samples[index]) - EIGHT_BIT_SILENCE) << Byte.SIZE);
            }
        } else {
            pcm = new short[this.samples.length / Short.BYTES];

            ByteBuffer.wrap(this.samples).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        }

        return new SoundClip(this.sampleRate, this.channels, pcm);
    }
}
