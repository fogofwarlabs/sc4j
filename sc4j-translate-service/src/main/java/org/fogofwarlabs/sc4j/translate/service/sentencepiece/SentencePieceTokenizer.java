package org.fogofwarlabs.sc4j.translate.service.sentencepiece;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.model.TextTokenizer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Tokenizes text with a SentencePiece BPE model ({@code sentencepiece.bpe.model}), as SentencePiece encodes and decodes ids: the model's
 * normalizer runs first, the text is split into UTF-8 characters, and the adjacent pair whose joined piece has the highest score is merged
 * until no pair forms a piece, the leftmost pair first among equal scores. Pieces that are not in the vocabulary become the unknown id, a
 * run of them one id.
 * <p>
 * Models with user-defined, unused or byte pieces, with byte fallback or with the space as a suffix are refused.
 */
public final class SentencePieceTokenizer implements TextTokenizer {
    private static final int MODEL_PIECES_FIELD = 1;
    private static final int MODEL_TRAINER_FIELD = 2;
    private static final int MODEL_NORMALIZER_FIELD = 3;
    private static final int PIECE_TEXT_FIELD = 1;
    private static final int PIECE_SCORE_FIELD = 2;
    private static final int PIECE_TYPE_FIELD = 3;
    private static final int TRAINER_MODEL_TYPE_FIELD = 3;
    private static final int TRAINER_SPACE_SUFFIX_FIELD = 24;
    private static final int TRAINER_BYTE_FALLBACK_FIELD = 35;
    private static final int NORMALIZER_CHARACTER_MAP_FIELD = 2;
    private static final int NORMALIZER_DUMMY_PREFIX_FIELD = 3;
    private static final int NORMALIZER_EXTRA_WHITESPACES_FIELD = 4;
    private static final int NORMALIZER_ESCAPE_WHITESPACES_FIELD = 5;

    private static final int BPE_MODEL_TYPE = 2;
    private static final byte NORMAL_PIECE = 1;
    private static final byte UNKNOWN_PIECE = 2;
    private static final byte CONTROL_PIECE = 3;
    private static final int MISSING = -1;
    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;
    private static final int SLOTS_PER_PIECE = 2;
    private static final byte SPACE = ' ';

