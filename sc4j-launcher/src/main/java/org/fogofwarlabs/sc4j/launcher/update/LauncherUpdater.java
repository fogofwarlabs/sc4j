package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.ReleaseCheck;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.*;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * Updates the install from the releases of the update repository. A release {@code v<version>} carries one user package per platform,
 * {@code SC4J-<version>-<platform>.zip} ({@link PackagePlatform}), each with its checksum file {@code SC4J-<version>-<platform>.zip.sha256}; the
 * updater takes only the package of the platform it runs on, and unpacks only a package that names that platform.
 * <p>
 * {@link #findUpdate} reads the latest release and tells whether it is newer than the running launcher. {@link #download} keeps the package
 * in the update folder ({@link UpdateFolder}) only when its checksum matches, and {@link #unpack} unpacks it there and checks that it holds
 * every item an update replaces ({@link PackageSwap#ITEMS}). {@link #startUpdater} starts the updater ({@link UpdaterMain}) from the unpacked
 * package with the package's own Java runtime, so that it uses no file it replaces; the launcher exits then, as the updater waits for the
 * launcher's process to end.
 */
public final class LauncherUpdater {
    private static final @NotNull String PACKAGE_PREFIX = "SC4J-";
    private static final @NotNull String PLATFORM_SEPARATOR = "-";
    private static final @NotNull String PACKAGE_EXTENSION = ".zip";
    private static final @NotNull String CHECKSUM_EXTENSION = ".sha256";
    private static final long MAXIMUM_PACKAGE_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long MAXIMUM_UNPACKED_SIZE = 4L * 1024 * 1024 * 1024;
    private static final @NotNull String BINARY_FOLDER = "bin";
    private static final @NotNull String LIBRARY_FOLDER = "lib";
    private static final @NotNull String WINDOWS_JAVA = "java.exe";
    private static final @NotNull String WINDOWS_WINDOWED_JAVA = "javaw.exe";
    private static final @NotNull String UNIX_JAVA = "java";
    private static final @NotNull String JAVA_SPAWN_HELPER = "jspawnhelper";
    private static final @NotNull String CLASS_PATH_WILDCARD = "*";
    private static final @NotNull String CLASS_PATH_OPTION = "-cp";
    private static final @NotNull String NO_PERFORMANCE_DATA_OPTION = "-XX:-UsePerfData";
    private static final @NotNull String TEMPORARY_DIRECTORY_OPTION = "-Djava.io.tmpdir=";
    private static final @NotNull Set<@NotNull PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

    /**
     * Tells whether the launcher checks for updates by itself at its start: only a release build does, never a development build
     * ({@code -SNAPSHOT}) or a run from the source tree. Any build can check when the player asks.
     *
     * @param running the running launcher's version, or {@code null} when it is not a semantic version
     * @return {@code true} if the version is a release version, {@code false} otherwise
     */
    public static boolean isAutomaticCheckAllowed(@Nullable ReleaseVersion running) {
        return running != null && !running.isSnapshot();
    }

    private static void markExecutable(@NotNull Path packageRoot) throws IOException {
        if (Files.getFileAttributeView(packageRoot, PosixFileAttributeView.class) == null) {
            return;
        }

        for (String item : List.of(PackageSwap.JAVA_FOLDER, InstallLayout.LAUNCHER_FOLDER, InstallLayout.RUNTIME_FOLDER)) {
            Path binaries = packageRoot.resolve(item).resolve(BINARY_FOLDER);

            if (Files.isDirectory(binaries)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(binaries)) {
                    for (Path file : files) {
                        Files.setPosixFilePermissions(file, EXECUTABLE);
                    }
                }
            }
        }

        Path spawnHelper = packageRoot.resolve(PackageSwap.JAVA_FOLDER).resolve(LIBRARY_FOLDER).resolve(JAVA_SPAWN_HELPER);

        if (Files.isRegularFile(spawnHelper)) {
            Files.setPosixFilePermissions(spawnHelper, EXECUTABLE);
        }

        Files.setPosixFilePermissions(packageRoot.resolve(PackageSwap.UNIX_START_FILE), EXECUTABLE);

        Path finderStartFile = packageRoot.resolve(PackageSwap.MACOS_START_FILE);

        if (Files.isRegularFile(finderStartFile)) {
            Files.setPosixFilePermissions(finderStartFile, EXECUTABLE);
        }
    }

    /**
     * Reads the platform an unpacked package names in its {@link PackageSwap#PACKAGE_PLATFORM_FILE}.
     *
     * @param packageRoot the package's folder
     * @return the platform id, or an empty string when the package names none
     * @throws IOException if the file exists but cannot be read
     */
    private static @NotNull String readPackagePlatform(@NotNull Path packageRoot) throws IOException {
        try {
            return Files.readString(packageRoot.resolve(PackageSwap.PACKAGE_PLATFORM_FILE), StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException exception) {
            return "";
        }
    }


    private final @NotNull InstallLayout install;
    private final @NotNull UpdateFolder folder;
    private final @NotNull ReleaseClient client;
    private final @Nullable PackagePlatform platform;

    /**
     * Creates a {@link LauncherUpdater} instance for the platform the launcher runs on ({@link PackagePlatform#findCurrent()}).
     *
     * @param install the install
     * @param client  reads the releases
     */
    public LauncherUpdater(@NotNull InstallLayout install, @NotNull ReleaseClient client) {
        this(install, client, PackagePlatform.findCurrent());
    }

    /**
     * Creates a {@link LauncherUpdater} instance.
     *
     * @param install  the install
     * @param client   reads the releases
     * @param platform the platform whose packages the install takes, or {@code null} when no package is built for the install's platform
     */
    public LauncherUpdater(@NotNull InstallLayout install, @NotNull ReleaseClient client, @Nullable PackagePlatform platform) {
        this.install  = install;
        this.folder   = UpdateFolder.of(install);
        this.client   = client;
        this.platform = platform;
    }

    /**
     * Reads the latest release of the update repository and returns it when it is newer than the running launcher. A missing running version
     * counts as older than every release.
     *
     * @param repository the update repository
     * @param running    the running launcher's version, or {@code null} when it is not a semantic version
     * @return the newer release, or {@code null} when the running launcher is up to date
     * @throws ReleaseException if the release cannot be read, its tag is not a version, it lacks the package of this platform
     *                          ({@link ReleaseProblem#PLATFORM_MISSING}) or the package's checksum file
     */
    public @Nullable LauncherRelease findUpdate(@NotNull ReleaseRepository repository, @Nullable ReleaseVersion running) throws ReleaseException {
        ReleaseCheck check = ReleaseCheck.read(this.client, repository, running);
        Release release = check.getRelease();
        ReleaseVersion latest = check.getLatestVersion();

        if (!check.isNewer()) {
            return null;
        }

        PackagePlatform current = this.platform;

        if (current == null) {
            throw new ReleaseException(ReleaseProblem.PLATFORM_MISSING, Exceptions.format("No package of the release {0} runs on this system",
                                                                                          release.getTag()));
        }

        String packageName = PACKAGE_PREFIX + latest + PLATFORM_SEPARATOR + current.getId() + PACKAGE_EXTENSION;
        ReleaseAsset packageFile = release.findAsset(packageName);
        ReleaseAsset checksumFile = release.findAsset(packageName + CHECKSUM_EXTENSION);

        if (packageFile == null) {
            throw new ReleaseException(ReleaseProblem.PLATFORM_MISSING, Exceptions.format("The release {0} has no package for {1} ({2})",
                                                                                          release.getTag(), current.getId(), packageName));
        }

        if (checksumFile == null) {
            throw new ReleaseException(ReleaseProblem.ASSET_MISSING, Exceptions.format("The release {0} has no checksum file of {1}",
                                                                                       release.getTag(), packageName));
        }

        return new LauncherRelease(latest, release.getPublishedAt(), release.getNotes(), packageFile, checksumFile);
    }

    /**
     * Downloads the user package of a release into the update folder, kept only when its checksum matches.
     *
     * @param release  the release
     * @param progress receives the share downloaded, from {@code 0} to {@code 1}, on the downloading thread
     * @return the downloaded package
     * @throws IOException if the package cannot be downloaded, does not match its checksum or cannot be written
     */
    public @NotNull Path download(@NotNull LauncherRelease release, @NotNull DoubleConsumer progress) throws IOException {
        Path target = this.folder.getDownloadFile(release.getPackageFile().getName());

        this.client.download(release.getPackageFile(), release.getChecksumFile(), target, MAXIMUM_PACKAGE_SIZE, progress);

        return target;
    }

    /**
     * Unpacks a downloaded user package into the update folder, replacing a package unpacked before, and checks that it holds every item an
     * update replaces and names the platform of this updater. On systems with POSIX permissions the programs of the package are made
     * executable.
     *
     * @param release     the release the package belongs to
     * @param packageFile the downloaded package
     * @return the unpacked package's folder {@code SC4J-<version>}
     * @throws InvalidPackageException if the package is broken, not an SC4J package of the release or a package of another platform
     * @throws IOException             if the package cannot be written
     */
    public @NotNull Path unpack(@NotNull LauncherRelease release, @NotNull Path packageFile) throws IOException {
        Path packageDirectory = this.folder.getPackageDirectory();
        Path packageRoot = packageDirectory.resolve(PACKAGE_PREFIX + release.getVersion());

        FileTreeUtils.delete(packageDirectory);

        String packagePlatform;

        try {
            FileTreeUtils.unzip(packageFile, packageDirectory, MAXIMUM_UNPACKED_SIZE);
            PackageSwap.checkPackage(packageRoot);
            packagePlatform = readPackagePlatform(packageRoot);
        } catch (IOException exception) {
            throw new InvalidPackageException(Exceptions.format("{0} is not an SC4J package: {1}", packageFile.getFileName(), exception.getMessage()),
                                              exception);
        }

        PackagePlatform current = this.platform;

        if (current == null || !current.getId().equals(packagePlatform)) {
            throw new InvalidPackageException(Exceptions.format("{0} is a package for {1}, not for this system ({2})", packageFile.getFileName(),
                                                                packagePlatform.isEmpty() ? "no named platform" : packagePlatform,
                                                                current == null ? "no package platform" : current.getId()));
        }

        markExecutable(packageRoot);

        return packageRoot;
    }

    /**
     * Starts the updater of an unpacked package: the package's Java runtime runs {@link UpdaterMain} from the package's launcher, which waits
     * for the launcher's process to end, replaces the programs of the install and starts the launcher again. On Windows the runtime's
     * {@code javaw.exe} runs it, so that it holds no console window. It runs in the update folder, so that its working folder is no folder it
     * replaces, and its output goes to the update folder's log. The launcher must exit after this call.
     *
     * @param packageRoot       the unpacked package ({@link #unpack})
     * @param launcherProcessId the process id of the running launcher
     * @param windows           whether the install runs on Windows
     * @return the started updater process
     * @throws IOException if the updater cannot be started
     */
    public @NotNull Process startUpdater(@NotNull Path packageRoot, long launcherProcessId, boolean windows) throws IOException {
        Path binaries = packageRoot.resolve(PackageSwap.JAVA_FOLDER).resolve(BINARY_FOLDER);
        Path java = binaries.resolve(windows ? WINDOWS_JAVA : UNIX_JAVA);
        Path libraries = packageRoot.resolve(InstallLayout.LAUNCHER_FOLDER).resolve(LIBRARY_FOLDER);
        Path temporary = this.folder.getTemporaryDirectory();

        if (windows && Files.isRegularFile(binaries.resolve(WINDOWS_WINDOWED_JAVA))) {
            java = binaries.resolve(WINDOWS_WINDOWED_JAVA);
        }

        List<String> command = List.of(java.toString(), NO_PERFORMANCE_DATA_OPTION, TEMPORARY_DIRECTORY_OPTION + temporary,
                                       CLASS_PATH_OPTION, libraries + File.separator + CLASS_PATH_WILDCARD, UpdaterMain.class.getName(),
                                       UpdaterMain.INSTALL_OPTION, this.install.getRoot().toString(),
                                       UpdaterMain.PACKAGE_OPTION, packageRoot.toAbsolutePath().toString(),
                                       UpdaterMain.WAIT_OPTION, Long.toString(launcherProcessId));

        Files.createDirectories(temporary);

        return new ProcessBuilder(command).directory(this.folder.getRoot().toFile()).redirectErrorStream(true)
                                          .redirectOutput(ProcessBuilder.Redirect.appendTo(this.folder.getLogFile().toFile())).start();
    }

    /**
     * Reads why the last update failed and removes the note, so that it is shown once.
     *
     * @return the reason the updater wrote, or {@code null} when the last update did not fail
     */
    public @Nullable String takeFailure() {
        Path failure = this.folder.getFailureFile();

        try {
            String text = Files.readString(failure, StandardCharsets.UTF_8).strip();

            Files.deleteIfExists(failure);

            return text.isEmpty() ? null : text;
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    /**
     * Removes what downloads and updates left in the update folder, except the updater's log. A file that is still in use stays until a
     * later start.
     */
    public void cleanUp() {
        if (!Files.isDirectory(this.folder.getRoot())) {
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.folder.getRoot())) {
            for (Path entry : entries) {
                if (entry.equals(this.folder.getLogFile())) {
                    continue;
                }

                try {
                    FileTreeUtils.delete(entry);
                } catch (IOException exception) {
                    // A file still in use, such as the updater's Java runtime while it ends, is removed at a later start.
                }
            }
        } catch (IOException exception) {
            // The folder cannot be listed now; a later start tries again.
        }
    }
}
