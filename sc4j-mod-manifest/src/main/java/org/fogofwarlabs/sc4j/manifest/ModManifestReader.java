package org.fogofwarlabs.sc4j.manifest;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.manifest.settings.SettingsSchema;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.JsonFieldReader;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a mod jar as a zip file: its manifest {@value #FILE_NAME} at the jar root and the images the manifest names.
 * It never loads or runs a class of the mod, so a broken mod cannot harm the launcher or the runtime that reads it.
 * <p>
 * Reading validates the manifest: the required fields {@code format}, {@code id}, {@code name}, {@code version},
 * {@code engine} and {@code entry}, their syntax, the optional fields' types, the settings schema, the tools (each with an id of the mod id's
 * syntax, unique within the mod, and a Java class name as {@code main}) and the presence of every named image. Every problem found is
 * reported together in one {@link ManifestException}. Whether the runtime
 * supports the mod's engine version is a separate check ({@link ModManifest#checkEngine}).
 */
public final class ModManifestReader {
    /**
     * The manifest's jar entry.
     */
    public static final @NotNull String FILE_NAME = "sc4j-mod.json";

    /**
     * The manifest format this release reads.
     */
    public static final int FORMAT = 1;

    /**
     * The largest manifest read, in bytes.
     */
    public static final int MAXIMUM_MANIFEST_SIZE = 1024 * 1024;

    /**
     * The largest image read, in bytes.
     */
    public static final int MAXIMUM_IMAGE_SIZE = 16 * 1024 * 1024;

    private static final @NotNull Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final @NotNull Pattern ENTRY_PATTERN = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");
    private static final @NotNull Pattern ENTRY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");
    private static final @NotNull String PARENT_SEGMENT = "..";
    private static final byte @NotNull [] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String ID_FIELD = "id";
    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String VERSION_FIELD = "version";
    private static final @NotNull String ENGINE_FIELD = "engine";
    private static final @NotNull String ENTRY_FIELD = "entry";
    private static final @NotNull String DESCRIPTION_FIELD = "description";
    private static final @NotNull String AUTHORS_FIELD = "authors";
    private static final @NotNull String LICENSE_FIELD = "license";
    private static final @NotNull String NOTICES_FIELD = "notices";
    private static final @NotNull String WEBSITE_FIELD = "website";
    private static final @NotNull String IMAGES_FIELD = "images";
    private static final @NotNull String ICON_FIELD = "icon";
    private static final @NotNull String BANNER_FIELD = "banner";
    private static final @NotNull String SCREENSHOTS_FIELD = "screenshots";
    private static final @NotNull String SETTINGS_FIELD = "settings";
    private static final @NotNull String TOOLS_FIELD = "tools";
    private static final @NotNull String TOOL_ID_FIELD = "id";
    private static final @NotNull String TOOL_MAIN_FIELD = "main";

