package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the programs of an install with those of an unpacked user package: the items {@link #ITEMS}, that is {@code java/},
 * {@code launcher/}, {@code runtime/}, the start program {@code SC4J.exe} and the console program {@code SC4J-cli.exe} with their folder
 * {@code app/}, the start files, {@code LICENSE}, {@code NOTICE} and {@code licenses/}. Only the Windows package has the items
 * {@link #WINDOWS_ITEMS}; an install updated from a package without them loses its own.
 * <p>
 * The mod jars the package ships, listed in its file {@link #PACKAGE_MODS_FILE}, are updated too, compared with the same list of the install:
 * a listed jar the install has is replaced when the install's list names it too, a listed jar the install's list does not name is added when
 * the install has no such jar, and a jar only the install's list names is removed. So a shipped jar the player deleted stays deleted, and the
 * jars the player installed are not touched. An install without the list has every listed jar replaced or added. {@code userdata/} and
 * every other file of the install stay as they are.
 * <p>
 * Each package item is copied into {@link #STAGED_FOLDER} of the backup folder, the install's item is moved into the backup folder, and the
 * copy is moved into its place; the package is not changed. On a failure every replaced item is moved aside and its backup moved back; an
 * item that cannot be restored keeps the new one. A failed move is tried again a few times.
 */
public final class PackageSwap {
    /**
     * The Java runtime folder of the user package.
     */
    public static final @NotNull String JAVA_FOLDER = "java";

    /**
     * The start program of the launcher on Windows.
     */
    public static final @NotNull String WINDOWS_PROGRAM = "SC4J.exe";

    /**
     * The console program of the launcher on Windows, which runs the launcher's console commands in the console it is started from.
     */
    public static final @NotNull String WINDOWS_CONSOLE_PROGRAM = "SC4J-cli.exe";

    /**
     * The folder of the configurations of {@link #WINDOWS_PROGRAM} and {@link #WINDOWS_CONSOLE_PROGRAM}.
     */
    public static final @NotNull String WINDOWS_PROGRAM_FOLDER = "app";

    /**
     * The start file of the launcher on Windows.
     */
    public static final @NotNull String WINDOWS_START_FILE = "SC4J.bat";

    /**
     * The start file of the launcher on other systems.
     */
    public static final @NotNull String UNIX_START_FILE = "SC4J";

    /**
     * The items of the install an update replaces, in the order they are replaced.
     */
    public static final @NotNull List<@NotNull String> ITEMS = List.of(JAVA_FOLDER, InstallLayout.LAUNCHER_FOLDER, InstallLayout.RUNTIME_FOLDER,
                                                                       WINDOWS_PROGRAM, WINDOWS_CONSOLE_PROGRAM, WINDOWS_PROGRAM_FOLDER,
                                                                       WINDOWS_START_FILE, UNIX_START_FILE, "LICENSE", "NOTICE", "licenses");

    /**
     * The items of {@link #ITEMS} that only the Windows package has.
     */
    public static final @NotNull List<@NotNull String> WINDOWS_ITEMS = List.of(WINDOWS_PROGRAM, WINDOWS_CONSOLE_PROGRAM, WINDOWS_PROGRAM_FOLDER);

    /**
     * The file of a package, kept in the install, that lists the jars of {@code mods/} the package ships, one file name per line.
     */
    public static final @NotNull String PACKAGE_MODS_FILE = InstallLayout.LAUNCHER_FOLDER + "/package-mods.txt";

    /**
     * The folder of the backup folder that holds the staged package items and the items moved aside when an update is undone.
     */
    public static final @NotNull String STAGED_FOLDER = ".staged";

    private static final @NotNull String JAR_EXTENSION = ".jar";

    /**
     * Checks that an unpacked user package holds every item an update replaces, the {@link #WINDOWS_ITEMS} aside, and every mod jar its
     * {@link #PACKAGE_MODS_FILE} lists.
     *
     * @param packageRoot the package's folder
     * @throws IOException if an item or a listed mod jar is missing or the list cannot be read
     */
    public static void checkPackage(@NotNull Path packageRoot) throws IOException {
        for (String item : ITEMS) {
            if (!WINDOWS_ITEMS.contains(item) && !Files.exists(packageRoot.resolve(item), LinkOption.NOFOLLOW_LINKS)) {
                throw Exceptions.newIOException("The package {0} has no {1}.", packageRoot, item);
            }
        }

        List<String> packageMods = readPackageMods(packageRoot);

        if (packageMods != null) {
            for (String jar : packageMods) {
                if (!Files.isRegularFile(packageRoot.resolve(InstallLayout.MODS_FOLDER).resolve(jar), LinkOption.NOFOLLOW_LINKS)) {
                    throw Exceptions.newIOException("The package {0} has no {1}/{2}.", packageRoot, InstallLayout.MODS_FOLDER, jar);
                }
            }
        }
    }

    /**
     * Reads the jars of {@code mods/} that a package ships, or that the package an install came from shipped, from its
     * {@link #PACKAGE_MODS_FILE}. Blank lines are skipped.
     *
     * @param root the package's or the install's folder
     * @return the jar file names, or {@code null} when the folder has no such file
     * @throws IOException if the file cannot be read or a line is not the file name of a jar
     */
    public static @Nullable List<@NotNull String> readPackageMods(@NotNull Path root) throws IOException {
        Path file = root.resolve(PACKAGE_MODS_FILE);

        if (!Files.isRegularFile(file)) {
            return null;
        }

        List<String> jars = new ArrayList<>();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String jar = line.strip();

            if (jar.isEmpty()) {
                continue;
            }

            if (!jar.endsWith(JAR_EXTENSION) || jar.contains("/") || jar.contains("\\") || jar.contains(":")) {
                throw Exceptions.newIOException("{0} names {1}, which is not the file name of a jar.", file, jar);
            }

            jars.add(jar);
        }

        return jars;
    }


    private final @NotNull Path installRoot;
    private final @NotNull Path packageRoot;
    private final @NotNull Path backupRoot;
    private final int moveAttempts;
    private final long retryDelayMillis;

    /**
     * Creates a {@link PackageSwap} instance.
     *
     * @param installRoot      the install folder
     * @param packageRoot      the unpacked user package
     * @param backupRoot       the folder that keeps the replaced items, deleted by the caller; it holds none of the items and is on the install's file system
     * @param moveAttempts     how often a move is tried, at least {@code 1}
     * @param retryDelayMillis the wait before a move is tried again, in milliseconds
     */
    public PackageSwap(@NotNull Path installRoot, @NotNull Path packageRoot, @NotNull Path backupRoot, int moveAttempts, long retryDelayMillis) {
        this.installRoot      = installRoot;
        this.packageRoot      = packageRoot;
        this.backupRoot       = backupRoot;
        this.moveAttempts     = Math.max(1, moveAttempts);
        this.retryDelayMillis = retryDelayMillis;
    }

    /**
     * Replaces the items of the install and the mod jars the package ships with those of the package. On a failure the old items are restored
     * before the failure is thrown; its message says when they could not all be restored.
     *
     * @throws IOException if the package lacks an item, a list of mod jars cannot be read or an item cannot be replaced
     */
    public void apply() throws IOException {
        List<String> items = new ArrayList<>(ITEMS);
        List<String> replaced = new ArrayList<>();
        Path stagedRoot = this.backupRoot.resolve(STAGED_FOLDER);

        checkPackage(this.packageRoot);
        items.addAll(this.findModItems());
        FileTreeUtils.delete(stagedRoot);
        Files.createDirectories(stagedRoot);

        try {
            for (String item : items) {
                Path installed = this.installRoot.resolve(item);
                Path packaged = this.packageRoot.resolve(item);
                Path staged = stagedRoot.resolve(item);

                if (Files.exists(packaged, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(staged.getParent());
                    FileTreeUtils.copy(packaged, staged);
                }

                if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = this.backupRoot.resolve(item);

                    Files.createDirectories(backup.getParent());
                    this.move(installed, backup);
                }

                replaced.add(item);

                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(installed.getParent());
                    this.move(staged, installed);
                }
            }
        } catch (IOException exception) {
            List<String> unrestored = this.restore(replaced, stagedRoot, exception);

            if (!unrestored.isEmpty()) {
                throw Exceptions.newIOException(exception, "The update failed and {0} kept the new files; the old ones are in {1}.", unrestored,
                                                this.backupRoot);
            }

            throw exception;
        }
    }

    private @NotNull List<@NotNull String> findModItems() throws IOException {
        List<String> installedMods = readPackageMods(this.installRoot);
        List<String> packageMods = readPackageMods(this.packageRoot);
        List<String> items = new ArrayList<>();

        if (packageMods != null) {
            for (String jar : packageMods) {
                boolean shipped = installedMods != null && installedMods.contains(jar);
                boolean present = Files.exists(this.installRoot.resolve(InstallLayout.MODS_FOLDER).resolve(jar), LinkOption.NOFOLLOW_LINKS);

                // A present jar the install's list does not name is the player's; an absent jar it names was deleted by the player.
                if (installedMods == null || present == shipped) {
                    items.add(InstallLayout.MODS_FOLDER + "/" + jar);
                }
            }
        }

        if (installedMods != null) {
            for (String jar : installedMods) {
                if (packageMods == null || !packageMods.contains(jar)) {
                    items.add(InstallLayout.MODS_FOLDER + "/" + jar);
                }
            }
        }

        return items;
    }

    /**
     * Moves every replaced item aside and its backup back, the last replaced first, and adds each failure to the update's failure.
     *
     * @return the items that kept the new files
     */
    private @NotNull List<@NotNull String> restore(@NotNull List<@NotNull String> replaced, @NotNull Path stagedRoot,
                                                   @NotNull IOException updateFailure) {
        List<String> unrestored = new ArrayList<>();

        for (String item : replaced.reversed()) {
            Path installed = this.installRoot.resolve(item);
            Path backup = this.backupRoot.resolve(item);
            Path aside = stagedRoot.resolve(item);

            try {
                if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                    FileTreeUtils.delete(aside);
                    Files.createDirectories(aside.getParent());
                    this.move(installed, aside);
                }

                if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    this.move(backup, installed);
                }
            } catch (IOException restoreFailure) {
                updateFailure.addSuppressed(restoreFailure);
                unrestored.add(item);
            }
        }

        return unrestored;
    }

    private void move(@NotNull Path source, @NotNull Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target);

                return;
            } catch (IOException exception) {
                if (attempt >= this.moveAttempts) {
                    throw exception;
                }
            }

            try {
                Thread.sleep(this.retryDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                throw Exceptions.newIOException(exception, "Interrupted while moving {0}.", source);
            }
        }
    }
}
