package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.engine.client.app.GameFileListScene;
import org.fogofwarlabs.sc4j.engine.client.command.ConstructAction;
import org.fogofwarlabs.sc4j.engine.client.dialog.ComboBox;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.game.GameScreen;
import org.fogofwarlabs.sc4j.engine.client.game.GameTimeUtils;
import org.fogofwarlabs.sc4j.engine.client.hud.CommandButton;
import org.fogofwarlabs.sc4j.engine.client.hud.ProductionQueuePanel;
import org.fogofwarlabs.sc4j.engine.client.hud.ReplayPanel;
import org.fogofwarlabs.sc4j.engine.client.hud.ResourceBar;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.render.PictureRenderer;
import org.fogofwarlabs.sc4j.engine.client.render.WorldViewport;
import org.fogofwarlabs.sc4j.engine.client.skin.HudSkin;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.trigger.PlayerOutcome;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tests the demo's client offscreen: from the main menu through the game setup and the loading screen into a game against the computer (an arrow key
 * released while the console is open stops scrolling, training and building through the command card, the computer's units coming to the person's
 * base), to the outcome screen and its stored replay, and through the replay list (players named by their factions) into the replay viewer with
 * its speed and seek commands by game time, its replay panel (seek bar, next player) and the followed player's top bar and skin. At a display scale
 * the main menu keeps its logical layout, is drawn larger with the font's detail face and takes the pointer in logical units, and the game screen
 * draws the skin pictures and the cursor from their detail pictures. The queue panel of the selected town hall cancels the pressed slot with its full
 * cost back, and the one slot of a building under construction cancels the construction, at scale 1 and 2.
 */
final class DemoClientTest {
    private static final long UPDATE_MILLISECONDS = (long) DemoGameScene.LOOP_MILLISECONDS * DemoGameScene.MAXIMUM_LOOPS_PER_UPDATE;
    private static final int LOOP_LIMIT = 60000;
    private static final int TRAINING_LOOPS = 200;
    private static final int START_WORKERS = 4;
    private static final int CENTER_X = DemoClientScenario.WIDTH / 2;
    private static final int CENTER_Y = DemoClientScenario.HEIGHT / 2;
    private static final int SITE_STEP = 48;
    private static final int SITE_TRIES = 8;
    private static final int WAVE_SIZE = 3;
    private static final long LATE_PERCENT = 92;
    private static final long PERCENT = 100;
    /**
     * The game time the replay viewer seeks to, and its game loop.
     */
    private static final @NotNull String SEEK_TIME = "2:06";
    private static final long SEEK_LOOP = 3000;
    /**
     * The earlier game time the replay viewer seeks back to, and its game loop.
     */
    private static final @NotNull String SEEK_BACK_TIME = "1:03";
    private static final long SEEK_BACK_LOOP = 1500;
    private static final @NotNull String END_TIME = "99:00";
    private static final @NotNull String REPLAY_PLAYERS = "Dawnhold vs Cindermaw";
    private static final int PLAYERS_COLUMN = 1;
    private static final @NotNull String TILLER = "tiller";
    private static final @NotNull String HEARTHSTEAD = "hearthstead";
    private static final @NotNull String EMBER_LODGE = "ember-lodge";
    private static final int SECOND_FACTION = 1;
    private static final int QUEUE_GOLD = 1000;
    private static final int QUEUED_ENTRIES = 3;
    private static final int QUEUE_PROGRESS_LOOPS = 20;
    private static final int TILLER_COST = 50;
    private static final @NotNull String LARDER = "larder";
    private static final @NotNull String GOLD_MINE = "gold-mine";
    private static final @NotNull String NO_GOLD_MESSAGE = "Not enough Gold.";
    private static final int LARDER_COST = 100;
    private static final int GATHER_LOOPS = 6000;
    private static final @NotNull String CANCEL_LABEL = "Cancel";
    private static final float HALF = 2;
    /**
     * The size multiple of a detail picture and of the font's detail face.
     */
    private static final int DETAIL_MULTIPLE = 2;
    /**
     * The site steps of the first larder site the person tries, and the rows the tried sites alternate between.
     */
    private static final int FIRST_SITE_STEPS = 2;
    private static final int SITE_ROWS = 2;
    /**
     * The offset into a replay panel button from its top left pixel.
     */
    private static final int BUTTON_INSET = 2;
    /**
     * The logical size of the main menu dialog and the centre of its Start game button in the dialog.
     */
    private static final int MENU_WIDTH = 400;
    private static final int MENU_HEIGHT = 448;
    private static final int START_BUTTON_X = 200;
    private static final int START_BUTTON_Y = 134;
    /**
     * Skin pictures of both factions and the shared pictures that have a detail picture at twice the size.
     */
    private static final @NotNull List<@NotNull String> DETAIL_PICTURES = List.of(
            "dawnhold-panel", "dawnhold-button-normal", "dawnhold-topbar", "dawnhold-console-command", "dawnhold-slot", "dawnhold-order-move",
            "cindermaw-panel", "cindermaw-console-selection", "cindermaw-supply",
            "emblem-dawnhold", "emblem-cindermaw", "icon-tiller", "portrait-tiller", "portrait-ember-lodge", "resource-gold"
    );

