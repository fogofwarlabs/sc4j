package org.fogofwarlabs.sc4j.launcher;

import javafx.application.Platform;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Opens folders in the system file manager through the Java desktop API, on a thread of its own so that a slow file manager start does not
 * hold the JavaFX thread. A missing folder is created first. Where the desktop API cannot open files (no desktop, or no open action) or the
 * file manager does not start, the folder's file address goes to the document opener on the JavaFX thread instead.
 */
public final class FolderOpener {
    private static final @NotNull Logger LOGGER = Logger.of(FolderOpener.class);
    private static final @NotNull String THREAD_NAME = "sc4j-launcher-open-folder";

    private final @NotNull Consumer<@NotNull String> showDocument;

    /**
     * Creates a {@link FolderOpener} instance.
     *
     * @param showDocument opens a file address with the system's application, called on the JavaFX thread when the desktop API cannot open the folder
     */
    public FolderOpener(@NotNull Consumer<@NotNull String> showDocument) {
        this.showDocument = showDocument;
    }

    /**
     * Opens a folder in the system file manager, creating it first if it is missing; returns before the file manager starts.
     *
     * @param folder the folder
     */
    public void open(@NotNull Path folder) {
        Thread.ofPlatform().name(THREAD_NAME).daemon().start(() -> this.openNow(folder));
    }

    private void openNow(@NotNull Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            // The folder is opened anyway; the file manager reports a missing folder itself.
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(folder.toFile());

                return;
            } catch (IOException | IllegalArgumentException exception) {
                LOGGER.warning("The file manager cannot open {0}: {1}", folder, exception.getMessage());
            }
        }

        String address = folder.toUri().toString();

        Platform.runLater(() -> this.showDocument.accept(address));
    }
}
