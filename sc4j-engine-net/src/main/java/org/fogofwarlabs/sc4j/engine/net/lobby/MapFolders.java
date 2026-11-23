package org.fogofwarlabs.sc4j.engine.net.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds the map folders of a mod: the ordered folders a lobby's map is searched in and the folder a received map is saved to.
 * <p>
 * A search ({@link #findFile}) takes the first file, in folder order, that the given {@link MapFileMatch} accepts; the file name is not
 * compared, so a renamed copy is found. Within a folder its files come before its subfolders, which are searched the same way in their
 * listing order. A save ({@link #saveFile}) never replaces another file: it reuses a file of the same contents under the received name or a
 * numbered one ({@code "<name> (2)<extension>"}), else writes a temporary {@code .part} file that then takes the first free name, so two runs
 * that save one map at the same time never write into one file.
 */
public final class MapFolders {
    /**
     * The map folder of a mod's storage.
     */
    public static final @NotNull String STORAGE_FOLDER = "maps";
    /**
     * The subfolder of the first map folder that a mod's storage saves received maps to.
     */
    public static final @NotNull String DOWNLOAD_FOLDER = "downloads";

    private static final @NotNull Logger LOGGER = Logger.of(MapFolders.class);

    /**
     * The most names a save tries: the received name, then {@code "<name> (2)<extension>"} up to this number.
     */
    private static final int SAVE_NAME_LIMIT = 99;
    /**
     * The first number of a save name taken when the received name holds another file.
     */
    private static final int FIRST_NAME_NUMBER = 2;
    /**
     * The prefix and suffix of the temporary file a save is written to before it takes its name.
     */
    private static final @NotNull String PARTIAL_PREFIX = "map-";
    private static final @NotNull String PARTIAL_SUFFIX = ".part";

    /**
     * Creates a {@link MapFolders} instance that searches the folders in order and saves into a subfolder of the first one.
     *
     * @param readFolders   the map folders in search order, the player's own map folder first
     * @param saveSubfolder the name of the subfolder of the first map folder that received maps are saved to
     * @return the created {@link MapFolders} instance, without a save folder when no folder is given
     */
    public static @NotNull MapFolders of(@NotNull List<Path> readFolders, @NotNull String saveSubfolder) {
        return new MapFolders(readFolders, readFolders.isEmpty() ? null : readFolders.getFirst().resolve(saveSubfolder));
    }

    /**
     * Searches one folder: its files in listing order, then its subfolders the same way. A folder that cannot be read is skipped.
     */
    private static @Nullable Path searchFolder(@NotNull Path folder, @NotNull MapFileMatch match) {
        List<Path> entries = new ArrayList<>();

        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                entries.add(child);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("MAP SEARCH: " + folder + " skipped: " + exception.getMessage());

            return null;
        }

        for (Path entry : entries) {
            if (Files.isRegularFile(entry) && isMatchingFile(entry, match)) {
                return entry;
            }
        }

        for (Path entry : entries) {
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            Path found = searchFolder(entry, match);

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Checks a file with a match; a file that cannot be read does not match.
     */
    private static boolean isMatchingFile(@NotNull Path file, @NotNull MapFileMatch match) {
        try {
            return match.matches(file);
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("MAP SEARCH: " + file + " skipped: " + exception.getMessage());

            return false;
        }
    }

    /**
     * Checks whether a file holds the given contents; a file that cannot be read does not.
     */
    private static boolean hasContents(@NotNull Path file, byte @NotNull [] contents) {
        try {
            return Files.size(file) == contents.length && Arrays.equals(contents, Files.readAllBytes(file));
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Gives a save name: the received name for number {@code 1}, else {@code "<stem> (<number>)<extension>"}.
     */
    private static @NotNull String getSaveName(@NotNull String name, int number) {
        if (number < FIRST_NAME_NUMBER) {
            return name;
        }

        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";

        return stem + " (" + number + ")" + extension;
    }


    private final @NotNull List<Path> readFolders;
    private final @Nullable Path saveFolder;

    /**
     * Creates a {@link MapFolders} instance.
     *
     * @param readFolders the map folders in search order
     * @param saveFolder  the folder received maps are saved to, or {@code null} for none (a save then fails)
     */
    public MapFolders(@NotNull List<Path> readFolders, @Nullable Path saveFolder) {
        this.readFolders = List.copyOf(readFolders);
        this.saveFolder  = saveFolder;
    }

    /**
     * @return the map folders in search order
     */
    public @NotNull List<Path> getReadFolders() {
        return this.readFolders;
    }

    /**
     * @return the folder received maps are saved to, or {@code null} for none
     */
    public @Nullable Path getSaveFolder() {
        return this.saveFolder;
    }


    /**
     * Finds a local copy of a map: the first file, searching the folders in order and in each folder its files before its subfolders, that the
     * match accepts. File names are not compared; a folder or file that cannot be read is skipped.
     *
     * @param match the match of the wanted map
     * @return the first matching file, or {@code null} if no folder holds one
     */
    public @Nullable Path findFile(@NotNull MapFileMatch match) {
        for (Path folder : this.readFolders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }

            Path found = searchFolder(folder, match);

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Saves a received map into the save folder, created if missing, under its file name, which must be one name directly inside the folder.
     * A file of that name and the same contents is taken as it is; a file of other contents is never replaced (another run or the host may
     * have it open), the map takes the next free name {@code "<stem> (<number>)<extension>"} instead. The bytes go to a temporary file of a
     * unique name first, which then takes the name only while the name is free; the temporary file never stays.
     *
     * @param fileName the received file name
     * @param contents the map file contents
     * @return the absolute, normalized file that holds the contents
     * @throws IOException if there is no save folder, the name is no single name inside it, the folder or file cannot be written, or every name holds another file
     */
    public @NotNull Path saveFile(@NotNull String fileName, byte @NotNull [] contents) throws IOException {
        Path folder = this.saveFolder;

        if (folder == null) {
            throw Exceptions.newIOException("There is no folder to save the map {0} to.", fileName);
        }

        Path base = folder.toAbsolutePath().normalize();

        try {
            Path name = base.getFileSystem().getPath(fileName);

            if (name.isAbsolute() || name.getRoot() != null || name.getNameCount() != 1 || !base.equals(base.resolve(name).normalize().getParent())) {
                throw Exceptions.newIOException("The map file name {0} is not a name inside the save folder.", fileName);
            }
        } catch (InvalidPathException exception) {
            throw Exceptions.newIOException(exception, "Invalid map file name: {0}", fileName);
        }

        Files.createDirectories(base);

        Path partial = null;

        try {
            for (int number = 1; number <= SAVE_NAME_LIMIT; number++) {
                Path target = base.resolve(getSaveName(fileName, number));

                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (hasContents(target, contents)) {
                        return target;
                    }

                    continue;
                }

                if (partial == null) {
                    partial = Files.createTempFile(base, PARTIAL_PREFIX, PARTIAL_SUFFIX);
                    Files.write(partial, contents);
                }

                try {
                    Files.move(partial, target);
                    partial = null;

                    return target;
                } catch (FileAlreadyExistsException exception) {
                    // Another run took the name meanwhile: its file is taken when it holds the same contents, else the next name is tried.
                    if (hasContents(target, contents)) {
                        return target;
                    }
                }
            }
        } finally {
            if (partial != null) {
                Files.deleteIfExists(partial);
            }
        }

        throw Exceptions.newIOException("Every name of the map file {0} holds another file.", fileName);
    }
}
