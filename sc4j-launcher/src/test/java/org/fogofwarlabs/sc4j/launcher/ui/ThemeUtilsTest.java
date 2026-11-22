package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

/**
 * Tests the theme's font stylesheets: a Korean or Chinese UI adds its font stylesheet when its font is installed, and no other UI adds it.
 */
final class ThemeUtilsTest {
    private static final @NotNull String KOREAN_STYLESHEET = "sc4j-launcher-ko.css";
    private static final @NotNull String CHINESE_STYLESHEET = "sc4j-launcher-zh.css";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    @ParameterizedTest
    @CsvSource({"en, ''", "ko, Malgun Gothic", "zh, Microsoft YaHei"})
    void apply_should_add_the_font_stylesheet_of_the_language_when_its_font_is_installed(@NotNull String languageCode,
                                                                                         @NotNull String fontFamily) throws Exception {
        UiLanguage language = LauncherFxScenario.requireLanguage(languageCode);

        LauncherFxScenario.run(() -> {
            boolean installed = !fontFamily.isEmpty() && Font.getFamilies().contains(fontFamily);
            Scene scene = new Scene(new VBox());

            ThemeUtils.apply(scene, language);

            List<String> stylesheets = scene.getStylesheets();

            Assertions.assertEquals(installed && language == UiLanguage.KOREAN, stylesheets.getLast().endsWith(KOREAN_STYLESHEET), "Korean");
            Assertions.assertEquals(installed && language == UiLanguage.CHINESE, stylesheets.getLast().endsWith(CHINESE_STYLESHEET), "Chinese");

            return null;
        });
    }
}
