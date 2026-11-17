package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests that the chamfered controls of the launcher theme keep the stylesheet's cut size in pixels at every width, height and display scale,
 * with their 1 pixel edge as wide along the cut as along the sides, at 100% and 200% in English and Korean.
 */
final class ChamferUtilsTest {
    private static final double PERCENT_SCALE = 100;
    private static final double NARROW_WIDTH = 80;
    private static final double WIDE_WIDTH = 400;
    private static final double BUTTON_HEIGHT = 34;
    private static final double TALL_BUTTON_HEIGHT = 60;
    private static final double BADGE_HEIGHT = 18;
    private static final double TALL_BADGE_HEIGHT = 40;
    private static final double BUTTON_CUT = 6;
    private static final double BADGE_CUT = 4;

    /**
     * The side of the square at a corner whose rows are checked, in logical units; larger than every cut.
     */
    private static final double CORNER_BOX = 12;

    /**
     * The opacity from which a pixel counts as wholly covered by the control.
     */
    private static final double OPAQUE = 0.99;
    private static final double EDGE_WIDTH = 1;

    /**
     * Half the diagonal of a screen pixel: how far a pixel reaches across a 45-degree line from its centre.
     */
    private static final double PIXEL_REACH = Math.sqrt(2) / 2;
    private static final double COLOUR_TOLERANCE = 0.1;

    /**
     * The distance kept from the right side when a row's fill colour is read, in logical units: inside the edge and the cut-free corner.
     */
    private static final double FILL_SAMPLE_INSET = 3;

    /**
     * The distance from the cut at which the top edge's colour is read, in logical units.
     */
    private static final double EDGE_SAMPLE_GAP = 8;
    private static final double SAMPLE_PADDING = 24;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull UiLanguage selectLanguage(@NotNull String languageCode) {
        return languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
    }

    private static @NotNull WritableImage render(@NotNull Region region, double width, double height, double scale) {
        SnapshotParameters parameters = new SnapshotParameters();

        region.resize(width, height);
        region.layout();
        parameters.setFill(Color.TRANSPARENT);
        parameters.setTransform(Transform.scale(scale, scale));

        return region.snapshot(parameters, null);
    }

    /**
     * Counts the pixels of a row from one end up to the first one that the control covers wholly.
     */
    private static int countOpenPixels(@NotNull PixelReader pixels, int row, int start, int step) {
        int count = 0;

        for (int x = start; pixels.getColor(x, row).getOpacity() < OPAQUE; x += step) {
            count++;
        }

        return count;
    }

    private static double getDistance(@NotNull Color first, @NotNull Color second) {
        return Math.max(Math.abs(first.getRed() - second.getRed()),
                        Math.max(Math.abs(first.getGreen() - second.getGreen()), Math.abs(first.getBlue() - second.getBlue())));
    }

