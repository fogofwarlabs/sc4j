package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the game menu of a demo game against the computer: Escape opens it over the paused game, Resume lets the game run on, Leave game
 * returns to the main menu with the replay stored.
 */
final class DemoGameSceneTest {
    private static final long UPDATE_MILLISECONDS = (long) DemoGameScene.LOOP_MILLISECONDS * DemoGameScene.MAXIMUM_LOOPS_PER_UPDATE;

    private static void assertMenuOpen(@NotNull DemoClientScenario scenario) {
        List<ClientScene> modals = scenario.getClient().getScenes().getModalScenes();

        Assertions.assertEquals(1, modals.size(), "one popup is open");

        DemoPopup menu = Assertions.assertInstanceOf(DemoPopup.class, modals.getFirst());

        Assertions.assertEquals(DemoGameScene.MENU_DIALOG, menu.getDialogId());
    }

    @Test
    void demo_game_scene_escape_opens_the_game_menu_over_the_paused_game_and_leave_returns_to_the_menu(@TempDir @NotNull Path storage) throws IOException,
                                                                                                                                              ManifestException,
                                                                                                                                              ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage)) {
            scenario.runFrame();
            scenario.type("s");
            scenario.runFrame();
            scenario.press(Key.ENTER);
            scenario.runFrame();
            scenario.runFrame();
            scenario.runFrame();
            scenario.runFrame();

            DemoGameScene gameScene = Assertions.assertInstanceOf(DemoGameScene.class, scenario.getScene());
            Game game = gameScene.getGame();

            Assertions.assertNotNull(game);
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.press(Key.ESCAPE);
            scenario.runFrame();
            assertMenuOpen(scenario);

            long frame = game.getClock().getFrame();

            scenario.runUpdate(UPDATE_MILLISECONDS);
            Assertions.assertEquals(frame, game.getClock().getFrame(), "the game pauses while its menu is open");

            scenario.press(Key.ESCAPE);
            Assertions.assertTrue(scenario.getClient().getScenes().getModalScenes().isEmpty(), "Escape resumes");
            scenario.runUpdate(UPDATE_MILLISECONDS);
            Assertions.assertTrue(game.getClock().getFrame() > frame, "the game runs on");

            scenario.press(Key.F10);
            assertMenuOpen(scenario);
            scenario.type("l");
            scenario.runFrame();

            Assertions.assertSame(scenario.getMenu(), scenario.getScene(), "Leave game returns to the main menu");
            Assertions.assertTrue(scenario.getClient().getScenes().getModalScenes().isEmpty());
        }
    }
}
