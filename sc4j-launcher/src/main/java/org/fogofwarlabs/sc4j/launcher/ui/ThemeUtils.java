package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the launcher theme: the stylesheet {@code theme/sc4j-launcher.css} next to this class, the font fallback and
 * Korean font stylesheets, and the branding images. The theme uses no game assets.
 * <p>
 * Sizes are JavaFX logical units; on a scaled display one unit covers several screen pixels. Raster images are therefore decoded at their
 * logical size times an image scale ({@link #getImageScale}) and shown at their logical size, so they stay sharp.
 */
public final class ThemeUtils {
    /**
     * The SC4J logo.
     */
    public static final @NotNull String SC4J_LOGO = "theme/images/sc4j-logo-512.png";

    /**
     * The Fog of War Labs logo, the publisher mark.
     */
    public static final @NotNull String FOG_OF_WAR_LABS_LOGO = "theme/images/fogofwarlabs-logo-512.png";

    /**
     * The validation failure state of a form control, styled as {@code :invalid}.
     */
    public static final @NotNull PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    private static final @NotNull String STYLESHEET = "theme/sc4j-launcher.css";
    private static final @NotNull String PORTABLE_STYLESHEET = "theme/sc4j-launcher-portable.css";
    private static final @NotNull String KOREAN_STYLESHEET = "theme/sc4j-launcher-ko.css";

    /**
     * The font families the stylesheet names; when one is missing the portable stylesheet replaces all of them.
     */
    private static final @NotNull List<@NotNull String> THEME_FONT_FAMILIES = List.of("Segoe UI", "Segoe UI Semibold", "Cascadia Mono");
    private static final @NotNull String KOREAN_FONT_FAMILY = "Malgun Gothic";

    /**
     * Returns the image scale for the current screens: the largest output scale of any screen, so that an image is sharp on every monitor
     * the window can be moved to and is only reduced on the others.
     *
     * @return the screen pixels per logical unit, at least {@code 1}
     */
    public static double getImageScale() {
        double scale = 1;

        for (Screen screen : Screen.getScreens()) {
            scale = Math.max(scale, Math.max(screen.getOutputScaleX(), screen.getOutputScaleY()));
        }

        return scale;
    }

    /**
     * Applies the theme to a scene: the stylesheet, the portable font stylesheet when a theme font is missing, and the
     * Korean font stylesheet for a Korean UI when its font is installed.
     *
     * @param scene    the scene
     * @param language the UI language
     */
    public static void apply(@NotNull Scene scene, @NotNull UiLanguage language) {
        scene.getStylesheets().setAll(getStylesheets(language));
        ChamferUtils.install(scene);
    }

    private static @NotNull List<@NotNull String> getStylesheets(@NotNull UiLanguage language) {
        List<String> families = Font.getFamilies();
        List<String> stylesheets = new ArrayList<>();

        stylesheets.add(getResource(STYLESHEET).toExternalForm());

        if (!families.containsAll(THEME_FONT_FAMILIES)) {
            stylesheets.add(getResource(PORTABLE_STYLESHEET).toExternalForm());
        }

        if (language == UiLanguage.KOREAN && families.contains(KOREAN_FONT_FAMILY)) {
            stylesheets.add(getResource(KOREAN_STYLESHEET).toExternalForm());
        }

        return stylesheets;
    }

    /**
     * Loads a square theme image, resampled once to the target size times the image scale so that small logos stay sharp; an image view shows
     * it at the target size.
     *
     * @param path       the image path relative to this class, such as {@link #SC4J_LOGO}
     * @param size       the target width and height in logical units
     * @param imageScale the screen pixels per logical unit ({@link #getImageScale})
     * @return the loaded image
     * @throws IllegalStateException if the image resource is missing
     */
    public static @NotNull Image loadImage(@NotNull String path, double size, double imageScale) {
        return new Image(getResource(path).toExternalForm(), size * imageScale, size * imageScale, true, true);
    }

    /**
     * Decodes a mod image (a PNG file of the mod jar), resampled once to fit a box times the image scale; an image view shows it fitted to
     * the box.
     *
     * @param contents   the PNG contents
     * @param width      the box width in logical units, or {@code 0} for the width of the image file
     * @param height     the box height in logical units, or {@code 0} for the height of the image file
     * @param imageScale the screen pixels per logical unit ({@link #getImageScale})
     * @return the decoded image; an image that cannot be decoded reports {@link Image#isError()}
     */
    public static @NotNull Image decodeImage(byte @NotNull [] contents, double width, double height, double imageScale) {
        return new Image(new ByteArrayInputStream(contents), width * imageScale, height * imageScale, true, true);
    }

    private static @NotNull URL getResource(@NotNull String path) {
        URL url = ThemeUtils.class.getResource(path);

        if (url == null) {
            throw Exceptions.newIllegalStateException("Missing theme resource: {0}", path);
        }

        return url;
    }


    private ThemeUtils() {
    }
}
