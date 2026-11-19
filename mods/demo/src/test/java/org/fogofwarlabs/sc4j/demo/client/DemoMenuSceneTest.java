package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.demo.net.DemoNetworkSettings;
import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.app.GameFileListScene;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.trigger.PlayerOutcome;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Tests that every menu screen and the options popup of the demo show their dialogs whole at window sizes from 1280x720 to 2560x1440 and display
 * scales from 1.0 to 2.0, down to the engine minimum logical area.
 */
final class DemoMenuSceneTest {
    private static final int PERCENT = 100;
    /**
     * The game loops of the shown outcome and their game time at normal speed.
     */
    private static final long OUTCOME_LOOPS = 5667;
    private static final @NotNull String OUTCOME_TIME = "Game time 3:58";

    private static <S extends ClientScene> @NotNull S show(@NotNull DemoClientScenario scenario, @NotNull S scene) throws IOException {
        scenario.getClient().getScenes().replace(scene);
        scenario.runFrame();
        scenario.runFrame();

        Assertions.assertSame(scene, scenario.getScene(), "the scene opened");

        return scene;
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0", "1280, 720, 1.5", "1280, 720, 2.0", "1920, 1080, 1.5", "2560, 1440, 1.0"})
    void demo_menus_show_their_dialogs_whole_at_every_window_size_and_scale(int width, int height, float scale, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                                       ManifestException,
                                                                                                                                                       ModException {
        String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT);

        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            MainMenuScene menu = scenario.getMenu();
            DemoClient demo = menu.getDemo();

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            scenario.runFrame();
            scenario.saveFrame("menu-main-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), menu.requireDialog(), "the main menu");

            GameSetupScene setup = show(scenario, new GameSetupScene(demo, menu));

            scenario.saveFrame("menu-setup-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), setup.requireDialog(), "the game setup");

            GameFileListScene replays = show(scenario, GameFileListScene.createReplayList("Replays", demo.getTheme(), DemoClient.REPLAY_FOLDER,
                                                                                          DemoGameScene.LOOP_MILLISECONDS, menu));

            scenario.saveFrame("menu-replays-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), replays.getDialog(), "the replay list");

            show(scenario, menu);

            DemoPopup options = OptionsPopup.open(demo, () -> {
                // The menu shows nothing that the options change.
            });

            scenario.runFrame();
            scenario.saveFrame("menu-options-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), options.requireDialog(), "the options");
            options.close();
            scenario.runFrame();

            OutcomeScene outcome = show(scenario, new OutcomeScene(demo, menu, PlayerOutcome.VICTORY, demo.getContent().getFactions().getFirst(),
                                                                   "Ashen Ford", OUTCOME_LOOPS, ""));

            scenario.saveFrame("menu-outcome-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), outcome.requireDialog(), "the outcome");
            Assertions.assertEquals(OUTCOME_TIME, outcome.requireControl(OutcomeScene.LENGTH_ID).getText(), "the outcome shows the game time");

            NetworkScene network = show(scenario, new NetworkScene(demo, menu, DemoNetworkSettings.ANY_PORTS));

            scenario.saveFrame("menu-network-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), network.requireDialog(), "the network screen");

            scenario.type("h");
            scenario.runFrame();
            scenario.runFrame();

            NetworkRoomScene room = Assertions.assertInstanceOf(NetworkRoomScene.class, scenario.getScene(), "Host opens a room");

            scenario.saveFrame("menu-room-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), room.requireDialog(), "the room");
        }
    }
}
