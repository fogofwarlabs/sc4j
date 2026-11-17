package org.fogofwarlabs.sc4j.catalog.mods;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.JsonFieldReader;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the catalog of mods that the Install mod dialog lists:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "mods": [
 *     {
 *       "name": {"en": "My Mod", "ko": "내 모드"},
 *       "repository": "owner/my-mod",
 *       "description": {"en": "A short description.", "ko": "짧은 설명."}
 *     }
 *   ]
 * }
 *}
 * {@code name} and {@code description} are localized texts of the same form as in the mod manifest ({@link LocalizedText});
 * {@code description} is optional. An entry without a valid name or repository, or with an invalid description, is left out. The reader
 * builds in no entry: without the catalog file the list is empty.
 */
public final class ModIndex {
    /**
     * The address of the SC4J mod catalog.
     */
    public static final @NotNull URI DEFAULT_ADDRESS = URI.create("https://raw.githubusercontent.com/fogofwarlabs/sc4j-mods/main/mods.json");

    /**
     * The catalog format this release reads.
     */
    public static final int FORMAT = 1;

    private static final int MAXIMUM_SIZE = 1024 * 1024;
    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String MODS_FIELD = "mods";
    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String REPOSITORY_FIELD = "repository";
    private static final @NotNull String DESCRIPTION_FIELD = "description";

    /**
     * Parses a catalog.
     *
     * @param contents the catalog file
     * @return the valid entries, in the order of the file
     * @throws IOException if the file is not JSON or not a catalog of format {@value #FORMAT}
     */
    public static @NotNull List<@NotNull ModIndexEntry> parse(byte @NotNull [] contents) throws IOException {
        if (!(JsonReader.parse(contents) instanceof Map<?, ?> catalog) || !(catalog.get(FORMAT_FIELD) instanceof Long format) || format != FORMAT ||
            !(catalog.get(MODS_FIELD) instanceof List<?> mods)) {
            throw Exceptions.newIOException("Not a mod catalog of format {0}", FORMAT);
        }

        List<ModIndexEntry> entries = new ArrayList<>(mods.size());

        for (int index = 0; index < mods.size(); index++) {
            ValidationResult.Builder problems = new ValidationResult.Builder();
            JsonFieldReader entry = JsonFieldReader.of(mods.get(index), MODS_FIELD + "[" + index + "]", problems);

            if (entry == null) {
                continue;
            }

            LocalizedText name = entry.readRequiredLocalizedText(NAME_FIELD);
            String repositoryText = entry.readRequiredString(REPOSITORY_FIELD);
            LocalizedText description = entry.readOptionalLocalizedText(DESCRIPTION_FIELD);
            ReleaseRepository repository = repositoryText == null ? null : ReleaseRepository.parse(repositoryText);

            if (problems.isValid() && name != null && repository != null) {
                entries.add(new ModIndexEntry(name, repository, description));
            }
        }

        return entries;
    }


    private final @NotNull ReleaseClient client;
    private final @NotNull URI address;

    /**
     * Creates a {@link ModIndex} instance.
     *
     * @param client  reads the catalog
     * @param address the catalog's address, normally {@link #DEFAULT_ADDRESS}
     */
    public ModIndex(@NotNull ReleaseClient client, @NotNull URI address) {
        this.client  = client;
        this.address = address;
    }

    /**
     * Reads the catalog.
     *
     * @return the valid entries, in the order of the file
     * @throws IOException if the catalog cannot be read or is not a catalog
     */
    public @NotNull List<@NotNull ModIndexEntry> read() throws IOException {
        return parse(this.client.read(this.address, MAXIMUM_SIZE));
    }
}