    private static @NotNull List<@NotNull Unit> findUnits(@NotNull GameView view, @NotNull Player owner, @NotNull String typeId) {
        return view.getUnits().stream().filter(unit -> {
            UnitView unitView = view.getUnitView(unit);
            UnitType type = unitView.getType();

            return unitView.getOwner() == owner && type != null && type.getDebugName().equals(typeId);
        }).toList();
    }

    /**
     * Finds the gold mine nearest to the town hall.
     */
    private static @NotNull Unit findNearestMine(@NotNull GameView view, @NotNull UnitView townHall) {
        Unit nearest = null;
        long nearestDistance = Long.MAX_VALUE;

        for (Unit unit : view.getUnits()) {
            UnitView unitView = view.getUnitView(unit);
            UnitType type = unitView.getType();
            long distanceX = unitView.getX() - townHall.getX();
            long distanceY = unitView.getY() - townHall.getY();

            if (type != null && type.getDebugName().equals(GOLD_MINE) && distanceX * distanceX + distanceY * distanceY < nearestDistance) {
                nearest         = unit;
                nearestDistance = distanceX * distanceX + distanceY * distanceY;
            }
        }

        return Assertions.assertInstanceOf(Unit.class, nearest, "the map has a gold mine");
    }

    /**
     * Finds the logical middle of a filled queue slot by searching the panel for the slot under the pointer.
     */
    private static float @NotNull [] findSlot(@NotNull ProductionQueuePanel panel, int slot) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -1;
        float bottom = -1;

        for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
            for (float x = panel.getX(); x < panel.getX() + panel.getWidth(); x++) {
                if (panel.findSlotAt(x, y) == slot) {
                    left   = Math.min(left, x);
                    top    = Math.min(top, y);
                    right  = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }

        Assertions.assertTrue(right >= 0, "queue slot " + slot + " is shown");

        return new float[]{(left + right) / HALF, (top + bottom) / HALF};
    }

    /**
     * Places a larder under construction for a worker's owner on the first free site on a diagonal away from the town hall.
     */
    private static @NotNull Unit placeLarder(@NotNull Game game, @NotNull Unit worker, @NotNull UnitView townHall) {
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        UnitType larder = game.getContent().getDefinition().getUnitTypes().getTypes().getTypes().stream()
                              .filter(type -> type.getDebugName().equals(LARDER)).findFirst().orElseThrow();

        for (int attempt = 1; attempt <= SITE_TRIES; attempt++) {
            Unit building = production.placeBuilding(worker, larder, townHall.getX() + SITE_STEP * attempt, townHall.getY() + SITE_STEP * attempt, 0);

            if (building != null) {
                return building;
            }
        }

        return Assertions.fail("No free site for the larder.");
    }

