package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.dialog.ComboBox;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsGroup;
import org.fogofwarlabs.sc4j.engine.client.settings.SettingsStore;
import org.fogofwarlabs.sc4j.engine.client.world.ContinuousScrollRate;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the demo's options from the main menu and the game menu: OK applies the chosen sound volume, music volume and interface size to the
 * client and stores the options in the demo's own settings file, Cancel changes nothing, a new run reads them back, and a game starts at the
 * chosen game speed with the camera at the chosen scroll speed, which the game menu's options change at once. The menus play the menu music
 * and a game the game music.
 */
final class OptionsPopupTest {
    private static final int HALF_VOLUME = 2;
    private static final int QUARTER_VOLUME = 1;
    private static final float QUARTER_GAIN = 0.25f;
    private static final int FOUR_TIMES = 2;
    private static final int FASTEST_SCROLL = 3;
    private static final int ONE_AND_A_HALF = 2;
    private static final int SLOW_SCROLL = 0;
    private static final float DELTA = 0.001f;
    private static final float HALF_GAIN = 0.5f;
    private static final float CHOSEN_USER_SCALE = 1.5f;
    /**
     * The scroll speeds of the fastest and the slow choice as factors of the engine's default.
     */
    private static final float FASTEST_FACTOR = 2;
    private static final float SLOW_FACTOR = 0.5f;
    /**
     * The frames from the setup's start to the open game: the loading screen and the game's first frames.
     */
    private static final int START_FRAMES = 4;

    private static @NotNull DemoPopup requireOptions(@NotNull DemoClientScenario scenario) {
        List<ClientScene> modals = scenario.getClient().getScenes().getModalScenes();
        DemoPopup popup = Assertions.assertInstanceOf(DemoPopup.class, modals.getLast(), "a popup is open");

        Assertions.assertEquals(OptionsPopup.DIALOG, popup.getDialogId());

        return popup;
    }

    private static void choose(@NotNull DemoPopup popup, int id, int selection) {
        ((ComboBox) popup.requireControl(id)).setSelection(selection);
    }

    @Test
    void options_apply_on_ok_are_stored_per_mod_and_read_back_by_the_next_run(@TempDir @NotNull Path storage) throws IOException,
                                                                                                                     ManifestException,
                                                                                                                     ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage)) {
            scenario.runFrame();

            Assertions.assertEquals(DemoClient.MENU_MUSIC.getFirst(), scenario.getClient().getMusic().getTrack(), "the menu music plays");

            scenario.type("o");
            scenario.runFrame();

            DemoPopup cancelled = requireOptions(scenario);

            choose(cancelled, OptionsPopup.SOUND_ID, 0);
            scenario.press(Key.ESCAPE);

            Assertions.assertTrue(scenario.getClient().getScenes().getModalScenes().isEmpty(), "Cancel closes the options");
            Assertions.assertEquals(1, scenario.getClient().getAudio().getMasterBus().getGain(), DELTA, "Cancel changes nothing");
            Assertions.assertFalse(Files.exists(scenario.getClient().getStorage().getSettingsFile()), "Cancel stores nothing");

            scenario.type("o");
            scenario.runFrame();

            DemoPopup options = requireOptions(scenario);

            choose(options, OptionsPopup.SOUND_ID, HALF_VOLUME);
            choose(options, OptionsPopup.MUSIC_ID, QUARTER_VOLUME);
            choose(options, OptionsPopup.GAME_SPEED_ID, FOUR_TIMES);
            choose(options, OptionsPopup.SCROLL_SPEED_ID, FASTEST_SCROLL);
            choose(options, OptionsPopup.INTERFACE_SIZE_ID, ONE_AND_A_HALF);
            scenario.runFrame();
            scenario.press(Key.ENTER);

            SettingsGroup stored = SettingsStore.read(scenario.getClient().getStorage().getSettingsFile()).getGroup(DemoOptions.GROUP);

            Assertions.assertTrue(scenario.getClient().getScenes().getModalScenes().isEmpty(), "OK closes the options");
            Assertions.assertEquals(HALF_GAIN, scenario.getClient().getAudio().getMasterBus().getGain(), DELTA, "the sound plays at half volume");
            Assertions.assertEquals(QUARTER_GAIN, scenario.getClient().getMusic().getVolume(), DELTA, "the music plays at a quarter volume");
            Assertions.assertEquals(CHOSEN_USER_SCALE, scenario.getClient().getDisplayScale().getUserScale(), DELTA,
                                    "the interface is half as large again");
            Assertions.assertNotNull(stored, "the options are stored in the demo's settings file");
            Assertions.assertEquals(List.of("soundVolume", "musicVolume", "gameSpeed", "scrollSpeed", "interfaceSize"), stored.getKeys());
        }

        try (DemoClientScenario scenario = new DemoClientScenario(storage)) {
            DemoOptions options = scenario.getMenu().getDemo().getOptions();

            Assertions.assertEquals(DemoOptions.SOUND_VOLUMES.get(HALF_VOLUME), options.getSoundVolume(), "the next run reads the volume back");
            Assertions.assertEquals(QUARTER_GAIN, scenario.getClient().getMusic().getVolume(), DELTA, "and the music volume");
            Assertions.assertEquals(CHOSEN_USER_SCALE, scenario.getClient().getDisplayScale().getUserScale(), DELTA,
                                    "and applies the interface size");

            scenario.runFrame();
            scenario.type("s");
            scenario.runFrame();
            scenario.press(Key.ENTER);

            for (int frame = 0; frame < START_FRAMES; frame++) {
                scenario.runFrame();
            }

            DemoGameScene game = Assertions.assertInstanceOf(DemoGameScene.class, scenario.getScene());
            DemoGameScreen screen = game.getScreen();

            Assertions.assertNotNull(screen);
            Assertions.assertEquals(DemoGameScene.SPEEDS.get(FOUR_TIMES), game.getSpeed(), "the game starts at the chosen speed");
            Assertions.assertEquals(DemoClient.GAME_MUSIC.getFirst(), scenario.getClient().getMusic().getTrack(), "the game music plays");
            Assertions.assertEquals(ContinuousScrollRate.DEFAULT_KEY_SPEED * FASTEST_FACTOR, screen.getScreen().getScrollRate().getKeySpeed(), DELTA,
                                    "the camera scrolls at the fastest speed");

            scenario.press(Key.F10);
            scenario.type("o");
            scenario.runFrame();
            choose(requireOptions(scenario), OptionsPopup.SCROLL_SPEED_ID, SLOW_SCROLL);
            scenario.press(Key.ENTER);

            Assertions.assertEquals(ContinuousScrollRate.DEFAULT_KEY_SPEED * SLOW_FACTOR, screen.getScreen().getScrollRate().getKeySpeed(), DELTA,
                                    "the game menu's options change the scroll speed at once");
        }
    }
}
