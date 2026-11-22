package org.fogofwarlabs.sc4j.manifest;

import org.fogofwarlabs.sc4j.manifest.settings.SettingsSchema;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Describes a mod as its manifest {@code sc4j-mod.json} declares it: identity ({@code id}, {@code version}), the engine
 * API version it is built for, the entry class, the texts, legal notices and images the launcher shows, the pre-run settings schema and the
 * command line tools.
 * <p>
 * {@link ModManifestReader} creates instances from a mod jar or from manifest bytes; every field is already validated.
 */
public final class ModManifest {
    private final int format;
    private final @NotNull String id;
    private final @NotNull ModVersion version;
    private final @NotNull EngineVersion engine;
    private final @NotNull String entry;

    private final @NotNull LocalizedText name;
    private final @Nullable LocalizedText description;
    private final @NotNull List<@NotNull String> authors;
    private final @Nullable String license;
    private final @NotNull List<@NotNull LocalizedText> notices;
    private final @Nullable String website;
    private final @NotNull ModImages images;

    private final @NotNull SettingsSchema settings;
    private final @NotNull List<@NotNull ModTool> tools;

    /**
     * Creates a {@link ModManifest} instance from validated fields.
     *
     * @param format      the manifest format
     * @param id          the mod id: lower case letters, digits and single hyphens; also the mod's folder name
     * @param version     the mod version
     * @param engine      the engine API version the mod is built for
     * @param entry       the binary name of the class that implements the engine's mod interface
     * @param name        the display name
     * @param description the display description, or {@code null}
     * @param authors     the authors, in listed order
     * @param license     the license text or name, or {@code null}
     * @param notices     the legal notices, in listed order
     * @param website     the website address, or {@code null}
     * @param images      the launcher images
     * @param settings    the pre-run settings schema
     * @param tools       the command line tools, in listed order
     */
    ModManifest(int format, @NotNull String id, @NotNull ModVersion version, @NotNull EngineVersion engine, @NotNull String entry,
                @NotNull LocalizedText name, @Nullable LocalizedText description, @NotNull List<@NotNull String> authors,
                @Nullable String license, @NotNull List<@NotNull LocalizedText> notices, @Nullable String website, @NotNull ModImages images,
                @NotNull SettingsSchema settings, @NotNull List<@NotNull ModTool> tools) {
        this.format  = format;
        this.id      = id;
        this.version = version;
        this.engine  = engine;
        this.entry   = entry;

        this.name        = name;
        this.description = description;
        this.authors     = List.copyOf(authors);
        this.license     = license;
        this.notices     = List.copyOf(notices);
        this.website     = website;
        this.images      = images;

        this.settings = settings;
        this.tools    = List.copyOf(tools);
    }

    /**
     * @return the manifest format ({@code format})
     */
    public int getFormat() {
        return this.format;
    }

    /**
     * @return the mod id ({@code id}): lower case letters, digits and single hyphens, also used as the mod's folder name
     */
    public @NotNull String getId() {
        return this.id;
    }

    /**
     * @return the mod version ({@code version})
     */
    public @NotNull ModVersion getVersion() {
        return this.version;
    }

    /**
     * @return the engine API version the mod is built for ({@code engine})
     */
    public @NotNull EngineVersion getEngine() {
        return this.engine;
    }

    /**
     * @return the binary name of the entry class ({@code entry})
     */
    public @NotNull String getEntry() {
        return this.entry;
    }

    /**
     * @return the display name ({@code name})
     */
    public @NotNull LocalizedText getName() {
        return this.name;
    }

    /**
     * @return the display description ({@code description}), or {@code null} when there is none
     */
    public @Nullable LocalizedText getDescription() {
        return this.description;
    }

    /**
     * @return the authors ({@code authors}), in listed order
     */
    public @NotNull List<@NotNull String> getAuthors() {
        return this.authors;
    }

    /**
     * @return the license ({@code license}), or {@code null} when there is none
     */
    public @Nullable String getLicense() {
        return this.license;
    }

    /**
     * @return the legal notices ({@code notices}), such as trademark notices and asset credits, in listed order; empty when there are none
     */
    public @NotNull List<@NotNull LocalizedText> getNotices() {
        return this.notices;
    }

    /**
     * @return the website address ({@code website}), or {@code null} when there is none
     */
    public @Nullable String getWebsite() {
        return this.website;
    }

    /**
     * @return the launcher images ({@code images})
     */
    public @NotNull ModImages getImages() {
        return this.images;
    }

    /**
     * @return the pre-run settings schema ({@code settings})
     */
    public @NotNull SettingsSchema getSettings() {
        return this.settings;
    }

    /**
     * @return the command line tools ({@code tools}), in listed order; empty when there are none
     */
    public @NotNull List<@NotNull ModTool> getTools() {
        return this.tools;
    }

    /**
     * Finds a command line tool by its id.
     *
     * @param toolId the tool id
     * @return the tool, or {@code null} when the mod has no such tool
     */
    public @Nullable ModTool findTool(@NotNull String toolId) {
        for (ModTool tool : this.tools) {
            if (tool.getId().equals(toolId)) {
                return tool;
            }
        }

        return null;
    }

    /**
     * Checks the mod's engine version against the engine version of a runtime ({@link EngineVersion#supports}).
     *
     * @param runtimeVersion the engine API version the runtime provides, normally {@link EngineVersion#CURRENT}
     * @return a {@link ProblemCode#ENGINE_UNSUPPORTED} problem when the runtime does not support the mod, valid otherwise
     */
    public @NotNull ValidationResult checkEngine(@NotNull EngineVersion runtimeVersion) {
        if (runtimeVersion.supports(this.engine)) {
            return ValidationResult.VALID;
        }

        return new ValidationResult.Builder().add(ProblemCode.ENGINE_UNSUPPORTED, "engine",
                                                  "The mod needs engine {0}, this runtime provides engine {1}.", this.engine, runtimeVersion)
                                             .build();
    }
}