    /**
     * Reads a SentencePiece BPE model file.
     *
     * @param file the model file
     * @return the created {@link SentencePieceTokenizer} instance
     * @throws IOException if the file cannot be read, is malformed or describes a model this tokenizer does not support
     */
    public static @NotNull SentencePieceTokenizer read(@NotNull Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    /**
     * Parses the bytes of a SentencePiece BPE model file.
     *
     * @param model the bytes of the model file
     * @return the created {@link SentencePieceTokenizer} instance
     * @throws IOException if the bytes are malformed or describe a model this tokenizer does not support
     */
    public static @NotNull SentencePieceTokenizer parse(byte @NotNull [] model) throws IOException {
        ProtobufReader reader = new ProtobufReader(model, 0, model.length);
        int pieceCount = 0;

        // The pieces come first in the file; count them so that their arrays are allocated once.
        while (reader.hasField()) {
            int field = reader.readField();

            if (field == MODEL_PIECES_FIELD && reader.getWireType() == ProtobufReader.LENGTH_DELIMITED) {
                pieceCount++;
            }

            reader.skipValue();
        }

        int[] offsets = new int[pieceCount + 1];
        float[] scores = new float[pieceCount];
        byte[] types = new byte[pieceCount];
        byte[] pieces = new byte[model.length];
        int pieceIndex = 0;
        int pieceLength = 0;
        byte[] characterMap = new byte[0];
        boolean addDummyPrefix = true;
        boolean removeExtraWhitespaces = true;
        boolean escapeWhitespaces = true;

        reader = new ProtobufReader(model, 0, model.length);

        while (reader.hasField()) {
            int field = reader.readField();

            if (reader.getWireType() != ProtobufReader.LENGTH_DELIMITED) {
                reader.skipValue();

                continue;
            }

            int start = reader.readLengthDelimited();
            ProtobufReader message = new ProtobufReader(model, start, reader.getPosition());

            switch (field) {
                case MODEL_PIECES_FIELD -> {
                    offsets[pieceIndex] = pieceLength;
                    scores[pieceIndex]  = 0.0f;
                    types[pieceIndex]   = NORMAL_PIECE;

                    while (message.hasField()) {
                        int pieceField = message.readField();

                        if (pieceField == PIECE_TEXT_FIELD && message.getWireType() == ProtobufReader.LENGTH_DELIMITED) {
                            int textStart = message.readLengthDelimited();
                            int textLength = message.getPosition() - textStart;

                            System.arraycopy(model, textStart, pieces, pieceLength, textLength);
                            pieceLength += textLength;
                        } else if (pieceField == PIECE_SCORE_FIELD && message.getWireType() == ProtobufReader.FIXED32) {
                            scores[pieceIndex] = Float.intBitsToFloat(message.readFixed32());
                        } else if (pieceField == PIECE_TYPE_FIELD && message.getWireType() == ProtobufReader.VARINT) {
                            types[pieceIndex] = (byte) message.readVarint();
                        } else {
                            message.skipValue();
                        }
                    }

                    pieceIndex++;
                }
                case MODEL_TRAINER_FIELD -> readTrainer(message);
                case MODEL_NORMALIZER_FIELD -> {
                    while (message.hasField()) {
                        int normalizerField = message.readField();

                        if (normalizerField == NORMALIZER_CHARACTER_MAP_FIELD && message.getWireType() == ProtobufReader.LENGTH_DELIMITED) {
                            int mapStart = message.readLengthDelimited();

                            characterMap = Arrays.copyOfRange(model, mapStart, message.getPosition());
                        } else if (normalizerField == NORMALIZER_DUMMY_PREFIX_FIELD && message.getWireType() == ProtobufReader.VARINT) {
                            addDummyPrefix = message.readVarint() != 0;
                        } else if (normalizerField == NORMALIZER_EXTRA_WHITESPACES_FIELD && message.getWireType() == ProtobufReader.VARINT) {
                            removeExtraWhitespaces = message.readVarint() != 0;
                        } else if (normalizerField == NORMALIZER_ESCAPE_WHITESPACES_FIELD && message.getWireType() == ProtobufReader.VARINT) {
                            escapeWhitespaces = message.readVarint() != 0;
                        } else {
                            message.skipValue();
                        }
                    }
                }
                default -> {
                    // Self-test data and the denormalizer are not used.
                }
            }
        }

        offsets[pieceCount] = pieceLength;

        return new SentencePieceTokenizer(Arrays.copyOf(pieces, pieceLength), offsets, scores, types,
                                          new SentencePieceNormalizer(characterMap, addDummyPrefix, removeExtraWhitespaces, escapeWhitespaces));
    }

    private static void readTrainer(@NotNull ProtobufReader message) throws IOException {
        int modelType = 1;

        while (message.hasField()) {
            int field = message.readField();

            if (message.getWireType() != ProtobufReader.VARINT) {
                message.skipValue();
            } else if (field == TRAINER_MODEL_TYPE_FIELD) {
                modelType = (int) message.readVarint();
            } else if ((field == TRAINER_SPACE_SUFFIX_FIELD || field == TRAINER_BYTE_FALLBACK_FIELD) && message.readVarint() != 0) {
                throw Exceptions.newIOException("The SentencePiece model uses option {0}, which is not supported", field);
            } else {
                message.skipValue();
            }
        }

        if (modelType != BPE_MODEL_TYPE) {
            throw Exceptions.newIOException("The SentencePiece model has type {0}; only BPE models are supported", modelType);
        }
    }

    private static int hash(byte @NotNull [] data, int start, int length) {
        int hash = FNV_OFFSET_BASIS;

        for (int index = start; index < start + length; index++) {
            hash = (hash ^ (data[index] & 0xFF)) * FNV_PRIME;
        }

        return hash;
    }


    private final byte @NotNull [] pieces;
    private final int @NotNull [] offsets;
    private final float @NotNull [] scores;
    private final byte @NotNull [] types;
    private final int unknownId;

    /**
     * The open-addressing table of the normal pieces: a piece id plus one, or {@code 0} for an empty slot.
     */
    private final int @NotNull [] slots;
    private final int slotMask;

    private final @NotNull SentencePieceNormalizer normalizer;

    private SentencePieceTokenizer(byte @NotNull [] pieces, int @NotNull [] offsets, float @NotNull [] scores, byte @NotNull [] types,
                                   @NotNull SentencePieceNormalizer normalizer) throws IOException {
        int unknownId = MISSING;
        int slotCount = Integer.highestOneBit(Math.max(1, types.length * SLOTS_PER_PIECE - 1)) << 1;

        this.pieces   = pieces;
        this.offsets  = offsets;
        this.scores   = scores;
        this.types    = types;
        this.slots    = new int[slotCount];
        this.slotMask = slotCount - 1;

        for (int id = 0; id < types.length; id++) {
            if (types[id] == UNKNOWN_PIECE) {
                unknownId = id;
            } else if (types[id] == NORMAL_PIECE) {
                int length = offsets[id + 1] - offsets[id];
                int slot = hash(pieces, offsets[id], length) & this.slotMask;

                while (this.slots[slot] != 0) {
                    if (this.matches(this.slots[slot] - 1, pieces, offsets[id], length)) {
                        throw Exceptions.newIOException("The SentencePiece model has the piece {0} twice", id);
                    }

                    slot = (slot + 1) & this.slotMask;
                }

                this.slots[slot] = id + 1;
            } else if (types[id] != CONTROL_PIECE) {
                throw Exceptions.newIOException("The SentencePiece model has piece {0} of type {1}, which is not supported", id, types[id]);
            }
        }

        if (unknownId == MISSING) {
            throw Exceptions.newIOException("The SentencePiece model has no unknown piece");
        }

        this.unknownId  = unknownId;
        this.normalizer = normalizer;
    }

    /**
     * @return the number of pieces of the vocabulary
     */
    public int getPieceCount() {
        return this.types.length;
    }

    /**
     * @return the id of the unknown piece
     */
    public int getUnknownId() {
        return this.unknownId;
    }

    /**
     * Converts text to pieces.
     *
     * @param text the text
     * @return the pieces, empty when the text has nothing but spaces
     */
    @Override
    public @NotNull String @NotNull [] encode(@NotNull String text) {
        int[] ids = this.encodeIds(text);
        String[] encoded = new String[ids.length];

        for (int index = 0; index < ids.length; index++) {
            int start = this.offsets[ids[index]];

            encoded[index] = new String(this.pieces, start, this.offsets[ids[index] + 1] - start, StandardCharsets.UTF_8);
        }

        return encoded;
    }

    /**
     * Converts text to piece ids.
     *
     * @param text the text
     * @return the piece ids, empty when the text has nothing but spaces
     */
    public int @NotNull [] encodeIds(@NotNull String text) {
        byte[] normalized = this.normalizer.normalize(text.getBytes(StandardCharsets.UTF_8));
        int capacity = normalized.length;
        int[] starts = new int[capacity];
        int[] lengths = new int[capacity];
        int[] previous = new int[capacity];
        int[] next = new int[capacity];
        float[] pairScores = new float[capacity];
        int count = 0;

        for (int position = 0; position < normalized.length; count++) {
            int length = Math.min(SentencePieceNormalizer.characterLength(normalized[position]), normalized.length - position);

            starts[count]   = position;
            lengths[count]  = length;
            previous[count] = count - 1;

            position += length;
            next[count] = position < normalized.length ? count + 1 : MISSING;
        }

        for (int index = 0; index < count; index++) {
            pairScores[index] = this.scorePair(normalized, starts, lengths, next, index);
        }

        while (count > 0) {
            int best = MISSING;

            // Symbol 0 is never merged into a left neighbour, so the chain always starts there; scanning left to right keeps the
            // leftmost pair among equal scores.
            for (int index = 0; index != MISSING; index = next[index]) {
                if (!Float.isNaN(pairScores[index]) && (best == MISSING || pairScores[index] > pairScores[best])) {
                    best = index;
                }
            }

            if (best == MISSING) {
                break;
            }

            int right = next[best];

            lengths[best] += lengths[right];
            next[best] = next[right];

            if (next[right] != MISSING) {
                previous[next[right]] = best;
            }

            if (previous[best] != MISSING) {
                pairScores[previous[best]] = this.scorePair(normalized, starts, lengths, next, previous[best]);
            }

            pairScores[best] = this.scorePair(normalized, starts, lengths, next, best);
        }

        int[] tokens = new int[count];
        int tokenCount = 0;
        boolean previousUnknown = false;

        for (int index = 0; count > 0 && index != MISSING; index = next[index]) {
            int id = this.findPiece(normalized, starts[index], lengths[index]);
            boolean unknown = id == MISSING;

            // A run of unknown pieces becomes one unknown id.
            if (!(unknown && previousUnknown)) {
                tokens[tokenCount++] = unknown ? this.unknownId : id;
            }

            previousUnknown = unknown;
        }

        return Arrays.copyOf(tokens, tokenCount);
    }

    /**
     * Converts pieces to text; control pieces, the unknown piece and pieces outside the vocabulary give no text.
     *
     * @param pieces the pieces
     * @return the text
     */
    @Override
    public @NotNull String decode(@NotNull String @NotNull [] pieces) {
        int[] ids = new int[pieces.length];
        int idCount = 0;

        for (String piece : pieces) {
            byte[] text = piece.getBytes(StandardCharsets.UTF_8);
            int id = this.findPiece(text, 0, text.length);

            if (id != MISSING) {
                ids[idCount++] = id;
            }
        }

        return this.decodeIds(Arrays.copyOf(ids, idCount));
    }

    /**
     * Converts piece ids to text; ids of control pieces and of the unknown piece give no text.
     *
     * @param tokens the piece ids
     * @return the text
     */
    public @NotNull String decodeIds(int @NotNull [] tokens) {
        byte[] output = new byte[0];
        int outputLength = 0;

        for (int id : tokens) {
            if (id < 0 || id >= this.types.length || this.types[id] != NORMAL_PIECE) {
                continue;
            }

            int start = this.offsets[id];
            int end = this.offsets[id + 1];

            // The space of the first piece is the one the normalizer put in front of the text.
            if (outputLength == 0 && this.startsWithSpaceSymbol(start, end)) {
                start += SentencePieceNormalizer.SPACE_SYMBOL.length;
            }

            if (output.length < outputLength + end - start) {
                output = Arrays.copyOf(output, Math.max(outputLength + end - start, output.length * 2));
            }

            while (start < end) {
                if (this.startsWithSpaceSymbol(start, end)) {
                    output[outputLength++] = SPACE;

                    start += SentencePieceNormalizer.SPACE_SYMBOL.length;
                } else {
                    output[outputLength++] = this.pieces[start++];
                }
            }
        }

        return new String(output, 0, outputLength, StandardCharsets.UTF_8);
    }

    /**
     * Returns the score of the piece that joins a symbol and the next one, or {@code NaN} when there is no next symbol or no such piece.
     */
    private float scorePair(byte @NotNull [] text, int @NotNull [] starts, int @NotNull [] lengths, int @NotNull [] next, int left) {
        int right = next[left];

        if (right == MISSING) {
            return Float.NaN;
        }

        int id = this.findPiece(text, starts[left], lengths[left] + lengths[right]);

        return id == MISSING ? Float.NaN : this.scores[id];
    }

    private int findPiece(byte @NotNull [] text, int start, int length) {
        int slot = hash(text, start, length) & this.slotMask;

        while (this.slots[slot] != 0) {
            int id = this.slots[slot] - 1;

            if (this.matches(id, text, start, length)) {
                return id;
            }

            slot = (slot + 1) & this.slotMask;
        }

        return MISSING;
    }

    private boolean matches(int id, byte @NotNull [] text, int start, int length) {
        int pieceStart = this.offsets[id];

        return this.offsets[id + 1] - pieceStart == length && Arrays.equals(this.pieces, pieceStart, pieceStart + length, text, start, start + length);
    }

    private boolean startsWithSpaceSymbol(int start, int end) {
        byte[] symbol = SentencePieceNormalizer.SPACE_SYMBOL;

        return end - start >= symbol.length && Arrays.equals(this.pieces, start, start + symbol.length, symbol, 0, symbol.length);
    }
}
