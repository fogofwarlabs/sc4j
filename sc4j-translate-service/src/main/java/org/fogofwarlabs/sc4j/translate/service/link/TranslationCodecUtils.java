package org.fogofwarlabs.sc4j.translate.service.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.*;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Encodes the requests and the translations of the {@value TranslationLinkService#SERVICE_NAME} service of the launcher link. Launcher and
 * runtime come from one install, so the form carries no version:
 * {@snippet lang = "text":
 * request:     text, target language tag, priority name, int span count, (int start, int end) per span, boolean has context,
 *              [text kind, description, int entry count, (source term, target language tag, target term) per entry]
 * translation: original text, translated text, boolean has source, [source language tag], status name
 *}
 * A text is an {@code int} byte length followed by its UTF-8 bytes; numbers are big-endian.
 */
final class TranslationCodecUtils {
    /**
     * Encodes a request.
     *
     * @param request the request
     * @return the payload
     */
    static byte @NotNull [] encodeRequest(@NotNull TranslationRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TranslationContext context = request.getContext();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeText(output, request.getText());
            writeText(output, request.getTarget().toLanguageTag());
            writeText(output, request.getPriority().name());
            output.writeInt(request.getProtectedSpans().size());

            for (ProtectedSpan span : request.getProtectedSpans()) {
                output.writeInt(span.getStart());
                output.writeInt(span.getEnd());
            }

            output.writeBoolean(context != null);

            if (context != null) {
                writeText(output, context.getTextKind());
                writeText(output, context.getDescription());
                output.writeInt(context.getGlossary().size());

                for (GlossaryEntry entry : context.getGlossary()) {
                    writeText(output, entry.getSourceTerm());
                    writeText(output, entry.getTarget().toLanguageTag());
                    writeText(output, entry.getTargetTerm());
                }
            }
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }

        return bytes.toByteArray();
    }

    /**
     * Decodes a request.
     *
     * @param payload the payload
     * @return the request
     * @throws IOException if the payload is not an encoded request
     */
    static @NotNull TranslationRequest decodeRequest(byte @NotNull [] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String text = readText(input);
            Locale target = Locale.forLanguageTag(readText(input));
            TranslationPriority priority = TranslationPriority.valueOf(readText(input));
            int spanCount = input.readInt();
            TranslationRequest.Builder builder = new TranslationRequest.Builder(text, target).setPriority(priority);

            if (spanCount < 0 || spanCount > text.length()) {
                throw Exceptions.newIOException("Invalid protected span count: {0}", spanCount);
            }

            for (int index = 0; index < spanCount; index++) {
                builder.addProtectedSpan(input.readInt(), input.readInt());
            }

            if (input.readBoolean()) {
                builder.setContext(readContext(input));
            }

            checkEnd(input);

            return builder.build();
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid translation request: {0}", exception.getMessage());
        }
    }

    /**
     * Encodes a translation.
     *
     * @param translation the translation
     * @return the payload
     */
    static byte @NotNull [] encodeTranslation(@NotNull Translation translation) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Locale source = translation.getSource();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeText(output, translation.getOriginalText());
            writeText(output, translation.getTranslatedText());
            output.writeBoolean(source != null);

            if (source != null) {
                writeText(output, source.toLanguageTag());
            }

            writeText(output, translation.getStatus().name());
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }

        return bytes.toByteArray();
    }

    /**
     * Decodes a translation.
     *
     * @param payload the payload
     * @return the translation
     * @throws IOException if the payload is not an encoded translation
     */
    static @NotNull Translation decodeTranslation(byte @NotNull [] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String originalText = readText(input);
            String translatedText = readText(input);
            Locale source = input.readBoolean() ? Locale.forLanguageTag(readText(input)) : null;
            TranslationStatus status = TranslationStatus.valueOf(readText(input));

            checkEnd(input);

            return new Translation(originalText, translatedText, source, status);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid translation: {0}", exception.getMessage());
        }
    }

    private static @NotNull TranslationContext readContext(@NotNull DataInputStream input) throws IOException {
        TranslationContext.Builder builder = new TranslationContext.Builder().setTextKind(readText(input)).setDescription(readText(input));
        int entryCount = input.readInt();

        if (entryCount < 0 || entryCount > input.available()) {
            throw Exceptions.newIOException("Invalid glossary entry count: {0}", entryCount);
        }

        for (int index = 0; index < entryCount; index++) {
            String sourceTerm = readText(input);
            Locale target = Locale.forLanguageTag(readText(input));

            builder.addGlossaryEntry(sourceTerm, target, readText(input));
        }

        return builder.build();
    }

    private static void writeText(@NotNull DataOutputStream output, @NotNull String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static @NotNull String readText(@NotNull DataInputStream input) throws IOException {
        int length = input.readInt();

        if (length < 0 || length > input.available()) {
            throw Exceptions.newIOException("Invalid text length: {0}", length);
        }

        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void checkEnd(@NotNull DataInputStream input) throws IOException {
        if (input.available() > 0) {
            throw Exceptions.newIOException("{0} bytes after the end", input.available());
        }
    }


    private TranslationCodecUtils() {
    }
}
