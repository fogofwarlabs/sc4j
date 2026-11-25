package org.fogofwarlabs.sc4j.launcher.update;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Lists the platforms a user package is built for: an operating system and an architecture. A release carries one package per platform,
 * {@code SC4J-<version>-<id>.zip}, and a package names its platform in {@link PackageSwap#PACKAGE_PLATFORM_FILE}; the launcher updates only
 * from a package of the platform it runs on.
 */
public enum PackagePlatform {
    /**
     * Windows on x64.
     */
    WINDOWS_X64("windows-x64"),
    /**
     * Linux on x64.
     */
    LINUX_X64("linux-x64"),
    /**
     * Linux on ARM64.
     */
    LINUX_ARM64("linux-arm64"),
    /**
     * macOS on x64 (Intel).
     */
    MACOS_X64("macos-x64"),
    /**
     * macOS on ARM64 (Apple silicon).
     */
    MACOS_ARM64("macos-arm64");

    private static final @NotNull String OPERATING_SYSTEM_PROPERTY = "os.name";
    private static final @NotNull String ARCHITECTURE_PROPERTY = "os.arch";
    private static final @NotNull String WINDOWS_NAME_PREFIX = "windows";
    private static final @NotNull String LINUX_NAME_PREFIX = "linux";
    private static final @NotNull String MAC_NAME_PREFIX = "mac";
    private static final @NotNull List<@NotNull String> X64_ARCHITECTURES = List.of("amd64", "x86_64");
    private static final @NotNull List<@NotNull String> ARM64_ARCHITECTURES = List.of("aarch64", "arm64");

    /**
     * Finds the platform of an operating system and an architecture as the JVM names them ({@code os.name}, {@code os.arch}).
     *
     * @param operatingSystem the operating system's name, such as {@code Windows 11} or {@code Mac OS X}
     * @param architecture    the architecture's name, such as {@code amd64} or {@code aarch64}
     * @return the platform, or {@code null} when no package is built for it
     */
    public static @Nullable PackagePlatform find(@NotNull String operatingSystem, @NotNull String architecture) {
        String system = operatingSystem.toLowerCase(Locale.ROOT);
        String processor = architecture.toLowerCase(Locale.ROOT);
        boolean x64 = X64_ARCHITECTURES.contains(processor);
        boolean arm64 = ARM64_ARCHITECTURES.contains(processor);

        if (system.startsWith(WINDOWS_NAME_PREFIX)) {
            return x64 ? WINDOWS_X64 : null;
        }

        if (system.startsWith(LINUX_NAME_PREFIX)) {
            return x64 ? LINUX_X64 : arm64 ? LINUX_ARM64 : null;
        }

        if (system.startsWith(MAC_NAME_PREFIX)) {
            return x64 ? MACOS_X64 : arm64 ? MACOS_ARM64 : null;
        }

        return null;
    }

    /**
     * Finds the platform the launcher runs on.
     *
     * @return the platform, or {@code null} when no package is built for it
     */
    public static @Nullable PackagePlatform findCurrent() {
        return find(System.getProperty(OPERATING_SYSTEM_PROPERTY, ""), System.getProperty(ARCHITECTURE_PROPERTY, ""));
    }

    /**
     * Finds the platform with an id.
     *
     * @param id the id, such as {@code linux-x64}
     * @return the platform, or {@code null} when no platform has the id
     */
    public static @Nullable PackagePlatform findById(@NotNull String id) {
        for (PackagePlatform platform : values()) {
            if (platform.id.equals(id)) {
                return platform;
            }
        }

        return null;
    }


    private final @NotNull String id;

    PackagePlatform(@NotNull String id) {
        this.id = id;
    }

    /**
     * @return the id in the package's file name and platform file, such as {@code linux-x64}
     */
    public @NotNull String getId() {
        return this.id;
    }
}
