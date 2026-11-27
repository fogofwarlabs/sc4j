package org.fogofwarlabs.sc4j.translate.service.fasttext;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.translate.service.model.IdentifiedLanguage;
import org.fogofwarlabs.sc4j.translate.service.model.LanguageIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Identifies languages with a supervised fastText model ({@code model.bin}, such as lid218e) as fastText predicts: the text is split at
 * white space and ends with the end-of-sentence word; each word adds its own row, when it is in the dictionary, and the rows of its
 * character n-grams, hashed into buckets; the hidden vector is the average of these input rows, and the output layer with the model's loss
 * gives the label probabilities.
 * <p>
 * The file is memory-mapped and only the input rows a text needs are read; the dictionary and the output layer are read at open. Quantized
 * models and models with the hierarchical softmax loss are refused.
 */
public final class FastTextLanguageIdentifier implements LanguageIdentifier {
    private static final int MAGIC = 793712314;
    private static final int VERSION = 12;
    private static final int SUPERVISED_MODEL = 3;
    private static final int HIERARCHICAL_SOFTMAX_LOSS = 1;
    private static final int NEGATIVE_SAMPLING_LOSS = 2;
    private static final int SOFTMAX_LOSS = 3;
    private static final int ONE_VERSUS_ALL_LOSS = 4;
    private static final byte WORD_ENTRY = 0;
    private static final byte LABEL_ENTRY = 1;

    private static final @NotNull String LABEL_PREFIX = "__label__";
    private static final byte @NotNull [] LABEL_PREFIX_BYTES = LABEL_PREFIX.getBytes(StandardCharsets.UTF_8);
    private static final byte @NotNull [] END_OF_SENTENCE = "</s>".getBytes(StandardCharsets.UTF_8);
    private static final byte BEGIN_OF_WORD = '<';
    private static final byte END_OF_WORD = '>';
    private static final byte @NotNull [] WORD_SEPARATORS = {' ', '\n', '\r', '\t', 0x0B, '\f', 0};

    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;
    private static final long WORD_NGRAM_MULTIPLIER = 116049371L;
    private static final int UTF8_CONTINUATION_MASK = 0xC0;
    private static final int UTF8_CONTINUATION = 0x80;
    private static final int SLOTS_PER_WORD = 2;
    private static final int MISSING = -1;

    private static final int SIGMOID_TABLE_SIZE = 512;
    private static final int MAXIMUM_SIGMOID = 8;
    private static final float @NotNull [] SIGMOID_TABLE;

    /**
     * The argument layout of the file header: twelve 32-bit values, then the sampling threshold.
     */
    private static final int DIMENSION_INDEX = 0;
    private static final int WORD_NGRAMS_INDEX = 5;
    private static final int LOSS_INDEX = 6;
    private static final int MODEL_INDEX = 7;
    private static final int BUCKET_INDEX = 8;
    private static final int MINIMUM_NGRAM_INDEX = 9;
    private static final int MAXIMUM_NGRAM_INDEX = 10;
    private static final int ARGUMENT_COUNT = 12;

    private static final @NotNull ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final @NotNull ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final @NotNull ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * A growable list of {@code int} values.
     */
    private static final class IntArrayBuilder {
        private int @NotNull [] values;
        private int size;

        private IntArrayBuilder(int capacity) {
            this.values = new int[Math.max(1, capacity)];
            this.size   = 0;
        }

