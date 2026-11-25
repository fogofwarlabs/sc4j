package org.fogofwarlabs.sc4j.launcher.update;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes the files of small user packages for the update tests: the programs an update replaces and the mod jars a package ships, written as
 * a folder or zipped.
 */
final class UserPackageScenario {
    /**
     * The platform of the packages and updaters of the tests, whatever platform runs them.
     */
    static final @NotNull PackagePlatform PLATFORM = PackagePlatform.LINUX_X64;

    /**
     * Gives the platform file of a package ({@link PackageSwap#PACKAGE_PLATFORM_FILE}).
     *
     * @param platform the platform the package names
     * @return the file by its path in the package
     */
    static @NotNull Map<@NotNull String, @NotNull String> createPlatformFile(@NotNull PackagePlatform platform) {
        return Map.of(PackageSwap.PACKAGE_PLATFORM_FILE, platform.getId() + "\n");
    }

    /**
     * Gives the files of a user package with each item a program of the update replaces.
     *
     * @param text the contents of every file
     * @return the files by their path in the package
     */
    static @NotNull Map<@NotNull String, @NotNull String> createProgramFiles(@NotNull String text) {
        Map<String, String> files = new LinkedHashMap<>();

        files.put("java/bin/java", text);
        files.put("launcher/lib/sc4j-launcher.jar", text);
        files.put("runtime/lib/sc4j-runtime.jar", text);
        files.put(PackageSwap.WINDOWS_PROGRAM, text);
        files.put(PackageSwap.WINDOWS_PROGRAM_FOLDER + "/SC4J.cfg", text);
        files.put(PackageSwap.WINDOWS_START_FILE, text);
        files.put(PackageSwap.UNIX_START_FILE, text);
        files.put("LICENSE", text);
        files.put("NOTICE", text);
        files.put("licenses/example.txt", text);

        return files;
    }

    /**
     * Gives the files of the mod jars a user package ships: the jars in {@code mods/} and their list {@link PackageSwap#PACKAGE_MODS_FILE}.
     *
     * @param text the contents of every jar
     * @param jars the jar file names
     * @return the files by their path in the package
     */
    static @NotNull Map<@NotNull String, @NotNull String> createModFiles(@NotNull String text, @NotNull String @NotNull ... jars) {
        Map<String, String> files = new LinkedHashMap<>();

        files.put(PackageSwap.PACKAGE_MODS_FILE, String.join("\n", jars) + "\n");

        for (String jar : jars) {
            files.put("mods/" + jar, text);
        }

        return files;
    }

    /**
     * Writes text files below a folder.
     *
     * @param root  the folder
     * @param files the contents by relative path
     * @throws IOException if a file cannot be written
     */
    static void writeFiles(@NotNull Path root, @NotNull Map<@NotNull String, @NotNull String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = root.resolve(file.getKey());

            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Zips text files the way the user package is zipped, under one top folder.
     *
     * @param folder the top folder, such as {@code SC4J-1.2.0}, or an empty string for none
     * @param files  the contents by relative path
     * @return the zip file's contents
     * @throws IOException if the zip cannot be written
     */
    static byte @NotNull [] createZip(@NotNull String folder, @NotNull Map<@NotNull String, @NotNull String> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(folder.isEmpty() ? file.getKey() : folder + "/" + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        return output.toByteArray();
    }

    private UserPackageScenario() {
    }
}
