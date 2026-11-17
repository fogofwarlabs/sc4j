package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs launcher views in the JavaFX toolkit of a test JVM without showing a window: starts the toolkit once, runs code on its thread, waits
 * for a state that work on other threads brings, finds buttons by their text, tells whether a node is shown and saves renderings at a display
 * scale as sample pictures in {@value #SAMPLE_FOLDER}.
 */
public final class LauncherFxScenario {
    /**
     * The folder of the sample pictures, relative to the module.
     */
    public static final @NotNull String SAMPLE_FOLDER = "build/launcher-frames";

    private static final @NotNull String PNG_FORMAT = "png";
    private static final long FX_TIMEOUT_SECONDS = 60;
    private static final long WAIT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 20L;

    /**
     * Starts the JavaFX toolkit unless it runs already in this JVM.
     */
    public static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException exception) {
            // The toolkit of this test JVM runs already.
        }
    }

    /**
     * Runs code on the JavaFX thread and waits for its result.
     *
     * @param code the code
     * @param <T>  the result type
     * @return the code's result
     * @throws Exception if the code failed or did not end in time
     */
    public static <T> T run(@NotNull Callable<T> code) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                result.complete(code.call());
            } catch (Exception | Error exception) {
                result.completeExceptionally(exception);
            }
        });

        return result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Waits until a condition, checked on the JavaFX thread, holds.
     *
     * @param condition   the condition
     * @param description what is awaited, for the failure message
     * @throws Exception if the condition does not hold in time
     */
    public static void await(@NotNull BooleanSupplier condition, @NotNull String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

        while (!run(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) {
                throw Exceptions.newAssertionError("Not reached in {0} ms: {1}", WAIT_MILLIS, description);
            }

            Thread.sleep(POLL_MILLIS);
        }
    }

    /**
     * Finds the shown button with a text under a node; call it on the JavaFX thread.
     *
     * @param root the node
     * @param text the button's text
     * @return the last such button in the scene graph order (the one of the top dialog)
     */
    public static @NotNull Button findButton(@NotNull Node root, @NotNull String text) {
        Button found = null;

        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText().equals(text) && button.isVisible()) {
                found = button;
            }
        }

        Assertions.assertNotNull(found, () -> "no button " + text);

        return found;
    }

    /**
     * Turns off the viewport cache for offscreen snapshots.
     *
     * @param root the node whose scroll viewports are drawn
     */
    public static void disableViewportCache(@NotNull Parent root) {
        for (Node viewport : root.lookupAll(".viewport")) {
            viewport.setCache(false);
        }
    }

    /**
     * Checks whether a node is shown: it and every node above it are visible.
     *
     * @param node the node
     * @return {@code true} if the node and all its parents are visible, {@code false} otherwise
     */
    public static boolean isShown(@NotNull Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Renders a laid-out node at a display scale and saves the picture; call it on the JavaFX thread.
     *
     * @param root  the node
     * @param scale the display scale, screen pixels per logical unit
     * @param name  the picture's name without the extension
     * @throws IOException if the picture cannot be written
     */
    public static void saveSample(@NotNull Node root, double scale, @NotNull String name) throws IOException {
        SnapshotParameters parameters = new SnapshotParameters();

        parameters.setTransform(Transform.scale(scale, scale));

        WritableImage snapshot = root.snapshot(parameters, null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        PixelReader pixels = snapshot.getPixelReader();
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Path sample = Path.of(SAMPLE_FOLDER, name + "." + PNG_FORMAT);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                picture.setRGB(x, y, pixels.getArgb(x, y));
            }
        }

        Files.createDirectories(sample.getParent());
        Assertions.assertTrue(ImageIO.write(picture, PNG_FORMAT, sample.toFile()));
    }


    private LauncherFxScenario() {
    }
}