    /**
     * Checks the cuts of a rendered control. A 45-degree cut of {@code c} screen pixels leaves {@code c - y} pixels of row {@code y} (counted
     * from the cut's side) not wholly covered, so both cuts have the stylesheet's size and angle when every row near them does. Then the screen
     * pixel on the middle of the top-left cut that lies wholly inside the edge has the edge's colour (when the scale gives the edge a whole pixel
     * along the diagonal), and the first one that lies wholly past the edge has the fill colour of its row.
     */
    private static void assertChamfer(@NotNull WritableImage image, double cut, double scale, @NotNull String description) {
        PixelReader pixels = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int side = (int) Math.round(CORNER_BOX * scale);
        double deviceCut = cut * scale;
        double deviceEdge = EDGE_WIDTH * scale;
        Color edge = pixels.getColor((int) Math.round((cut + EDGE_SAMPLE_GAP) * scale), 0);

        for (int row = 0; row < side; row++) {
            int expected = Math.max(0, (int) Math.round(deviceCut) - row);
            int topRow = row;
            int bottomRow = height - 1 - row;

            Assertions.assertEquals(expected, countOpenPixels(pixels, topRow, 0, 1), () -> description + ": top-left cut, row " + topRow);
            Assertions.assertEquals(expected, countOpenPixels(pixels, bottomRow, width - 1, -1),
                                    () -> description + ": bottom-right cut, row " + bottomRow);
        }

        // Pixel (i, i) has its centre (2 i + 1 - c) / sqrt(2) device pixels inside the cut line x + y = c and reaches PIXEL_REACH either way.
        for (int index = 0; index < side; index++) {
            double distance = (2 * index + 1 - deviceCut) / Math.sqrt(2);
            Color color = pixels.getColor(index, index);

            if (distance - PIXEL_REACH >= 0 && distance + PIXEL_REACH <= deviceEdge) {
                Assertions.assertTrue(getDistance(edge, color) <= COLOUR_TOLERANCE,
                                      () -> description + ": edge colour " + edge + " inside the cut's edge, found " + color);
            }

            if (distance - PIXEL_REACH > deviceEdge) {
                Color fill = pixels.getColor(width - 1 - (int) Math.round(FILL_SAMPLE_INSET * scale), index);

                Assertions.assertTrue(getDistance(fill, color) <= COLOUR_TOLERANCE,
                                      () -> description + ": fill colour " + fill + " right past the cut's edge, found " + color);

                return;
            }
        }

        Assertions.fail(description + ": no pixel past the cut's edge");
    }

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void chamfered_controls_keep_the_cut_size_at_every_width_and_height(double scale, @NotNull String languageCode) throws Exception {
        UiLanguage language = selectLanguage(languageCode);
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();

        LauncherFxScenario.run(() -> {
            Button button = ComponentUtils.createButton(texts.get("session.save"), ButtonRole.SECONDARY);
            Label badge = ComponentUtils.createBadge(texts.get("run.state.running"), Tone.SUCCESS);
            Group group = new Group(button, badge);
            Scene scene = new Scene(group);

            group.setAutoSizeChildren(false);
            ThemeUtils.apply(scene, language);
            group.applyCss();

            for (double width : new double[]{NARROW_WIDTH, WIDE_WIDTH}) {
                for (double height : new double[]{BUTTON_HEIGHT, TALL_BUTTON_HEIGHT}) {
                    assertChamfer(render(button, width, height, scale), BUTTON_CUT, scale, "button " + width + "x" + height);
                }

                for (double height : new double[]{BADGE_HEIGHT, TALL_BADGE_HEIGHT}) {
                    assertChamfer(render(badge, width, height, scale), BADGE_CUT, scale, "badge " + width + "x" + height);
                }
            }

            return null;
        });

        LauncherFxScenario.run(() -> {
            HBox narrow = ComponentUtils.createRow(Spacing.S,
                                                   ComponentUtils.createButton(texts.get("session.save"), ButtonRole.PRIMARY),
                                                   ComponentUtils.createButton(texts.get("session.cancel"), ButtonRole.SECONDARY),
                                                   ComponentUtils.createSmallButton(texts.get("app.about"), ButtonRole.DANGER),
                                                   ComponentUtils.createBadge(texts.get("run.state.running"), Tone.ACCENT),
                                                   ComponentUtils.createBadge(texts.get("run.state.ended"), Tone.SUCCESS));
            Button wide = ComponentUtils.createButton(texts.get("session.save"), ButtonRole.SECONDARY);
            TabPane tabs = new TabPane(new Tab(texts.get("run.tab.log")), new Tab(texts.get("run.tab.command")), new Tab(texts.get("run.tab.settings")));
            VBox sample = ComponentUtils.createColumn(Spacing.M, narrow, wide, tabs);
            Scene scene = new Scene(sample, WIDE_WIDTH + WIDE_WIDTH, WIDE_WIDTH / 2);

            wide.setPrefWidth(WIDE_WIDTH);
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            // The padding holds the glow of the primary button, which reaches past the button.
            sample.setPadding(new Insets(SAMPLE_PADDING));
            sample.getStyleClass().add("app-backdrop");
            ThemeUtils.apply(scene, language);
            sample.applyCss();
            sample.layout();
            LauncherFxScenario.saveSample(sample, scale, "chamfer-" + suffix);

            return null;
        });
    }
}
