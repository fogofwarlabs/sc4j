package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommand;
import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommandRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Registers the engine camera slash commands of a client: {@code /camera} (show or centre the camera), {@code /zoom} (zoom and zoom
 * limits in percent), {@code /edgescroll} (edge scrolling on, off or its speed) and {@code /keyscroll} (arrow key scrolling on, off or its
 * speed) of a camera scroll with a continuous rate.
 */
public final class CameraCommandUtils {
    private static final float PERCENT = 100;
    private static final @NotNull String RESET = "reset";
    private static final @NotNull String LIMITS = "limits";
    private static final int POSITION_ARGUMENT_COUNT = 2;
    private static final int LIMITS_ARGUMENT_COUNT = 2;

    /**
     * Registers the camera commands of a camera and its scroll.
     *
     * @param registry the registry of the client's commands
     * @param camera   the camera the commands change
     * @param scroll   the camera's scroll, whose edge and key scrolling the commands turn on or off
     * @param rate     the scroll's continuous rate, whose speeds the commands set
     */
    public static void register(@NotNull ConsoleCommandRegistry registry, @NotNull Camera camera, @NotNull CameraScroll scroll,
                                @NotNull ContinuousScrollRate rate) {
        registry.register(ConsoleCommand.of("/camera", "[<x> <y>]", "show the camera, or centre it on a world position")
                                        .describe("Shows the world position of the camera's top-left corner and the shown area, or centres the " +
                                                  "camera on a world position in pixels as far as the map allows.")
                                        .whenEmpty("show the camera position, the shown area and the zoom", arguments -> describeCamera(camera))
                                        .otherwise("<x> <y>", "centre the camera on the world position", arguments -> centerCamera(camera, arguments))
                                        .availability("in a game.")
                                        .example("/camera 1024 768"));
        registry.register(ConsoleCommand.of("/zoom", "[reset|<percent>|limits <minimum> <maximum>]", "zoom the world view")
                                        .describe("Sets the zoom of the world view in percent of the world's own size, limited to the zoom " +
                                                  "limits; the centre of the shown area stays.")
                                        .whenEmpty("show the zoom and its limits", arguments -> describeZoom(camera))
                                        .subcommand(RESET, "", "zoom to 100 percent", arguments -> zoom(camera, PERCENT))
                                        .subcommand(LIMITS, "<minimum> <maximum>", "set the zoom limits in percent",
                                                    arguments -> setZoomLimits(camera, arguments))
                                        .otherwise("<percent>", "zoom to the percent", arguments -> zoom(camera, arguments))
                                        .availability("in a game.")
                                        .example("/zoom 150")
                                        .example("/zoom limits 50 300"));
        registry.register(ConsoleCommand.of("/edgescroll", "[on|off|<speed>]", "scroll the camera with the pointer at a screen edge")
                                        .describe("Turns edge scrolling on or off, or sets its speed in screen pixels per second.")
                                        .whenEmpty("show whether edge scrolling is on and its speed", arguments -> describeEdgeScroll(scroll, rate))
                                        .subcommand(ConsoleCommand.ON, "", "turn edge scrolling on",
                                                    arguments -> setEdgeScrolling(scroll, rate, true))
                                        .subcommand(ConsoleCommand.OFF, "", "turn edge scrolling off",
                                                    arguments -> setEdgeScrolling(scroll, rate, false))
                                        .otherwise("<speed>", "set the speed in screen pixels per second",
                                                   arguments -> setEdgeSpeed(scroll, rate, arguments))
                                        .availability("in a game.")
                                        .example("/edgescroll 600"));
        registry.register(ConsoleCommand.of("/keyscroll", "[on|off|<speed>]", "scroll the camera with the arrow keys")
                                        .describe("Turns scrolling with the arrow keys on or off, or sets its speed in screen pixels per second.")
                                        .whenEmpty("show whether key scrolling is on and its speed", arguments -> describeKeyScroll(scroll, rate))
                                        .subcommand(ConsoleCommand.ON, "", "turn key scrolling on",
                                                    arguments -> setKeyScrolling(scroll, rate, true))
                                        .subcommand(ConsoleCommand.OFF, "", "turn key scrolling off",
                                                    arguments -> setKeyScrolling(scroll, rate, false))
                                        .otherwise("<speed>", "set the speed in screen pixels per second",
                                                   arguments -> setKeySpeed(scroll, rate, arguments))
                                        .availability("in a game.")
                                        .example("/keyscroll 1500"));
    }