    /**
     * Reads and validates a mod jar's manifest and images.
     *
     * @param jar the mod jar
     * @return the archive with the validated manifest and the image contents
     * @throws ManifestException if the jar cannot be read as a zip file, has no manifest, the manifest is invalid or a named
     *                           image is missing or not a PNG file
     */
    public static @NotNull ModArchive read(@NotNull Path jar) throws ManifestException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifestEntry = zip.getEntry(FILE_NAME);

            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw createException(ProblemCode.MANIFEST_MISSING, FILE_NAME, "The jar has no {0} at its root.", FILE_NAME);
            }

            byte[] contents = readEntry(zip, manifestEntry, MAXIMUM_MANIFEST_SIZE);

            if (contents == null) {
                throw createException(ProblemCode.MANIFEST_SYNTAX, FILE_NAME, "The manifest is larger than {0} bytes.", MAXIMUM_MANIFEST_SIZE);
            }

            ModManifest manifest = parse(contents);
            ValidationResult.Builder problems = new ValidationResult.Builder();
            Map<String, byte[]> images = new LinkedHashMap<>();

            for (String imageEntry : manifest.getImages().getEntries()) {
                byte[] image = readImage(zip, imageEntry, problems);

                if (image != null) {
                    images.put(imageEntry, image);
                }
            }

            if (!problems.isValid()) {
                throw new ManifestException(problems.build());
            }

            return new ModArchive(jar, manifest, images);
        } catch (IOException exception) {
            throw createException(ProblemCode.ARCHIVE_UNREADABLE, jar.toString(),
                                  "The mod jar cannot be read as a zip file: {0}", exception.getMessage());
        }
    }

    /**
     * Parses and validates manifest contents without a jar; image entries are checked for syntax only.
     *
     * @param contents the UTF-8 JSON contents of {@value #FILE_NAME}
     * @return the validated manifest
     * @throws ManifestException if the contents are not a JSON object or any field is missing or invalid
     */
    public static @NotNull ModManifest parse(byte @NotNull [] contents) throws ManifestException {
        Object parsed;

        try {
            parsed = JsonReader.parse(contents);
        } catch (IOException exception) {
            throw createException(ProblemCode.MANIFEST_SYNTAX, FILE_NAME, "The manifest is not valid JSON: {0}", exception.getMessage());
        }

        ValidationResult.Builder problems = new ValidationResult.Builder();
        JsonFieldReader root = JsonFieldReader.of(parsed, "", problems);

        if (root == null) {
            throw new ManifestException(problems.build());
        }

        Long format = root.readRequiredInteger(FORMAT_FIELD);

        if (format != null && format != FORMAT) {
            problems.add(ProblemCode.FORMAT_UNSUPPORTED, FORMAT_FIELD, "Manifest format {0} is not supported; this release reads format {1}.",
                         format, FORMAT);
        }

        String id = root.readRequiredString(ID_FIELD);

        if (id != null && !ID_PATTERN.matcher(id).matches()) {
            problems.add(ProblemCode.ID_SYNTAX, ID_FIELD, "Invalid id {0}: expected lower case letters and digits, separated by single hyphens.", id);
        }

        ModVersion version = parseVersion(root.readRequiredString(VERSION_FIELD), problems);
        EngineVersion engine = parseEngine(root.readRequiredString(ENGINE_FIELD), problems);
        String entry = root.readRequiredString(ENTRY_FIELD);

        if (entry != null && !ENTRY_PATTERN.matcher(entry).matches()) {
            problems.add(ProblemCode.ENTRY_SYNTAX, ENTRY_FIELD,
                         "Invalid entry {0}: expected a Java class name such as org.example.ExampleMod.", entry);
        }

        LocalizedText name = root.readRequiredLocalizedText(NAME_FIELD);
        LocalizedText description = root.readOptionalLocalizedText(DESCRIPTION_FIELD);
        List<String> authors = root.readOptionalStringList(AUTHORS_FIELD);
        String license = root.readOptionalString(LICENSE_FIELD);
        List<LocalizedText> notices = root.readOptionalLocalizedTextList(NOTICES_FIELD);
        String website = root.readOptionalString(WEBSITE_FIELD);
        ModImages images = readImages(root.readOptionalObject(IMAGES_FIELD));
        SettingsSchema settings = SettingsSchema.read(root.readOptionalList(SETTINGS_FIELD), SETTINGS_FIELD, problems);
        List<ModTool> tools = readTools(root);

        if (!problems.isValid() || format == null || id == null || version == null || engine == null || entry == null || name == null) {
            throw new ManifestException(problems.build());
        }

        return new ModManifest(format.intValue(), id, version, engine, entry,
                               name, description, authors == null ? List.of() : authors, license,
                               notices == null ? List.of() : notices, website, images,
                               settings, tools);
    }

    /**
     * Reads the optional {@code tools}: a list of objects, each with an {@code id} of the mod id's syntax, unique within the list, and a Java
     * class name as {@code main}.
     */
    private static @NotNull List<@NotNull ModTool> readTools(@NotNull JsonFieldReader root) {
        List<?> entries = root.readOptionalList(TOOLS_FIELD);

        if (entries == null) {
            return List.of();
        }

        List<ModTool> tools = new ArrayList<>(entries.size());
        Set<String> toolIds = new HashSet<>();

        for (int index = 0; index < entries.size(); index++) {
            JsonFieldReader tool = JsonFieldReader.of(entries.get(index), TOOLS_FIELD + "[" + index + "]", root.getProblems());

            if (tool == null) {
                continue;
            }

            String toolId = tool.readRequiredString(TOOL_ID_FIELD);
            String main = tool.readRequiredString(TOOL_MAIN_FIELD);

            if (toolId != null && !ID_PATTERN.matcher(toolId).matches()) {
                tool.getProblems().add(ProblemCode.FIELD_VALUE, tool.getPath(TOOL_ID_FIELD),
                                       "Invalid tool id {0}: expected lower case letters and digits, separated by single hyphens.", toolId);
                toolId = null;
            } else if (toolId != null && !toolIds.add(toolId)) {
                tool.getProblems().add(ProblemCode.FIELD_VALUE, tool.getPath(TOOL_ID_FIELD), "Duplicate tool id {0}.", toolId);
                toolId = null;
            }

            if (main != null && !ENTRY_PATTERN.matcher(main).matches()) {
                tool.getProblems().add(ProblemCode.FIELD_VALUE, tool.getPath(TOOL_MAIN_FIELD),
                                       "Invalid main class {0}: expected a Java class name such as org.example.ExampleTool.", main);
                main = null;
            }

            if (toolId != null && main != null) {
                tools.add(new ModTool(toolId, main));
            }
        }

        return tools;
    }

    private static @Nullable ModVersion parseVersion(@Nullable String text, @NotNull ValidationResult.Builder problems) {
        if (text == null) {
            return null;
        }

        try {
            return ModVersion.parse(text);
        } catch (IllegalArgumentException exception) {
            problems.add(ProblemCode.VERSION_SYNTAX, VERSION_FIELD, "Invalid version {0}: expected major.minor.patch such as 1.0.0.", text);

            return null;
        }
    }

    private static @Nullable EngineVersion parseEngine(@Nullable String text, @NotNull ValidationResult.Builder problems) {
        if (text == null) {
            return null;
        }

        try {
            return EngineVersion.parse(text);
        } catch (IllegalArgumentException exception) {
            problems.add(ProblemCode.ENGINE_SYNTAX, ENGINE_FIELD, "Invalid engine version {0}: expected major.minor such as 1.0.", text);

            return null;
        }
    }

    private static @NotNull ModImages readImages(@Nullable JsonFieldReader images) {
        if (images == null) {
            return ModImages.NONE;
        }

        String icon = readImageEntry(images, ICON_FIELD, images.readOptionalString(ICON_FIELD));
        String banner = readImageEntry(images, BANNER_FIELD, images.readOptionalString(BANNER_FIELD));
        List<String> screenshots = images.readOptionalStringList(SCREENSHOTS_FIELD);

        if (screenshots == null) {
            return new ModImages(icon, banner, List.of());
        }

        for (String screenshot : screenshots) {
            readImageEntry(images, SCREENSHOTS_FIELD, screenshot);
        }

        return new ModImages(icon, banner, screenshots);
    }

    private static @Nullable String readImageEntry(@NotNull JsonFieldReader images, @NotNull String field, @Nullable String entry) {
        if (entry == null) {
            return null;
        }

        if (!ENTRY_NAME_PATTERN.matcher(entry).matches() || Arrays.asList(entry.split("/")).contains(PARENT_SEGMENT)) {
            images.getProblems().add(ProblemCode.FIELD_VALUE, images.getPath(field),
                                     "Invalid jar entry {0}: expected a relative path such as meta/icon.png.", entry);

            return null;
        }

        return entry;
    }

    private static byte @Nullable [] readImage(@NotNull ZipFile zip, @NotNull String imageEntry, @NotNull ValidationResult.Builder problems) throws IOException {
        ZipEntry entry = zip.getEntry(imageEntry);

        if (entry == null || entry.isDirectory()) {
            problems.add(ProblemCode.IMAGE_MISSING, imageEntry, "The image is not in the jar.");

            return null;
        }

        byte[] contents = readEntry(zip, entry, MAXIMUM_IMAGE_SIZE);

        if (contents == null) {
            problems.add(ProblemCode.IMAGE_MISSING, imageEntry, "The image is larger than {0} bytes.", MAXIMUM_IMAGE_SIZE);

            return null;
        }

        if (contents.length < PNG_SIGNATURE.length || !Arrays.equals(contents, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            problems.add(ProblemCode.IMAGE_MISSING, imageEntry, "The image is not a PNG file.");

            return null;
        }

        return contents;
    }

    private static byte @Nullable [] readEntry(@NotNull ZipFile zip, @NotNull ZipEntry entry, int maximumSize) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] contents = input.readNBytes(maximumSize + 1);

            return contents.length > maximumSize ? null : contents;
        }
    }

    private static @NotNull ManifestException createException(@NotNull ProblemCode code, @NotNull String subject,
                                                              @NotNull String text, @Nullable Object @NotNull ... arguments) {
        return new ManifestException(new ValidationResult.Builder().add(code, subject, text, arguments).build());
    }


    private ModManifestReader() {
    }
}
