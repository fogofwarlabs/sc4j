package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests the layout of the selected mod's banner: the icon area and the banner have the same size for a mod with an icon (of any aspect
 * ratio) and a mod without one, and an icon is scaled to fit inside the icon area. Run is disabled while a run is in progress and enabled
 * after it. The update notice centers its text on its Update button.
 */
final class ModDetailViewTest {
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "%s",
                                                      "name": {"en": "Example %s"},
                                                      "version": "1.0.0",
                                                      "engine": "0.1",
                                                      "entry": "org.example.ExampleMod"%s
                                                    }
                                                    """;
    private static final @NotNull String ICON_IMAGES = ",\n  \"images\": {\"icon\": \"meta/icon.png\"}";
    private static final @NotNull String ICON_ENTRY = "meta/icon.png";
    private static final @NotNull String WITH_ICON_ID = "with-icon";
    private static final @NotNull String WITHOUT_ICON_ID = "without-icon";
    private static final @NotNull String PNG_FORMAT = "png";
    private static final @NotNull String BANNER_SELECTOR = ".banner";
    private static final @NotNull String THUMBNAIL_SELECTOR = ".thumbnail";
    private static final @NotNull String IMAGE_SELECTOR = "ImageView";
    private static final @NotNull Bounds NO_IMAGE = new BoundingBox(0, 0, -1, -1);
    private static final int ICON_WIDTH = 200;
    private static final int ICON_HEIGHT = 120;
    private static final int ICON_COLOR = 0xFF38D6FF;
    private static final double SCENE_WIDTH = 1100;
    private static final double SCENE_HEIGHT = 700;
    private static final long FX_TIMEOUT_SECONDS = 30;
    private static final double ASPECT_RATIO_TOLERANCE = 0.05;
    private static final @NotNull String NOTICE_SELECTOR = ".notice";
    private static final @NotNull String UPDATE_RELEASE = "v1.1.0";
    private static final double CENTER_TOLERANCE = 1;
    private static final @NotNull Runnable NO_CHANGE = () -> {
    };

    /**
     * Receives no action; the test only lays the view out.
     */
    private static final class NoActions implements ModDetailView.Actions {
        @Override
        public void onRun(@NotNull ModEntry entry, @NotNull SettingsForm form) {
        }

        @Override
        public void onCopy(@NotNull String text) {
        }

        @Override
        public void onOpenWebsite(@NotNull String address) {
        }

        @Override
        public void onUpdate(@NotNull ModEntry entry) {
        }

        @Override
        public void onChangeVersion(@NotNull ModEntry entry) {
        }

        @Override
        public void onRemove(@NotNull ModEntry entry) {
        }
    }

    /**
     * The sizes of one laid-out detail view.
     */
    private static final class BannerLayout {
        private final @NotNull Bounds banner;
        private final @NotNull Bounds icon;
        private final @NotNull Bounds image;

        /**
         * Creates a {@link BannerLayout} instance.
         *
         * @param banner the banner's layout bounds
         * @param icon   the icon area's layout bounds
         * @param image  the bounds of the icon image in the icon area, empty when there is none
         */
        private BannerLayout(@NotNull Bounds banner, @NotNull Bounds icon, @NotNull Bounds image) {
            this.banner = banner;
            this.icon   = icon;
            this.image  = image;
        }
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    @Test
    void banner_and_icon_area_have_the_same_size_with_and_without_an_icon(@TempDir @NotNull Path directory) throws Exception {
        InstallLayout install = InstallLayout.of(directory);

        writeMod(install, WITH_ICON_ID + ".jar", MANIFEST.formatted(WITH_ICON_ID, WITH_ICON_ID, ICON_IMAGES), createIcon());
        writeMod(install, WITHOUT_ICON_ID + ".jar", MANIFEST.formatted(WITHOUT_ICON_ID, WITHOUT_ICON_ID, ""), null);

        ModCatalog catalog = ModCatalog.scan(install, InstallationRecords.NONE);
        BannerLayout withIcon = layOut(requireEntry(catalog, WITH_ICON_ID + ".jar"));
        BannerLayout withoutIcon = layOut(requireEntry(catalog, WITHOUT_ICON_ID + ".jar"));

        Assertions.assertEquals(withoutIcon.banner.getHeight(), withIcon.banner.getHeight());
        Assertions.assertEquals(withoutIcon.icon.getWidth(), withIcon.icon.getWidth());
        Assertions.assertEquals(withoutIcon.icon.getHeight(), withIcon.icon.getHeight());
        Assertions.assertTrue(withIcon.icon.getWidth() > 0);
        Assertions.assertTrue(withoutIcon.image.isEmpty(), "a mod without an icon shows the placeholder");

        // The wide icon fills the icon area's width inside the border and keeps its aspect ratio.
        Assertions.assertFalse(withIcon.image.isEmpty());
        Assertions.assertTrue(withIcon.image.getMinX() >= 0 && withIcon.image.getMaxX() <= withIcon.icon.getWidth(), withIcon.image::toString);
        Assertions.assertTrue(withIcon.image.getMinY() >= 0 && withIcon.image.getMaxY() <= withIcon.icon.getHeight(), withIcon.image::toString);
        Assertions.assertEquals((double) ICON_WIDTH / ICON_HEIGHT, withIcon.image.getWidth() / withIcon.image.getHeight(), ASPECT_RATIO_TOLERANCE);
    }

    @Test
    void run_stays_disabled_while_a_run_is_in_progress(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);

        writeMod(install, WITHOUT_ICON_ID + ".jar", MANIFEST.formatted(WITHOUT_ICON_ID, WITHOUT_ICON_ID, ""), null);

        ModEntry entry = requireEntry(ModCatalog.scan(install, InstallationRecords.NONE), WITHOUT_ICON_ID + ".jar");
        ModManifest manifest = entry.getManifest();

        Assertions.assertNotNull(manifest);

        LauncherFxScenario.run(() -> {
            SimpleBooleanProperty running = new SimpleBooleanProperty(true);
            ModDetailView view = new ModDetailView(entry, null, SettingsForm.create(manifest.getSettings(), Map.of()), texts, new ModArt(1),
                                                   running, NO_CHANGE, new NoActions());
            Region root = view.getRoot();
            Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);

            ThemeUtils.apply(scene, UiLanguage.ENGLISH);
            root.applyCss();
            root.layout();

            Button run = LauncherFxScenario.findButton(root, texts.get("detail.run"));

            Assertions.assertTrue(run.isDisabled());

            running.set(false);

            Assertions.assertFalse(run.isDisabled());

            return null;
        });
    }

    @Test
    void update_notice_centers_its_text_on_the_update_button(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);

        writeMod(install, WITHOUT_ICON_ID + ".jar", MANIFEST.formatted(WITHOUT_ICON_ID, WITHOUT_ICON_ID, ""), null);

        ModEntry entry = requireEntry(ModCatalog.scan(install, InstallationRecords.NONE), WITHOUT_ICON_ID + ".jar");
        ModManifest manifest = entry.getManifest();

        Assertions.assertNotNull(manifest);

        LauncherFxScenario.run(() -> {
            ModDetailView view = new ModDetailView(entry, UPDATE_RELEASE, SettingsForm.create(manifest.getSettings(), Map.of()), texts,
                                                   new ModArt(1), new SimpleBooleanProperty(false), NO_CHANGE, new NoActions());
            Region root = view.getRoot();
            Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);

            ThemeUtils.apply(scene, UiLanguage.ENGLISH);
            root.applyCss();
            root.layout();

            Node notice = root.lookup(NOTICE_SELECTOR);
            Button update = LauncherFxScenario.findButton(notice, texts.get("mods.update.start"));
            Label text = (Label) notice.lookup(".label");
            Bounds textBounds = text.getBoundsInParent();
            Bounds buttonBounds = update.getBoundsInParent();

            Assertions.assertEquals(buttonBounds.getCenterY(), textBounds.getCenterY(), CENTER_TOLERANCE, "the text is centered on the button");

            return null;
        });
    }

    private static @NotNull BannerLayout layOut(@NotNull ModEntry entry) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<BannerLayout> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                ModManifest manifest = entry.getManifest();

                Assertions.assertNotNull(manifest);

                SettingsForm form = SettingsForm.create(manifest.getSettings(), Map.of());
                ModDetailView view = new ModDetailView(entry, null, form, LauncherTexts.of(UiLanguage.ENGLISH), new ModArt(1),
                                                       new SimpleBooleanProperty(false), NO_CHANGE, new NoActions());
                Region root = view.getRoot();
                Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);

                ThemeUtils.apply(scene, UiLanguage.ENGLISH);
                root.applyCss();
                root.layout();

                Node banner = root.lookup(BANNER_SELECTOR);
                Node icon = banner.lookup(THUMBNAIL_SELECTOR);
                Node image = icon.lookup(IMAGE_SELECTOR);

                result.complete(new BannerLayout(banner.getLayoutBounds(), icon.getLayoutBounds(),
                                                 image instanceof ImageView imageView ? imageView.getBoundsInParent() : NO_IMAGE));
            } catch (RuntimeException | Error exception) {
                result.completeExceptionally(exception);
            }
        });

        return result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static @NotNull ModEntry requireEntry(@NotNull ModCatalog catalog, @NotNull String jarName) {
        ModEntry entry = catalog.findByJarName(jarName);

        Assertions.assertNotNull(entry, jarName);
        Assertions.assertEquals(ModStatus.READY, entry.getStatus(), () -> jarName + ": " + entry.getIssues());

        return entry;
    }

    private static byte @NotNull [] createIcon() throws IOException {
        BufferedImage image = new BufferedImage(ICON_WIDTH, ICON_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (int y = 0; y < ICON_HEIGHT; y++) {
            for (int x = 0; x < ICON_WIDTH; x++) {
                image.setRGB(x, y, ICON_COLOR);
            }
        }

        ImageIO.write(image, PNG_FORMAT, output);

        return output.toByteArray();
    }

    private static void writeMod(@NotNull InstallLayout install, @NotNull String jarName,
                                 @NotNull String manifest, byte @Nullable [] icon) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        entries.put(TestJarUtils.MANIFEST_ENTRY, manifest.getBytes(StandardCharsets.UTF_8));

        if (icon != null) {
            entries.put(ICON_ENTRY, icon);
        }

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(jarName), entries);
    }
}
