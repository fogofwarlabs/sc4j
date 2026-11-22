package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the outline that {@link ChamferUtils} gives a chamfered region: the polygon of the region's size with each corner cut by its radius,
 * the cut at most half the shorter side, the bottom side left open when the border has no bottom width, and a new outline for a new size.
 */
final class ChamferUtilsTest {
    private static final double CUT = 6;
    private static final double WIDTH = 80;
    private static final double HEIGHT = 34;
    private static final double WIDE_WIDTH = 400;
    private static final double TALL_HEIGHT = 60;
    private static final double LOW_WIDTH = 40;
    private static final double LOW_HEIGHT = 8;
    private static final @NotNull String CLOSE = "close";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    /**
     * Creates a region of a size whose background has corner radii and whose shape keeps its size and position, with its outline installed.
     *
     * @param width     the region width
     * @param height    the region height
     * @param radii     the corner radii, the cut at each corner
     * @param bottomGap {@code true} if the border has no bottom width, {@code false} if it has one on every side
     * @return the laid out region
     */
    private static @NotNull Region createRegion(double width, double height, @NotNull CornerRadii radii, boolean bottomGap) {
        Region region = new Region();
        BorderWidths widths = bottomGap ? new BorderWidths(1, 1, 0, 1) : new BorderWidths(1);

        region.setBackground(new Background(new BackgroundFill(Color.BLACK, radii, Insets.EMPTY)));
        region.setBorder(new Border(new BorderStroke(Color.WHITE, BorderStrokeStyle.SOLID, radii, widths)));
        region.setScaleShape(false);
        region.setCenterShape(false);
        region.resize(width, height);
        ChamferUtils.install(region);

        return region;
    }

    /**
     * Lists the elements of an outline: each point as {@code x y}, and {@link #CLOSE} for the closing segment.
     */
    private static @NotNull List<@NotNull String> listPoints(@Nullable Shape shape) {
        List<String> points = new ArrayList<>();

        Assertions.assertInstanceOf(Path.class, shape);

        for (PathElement element : ((Path) shape).getElements()) {
            switch (element) {
                case MoveTo move -> points.add((int) move.getX() + " " + (int) move.getY());
                case LineTo line -> points.add((int) line.getX() + " " + (int) line.getY());
                case ClosePath _ -> points.add(CLOSE);
                default -> Assertions.fail("The outline has a " + element.getClass().getSimpleName() + ".");
            }
        }

        return points;
    }

    @Test
    void install_should_cut_the_corners_by_their_radii_at_every_size() throws Exception {
        LauncherFxScenario.run(() -> {
            CornerRadii radii = new CornerRadii(CUT, 0, CUT, 0, false);

            Assertions.assertEquals(List.of("0 34", "0 6", "6 0", "80 0", "80 28", "74 34", CLOSE),
                                    listPoints(createRegion(WIDTH, HEIGHT, radii, false).getShape()));
            Assertions.assertEquals(List.of("0 60", "0 6", "6 0", "400 0", "400 54", "394 60", CLOSE),
                                    listPoints(createRegion(WIDE_WIDTH, TALL_HEIGHT, radii, false).getShape()), "the cuts keep their size");

            return null;
        });
    }

    @Test
    void install_should_cut_at_most_half_the_shorter_side() throws Exception {
        LauncherFxScenario.run(() -> {
            Region region = createRegion(LOW_WIDTH, LOW_HEIGHT, new CornerRadii(CUT), false);

            Assertions.assertEquals(List.of("4 8", "0 4", "0 4", "4 0", "36 0", "40 4", "40 4", "36 8", CLOSE), listPoints(region.getShape()));

            return null;
        });
    }

    @Test
    void install_should_leave_the_bottom_side_open_when_the_border_has_no_bottom_width() throws Exception {
        LauncherFxScenario.run(() -> {
            Region tab = createRegion(WIDTH, HEIGHT, new CornerRadii(CUT, CUT, 0, 0, false), true);

            Assertions.assertEquals(List.of("0 34", "0 6", "6 0", "74 0", "80 6", "80 34"), listPoints(tab.getShape()));

            return null;
        });
    }

    @Test
    void install_should_give_a_new_outline_when_the_size_changes() throws Exception {
        LauncherFxScenario.run(() -> {
            Region region = createRegion(WIDTH, HEIGHT, new CornerRadii(CUT, 0, CUT, 0, false), false);
            Shape first = region.getShape();

            region.resize(WIDTH, HEIGHT);

            Assertions.assertSame(first, region.getShape(), "the same size keeps the outline");

            region.resize(WIDE_WIDTH, TALL_HEIGHT);

            Assertions.assertNotSame(first, region.getShape());
            Assertions.assertEquals(List.of("0 60", "0 6", "6 0", "400 0", "400 54", "394 60", CLOSE), listPoints(region.getShape()));

            region.setBackground(new Background(new BackgroundFill(Color.BLACK, CornerRadii.EMPTY, Insets.EMPTY)));

            Assertions.assertNull(region.getShape(), "a region without cuts loses its outline");

            return null;
        });
    }
}