    private static @NotNull String describeCamera(@NotNull Camera camera) {
        return String.format(Locale.ROOT, "Camera at %.0f,%.0f, showing %.0fx%.0f of %dx%d at %.0f%%.", camera.getX(), camera.getY(),
                             camera.getViewWidth(), camera.getViewHeight(), camera.getMapWidth(), camera.getMapHeight(), camera.getZoom() * PERCENT);
    }

    private static @NotNull String centerCamera(@NotNull Camera camera, @NotNull String @NotNull [] arguments) {
        if (arguments.length != POSITION_ARGUMENT_COUNT) {
            return "Use /camera <x> <y> (/help camera).";
        }

        Float x = parse(arguments[0]);
        Float y = parse(arguments[1]);

        if (x == null || y == null) {
            return "Not a number: " + (x == null ? arguments[0] : arguments[1]) + ".";
        }

        camera.centerOn(x, y);

        return describeCamera(camera);
    }

    private static @NotNull String describeZoom(@NotNull Camera camera) {
        return String.format(Locale.ROOT, "Zoom %.0f%%, limits %.0f%% to %.0f%%.", camera.getZoom() * PERCENT, camera.getMinimumZoom() * PERCENT,
                             camera.getMaximumZoom() * PERCENT);
    }

    private static @NotNull String zoom(@NotNull Camera camera, @NotNull String @NotNull [] arguments) {
        Float percent = arguments.length == 1 ? parse(arguments[0]) : null;

        if (percent == null || !(percent > 0)) {
            return "Not a zoom percent: " + String.join(" ", arguments) + ".";
        }

        return zoom(camera, percent);
    }

    private static @NotNull String zoom(@NotNull Camera camera, float percent) {
        camera.setZoom(percent / PERCENT);

        return describeZoom(camera);
    }

    private static @NotNull String setZoomLimits(@NotNull Camera camera, @NotNull String @NotNull [] arguments) {
        Float minimum = arguments.length == LIMITS_ARGUMENT_COUNT ? parse(arguments[0]) : null;
        Float maximum = arguments.length == LIMITS_ARGUMENT_COUNT ? parse(arguments[1]) : null;

        if (minimum == null || maximum == null || !(minimum > 0) || maximum < minimum) {
            return "Use /zoom limits <minimum> <maximum> with 0 < minimum <= maximum (/help zoom).";
        }

        camera.setZoomLimits(minimum / PERCENT, maximum / PERCENT);

        return describeZoom(camera);
    }

    private static @NotNull String describeEdgeScroll(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate) {
        return String.format(Locale.ROOT, "Edge scrolling %s, %.0f px/s within %d px of an edge.",
                             scroll.isEdgeScrolling() ? ConsoleCommand.ON : ConsoleCommand.OFF, rate.getEdgeSpeed(), scroll.getEdgeWidth());
    }

    private static @NotNull String setEdgeScrolling(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate, boolean edgeScrolling) {
        scroll.setEdgeScrolling(edgeScrolling);

        return describeEdgeScroll(scroll, rate);
    }

    private static @NotNull String setEdgeSpeed(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate,
                                                @NotNull String @NotNull [] arguments) {
        Float speed = arguments.length == 1 ? parse(arguments[0]) : null;

        if (speed == null || !(speed > 0)) {
            return "Not an edge scroll speed: " + String.join(" ", arguments) + ".";
        }

        rate.setEdgeSpeed(speed);

        return describeEdgeScroll(scroll, rate);
    }

    private static @NotNull String describeKeyScroll(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate) {
        return String.format(Locale.ROOT, "Key scrolling %s, %.0f px/s.", scroll.isKeyScrolling() ? ConsoleCommand.ON : ConsoleCommand.OFF,
                             rate.getKeySpeed());
    }

    private static @NotNull String setKeyScrolling(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate, boolean keyScrolling) {
        scroll.setKeyScrolling(keyScrolling);

        return describeKeyScroll(scroll, rate);
    }

    private static @NotNull String setKeySpeed(@NotNull CameraScroll scroll, @NotNull ContinuousScrollRate rate,
                                               @NotNull String @NotNull [] arguments) {
        Float speed = arguments.length == 1 ? parse(arguments[0]) : null;

        if (speed == null || !(speed > 0)) {
            return "Not a key scroll speed: " + String.join(" ", arguments) + ".";
        }

        rate.setKeySpeed(speed);

        return describeKeyScroll(scroll, rate);
    }

    private static @Nullable Float parse(@NotNull String text) {
        try {
            float value = Float.parseFloat(text);

            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private CameraCommandUtils() {
    }
}
