package org.fogofwarlabs.sc4j.manifest.settings;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads where the computer records installed programs, for {@link DirectorySetting#detect}: the install locations of uninstall
 * entries and the program folders. It only reads; it never searches a drive.
 */
public interface InstallationRecords {
    /**
     * The records of a computer that records nothing.
     */
    @NotNull InstallationRecords NONE = new InstallationRecords() {
        /**
         * Returns the install locations of the uninstall entries with a name.
         *
         * @param entryName the entry name, such as {@code Example Game}
         * @return no location
         */
        @Override
        public @NotNull List<@NotNull Path> findUninstallLocations(@NotNull String entryName) {
            return List.of();
        }

        /**
         * @return no folder
         */
        @Override
        public @NotNull List<@NotNull Path> getProgramDirectories() {
            return List.of();
        }
    };

    /**
     * Returns the records of the running computer: the Windows uninstall entries and program folders on Windows; the application folders
     * (macOS) and the program folders of the Wine, Proton and similar prefixes at their default places elsewhere.
     *
     * @return the records of the running computer
     */
    static @NotNull InstallationRecords system() {
        return WindowsInstallationRecords.isRunning() ? new WindowsInstallationRecords() : UnixInstallationRecords.ofSystem();
    }

    /**
     * Returns the install locations of the uninstall entries with a name.
     *
     * @param entryName the entry name, such as {@code Example Game}
     * @return the recorded install folders, in the order they are looked up; empty when no entry has the name
     */
    @NotNull List<@NotNull Path> findUninstallLocations(@NotNull String entryName);

    /**
     * @return the folders programs are installed into by default, in the order they are looked at
     */
    @NotNull List<@NotNull Path> getProgramDirectories();
}
