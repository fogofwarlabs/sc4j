package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.animation.AnimationLibrary;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.SoundType;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.fogofwarlabs.sc4j.engine.map.TilesetLegend;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.resource.ResourceType;
import org.fogofwarlabs.sc4j.engine.resource.audio.SoundClip;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.engine.resource.graphics.*;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.StringTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads an engine standard data pack, a folder, a zip archive or the resources of a class loader, as a {@link ResourceProvider},
 * and its content definitions ({@link #readContent}). The package description gives the file format.
 * <p>
 * The pack reads its manifest when it is opened and every other file when a resource is loaded; the animation library
 * is read once and kept. Sprite sheets, sounds, palettes, string tables, tile graphics, fonts, dialogs and pictures are loaded under their
 * pack id as the key; this pack format has no texture sheets, so those loads throw {@link ResourceNotFoundException}.
 */
public final class StandardPack implements ResourceProvider, Closeable {
    /**
     * The prefix of a palette colour.
     */
    private static final @NotNull String COLOR_PREFIX = "#";
    /**
     * The number of hexadecimal digits of an opaque {@code RRGGBB} colour.
     */
    private static final int OPAQUE_COLOR_DIGITS = 6;
    /**
     * The number of hexadecimal digits of an {@code AARRGGBB} colour.
     */
    private static final int ALPHA_COLOR_DIGITS = 8;
    /**
     * The alpha bits of an opaque colour.
     */
    private static final int OPAQUE_ALPHA = 0xFF000000;
    /**
     * The radix of the colour digits.
     */
    private static final int HEXADECIMAL_RADIX = 16;

    /**
     * Opens a pack with the engine instructions.
     *
     * @param path the pack folder or zip archive
     * @return the opened pack
     * @throws IOException if the pack cannot be opened or its manifest is invalid
     */
    public static @NotNull StandardPack open(@NotNull Path path) throws IOException {
        return open(path, List.of());
    }

    /**
     * Opens a pack with the engine instructions and the instruction types of a mod.
     *
     * @param path             the pack folder or zip archive
     * @param instructionTypes the mod's instruction types
     * @return the opened pack
     * @throws IllegalArgumentException if a keyword of the mod's types is used twice, by an engine type or by inline labels
     * @throws IOException              if the pack cannot be opened or its manifest is invalid
     */
    public static @NotNull StandardPack open(@NotNull Path path, @NotNull List<@NotNull PackInstructionType> instructionTypes) throws IOException {
        Map<String, PackInstructionType> types = collectInstructionTypes(instructionTypes);

        return openSource(Files.isDirectory(path) ? new FolderPackSource(path) : new ZipPackSource(path), types);
    }

    /**
     * Opens a pack in the resources of a class loader with the engine instructions, such as a pack a mod ships in its own jar.
     *
     * @param loader the class loader whose resources hold the pack, for a mod the class loader of its classes
     * @param root   the resource path of the pack root, without leading or trailing {@code /}
     * @return the opened pack
     * @throws IOException if the root is invalid, or the manifest is missing or invalid
     */
    public static @NotNull StandardPack openResources(@NotNull ClassLoader loader, @NotNull String root) throws IOException {
        return openResources(loader, root, List.of());
    }

    /**
     * Opens a pack in the resources of a class loader with the engine instructions and the instruction types of a mod.
     *
     * @param loader           the class loader whose resources hold the pack, for a mod the class loader of its classes
     * @param root             the resource path of the pack root, without leading or trailing {@code /}
     * @param instructionTypes the mod's instruction types
     * @return the opened pack
     * @throws IllegalArgumentException if a keyword of the mod's types is used twice, by an engine type or by inline labels
     * @throws IOException              if the root is invalid, or the manifest is missing or invalid
     */
    public static @NotNull StandardPack openResources(@NotNull ClassLoader loader, @NotNull String root,
                                                      @NotNull List<@NotNull PackInstructionType> instructionTypes) throws IOException {
        Map<String, PackInstructionType> types = collectInstructionTypes(instructionTypes);

        return openSource(new ResourcePackSource(loader, root), types);
    }

    private static @NotNull Map<@NotNull String, @NotNull PackInstructionType> collectInstructionTypes(@NotNull List<@NotNull PackInstructionType> instructionTypes) {
        Map<String, PackInstructionType> types = new LinkedHashMap<>();

        for (StandardInstructionType type : StandardInstructionType.values()) {
            types.put(type.getKeyword(), type);
        }

        for (PackInstructionType type : instructionTypes) {
            if (type.getKeyword().equals(PackAnimationReader.LABEL_FIELD) || types.putIfAbsent(type.getKeyword(), type) != null) {
                throw Exceptions.newIllegalArgumentException("The instruction keyword {0} is already used.", type.getKeyword());
            }
        }

        return Collections.unmodifiableMap(types);
    }

    private static @NotNull StandardPack openSource(@NotNull PackSource source, @NotNull Map<@NotNull String, @NotNull PackInstructionType> types) throws IOException {
        try {
            return new StandardPack(source, PackManifest.read(source), types);
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    source::close
            );

            throw exception;
        }
    }

    private static int parseColor(@NotNull String color, @NotNull String location) throws IOException {
        int digits = color.length() - COLOR_PREFIX.length();

        if (!color.startsWith(COLOR_PREFIX) || digits != OPAQUE_COLOR_DIGITS && digits != ALPHA_COLOR_DIGITS) {
            throw Exceptions.newIOException("{0}: expected #RRGGBB or #AARRGGBB, found {1}.", location, color);
        }

        int value;

        try {
            value = Integer.parseUnsignedInt(color.substring(COLOR_PREFIX.length()), HEXADECIMAL_RADIX);
        } catch (NumberFormatException exception) {
            throw Exceptions.newIOException(exception, "{0}: expected #RRGGBB or #AARRGGBB, found {1}.", location, color);
        }

        return digits == OPAQUE_COLOR_DIGITS ? value | OPAQUE_ALPHA : value;
    }


    private final @NotNull PackSource source;
    private final @NotNull PackManifest manifest;
    private final @NotNull Map<@NotNull String, @NotNull PackInstructionType> instructionTypes;
    private final @NotNull List<@NotNull SoundType> soundTypes;
    private final @NotNull List<@NotNull ImageType> imageTypes;

    private @Nullable AnimationLibrary animations;

    private StandardPack(@NotNull PackSource source, @NotNull PackManifest manifest,
                         @NotNull Map<@NotNull String, @NotNull PackInstructionType> instructionTypes) {
        this.source           = source;
        this.manifest         = manifest;
        this.instructionTypes = instructionTypes;
        this.soundTypes       = manifest.getSoundIds().stream().map(SoundType::new).toList();
        this.imageTypes       = manifest.getSpriteSheetIds().stream().map(ImageType::new).toList();

        this.animations = null;
    }

    /**
     * @return the sprite sheet ids of the pack in index order: sprite sheet {@code n} has animation set {@code n}
     */
    public @NotNull List<@NotNull String> getSpriteSheetIds() {
        return this.manifest.getSpriteSheetIds();
    }

    /**
     * @return the sound ids of the pack in index order, the sound indices of the animations
     */
    public @NotNull List<@NotNull String> getSoundIds() {
        return this.manifest.getSoundIds();
    }

    /**
     * The sound types are the sounds the animations play; a mod registers them as its content sound types, in this order.
     *
     * @return the sound types of the pack in sound index order, named by their sound ids and not yet registered
     */
    public @NotNull List<@NotNull SoundType> getSoundTypes() {
        return this.soundTypes;
    }

    /**
     * The image types are the overlays the animations create; a mod registers them as its content image types, in this order, so that image
     * type {@code n} has animation set {@code n}.
     *
     * @return the image types of the pack in sprite sheet index order, one per sprite sheet, named by its id and not yet registered
     */
    public @NotNull List<@NotNull ImageType> getImageTypes() {
        return this.imageTypes;
    }


    /**
     * @return {@code true} if the pack has a content definitions file, {@code false} otherwise
     */
    public boolean hasContent() {
        return this.manifest.getContent() != null;
    }

    /**
     * Reads the content definitions file of the pack: its resource kinds, motion profiles, weapon types, factions and unit types with their
     * definitions and declarative requirements, as new types the mod registers ({@link PackContent#register}). Each call reads the file again
     * and creates new types.
     *
     * @param orderTypes the order types of the mod that a unit definition may name as its idle order, by their debug names
     * @return the content
     * @throws IOException if the pack has no content definitions file, or the file cannot be read, is not a content file of the format or names a missing type
     */
    public @NotNull PackContent readContent(@NotNull List<@NotNull OrderType> orderTypes) throws IOException {
        String path = this.manifest.getContent();

        if (path == null) {
            throw Exceptions.newIOException("The pack {0} has no content definitions file.", this.source.getLocation());
        }

        return PackContentReader.read(this.source.read(path), this.source.getLocation() + "!" + path, orderTypes);
    }


    /**
     * Loads a sprite sheet.
     *
     * @param key the key of the sprite sheet
     * @return the sprite sheet
     * @throws ResourceNotFoundException if the provider has no sprite sheet under the key
     * @throws IOException               if the sprite sheet cannot be read
     */
    @Override
    public @NotNull SpriteSheet loadSpriteSheet(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findSpriteSheet(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.SPRITE_SHEET, key);
        }

        return PackSpriteSheetUtils.read(this.source, path);
    }

    /**
     * Loads a palette.
     *
     * @param key the key of the palette
     * @return the palette
     * @throws ResourceNotFoundException if the provider has no palette under the key
     * @throws IOException               if the palette cannot be read
     */
    @Override
    public @NotNull Palette loadPalette(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findPalette(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.PALETTE, key);
        }

        List<?> entries = JsonObjectReader.parseArray(this.source.read(path), path);
        int[] colors = new int[entries.size()];

        if (colors.length < 1 || colors.length > Palette.MAXIMUM_SIZE) {
            throw Exceptions.newIOException("{0}: a palette has 1..{1} colours: {2}", path, Palette.MAXIMUM_SIZE, colors.length);
        }

        for (int index = 0; index < colors.length; index++) {
            String location = path + "[" + index + "]";

            colors[index] = parseColor(JsonObjectReader.toText(entries.get(index), location), location);
        }

        return new Palette(colors);
    }

    /**
     * Loads a texture sheet: an animated picture stored as block-compressed GPU textures.
     * <p>
     * This pack format has no texture sheets; its pictures are PNG sprite sheets.
     *
     * @param key the key of the texture sheet
     * @return the texture sheet
     * @throws ResourceNotFoundException always
     */
    @Override
    public @NotNull TextureSheet loadTextureSheet(@NotNull ResourceKey key) throws IOException {
        throw ResourceNotFoundException.create(ResourceType.TEXTURE_SHEET, key);
    }

    /**
     * Loads the graphics of a tileset.
     *
     * @param key the key of the tileset
     * @return the tile graphics
     * @throws ResourceNotFoundException if the provider has no tileset under the key
     * @throws IOException               if the tile graphics cannot be read
     */
    @Override
    public @NotNull TileGraphics loadTileGraphics(@NotNull ResourceKey key) throws IOException {
        return PackTilesetUtils.readGraphics(this.source, this.requireTileset(key));
    }

    /**
     * Loads the tile codes of a tileset: the tile indices of its {@link #loadTileGraphics tile graphics} that show each tile code of the
     * engine map format.
     *
     * @param key the key of the tileset
     * @return the legend of the tileset
     * @throws ResourceNotFoundException if the provider has no tileset under the key
     * @throws IOException               if the tileset cannot be read or a code names a missing tile
     */
    public @NotNull TilesetLegend loadTilesetLegend(@NotNull ResourceKey key) throws IOException {
        String path = this.requireTileset(key);

        return PackTilesetUtils.readLegend(this.source, path, PackTilesetUtils.readGraphics(this.source, path).getTileCount());
    }

    private @NotNull String requireTileset(@NotNull ResourceKey key) throws ResourceNotFoundException {
        String path = this.manifest.findTileset(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.TILE_GRAPHICS, key);
        }

        return path;
    }

    /**
     * Loads the animation library: every animation set and program of the provider.
     * <p>
     * The library is read at the first call and kept.
     *
     * @return the animation library
     * @throws IOException if the animations cannot be read
     */
    @Override
    public @NotNull AnimationLibrary loadAnimations() throws IOException {
        AnimationLibrary library = this.animations;

        if (library == null) {
            library = new PackAnimationReader(this.source, this.manifest, this.instructionTypes, this.soundTypes, this.imageTypes).read();

            this.animations = library;
        }

        return library;
    }

    /**
     * Loads a sound clip.
     *
     * @param key the key of the sound
     * @return the sound clip
     * @throws ResourceNotFoundException if the provider has no sound under the key
     * @throws IOException               if the sound cannot be read
     */
    @Override
    public @NotNull SoundClip loadSound(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findSound(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.SOUND, key);
        }

        return PackSoundUtils.read(this.source, path);
    }

    /**
     * Loads a string table.
     *
     * @param key the key of the string table
     * @return the string table
     * @throws ResourceNotFoundException if the provider has no string table under the key
     * @throws IOException               if the string table cannot be read
     */
    @Override
    public @NotNull StringTable loadStringTable(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findStringTable(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.STRING_TABLE, key);
        }

        List<?> entries = JsonObjectReader.parseArray(this.source.read(path), path);
        List<String> strings = new ArrayList<>(entries.size());

        for (int index = 0; index < entries.size(); index++) {
            strings.add(JsonObjectReader.toText(entries.get(index), path + "[" + index + "]"));
        }

        return new StringTable(strings);
    }

    /**
     * Loads a font face.
     *
     * @param key the key of the font
     * @return the font face
     * @throws ResourceNotFoundException if the provider has no font under the key
     * @throws IOException               if the font cannot be read
     */
    @Override
    public @NotNull FontFace loadFont(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findFont(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.FONT, key);
        }

        return PackFontUtils.read(this.source, path);
    }

    /**
     * Loads a dialog definition.
     *
     * @param key the key of the dialog
     * @return the dialog definition
     * @throws ResourceNotFoundException if the provider has no dialog under the key
     * @throws IOException               if the dialog cannot be read
     */
    @Override
    public @NotNull DialogDefinition loadDialog(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findDialog(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.DIALOG, key);
        }

        return PackDialogUtils.read(this.source, key.getName(), path);
    }

    /**
     * Loads a still picture of the interface: a screen, an emblem, a portrait, an icon, a skin piece or a cursor.
     *
     * @param key the key of the picture
     * @return the picture
     * @throws ResourceNotFoundException if the provider has no picture under the key
     * @throws IOException               if the picture cannot be read
     */
    @Override
    public @NotNull Picture loadPicture(@NotNull ResourceKey key) throws IOException {
        String path = this.manifest.findPicture(key);

        if (path == null) {
            throw ResourceNotFoundException.create(ResourceType.PICTURE, key);
        }

        return PackPictureUtils.read(this.source, path);
    }

    /**
     * Closes the pack; a zip archive is closed, and later loads fail.
     *
     * @throws IOException if the archive cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.source.close();
    }
}