        private void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.size * 2);
            }

            this.values[this.size++] = value;
        }

        private int get(int index) {
            return this.values[index];
        }

        private int size() {
            return this.size;
        }
    }

    static {
        SIGMOID_TABLE = new float[SIGMOID_TABLE_SIZE + 1];

        for (int index = 0; index <= SIGMOID_TABLE_SIZE; index++) {
            float value = (float) (index * 2 * MAXIMUM_SIGMOID) / SIGMOID_TABLE_SIZE - MAXIMUM_SIGMOID;

            SIGMOID_TABLE[index] = (float) (1.0 / (1.0 + Math.exp(-value)));
        }
    }

    /**
     * Opens a fastText model file.
     *
     * @param file the model file
     * @return the created {@link FastTextLanguageIdentifier} instance, which the caller closes
     * @throws IOException if the file cannot be read, is malformed or describes a model this identifier does not support
     */
    public static @NotNull FastTextLanguageIdentifier open(@NotNull Path file) throws IOException {
        Arena arena = Arena.ofShared();

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return new FastTextLanguageIdentifier(arena, channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena));
        } catch (IOException | RuntimeException exception) {
            arena.close();

            throw exception;
        }
    }

    private static int hash(byte @NotNull [] data, int start, int end) {
        int hash = FNV_OFFSET_BASIS;

        // The bytes are sign-extended, as fastText hashes chars.
        for (int index = start; index < end; index++) {
            hash = (hash ^ data[index]) * FNV_PRIME;
        }

        return hash;
    }

    private static boolean isSeparator(byte value) {
        for (byte separator : WORD_SEPARATORS) {
            if (value == separator) {
                return true;
            }
        }

        return false;
    }

    private static float sigmoid(float value) {
        if (value < -MAXIMUM_SIGMOID) {
            return 0.0f;
        }

        if (value > MAXIMUM_SIGMOID) {
            return 1.0f;
        }

        return SIGMOID_TABLE[(int) ((value + MAXIMUM_SIGMOID) * SIGMOID_TABLE_SIZE / MAXIMUM_SIGMOID / 2)];
    }

    private static boolean startsWith(byte @NotNull [] data, int start, int end, byte @NotNull [] prefix) {
        return end - start >= prefix.length && Arrays.equals(data, start, start + prefix.length, prefix, 0, prefix.length);
    }


    private final @NotNull Arena arena;
    private final @NotNull MemorySegment file;

    private final int dimension;
    private final int wordNgrams;
    private final int loss;
    private final int bucket;
    private final int minimumNgram;
    private final int maximumNgram;

    /**
     * The words of the dictionary: their bytes, the start of each word in them and the open-addressing table of word ids plus one.
     */
    private final byte @NotNull [] words;
    private final int @NotNull [] wordOffsets;
    private final int wordCount;
    private final int @NotNull [] slots;
    private final int slotMask;
    private final @NotNull String @NotNull [] labels;
    private final long pruneSize;
    private final @NotNull Map<@NotNull Integer, @NotNull Integer> pruneIndex;

    private final long inputOffset;
    private final float @NotNull [] output;

    private FastTextLanguageIdentifier(@NotNull Arena arena, @NotNull MemorySegment file) throws IOException {
        long position = 0;

        if (file.byteSize() < (long) (ARGUMENT_COUNT + 2) * Integer.BYTES || file.get(INT_LAYOUT, 0) != MAGIC ||
            file.get(INT_LAYOUT, Integer.BYTES) != VERSION) {
            throw Exceptions.newIOException("The file is not a fastText model of version {0}", VERSION);
        }

        position += 2 * Integer.BYTES;

        int[] arguments = new int[ARGUMENT_COUNT];

        for (int index = 0; index < ARGUMENT_COUNT; index++) {
            arguments[index] = file.get(INT_LAYOUT, position);
            position += Integer.BYTES;
        }

        // The sampling threshold, a double, is not used for prediction.
        position += Double.BYTES;

        if (arguments[MODEL_INDEX] != SUPERVISED_MODEL || arguments[LOSS_INDEX] == HIERARCHICAL_SOFTMAX_LOSS ||
            arguments[LOSS_INDEX] < NEGATIVE_SAMPLING_LOSS || arguments[LOSS_INDEX] > ONE_VERSUS_ALL_LOSS) {
            throw Exceptions.newIOException("The fastText model {0} with loss {1} is not supported", arguments[MODEL_INDEX], arguments[LOSS_INDEX]);
        }

        int entryCount = file.get(INT_LAYOUT, position);
        int wordCount = file.get(INT_LAYOUT, position + Integer.BYTES);
        int labelCount = file.get(INT_LAYOUT, position + 2 * Integer.BYTES);

        position += 3 * Integer.BYTES + Long.BYTES;

        long pruneSize = file.get(LONG_LAYOUT, position);

        position += Long.BYTES;

        if (wordCount < 0 || labelCount <= 0 || entryCount != wordCount + labelCount) {
            throw Exceptions.newIOException("Invalid fastText dictionary of {0} words and {1} labels", wordCount, labelCount);
        }

        ByteArrayOutputStream wordBytes = new ByteArrayOutputStream();
        int[] wordOffsets = new int[wordCount + 1];
        String[] labels = new String[labelCount];

        for (int index = 0; index < entryCount; index++) {
            long end = position;

            while (file.get(ValueLayout.JAVA_BYTE, end) != 0) {
                end++;
            }

            byte[] entry = file.asSlice(position, end - position).toArray(ValueLayout.JAVA_BYTE);
            byte type = file.get(ValueLayout.JAVA_BYTE, end + 1 + Long.BYTES);

            position = end + 1 + Long.BYTES + 1;

            if (index < wordCount) {
                if (type != WORD_ENTRY) {
                    throw Exceptions.newIOException("The fastText dictionary entry {0} is not a word", index);
                }

                wordOffsets[index] = wordBytes.size();
                wordBytes.writeBytes(entry);
            } else {
                if (type != LABEL_ENTRY) {
                    throw Exceptions.newIOException("The fastText dictionary entry {0} is not a label", index);
                }

                String label = new String(entry, StandardCharsets.UTF_8);

                labels[index - wordCount] = label.startsWith(LABEL_PREFIX) ? label.substring(LABEL_PREFIX.length()) : label;
            }
        }

        wordOffsets[wordCount] = wordBytes.size();

        Map<Integer, Integer> pruneIndex = new HashMap<>();

        for (long index = 0; index < pruneSize; index++) {
            pruneIndex.put(file.get(INT_LAYOUT, position), file.get(INT_LAYOUT, position + Integer.BYTES));
            position += 2 * Integer.BYTES;
        }

        if (file.get(ValueLayout.JAVA_BYTE, position) != 0) {
            throw Exceptions.newIOException("Quantized fastText models are not supported");
        }

        long inputRows = file.get(LONG_LAYOUT, position + 1);
        long inputColumns = file.get(LONG_LAYOUT, position + 1 + Long.BYTES);
        int dimension = arguments[DIMENSION_INDEX];

        position += 1 + 2 * Long.BYTES;

        if (inputColumns != dimension || inputRows != (long) wordCount + arguments[BUCKET_INDEX]) {
            throw Exceptions.newIOException("The fastText input matrix has {0} rows of {1}; expected {2} rows of {3}", inputRows, inputColumns,
                                            (long) wordCount + arguments[BUCKET_INDEX], dimension);
        }

        long inputOffset = position;

        position += inputRows * inputColumns * Float.BYTES;

        if (file.get(ValueLayout.JAVA_BYTE, position) != 0) {
            throw Exceptions.newIOException("Quantized fastText models are not supported");
        }

        long outputRows = file.get(LONG_LAYOUT, position + 1);
        long outputColumns = file.get(LONG_LAYOUT, position + 1 + Long.BYTES);

        position += 1 + 2 * Long.BYTES;

        if (outputRows != labelCount || outputColumns != dimension || position + outputRows * outputColumns * Float.BYTES > file.byteSize()) {
            throw Exceptions.newIOException("The fastText output matrix has {0} rows of {1}; expected {2} rows of {3}", outputRows, outputColumns,
                                            labelCount, dimension);
        }

        int slotCount = Integer.highestOneBit(Math.max(1, wordCount * SLOTS_PER_WORD - 1)) << 1;

        this.arena = arena;
        this.file  = file;

        this.dimension    = dimension;
        this.wordNgrams   = arguments[WORD_NGRAMS_INDEX];
        this.loss         = arguments[LOSS_INDEX];
        this.bucket       = arguments[BUCKET_INDEX];
        this.minimumNgram = arguments[MINIMUM_NGRAM_INDEX];
        this.maximumNgram = arguments[MAXIMUM_NGRAM_INDEX];

        this.words       = wordBytes.toByteArray();
        this.wordOffsets = wordOffsets;
        this.wordCount   = wordCount;
        this.slots       = new int[slotCount];
        this.slotMask    = slotCount - 1;
        this.labels      = labels;
        this.pruneSize   = pruneSize;
        this.pruneIndex  = pruneIndex;

        this.inputOffset = inputOffset;
        this.output      = file.asSlice(position, outputRows * outputColumns * Float.BYTES).toArray(FLOAT_LAYOUT);

        for (int id = 0; id < wordCount; id++) {
            int slot = hash(this.words, wordOffsets[id], wordOffsets[id + 1]) & this.slotMask;

            while (this.slots[slot] != 0) {
                slot = (slot + 1) & this.slotMask;
            }

            this.slots[slot] = id + 1;
        }
    }

    /**
     * @return the labels of the model without the label prefix, such as {@code kor_Hang}, in the order of the output layer; unmodifiable
     */
    public @NotNull List<@NotNull String> getLabels() {
        return List.of(this.labels);
    }

    /**
     * Identifies the most probable language of a text.
     *
     * @param text the text, on one line
     * @return the most probable language, or {@code null} when the text gives the model nothing to read
     */
    @Override
    public @Nullable IdentifiedLanguage identify(@NotNull String text) {
        List<IdentifiedLanguage> predictions = this.predict(text, 1);

        return predictions.isEmpty() ? null : predictions.getFirst();
    }

    /**
     * Predicts the most probable labels of a text. Line ends count as white space.
     *
     * @param text  the text
     * @param count the largest number of labels returned
     * @return the most probable labels, most probable first, empty when the text gives the model nothing to read
     */
    public @NotNull List<@NotNull IdentifiedLanguage> predict(@NotNull String text, int count) {
        IntArrayBuilder rows = this.findRows(text.getBytes(StandardCharsets.UTF_8));

        if (rows.size() == 0) {
            return List.of();
        }

        float[] hidden = new float[this.dimension];

        for (int index = 0; index < rows.size(); index++) {
            long offset = this.inputOffset + (long) rows.get(index) * this.dimension * Float.BYTES;

            for (int column = 0; column < this.dimension; column++) {
                hidden[column] += this.file.get(FLOAT_LAYOUT, offset + (long) column * Float.BYTES);
            }
        }

        float scale = (float) (1.0 / rows.size());

        for (int column = 0; column < this.dimension; column++) {
            hidden[column] *= scale;
        }

        float[] probabilities = this.computeProbabilities(hidden);
        boolean[] taken = new boolean[probabilities.length];
        List<IdentifiedLanguage> predictions = new ArrayList<>(Math.min(count, probabilities.length));

        while (predictions.size() < Math.min(count, probabilities.length)) {
            int best = MISSING;

            for (int label = 0; label < probabilities.length; label++) {
                if (!taken[label] && (best == MISSING || probabilities[label] > probabilities[best])) {
                    best = label;
                }
            }

            taken[best] = true;
            predictions.add(new IdentifiedLanguage(this.labels[best], probabilities[best]));
        }

        return predictions;
    }

    /**
     * Finds the input rows of a text as fastText reads a line: the words up to the end-of-sentence word, which the line end gives, each
     * with its own row when it is in the dictionary and with the rows of its character n-grams unless it is the end-of-sentence word, then
     * the rows of the hashed word n-grams. Words that look like labels are left out.
     */
    private @NotNull IntArrayBuilder findRows(byte @NotNull [] text) {
        IntArrayBuilder rows = new IntArrayBuilder(text.length * this.maximumNgram);
        IntArrayBuilder wordHashes = new IntArrayBuilder(text.length / 2);
        byte[] word = new byte[1 + Math.max(text.length, END_OF_SENTENCE.length) + 1];
        int position = 0;
        boolean endOfSentence = false;

        while (!endOfSentence) {
            while (position < text.length && isSeparator(text[position])) {
                position++;
            }

            int start = position;

            while (position < text.length && !isSeparator(text[position])) {
                position++;
            }

            boolean lineEnd = start == position;
            int length = lineEnd ? END_OF_SENTENCE.length : position - start;

            // The word goes between its boundary marks, which the character n-grams include.
            System.arraycopy(lineEnd ? END_OF_SENTENCE : text, lineEnd ? 0 : start, word, 1, length);
            endOfSentence = length == END_OF_SENTENCE.length && Arrays.equals(word, 1, 1 + length, END_OF_SENTENCE, 0, length);

            int wordHash = hash(word, 1, 1 + length);
            int id = this.findWord(word, 1, 1 + length, wordHash);

            if (id == MISSING && startsWith(word, 1, 1 + length, LABEL_PREFIX_BYTES)) {
                continue;
            }

            if (id != MISSING) {
                rows.add(id);
            }

            if (!endOfSentence) {
                word[0]          = BEGIN_OF_WORD;
                word[1 + length] = END_OF_WORD;

                this.addSubwords(rows, word, 2 + length);
            }

            wordHashes.add(wordHash);
        }

        for (int index = 0; index < wordHashes.size(); index++) {
            long hash = wordHashes.get(index);

            for (int next = index + 1; next < wordHashes.size() && next < index + this.wordNgrams; next++) {
                hash = hash * WORD_NGRAM_MULTIPLIER + wordHashes.get(next);
                this.addBucket(rows, (int) Long.remainderUnsigned(hash, this.bucket));
            }
        }

        return rows;
    }

    /**
     * Adds the rows of the character n-grams of a word with its boundary marks; n-grams count UTF-8 characters, and the single boundary
     * marks are left out.
     */
    private void addSubwords(@NotNull IntArrayBuilder rows, byte @NotNull [] word, int length) {
        for (int start = 0; start < length; start++) {
            if ((word[start] & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) {
                continue;
            }

            int end = start;

            for (int characters = 1; end < length && characters <= this.maximumNgram; characters++) {
                end++;

                while (end < length && (word[end] & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) {
                    end++;
                }

                if (characters >= this.minimumNgram && !(characters == 1 && (start == 0 || end == length))) {
                    this.addBucket(rows, Integer.remainderUnsigned(hash(word, start, end), this.bucket));
                }
            }
        }
    }

    /**
     * Adds the row of a hashed bucket, mapped through the prune index of a pruned model.
     */
    private void addBucket(@NotNull IntArrayBuilder rows, int bucketIndex) {
        if (this.pruneSize == 0 || bucketIndex < 0) {
            return;
        }

        int bucketRow = bucketIndex;

        if (this.pruneSize > 0) {
            Integer pruned = this.pruneIndex.get(bucketIndex);

            if (pruned == null) {
                return;
            }

            bucketRow = pruned;
        }

        rows.add(this.wordCount + bucketRow);
    }

    private int findWord(byte @NotNull [] token, int start, int end, int wordHash) {
        int slot = wordHash & this.slotMask;

        while (this.slots[slot] != 0) {
            int id = this.slots[slot] - 1;

            if (Arrays.equals(this.words, this.wordOffsets[id], this.wordOffsets[id + 1], token, start, end)) {
                return id;
            }

            slot = (slot + 1) & this.slotMask;
        }

        return MISSING;
    }

    /**
     * Computes the label probabilities of a hidden vector: the output layer, then the softmax, or the sigmoid of each label for the
     * one-versus-all and negative sampling losses.
     */
    private float @NotNull [] computeProbabilities(float @NotNull [] hidden) {
        float[] probabilities = new float[this.labels.length];

        for (int label = 0; label < probabilities.length; label++) {
            int offset = label * this.dimension;
            float sum = 0.0f;

            for (int column = 0; column < this.dimension; column++) {
                sum += this.output[offset + column] * hidden[column];
            }

            probabilities[label] = sum;
        }

        if (this.loss != SOFTMAX_LOSS) {
            for (int label = 0; label < probabilities.length; label++) {
                probabilities[label] = sigmoid(probabilities[label]);
            }

            return probabilities;
        }

        float maximum = probabilities[0];
        float total = 0.0f;

        for (float value : probabilities) {
            maximum = Math.max(value, maximum);
        }

        for (int label = 0; label < probabilities.length; label++) {
            probabilities[label] = (float) Math.exp(probabilities[label] - maximum);
            total += probabilities[label];
        }

        for (int label = 0; label < probabilities.length; label++) {
            probabilities[label] /= total;
        }

        return probabilities;
    }

    /**
     * Releases the model file.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.arena::close
        );
    }
}
