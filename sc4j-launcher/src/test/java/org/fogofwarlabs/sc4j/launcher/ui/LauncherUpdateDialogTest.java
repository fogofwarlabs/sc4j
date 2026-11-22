package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.release.ReleaseNotesUtils;
import org.fogofwarlabs.sc4j.launcher.update.LauncherRelease;
import org.fogofwarlabs.sc4j.launcher.update.LauncherUpdater;
import org.fogofwarlabs.sc4j.launcher.update.PackageSwap;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests the Update dialog offscreen, against a local stand-in for GitHub: the offer shows the versions, the release date, the download size
 * and the release notes, and Skip this version closes it; Update shows the download's progress and Cancel returns to the offer; a ready
 * package offers Restart, which waits while a game runs; a failed download shows its reason with Retry.
 */
final class LauncherUpdateDialogTest {
    private static final @NotNull String REPOSITORY = "owner/sc4j";
    private static final @NotNull String TAG = "v1.2.0";
    private static final @NotNull String VERSION = "1.2.0";
    private static final @NotNull String PACKAGE = "SC4J-1.2.0";
    private static final @NotNull String PACKAGE_FILE = PACKAGE + ".zip";
    private static final @NotNull String RUNNING_VERSION = "1.1.0";
    private static final @NotNull Instant PUBLISHED_AT = Instant.parse("2026-11-16T12:00:00Z");
    private static final @NotNull String NOTES = """
                                                 ## What's Changed
                                                 * The launcher opens the **update** dialog by itself in [#42](https://github.com/owner/sc4j/pull/42)
                                                 * `Skip this version` is saved in the launcher settings
                                                 * Release notes show as plain text
                                                 
                                                 **Full Changelog**: https://github.com/owner/sc4j/compare/v1.1.0...v1.2.0
                                                 """;
    private static final int FILLER_SIZE = 3 * 1024 * 1024;
    private static final long FILLER_SEED = 42;
    private static final @NotNull String FILLER_FILE = "/filler.bin";
    private static final double SHOWN_SHARE = 0.45;
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    @Test
    void offer_should_show_the_release_and_skip_this_version_should_close_the_dialog(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AtomicInteger skipped = new AtomicInteger();

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(server.getApi()));
            byte[] packageContents = createPackage(FILLER_SIZE);
            LauncherRelease release;

            server.addCheckedRelease(REPOSITORY, TAG, PUBLISHED_AT, NOTES, Map.of(PACKAGE_FILE, packageContents));
            release = findUpdate(updater);

