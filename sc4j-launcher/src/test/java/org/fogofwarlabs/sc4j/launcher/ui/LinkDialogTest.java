package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the dialog that asks before a link opens: it shows the whole address, Cancel opens nothing, Open hands the address to the opener; an
 * address of another scheme is refused.
 */
final class LinkDialogTest {
    private static final @NotNull String ADDRESS = "https://example.com/maps/fog-of-war/very/long/path/that/wraps?id=7&name=lost-temple-remake-final";
    private static final @NotNull String REFUSED_ADDRESS = "file:///C:/Windows/notepad.exe";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull Path directory) {
        LauncherShell shell = new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> {
        });

        ThemeUtils.apply(new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT), texts.getLanguage());

        return shell;
    }

    private static void layOut(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
    }

    private static boolean showsAddress(@NotNull Node dialog) {
        return dialog.lookupAll(".text-mono").stream().anyMatch(node -> node instanceof Text text && text.getText().equals(ADDRESS) &&
                                                                        LauncherFxScenario.isShown(text));
    }

    private static boolean showsLabel(@NotNull Node dialog, @NotNull String text) {
        return dialog.lookupAll(".label").stream().anyMatch(node -> node instanceof Label label && label.getText().equals(text) &&
                                                                    LauncherFxScenario.isShown(label));
    }

    @Test
    void open_should_hand_the_address_to_the_opener_and_cancel_should_open_nothing(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        List<String> opened = new ArrayList<>();

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory);
            Node dialog;

            // Cancel closes the dialog and opens nothing.
            new LinkDialog(shell, texts, ADDRESS, opened::add).open();
            layOut(shell);
            dialog = shell.getRoot().lookup(DIALOG_SELECTOR);

            Assertions.assertNotNull(dialog);
            Assertions.assertTrue(showsLabel(dialog, texts.get("link.text")));
            Assertions.assertTrue(showsAddress(dialog), "the dialog shows the whole address");
            Assertions.assertTrue(dialog.lookup(".link-address").getLayoutBounds().getWidth() <= ModalDialog.Width.REGULAR.getWidth(),
                                  "the address wraps inside the dialog");
            LauncherFxScenario.findButton(shell.getRoot(), texts.get("link.cancel")).fire();

            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR));
            Assertions.assertTrue(opened.isEmpty(), "Cancel opens nothing");

            // Open closes the dialog and hands the address to the opener.
            new LinkDialog(shell, texts, ADDRESS, opened::add).open();
            layOut(shell);
            LauncherFxScenario.findButton(shell.getRoot(), texts.get("link.open")).fire();

            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR));
            Assertions.assertEquals(List.of(ADDRESS), opened);

            return null;
        });
    }

    @Test
    void constructor_should_refuse_an_address_other_than_http_or_https(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        List<String> opened = new ArrayList<>();

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory);

            Assertions.assertThrows(IllegalArgumentException.class, () -> new LinkDialog(shell, texts, REFUSED_ADDRESS, opened::add));
            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR));

            return null;
        });

        Assertions.assertTrue(opened.isEmpty());
    }
}
