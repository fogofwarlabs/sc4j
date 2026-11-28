package org.fogofwarlabs.sc4j.engine.client.audio;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Checks that {@link WaveData#decode} keeps RIFF WAVE PCM samples, reduces 24-bit samples to their upper {@code 16} bits, skips padded unknown
 * chunks, rejects malformed files and decodes Ogg Vorbis streams up to their end.
 */
final class WaveDataTest {
    private static final int RIFF_IDENTIFIER = 0x46464952;
    private static final int WAVE_IDENTIFIER = 0x45564157;
    private static final int FORMAT_CHUNK_IDENTIFIER = 0x20746D66;
    private static final int JUNK_CHUNK_IDENTIFIER = 0x4B4E554A;
    private static final int DATA_CHUNK_IDENTIFIER = 0x61746164;

    private static final int FILE_BYTES = 58;
    private static final int RIFF_BYTES = 50;
    private static final int FORMAT_CHUNK_BYTES = 16;
    private static final int JUNK_CHUNK_BYTES = 1;
    private static final int DATA_CHUNK_BYTES = 4;

    private static final int PCM_FORMAT = 1;
    private static final int CHANNELS = 1;
    private static final int SAMPLE_RATE = 22050;
    private static final int BYTE_RATE = 44100;
    private static final int BLOCK_ALIGNMENT = 2;
    private static final int SAMPLE_BITS = 16;

    /**
     * The 24-bit file: its sizes and format, and the sample width it is reduced to.
     */
    private static final int TWENTY_FOUR_BIT_FILE_BYTES = 50;
    private static final int TWENTY_FOUR_BIT_RIFF_BYTES = 42;
    private static final int TWENTY_FOUR_BIT_DATA_CHUNK_BYTES = 6;
    private static final int TWENTY_FOUR_BIT_SAMPLE_RATE = 48000;
    private static final int TWENTY_FOUR_BIT_BYTE_RATE = 144000;
    private static final int TWENTY_FOUR_BIT_BLOCK_ALIGNMENT = 3;
    private static final int TWENTY_FOUR_BIT_SAMPLE_BITS = 24;
    private static final int REDUCED_SAMPLE_BITS = 16;

    /**
     * The unknown chunk's single byte and the pad byte that aligns the next chunk.
     */
    private static final int JUNK_BYTE = 7;
    private static final int PAD_BYTE = 0;

    /**
     * The malformed files: a compressed format tag, a RIFF size past the file end and a data size that splits a sample, written at their offsets.
     */
    private static final int RIFF_BYTES_OFFSET = 4;
    private static final int FORMAT_TAG_OFFSET = 20;
    private static final int DATA_CHUNK_BYTES_OFFSET = 50;
    private static final int COMPRESSED_FORMAT = 2;
    private static final int OVERSIZED_RIFF_BYTES = 255;
    private static final int MISALIGNED_DATA_CHUNK_BYTES = 3;

    /**
     * The bytes after the data chunk of a file whose RIFF size counts them: a chunk header whose size passes the file end, and two bytes.
     */
    private static final int TRAILING_BYTES = 10;
    private static final int TRAILING_CHUNK_BYTES = 0xFFFF0000;
    private static final int RIFF_HEADER_EXCLUDED_BYTES = 8;

    /**
     * The Ogg Vorbis stream cut out of a longer stereo {@code 44100} Hz stream: its pages keep the granule positions of the longer stream, it starts
     * with one long block followed by short blocks, has a run of long blocks after them and ends without an end-of-stream page.
     */
    private static final String CUT_STREAM_RESOURCE = "/audio/cut-stream.ogg";
    private static final int CUT_STREAM_CHANNELS = 2;
    private static final int CUT_STREAM_SAMPLE_RATE = 44100;
    private static final int CUT_STREAM_HEADER_PAGES_BYTES = 3927;

    /**
     * The sample frames decoded from the cut stream: the {@code 12224} its packets after the first one add up to, less the {@code 896} at the start
     * that overlap the first long block and are discarded.
     */
    private static final int CUT_STREAM_SAMPLE_FRAMES = 11328;

    private static byte @NotNull [] readCutStream() throws IOException {
        try (InputStream input = WaveDataTest.class.getResourceAsStream(CUT_STREAM_RESOURCE)) {
            if (input == null) {
                throw Exceptions.newIOException("Missing test resource {0}", CUT_STREAM_RESOURCE);
            }

            return input.readAllBytes();
        }
    }

    private static byte @NotNull [] createWaveContents() {
        ByteBuffer result = ByteBuffer.allocate(FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        result.putInt(RIFF_IDENTIFIER).putInt(RIFF_BYTES).putInt(WAVE_IDENTIFIER);
        result.putInt(FORMAT_CHUNK_IDENTIFIER).putInt(FORMAT_CHUNK_BYTES).putShort((short) PCM_FORMAT).putShort((short) CHANNELS);
        result.putInt(SAMPLE_RATE).putInt(BYTE_RATE).putShort((short) BLOCK_ALIGNMENT).putShort((short) SAMPLE_BITS);
        result.putInt(JUNK_CHUNK_IDENTIFIER).putInt(JUNK_CHUNK_BYTES).put((byte) JUNK_BYTE).put((byte) PAD_BYTE);
        result.putInt(DATA_CHUNK_IDENTIFIER).putInt(DATA_CHUNK_BYTES).putShort((short) 0x1234).putShort((short) 0xEDCC);

        return result.array();
    }


    @Test
    void decode_should_keep_pcm_samples_and_skip_padded_unknown_chunk() throws IOException {
        byte[] contents = createWaveContents();
        WaveData data = WaveData.decode(contents);

        Assertions.assertEquals(CHANNELS, data.getChannels());
        Assertions.assertEquals(SAMPLE_RATE, data.getSampleRate());
        Assertions.assertEquals(SAMPLE_BITS, data.getSampleBits());
        Assertions.assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xCC, (byte) 0xED}, data.getSamples());
    }

    @Test
    void decode_should_not_read_the_bytes_after_the_data_chunk() throws IOException {
        ByteBuffer file = ByteBuffer.allocate(FILE_BYTES + TRAILING_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        file.put(createWaveContents());
        file.putInt(JUNK_CHUNK_IDENTIFIER).putInt(TRAILING_CHUNK_BYTES).put((byte) JUNK_BYTE).put((byte) JUNK_BYTE);
        file.putInt(RIFF_BYTES_OFFSET, FILE_BYTES + TRAILING_BYTES - RIFF_HEADER_EXCLUDED_BYTES);

        WaveData data = WaveData.decode(file.array());

        Assertions.assertEquals(CHANNELS, data.getChannels());
        Assertions.assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xCC, (byte) 0xED}, data.getSamples());
    }

    @Test
    void decode_should_reduce_twenty_four_bit_samples_to_upper_sixteen_bits() throws IOException {
        ByteBuffer file = ByteBuffer.allocate(TWENTY_FOUR_BIT_FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        file.putInt(RIFF_IDENTIFIER).putInt(TWENTY_FOUR_BIT_RIFF_BYTES).putInt(WAVE_IDENTIFIER);
        file.putInt(FORMAT_CHUNK_IDENTIFIER).putInt(FORMAT_CHUNK_BYTES).putShort((short) PCM_FORMAT).putShort((short) CHANNELS);
        file.putInt(TWENTY_FOUR_BIT_SAMPLE_RATE).putInt(TWENTY_FOUR_BIT_BYTE_RATE).putShort((short) TWENTY_FOUR_BIT_BLOCK_ALIGNMENT)
            .putShort((short) TWENTY_FOUR_BIT_SAMPLE_BITS);
        file.putInt(DATA_CHUNK_IDENTIFIER).putInt(TWENTY_FOUR_BIT_DATA_CHUNK_BYTES);
        file.put(new byte[]{0x11, 0x22, 0x33, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC});

        WaveData data = WaveData.decode(file.array());

        Assertions.assertEquals(CHANNELS, data.getChannels());
        Assertions.assertEquals(TWENTY_FOUR_BIT_SAMPLE_RATE, data.getSampleRate());
        Assertions.assertEquals(REDUCED_SAMPLE_BITS, data.getSampleBits());
        Assertions.assertArrayEquals(new byte[]{0x22, 0x33, (byte) 0xBB, (byte) 0xCC}, data.getSamples());
    }

    @Test
    void decode_should_reject_compressed_truncated_and_misaligned_samples() {
        byte[] compressed = createWaveContents();

        compressed[FORMAT_TAG_OFFSET] = COMPRESSED_FORMAT;

        Assertions.assertThrows(IOException.class, () -> WaveData.decode(compressed));

        byte[] truncated = createWaveContents();

        truncated[RIFF_BYTES_OFFSET] = (byte) OVERSIZED_RIFF_BYTES;

        Assertions.assertThrows(IOException.class, () -> WaveData.decode(truncated));

        byte[] misaligned = createWaveContents();

        misaligned[DATA_CHUNK_BYTES_OFFSET] = MISALIGNED_DATA_CHUNK_BYTES;

        Assertions.assertThrows(IOException.class, () -> WaveData.decode(misaligned));
    }

    @Test
    void decode_should_decode_a_cut_ogg_vorbis_stream_past_its_frames_without_samples() throws IOException {
        WaveData data = WaveData.decode(readCutStream());

        Assertions.assertEquals(CUT_STREAM_CHANNELS, data.getChannels());
        Assertions.assertEquals(CUT_STREAM_SAMPLE_RATE, data.getSampleRate());
        Assertions.assertEquals(Short.SIZE, data.getSampleBits());
        Assertions.assertEquals(CUT_STREAM_SAMPLE_FRAMES * CUT_STREAM_CHANNELS * Short.BYTES, data.getSamples().length);
    }

    @Test
    void decode_should_reject_an_ogg_vorbis_stream_without_audio() throws IOException {
        byte[] headers = Arrays.copyOf(readCutStream(), CUT_STREAM_HEADER_PAGES_BYTES);

        Assertions.assertThrows(IOException.class, () -> WaveData.decode(headers));
    }
}
