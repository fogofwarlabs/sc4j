package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs launcher views in the JavaFX toolkit of a test JVM without showing a window: starts the toolkit once, finds the UI language of a
 * test parameter, runs code on its thread, waits for a state that work on other threads brings, finds buttons by their text, tells whether
 * a node is shown, and checks the height of panel headers, the room around the controls of panel headers and footers and the text of an
 * empty list.
 */
public final class LauncherFxScenario {
    private static final long FX_TIMEOUT_SECONDS = 60;
    private static final long WAIT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 20L;
    private static final double TOLERANCE = 1;
    private static final int LEADING_HEADER_CHILDREN = 2;
    private static final double BODY_PADDING = 16;

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
     * Finds the UI language of a test parameter.
     *
     * @param languageCode the language code, such as {@code ko}
     * @return the language
     */
    public static @NotNull UiLanguage requireLanguage(@NotNull String languageCode) {
        UiLanguage language = UiLanguage.fromCode(languageCode);

        Assertions.assertNotNull(language, languageCode);

        return language;
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
     * Checks every shown panel header: all have one height, and in a header with a shown control at its right end the room right of the last
     * control equals the room above and below it; call it on the JavaFX thread after a layout.
     *
     * @param root the node holding the panels
     * @return the number of headers with a control checked
     */
    public static int assertHeaderInsets(@NotNull Parent root) {
        int checked = 0;
        double headerHeight = -1;

        for (Node node : root.lookupAll(".panel-header")) {
            Parent header = (Parent) node;
            List<Node> children = header.getChildrenUnmodifiable();
            Node last = null;

            if (!isShown(header)) {
                continue;
            }

            if (headerHeight < 0) {
                headerHeight = header.getLayoutBounds().getHeight();
            }

            Assertions.assertEquals(headerHeight, header.getLayoutBounds().getHeight(), "every header has one height");

            for (int index = LEADING_HEADER_CHILDREN; index < children.size(); index++) {
                if (children.get(index).isManaged() && children.get(index).isVisible()) {
                    last = children.get(index);
                }
            }

            if (last == null) {
                continue;
            }

            Bounds bounds = getLayoutBoundsInParent(last);
            double height = header.getLayoutBounds().getHeight();
            double room = (height - bounds.getHeight()) / 2;

            Assertions.assertEquals(room, header.getLayoutBounds().getWidth() - bounds.getMaxX(), TOLERANCE, "room right of the header control");
            Assertions.assertEquals(room, bounds.getMinY(), TOLERANCE, "room above the header control");
            Assertions.assertEquals(room, height - bounds.getMaxY(), TOLERANCE, "room below the header control");
            checked++;
        }

        return checked;
    }

    /**
     * Checks every shown panel footer: the room left of its first laid out child and right of its last equals the room above and below them;
     * call it on the JavaFX thread after a layout.
     *
     * @param root the node holding the panels
     * @return the number of footers checked
     */
    public static int assertFooterInsets(@NotNull Parent root) {
        int checked = 0;

        for (Node node : root.lookupAll(".panel-footer")) {
            Parent footer = (Parent) node;
            Bounds content = null;

            if (!isShown(footer)) {
                continue;
            }

            for (Node child : footer.getChildrenUnmodifiable()) {
                if (child.isManaged()) {
                    content = content == null ? getLayoutBoundsInParent(child) : createUnion(content, getLayoutBoundsInParent(child));
                }
            }

            if (content == null) {
                continue;
            }

            Bounds bounds = footer.getLayoutBounds();
            double room = content.getMinY();

            Assertions.assertTrue(room > 0, "the footer has room around its controls");
            Assertions.assertEquals(room, bounds.getHeight() - content.getMaxY(), TOLERANCE, "room below the footer controls");
            Assertions.assertEquals(room, content.getMinX(), TOLERANCE, "room left of the footer controls");
            Assertions.assertEquals(room, bounds.getWidth() - content.getMaxX(), TOLERANCE, "room right of the footer controls");
            checked++;
        }

        return checked;
    }

    /**
     * Checks the text of an empty list: it wraps onto several lines, every line is centred in the list, and the text keeps the room of a panel
     * body from the list's sides; call it on the JavaFX thread after a layout.
     *
     * @param list the empty list
     */
    public static void assertPlaceholderCentred(@NotNull ListView<?> list) {
        Label placeholder = (Label) list.getPlaceholder();
        Text text = (Text) placeholder.lookup(".text");
        Bounds listBounds = list.localToScene(list.getLayoutBounds());
        Bounds labelBounds = placeholder.localToScene(placeholder.getLayoutBounds());
        double centre = (listBounds.getMinX() + listBounds.getMaxX()) / 2;

        Assertions.assertNotNull(text);
        Assertions.assertTrue(isShown(placeholder), "the empty list shows its text");
        Assertions.assertEquals(TextAlignment.CENTER, placeholder.getTextAlignment());
        Assertions.assertEquals(BODY_PADDING, labelBounds.getMinX() - listBounds.getMinX(), TOLERANCE, "room left of the text");
        Assertions.assertEquals(BODY_PADDING, listBounds.getMaxX() - labelBounds.getMaxX(), TOLERANCE, "room right of the text");

        List<Bounds> lines = listLines(text);

        Assertions.assertTrue(lines.size() > 1, "the text wraps");

        for (Bounds line : lines) {
            Bounds scene = text.localToScene(line);

            Assertions.assertTrue(scene.getMinX() >= labelBounds.getMinX() - TOLERANCE, "a line starts inside the text box");
            Assertions.assertTrue(scene.getMaxX() <= labelBounds.getMaxX() + TOLERANCE, "a line ends inside the text box");
            Assertions.assertEquals(centre, (scene.getMinX() + scene.getMaxX()) / 2, TOLERANCE, "a line is centred");
        }
    }

    private static @NotNull List<Bounds> listLines(@NotNull Text text) {
        List<Bounds> lines = new ArrayList<>();
        String content = text.getText();
        Bounds line = null;

        for (int index = 0; index < content.length(); index++) {
            if (Character.isWhitespace(content.charAt(index))) {
                continue;
            }

            Bounds leading = new javafx.scene.shape.Path(text.caretShape(index, true)).getLayoutBounds();
            Bounds trailing = new javafx.scene.shape.Path(text.caretShape(index, false)).getLayoutBounds();
            Bounds glyph = createUnion(leading, trailing);

            if (line != null && glyph.getCenterY() > line.getMaxY()) {
                lines.add(line);
                line = null;
            }

            line = line == null ? glyph : createUnion(line, glyph);
        }

        if (line != null) {
            lines.add(line);
        }

        return lines;
    }

    private static @NotNull Bounds getLayoutBoundsInParent(@NotNull Node node) {
        Bounds bounds = node.getLayoutBounds();

        return new BoundingBox(node.getLayoutX() + bounds.getMinX(), node.getLayoutY() + bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    private static @NotNull Bounds createUnion(@NotNull Bounds first, @NotNull Bounds second) {
        double minX = Math.min(first.getMinX(), second.getMinX());
        double minY = Math.min(first.getMinY(), second.getMinY());

        double maxX = Math.max(first.getMaxX(), second.getMaxX());
        double maxY = Math.max(first.getMaxY(), second.getMaxY());

        return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }


    private LauncherFxScenario() {
    }
}