            LauncherFxScenario.run(() -> {
                LauncherShell shell = createShell(texts, install);
                DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(texts.getLocale());
                TextArea notes;

                new LauncherUpdateDialog(shell, texts, 1, updater, release, RUNNING_VERSION, new SimpleBooleanProperty(false), skipped::incrementAndGet,
                                         () -> Assertions.fail("the launcher closed")).open();
                layOut(shell);
                notes = (TextArea) shell.getRoot().lookup(".text-area");

                Assertions.assertTrue(hasLabel(shell.getRoot(), texts.get("update.offer")));
                Assertions.assertTrue(hasLabel(shell.getRoot(), RUNNING_VERSION + " -> " + VERSION));
                Assertions.assertTrue(hasLabel(shell.getRoot(), dateFormat.format(PUBLISHED_AT.atZone(ZoneId.systemDefault()))));
                Assertions.assertTrue(hasLabel(shell.getRoot(), texts.formatSize(packageContents.length)));
                Assertions.assertTrue(hasLabel(shell.getRoot(), texts.get("update.note")));
                Assertions.assertNotNull(notes);
                Assertions.assertEquals(ReleaseNotesUtils.toPlainText(NOTES), notes.getText());
                Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.later")));
                Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.start")));

                LauncherFxScenario.findButton(shell.getRoot(), texts.get("update.skip")).fire();

                Assertions.assertEquals(1, skipped.get());
                Assertions.assertFalse(hasButton(shell.getRoot(), texts.get("update.start")), "Skip this version closes the dialog");

                return null;
            });
        }
    }

    @Test
    void download_should_show_its_progress_and_cancel_should_return_to_the_offer(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(server.getApi()));
            byte[] packageContents = createPackage(FILLER_SIZE);
            String total = " / " + texts.formatSize(packageContents.length);
            CountDownLatch resume;
            LauncherRelease release;
            LauncherShell shell;

            server.addCheckedRelease(REPOSITORY, TAG, PUBLISHED_AT, NOTES, Map.of(PACKAGE_FILE, packageContents));
            release = findUpdate(updater);
            resume  = server.pauseFile(PACKAGE_FILE);

            try {
                shell = LauncherFxScenario.run(() -> {
                    LauncherShell created = createShell(texts, install);

                    new LauncherUpdateDialog(created, texts, 1, updater, release, RUNNING_VERSION, new SimpleBooleanProperty(false), () -> {
                    }, () -> Assertions.fail("the launcher closed")).open();
                    LauncherFxScenario.findButton(created.getRoot(), texts.get("update.start")).fire();

                    return created;
                });
                LauncherFxScenario.await(() -> findLabelEndingWith(shell.getRoot(), total) != null && getProgress(shell) >= SHOWN_SHARE,
                                         "the download is halfway");
                LauncherFxScenario.run(() -> {
                    Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.cancel")));
                    Assertions.assertFalse(hasButton(shell.getRoot(), texts.get("update.start")));
                    layOut(shell);

                    LauncherFxScenario.findButton(shell.getRoot(), texts.get("update.cancel")).fire();

                    Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.start")), "Cancel returns to the offer");
                    Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.skip")));
                    Assertions.assertNull(findLabelEndingWith(shell.getRoot(), total), "Cancel hides the progress");

                    return null;
                });
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void download_should_unpack_the_package_and_restart_should_wait_for_a_running_game(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AtomicInteger closed = new AtomicInteger();

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(server.getApi()));
            LauncherRelease release;
            LauncherShell shell;

            server.addCheckedRelease(REPOSITORY, TAG, Map.of(PACKAGE_FILE, createPackage(0)));
            release = findUpdate(updater);

            shell = LauncherFxScenario.run(() -> {
                LauncherShell created = createShell(texts, install);

                new LauncherUpdateDialog(created, texts, 1, updater, release, RUNNING_VERSION, new SimpleBooleanProperty(true), () -> {
                }, closed::incrementAndGet).open();
                Assertions.assertNull(created.getRoot().lookup(".text-area"), "a release without notes shows no notes");
                LauncherFxScenario.findButton(created.getRoot(), texts.get("update.start")).fire();

                return created;
            });
            LauncherFxScenario.await(() -> hasButton(shell.getRoot(), texts.get("update.restart")), "the package is ready");
            LauncherFxScenario.run(() -> {
                Assertions.assertTrue(hasLabel(shell.getRoot(), texts.format("update.ready", release.getVersion())));
                LauncherFxScenario.findButton(shell.getRoot(), texts.get("update.restart")).fire();
                Assertions.assertTrue(hasLabel(shell.getRoot(), texts.get("update.running")), "Restart waits for the game");
                layOut(shell);

                return null;
            });

            Assertions.assertEquals(0, closed.get());
        }
    }

    @Test
    void failed_download_should_show_the_reason_and_retry_should_download_again(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        String mismatch = texts.describeReleaseProblem(ReleaseProblem.CHECKSUM_MISMATCH);

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(server.getApi()));
            byte[] packageContents = createPackage(0);
            byte[] otherChecksum = ReleaseServerScenario.createChecksum(new byte[]{1}, PACKAGE_FILE);
            LauncherRelease release;
            LauncherShell shell;

            server.addRelease(REPOSITORY, TAG, PUBLISHED_AT, NOTES, Map.of(PACKAGE_FILE, packageContents,
                                                                           PACKAGE_FILE + ReleaseServerScenario.CHECKSUM_EXTENSION, otherChecksum));
            release = findUpdate(updater);

            shell = LauncherFxScenario.run(() -> {
                LauncherShell created = createShell(texts, install);

                new LauncherUpdateDialog(created, texts, 1, updater, release, RUNNING_VERSION, new SimpleBooleanProperty(false), () -> {
                }, () -> Assertions.fail("the launcher closed")).open();
                LauncherFxScenario.findButton(created.getRoot(), texts.get("update.start")).fire();

                return created;
            });
            LauncherFxScenario.await(() -> hasButton(shell.getRoot(), texts.get("update.retry")), "the download failed");
            LauncherFxScenario.run(() -> {
                Assertions.assertTrue(hasLabel(shell.getRoot(), mismatch));
                Assertions.assertTrue(hasButton(shell.getRoot(), texts.get("update.later")));
                layOut(shell);

                return null;
            });

            server.addCheckedRelease(REPOSITORY, TAG, PUBLISHED_AT, NOTES, Map.of(PACKAGE_FILE, packageContents));
            LauncherFxScenario.run(() -> {
                LauncherFxScenario.findButton(shell.getRoot(), texts.get("update.retry")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> hasButton(shell.getRoot(), texts.get("update.restart")), "Retry readies the package");
            LauncherFxScenario.run(() -> {
                Assertions.assertFalse(hasLabel(shell.getRoot(), mismatch), "Retry hides the reason");

                return null;
            });
        }
    }

    private static @NotNull LauncherRelease findUpdate(@NotNull LauncherUpdater updater) throws IOException {
        ReleaseRepository repository = ReleaseRepository.parse(REPOSITORY);
        LauncherRelease release;

        Assertions.assertNotNull(repository);
        release = updater.findUpdate(repository, ReleaseVersion.parse(RUNNING_VERSION));
        Assertions.assertNotNull(release);

        return release;
    }

    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull InstallLayout install) {
        LauncherShell shell = new LauncherShell(texts, install, 1, section -> {
        }, () -> {
        }, () -> {
        });

        ThemeUtils.apply(new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT), texts.getLanguage());

        return shell;
    }

    private static double getProgress(@NotNull LauncherShell shell) {
        return shell.getRoot().lookup(".progress-bar") instanceof ProgressBar bar ? bar.getProgress() : 0;
    }

    private static void layOut(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
    }

    private static byte @NotNull [] createPackage(int fillerSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String item : PackageSwap.ITEMS) {
                zip.putNextEntry(new ZipEntry(PACKAGE + "/" + item));
                zip.write(item.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            if (fillerSize > 0) {
                byte[] filler = new byte[fillerSize];

                new Random(FILLER_SEED).nextBytes(filler);
                zip.putNextEntry(new ZipEntry(PACKAGE + FILLER_FILE));
                zip.write(filler);
                zip.closeEntry();
            }
        }

        return output.toByteArray();
    }

    private static boolean hasButton(@NotNull Node root, @NotNull String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText().equals(text) && LauncherFxScenario.isShown(button)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasLabel(@NotNull Node root, @NotNull String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getText().equals(text) && LauncherFxScenario.isShown(label)) {
                return true;
            }
        }

        return false;
    }

    private static @Nullable Label findLabelEndingWith(@NotNull Node root, @NotNull String suffix) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getText().endsWith(suffix) && LauncherFxScenario.isShown(label)) {
                return label;
            }
        }

        return null;
    }
}