    private static @NotNull List<@NotNull Unit> findVisibleEnemyFighters(@NotNull Game game, @NotNull GameView view, @NotNull Player person) {
        List<Unit> units = view.getUnits();
        List<Unit> fighters = new ArrayList<>();

        for (Unit shownUnit : units) {
            UnitView unit = view.getUnitView(shownUnit);
            UnitType type = unit.getType();

            if (type != null && unit.getOwner() == game.getPlayer(DemoGames.SECOND_SLOT) && view.isUnitVisible(person, unit.getUnit()) &&
                !game.getContent().getUnitTypes().hasTrait(type, UnitTrait.BUILDING) &&
                !game.getContent().getUnitTypes().hasTrait(type, UnitTrait.WORKER)) {
                fighters.add(unit.getUnit());
            }
        }

        return fighters;
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.5f, 2.0f})
    void demo_client_lays_the_main_menu_out_in_logical_units_at_a_display_scale(float scale, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                    ManifestException,
                                                                                                                                    ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, Math.round(DemoClientScenario.WIDTH * scale),
                                                                  Math.round(DemoClientScenario.HEIGHT * scale))) {
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            scenario.runFrame();

            Assertions.assertEquals(DemoClientScenario.WIDTH, scenario.getClient().getWidth());
            Assertions.assertEquals(DemoClientScenario.HEIGHT, scenario.getClient().getHeight());
            Assertions.assertEquals(scale > 1 ? DETAIL_MULTIPLE : 1,
                                    scenario.getMenu().getDemo().getFont().findDrawnMultiple(scenario.getClient().getCanvas(), TextStyle.DEFAULT));

            // The Start game button is at its logical place: a click there in framebuffer pixels opens the game setup.
            float buttonX = (DemoClientScenario.WIDTH - MENU_WIDTH) / 2f + START_BUTTON_X;
            float buttonY = (DemoClientScenario.HEIGHT - MENU_HEIGHT) / 2f + START_BUTTON_Y;

            scenario.click(PointerButton.LEFT, buttonX * scale, buttonY * scale);
            scenario.runFrame();
            Assertions.assertInstanceOf(GameSetupScene.class, scenario.getScene());
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 2.0f})
    void demo_client_draws_the_game_screen_from_detail_pictures_at_a_display_scale(float scale, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                       ManifestException,
                                                                                                                                       ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, Math.round(DemoClientScenario.WIDTH * scale),
                                                                  Math.round(DemoClientScenario.HEIGHT * scale))) {
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
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
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            GameView view = screen.getView();
            UnitView townHall = view.getUnitView(findUnits(view, game.getPlayer(DemoGames.FIRST_SLOT), HEARTHSTEAD).getFirst());
            WorldViewport viewport = new WorldViewport(engineScreen.getCamera().getProjection());

            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
            engineScreen.getCamera().applyTo(viewport);
            scenario.click(PointerButton.LEFT, viewport.worldToScreenX(townHall.getX(), townHall.getY()) * scale,
                           viewport.worldToScreenY(townHall.getX(), townHall.getY()) * scale);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            scenario.runFrame();

            Assertions.assertEquals(1, engineScreen.getSelection().getSize(), "a press on the town hall at its logical place selects it");
            Assertions.assertEquals(DemoClientScenario.WIDTH, engineScreen.getCamera().getScreenWidth(), "the screen is in logical units");

            try (PictureRenderer pictures = new PictureRenderer(scenario.getClient().getCanvas(), gameScene.getDemo().getContent().getPack())) {
                for (String id : DETAIL_PICTURES) {
                    Assertions.assertEquals(scale > 1 ? DETAIL_MULTIPLE : 1, pictures.findDrawnMultiple(pictures.getPicture(ResourceKey.of(id)), 1),
                                            id);
                }
            }

            DetailSet<Picture> cursor = gameScene.getDemo().getSkin().getCursor().getShown();

            Assertions.assertNotNull(cursor);
            Assertions.assertEquals(scale > 1 ? DETAIL_MULTIPLE : 1, cursor.findMultiple(Math.round(scale)),
                                    "the cursor at the cursor surface's scale");
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 2.0f})
    void demo_client_queue_panel_cancels_the_pressed_slot_and_a_construction(float scale, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                 ManifestException,
                                                                                                                                 ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, Math.round(DemoClientScenario.WIDTH * scale),
                                                                  Math.round(DemoClientScenario.HEIGHT * scale))) {
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
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
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            ProductionQueuePanel panel = screen.getQueuePanel();
            GameView view = screen.getView();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit townHall = findUnits(view, person, HEARTHSTEAD).getFirst();
            ResourceKind gold = gameScene.getDemo().getContent().getGold();

            game.getEconomy().setAmount(person, gold, QUEUE_GOLD);
            engineScreen.getSelection().select(townHall);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);

            for (int entry = 0; entry < QUEUED_ENTRIES; entry++) {
                scenario.press(Key.T);
            }

            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS * QUEUE_PROGRESS_LOOPS);
            Assertions.assertEquals(QUEUED_ENTRIES, game.getProduction().getQueueLength(townHall));
            Assertions.assertEquals(QUEUE_GOLD - QUEUED_ENTRIES * TILLER_COST, game.getEconomy().getAmount(person, gold));
            Assertions.assertTrue(engineScreen.getCommandCard().getButtons().stream().anyMatch(button -> button.getLabel().equals(CANCEL_LABEL)),
                                  "the command card shows the cancel button");

            scenario.runFrame();

            float[] secondSlot = findSlot(panel, 1);

            scenario.getClient().handleInput(InputEvent.createPointerMoved(secondSlot[0] * scale, secondSlot[1] * scale));
            scenario.runFrame();
            scenario.click(PointerButton.LEFT, secondSlot[0] * scale, secondSlot[1] * scale);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);

            Assertions.assertEquals(QUEUED_ENTRIES - 1, game.getProduction().getQueueLength(townHall), "the pressed slot is cancelled");
            Assertions.assertEquals(QUEUE_GOLD - (QUEUED_ENTRIES - 1) * TILLER_COST, game.getEconomy().getAmount(person, gold),
                                    "the full cost comes back");

            Unit tiller = findUnits(view, person, TILLER).getFirst();
            Unit larder = placeLarder(game, tiller, view.getUnitView(townHall));

            engineScreen.getSelection().select(larder);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS * QUEUE_PROGRESS_LOOPS);
            scenario.runFrame();
            Assertions.assertEquals(List.of(CANCEL_LABEL), engineScreen.getCommandCard().getButtons().stream().map(CommandButton::getLabel).toList(),
                                    "a building under construction shows only the cancel button");

            float[] constructionSlot = findSlot(panel, 0);

            scenario.click(PointerButton.LEFT, constructionSlot[0] * scale, constructionSlot[1] * scale);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);

            Assertions.assertTrue(findUnits(view, person, LARDER).isEmpty(), "the construction is cancelled");
            Assertions.assertEquals(QUEUE_GOLD - (QUEUED_ENTRIES - 1) * TILLER_COST, game.getEconomy().getAmount(person, gold),
                                    "the construction's full cost comes back");
        }
    }

    @Test
    void demo_client_plays_a_game_from_the_main_menu_and_views_its_replay(@TempDir @NotNull Path storage) throws IOException,
                                                                                                                 ManifestException,
                                                                                                                 ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage)) {
            scenario.runFrame();
            Assertions.assertSame(scenario.getMenu(), scenario.getScene());

            scenario.type("s");
            scenario.runFrame();
            Assertions.assertInstanceOf(GameSetupScene.class, scenario.getScene());

            scenario.press(Key.ENTER);
            scenario.runFrame();

            LoadingScene loading = Assertions.assertInstanceOf(LoadingScene.class, scenario.getScene());

            scenario.runFrame();
            scenario.runFrame();
            scenario.runFrame();

            DemoGameScene gameScene = Assertions.assertInstanceOf(DemoGameScene.class, scenario.getScene());

            Assertions.assertSame(loading.getGame(), gameScene, "the loading screen hands over to the game");

            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameView view = screen.getView();
            GameScreen engineScreen = screen.getScreen();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);

            Unit townHall = findUnits(view, person, HEARTHSTEAD).getFirst();
            UnitView townHallView = view.getUnitView(townHall);
            WorldViewport viewport = new WorldViewport(engineScreen.getCamera().getProjection());

            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();

            float startX = engineScreen.getCamera().getX();

            scenario.getClient().handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.RIGHT, 0));
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            Assertions.assertNotEquals(startX, engineScreen.getCamera().getX(), "a held arrow key scrolls the camera");
            scenario.press(Key.ENTER);
            Assertions.assertTrue(screen.getConsole().isOpen(), "Enter opens the console while the arrow key is held");
            scenario.getClient().handleInput(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, Key.RIGHT, 0));
            scenario.press(Key.ESCAPE);

            float scrolledX = engineScreen.getCamera().getX();
            float scrolledY = engineScreen.getCamera().getY();

            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            Assertions.assertEquals(scrolledX, engineScreen.getCamera().getX(), "the arrow key released in the console stops scrolling");
            Assertions.assertEquals(scrolledY, engineScreen.getCamera().getY());
            engineScreen.getCamera().applyTo(viewport);
            scenario.click(PointerButton.LEFT, viewport.worldToScreenX(townHallView.getX(), townHallView.getY()),
                           viewport.worldToScreenY(townHallView.getX(), townHallView.getY()));
            Assertions.assertEquals(1, engineScreen.getSelection().getSize(), "a press on the town hall selects it");
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            Assertions.assertEquals("Tiller", engineScreen.getCommandCard().getButtons().getFirst().getLabel(), "the town hall trains tillers");
            scenario.press(Key.T);

            for (int loop = 0; loop < TRAINING_LOOPS; loop += DemoGameScene.MAXIMUM_LOOPS_PER_UPDATE) {
                scenario.runUpdate(UPDATE_MILLISECONDS);
            }

            List<Unit> tillers = findUnits(view, person, TILLER);

            Assertions.assertEquals(START_WORKERS + 1, tillers.size(), "the trained tiller joins the start workers");

            engineScreen.getSelection().select(tillers.getFirst());
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            scenario.press(Key.L);
            Assertions.assertNull(engineScreen.getTranslator().getPendingAction(), "the larder is refused without its gold");
            Assertions.assertTrue(screen.getConsole().getLines().contains(NO_GOLD_MESSAGE), "the refusal shows a message");

            engineScreen.getSelection().select(tillers);
            engineScreen.getCamera().applyTo(viewport);

            UnitView mine = view.getUnitView(findNearestMine(view, townHallView));

            scenario.click(PointerButton.RIGHT, viewport.worldToScreenX(mine.getX(), mine.getY()), viewport.worldToScreenY(mine.getX(), mine.getY()));

            for (int loop = 0; loop < GATHER_LOOPS && game.getEconomy().getAmount(person, gameScene.getDemo().getContent().getGold()) < LARDER_COST;
                 loop += DemoGameScene.MAXIMUM_LOOPS_PER_UPDATE) {
                scenario.runUpdate(UPDATE_MILLISECONDS);
            }

            engineScreen.getSelection().select(tillers.getFirst());
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            scenario.press(Key.L);
            Assertions.assertInstanceOf(ConstructAction.class, engineScreen.getTranslator().getPendingAction(),
                                        "with the gathered gold the larder waits for its site");

            for (int attempt = 0; attempt < SITE_TRIES && engineScreen.getTranslator().getPendingAction() != null; attempt++) {
                scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
                scenario.runFrame();
                scenario.click(PointerButton.LEFT, CENTER_X + SITE_STEP * (attempt + FIRST_SITE_STEPS), CENTER_Y + SITE_STEP * (attempt % SITE_ROWS));
            }

            Assertions.assertNull(engineScreen.getTranslator().getPendingAction(), "a press on buildable ground orders the larder");
            scenario.runUpdate(UPDATE_MILLISECONDS);
            Assertions.assertSame(gameScene.getDemo().getContent().getOrders().getBuild(), view.getUnitView(tillers.getFirst()).getOrderType(),
                                  "the worker goes to build the larder");

            boolean midGameSaved = false;

            while (!DemoGames.isOver(game) && game.getClock().getFrame() < LOOP_LIMIT) {
                scenario.runUpdate(UPDATE_MILLISECONDS);

                List<Unit> enemies = midGameSaved || scenario.getScene() != gameScene ? List.of() : findVisibleEnemyFighters(game, view, person);

                if (enemies.size() >= WAVE_SIZE) {
                    UnitView enemyView = view.getUnitView(enemies.getFirst());

                    engineScreen.getCamera().centerOn(enemyView.getX(), enemyView.getY());
                    scenario.runFrame();
                    midGameSaved = true;
                }
            }

            Assertions.assertTrue(midGameSaved, "the computer's units came to the person's base");
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();

            OutcomeScene outcome = Assertions.assertInstanceOf(OutcomeScene.class, scenario.getScene());
            Path replays = storage.resolve(DemoClient.REPLAY_FOLDER);

            Assertions.assertEquals(PlayerOutcome.DEFEAT, outcome.getOutcome(), "the idle person loses to the computer");

            try (Stream<Path> files = Files.list(replays)) {
                Assertions.assertEquals(1, files.count(), "the game's replay is stored");
            }

            scenario.getClient().getScenes().replace(new OutcomeScene(outcome.getDemo(), scenario.getMenu(), PlayerOutcome.VICTORY,
                                                                      view.getFaction(person), "Ashen Ford: Dawnhold against Cindermaw",
                                                                      game.getClock().getFrame(), "game-1.sc4r"));
            scenario.runFrame();

            scenario.press(Key.ENTER);
            scenario.runFrame();
            Assertions.assertSame(scenario.getMenu(), scenario.getScene(), "continue returns to the main menu");

            scenario.type("r");
            scenario.runFrame();

            GameFileListScene list = Assertions.assertInstanceOf(GameFileListScene.class, scenario.getScene());

            Assertions.assertEquals(1, list.getEntries().size());
            Assertions.assertEquals(REPLAY_PLAYERS, list.getTable().getRows().getFirst().getCells().get(PLAYERS_COLUMN),
                                    "the replay's two players are named by their factions, the neutral slot left out");

            scenario.press(Key.ENTER);
            scenario.runFrame();

            ReplayViewerScene viewer = Assertions.assertInstanceOf(ReplayViewerScene.class, scenario.getScene());
            DemoGameScreen viewerScreen = viewer.getScreen();

            Assertions.assertNotNull(viewerScreen);
            viewerScreen.runCommand("/speed 16");
            viewerScreen.runCommand("/seek " + SEEK_TIME);
            Assertions.assertEquals(SEEK_LOOP, viewer.getLoop());
            viewerScreen.runCommand("/seek " + SEEK_BACK_TIME);
            Assertions.assertEquals(SEEK_BACK_LOOP, viewer.getLoop(), "a seek back restores the snapshot before it and plays on");
            viewerScreen.runCommand("/seek " + END_TIME);
            Assertions.assertEquals(viewer.getLoopCount(), viewer.getLoop());

            String lateTime = GameTimeUtils.format(viewer.getLoopCount() * LATE_PERCENT / PERCENT, DemoGameScene.LOOP_MILLISECONDS);
            String endTime = GameTimeUtils.format(viewer.getLoopCount(), DemoGameScene.LOOP_MILLISECONDS);

            viewerScreen.runCommand("/seek " + lateTime);
            Assertions.assertTrue(viewerScreen.getConsole().getLines().contains("At " + lateTime + " of " + endTime + "."),
                                  viewerScreen.getConsole().getLines().toString());

            ReplayPanel panel = Assertions.assertInstanceOf(ReplayPanel.class, viewerScreen.getScreen().getQueuePanel(),
                                                            "the console hosts the replay panel");
            ResourceBar topBar = viewerScreen.getScreen().getResourceBar();
            Player first = topBar.getViewedPlayer();

            Assertions.assertNotNull(first, "the top bar follows a player of the replay");
            Assertions.assertEquals("Dawnhold", viewer.getViewedPlayerName());

            scenario.runFrame();

            float[] playerButton = findReplayButton(panel, ReplayPanel.PLAYER_BUTTON);

            scenario.click(PointerButton.LEFT, playerButton[0], playerButton[1]);

            Assertions.assertNotSame(first, topBar.getViewedPlayer(), "the player button follows the next player");
            Assertions.assertEquals("Cindermaw", viewer.getViewedPlayerName());

            Player second = topBar.getViewedPlayer();

            Assertions.assertNotNull(second);
            Assertions.assertSame(scenario.getMenu().getDemo().getSkin().getHudSkins().findSkin(viewerScreen.getView().getFaction(second)),
                                  viewerScreen.getScreen().getResourceBar().getSkin(), "the HUD wears the followed player's skin");

            float barY = panel.getY() + panel.getHeight() / 2;
            float barX = panel.getX() + panel.getWidth() / 2;

            for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
                if (panel.findLoopAt(barX, y) >= 0) {
                    barY = y;

                    break;
                }
            }

            scenario.click(PointerButton.LEFT, barX, barY);

            Assertions.assertEquals(viewer.getLoopCount() / 2.0, viewer.getLoop(), viewer.getLoopCount() / (double) PERCENT,
                                    "a press on the middle of the bar seeks to the middle of the replay");

            scenario.runFrame();
        }
    }

    /**
     * Finds the centre of a button of the replay panel, in the frame's pixels at display scale one.
     */
    private static float @NotNull [] findReplayButton(@NotNull ReplayPanel panel, int button) {
        for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
            for (float x = panel.getX(); x < panel.getX() + panel.getWidth(); x++) {
                if (panel.findButtonAt(x, y) == button) {
                    return new float[]{x + BUTTON_INSET, y + BUTTON_INSET};
                }
            }
        }

        return Assertions.fail("the replay panel shows button " + button);
    }

    @Test
    void demo_client_shows_the_hud_skin_of_the_person_faction(@TempDir @NotNull Path storage) throws IOException, ManifestException, ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage)) {
            scenario.runFrame();
            scenario.type("s");
            scenario.runFrame();

            GameSetupScene setup = Assertions.assertInstanceOf(GameSetupScene.class, scenario.getScene());
            DemoSkin skin = setup.getDemo().getSkin();
            Faction faction = setup.getDemo().getContent().getFactions().get(SECOND_FACTION);

            ((ComboBox) setup.requireControl(GameSetupScene.FACTION_ID)).setSelection(SECOND_FACTION);
            ((ComboBox) setup.requireControl(GameSetupScene.OPPONENT_FACTION_ID)).setSelection(0);
            scenario.press(Key.ENTER);
            scenario.runFrame();
            scenario.runFrame();
            scenario.runFrame();
            scenario.runFrame();

            DemoGameScene gameScene = Assertions.assertInstanceOf(DemoGameScene.class, scenario.getScene());
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            HudSkin factionSkin = skin.getHudSkins().findSkin(faction);
            GameScreen engineScreen = screen.getScreen();

            Assertions.assertNotSame(skin.getHudSkins().getDefaultSkin(), factionSkin, "each faction has its own HUD skin");
            Assertions.assertSame(factionSkin, engineScreen.getCommandCard().getSkin(), "the HUD shows the skin of the person's faction");

            engineScreen.getSelection().select(findUnits(screen.getView(), game.getPlayer(DemoGames.FIRST_SLOT), EMBER_LODGE).getFirst());
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
        }
    }
}
