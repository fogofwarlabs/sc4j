package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.engine.client.command.CommandRefusal;
import org.fogofwarlabs.sc4j.engine.client.command.ConstructAction;
import org.fogofwarlabs.sc4j.engine.client.command.RallyAction;
import org.fogofwarlabs.sc4j.engine.client.dialog.ComboBox;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.game.GameScreen;
import org.fogofwarlabs.sc4j.engine.client.hud.*;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.client.world.Camera;
import org.fogofwarlabs.sc4j.engine.client.world.PlacementLayer;
import org.fogofwarlabs.sc4j.engine.client.world.RallyLayer;
import org.fogofwarlabs.sc4j.engine.client.world.UnitSpriteLayer;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.economy.HarvestSystem;
import org.fogofwarlabs.sc4j.engine.economy.StandardHarvestSystem;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.geom.Path2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the demo's game screen offscreen at window sizes and display scales: with a town hall training, the console spans the logical width
 * with its sections inside it, the production queue panel stands in its region between the selection panel and the command card with slots
 * large enough to read, the selected unit's name fits its panel, the console, the top bar, the portrait slot and the command buttons are laid
 * out from the pack's pictures, each drawn at its own resolution (the detail picture of the display scale), never scaled, and the cancel
 * button shows the faction's cancel picture of the display scale; a worker without gold sees its build buttons refused with a message; a single
 * unit and a group show their selection info, a small group as cards with each unit's name; the unexplored map's edge shows no terrain line,
 * the ground beyond the map shows the dark backdrop apart from the black of unexplored ground, and the view keeps its centre over the map at
 * the map's corner; a unit of the person that takes damage pings the minimap; and without a queue the selection panel takes the queue's
 * region, while a town hall whose entry waits for supply says so once and its hovered queue slot shows a tooltip in the faction's slot frame.
 * A building that waits for its site shows its sprite as a ghost, blocked over the town hall and free beside it; a right press with the town
 * hall selected sets its rally point, drawn as a line and a marker; a fighter counts its kill; and the minimap shows the diamond at the map's
 * aspect in a well wider than high.
 */
final class DemoGameScreenTest {
    private static final long UPDATE_MILLISECONDS = (long) DemoGameScene.LOOP_MILLISECONDS * DemoGameScene.MAXIMUM_LOOPS_PER_UPDATE;
    private static final int PERCENT = 100;
    private static final int GOLD = 1000;
    private static final int QUEUED_ENTRIES = 3;
    private static final int QUEUE_PROGRESS_LOOPS = 20;
    private static final float LARGEST_SCALE_ERROR = 0.001f;
    private static final @NotNull String DETAIL_SUFFIX = "-2x";
    /**
     * The size multiple of a detail picture.
     */
    private static final int DETAIL_MULTIPLE = 2;
    /**
     * The console pictures of a faction, each {@code <faction>-<piece>} in the pack.
     */
    private static final @NotNull List<@NotNull String> CONSOLE_PIECES = List.of(
            "console-plate", "console-frame", "console-minimap", "console-selection", "console-queue", "console-command", "topbar", "slot"
    );
    /**
     * The command button pictures of a faction; the normal one is always drawn, the others when a button is in their state.
     */
    private static final @NotNull List<@NotNull String> BUTTON_PIECES = List.of("button-normal", "button-hover", "button-pressed", "button-disabled");
    private static final @NotNull String NORMAL_BUTTON = "button-normal";
    private static final @NotNull String CANCEL_PICTURE = "cancel";
    /**
     * The slot frame of a faction, which the demo's HUD tooltips are drawn in.
     */
    private static final @NotNull String SLOT_PICTURE = "slot";
    /**
     * The smallest side of a queue slot in logical units, at which its unit icon still reads; the lowest console (at the engine minimum
     * height) leaves the five slots this much.
     */
    private static final float SMALLEST_QUEUE_SLOT = 32;
    /**
     * The padding of the selection panel's texts in logical units.
     */
    private static final float SELECTION_PADDING = 6;
    private static final @NotNull String NO_GOLD_MESSAGE = "Not enough Gold.";
    private static final @NotNull String NO_SUPPLY_MESSAGE = "Not enough Supply.";
    /**
     * The rows below the top bar that the messages may cover, in logical units, left out of the void check.
     */
    private static final float MESSAGE_ROWS = 80;
    /**
     * The largest share of the world view that the void beside the map may take: the centre of the view stays over the map.
     */
    private static final float LARGEST_VOID_SHARE = 0.55f;
    private static final int UNEXPLORED_COLOR = 0x000000;
    /**
     * How far from the map's outline the edge check looks, in logical units: the fog's cover and the tiles' edge pixels stay within it.
     */
    private static final float OUTLINE_MARGIN = 4;
    /**
     * The brightest channel the backdrop beyond the map reaches: darker than every ground of the tileset.
     */
    private static final int BACKDROP_BRIGHTEST = 64;
    private static final int CHANNEL_MASK = 0xFF;
    /**
     * The smallest share of the logical height the world view keeps between the resource bar and the console.
     */
    private static final float SMALLEST_WORLD_SHARE = 0.6f;
    /**
     * The smallest side of a command button in logical units, at which its icon still reads.
     */
    private static final float SMALLEST_COMMAND_BUTTON = 24;
    private static final int OUTLINE_LENGTH = 8;
    private static final int COORDINATES_PER_CORNER = 2;
    private static final float ASPECT_TOLERANCE = 0.01f;
    /**
     * How far from the town hall the free building site and the rally point lie on each axis, in world units.
     */
    private static final int SITE_DISTANCE = 128;
    /**
     * The colour of the rally line and marker, packed {@code 0xRRGGBB}, and how far a drawn pixel of it may differ per channel.
     */
    private static final int RALLY_RGB = RallyLayer.RALLY_COLOR >>> Byte.SIZE;
    private static final int RALLY_TOLERANCE = 24;
    /**
     * How far from a worker the selection test looks for a gold mine, in world pixels, and how many updates it waits at most for the worker
     * to carry gold.
     */
    private static final int MINE_RANGE = 4096;
    private static final int GATHER_UPDATES = 2000;
    /**
     * The offset into a queue slot or a command button from its top left pixel.
     */
    private static final int POINT_INSET = 2;

    private static void startGame(@NotNull DemoClientScenario scenario, int factionIndex) throws IOException {
        scenario.runFrame();
        scenario.type("s");
        scenario.runFrame();

        GameSetupScene setup = Assertions.assertInstanceOf(GameSetupScene.class, scenario.getScene());

        ((ComboBox) setup.requireControl(GameSetupScene.FACTION_ID)).setSelection(factionIndex);
        ((ComboBox) setup.requireControl(GameSetupScene.OPPONENT_FACTION_ID)).setSelection(1 - factionIndex);
        scenario.press(Key.ENTER);
        scenario.runFrame();
        scenario.runFrame();
        scenario.runFrame();
        scenario.runFrame();
    }

    /**
     * Watches a picture of the pack; a picture the pack lacks is not watched, and the test finds it not drawn.
     */
    private static void watch(@NotNull TextureScaleCheck check, @NotNull ResourceProvider pack, @NotNull String id) throws IOException {
        try {
            check.watch(id, pack.loadPicture(ResourceKey.of(id)));
        } catch (ResourceNotFoundException exception) {
            // The pack has no picture of this id.
        }
    }

    private static @NotNull DemoGameScene requireGameScene(@NotNull DemoClientScenario scenario) {
        DemoGameScene gameScene = Assertions.assertInstanceOf(DemoGameScene.class, scenario.getScene());

        Assertions.assertNotNull(gameScene.getGame());
        Assertions.assertNotNull(gameScene.getScreen());

        return gameScene;
    }

    private static @NotNull Unit findTownHall(@NotNull Game game, @NotNull GameView view, @NotNull Player owner) {
        return findUnits(game, view, owner, UnitTrait.RESOURCE_DEPOT).getFirst();
    }

    private static @NotNull List<@NotNull Unit> findUnits(@NotNull Game game, @NotNull GameView view, @NotNull Player owner,
                                                          @NotNull UnitTrait trait) {
        List<Unit> found = new ArrayList<>();

        for (Unit unit : view.getUnits()) {
            UnitView unitView = view.getUnitView(unit);
            UnitType type = unitView.getType();

            if (unitView.getOwner() == owner && type != null && game.getContent().getUnitTypes().hasTrait(type, trait)) {
                found.add(unit);
            }
        }

        Assertions.assertFalse(found.isEmpty(), "the person has a unit of the trait " + trait);

        return found;
    }

    /**
     * Measures the side of a queue slot: the columns of the panel whose rows show the slot.
     */
    private static float measureSlot(@NotNull ProductionQueuePanel panel, int slot) {
        float smallestX = Float.MAX_VALUE;
        float largestX = -1;

        for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
            for (float x = panel.getX(); x < panel.getX() + panel.getWidth(); x++) {
                if (panel.findSlotAt(x, y) == slot) {
                    smallestX = Math.min(smallestX, x);
                    largestX  = Math.max(largestX, x);
                }
            }
        }

        return largestX < 0 ? 0 : largestX - smallestX + 1;
    }

    /**
     * Measures the height of a command button: the rows of the card that show the button.
     */
    private static float measureButtonHeight(@NotNull CommandCard card, @NotNull CommandButton button) {
        float smallestY = Float.MAX_VALUE;
        float largestY = -1;

        for (float y = card.getY(); y < card.getY() + card.getHeight(); y++) {
            for (float x = card.getX(); x < card.getX() + card.getWidth(); x++) {
                if (card.findButtonAt(x, y) == button) {
                    smallestY = Math.min(smallestY, y);
                    largestY  = Math.max(largestY, y);
                }
            }
        }

        return largestY < 0 ? 0 : largestY - smallestY + 1;
    }

    /**
     * Finds a logical position inside a filled queue slot.
     */
    private static float @NotNull [] findSlotPoint(@NotNull ProductionQueuePanel panel, int slot) {
        for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
            for (float x = panel.getX(); x < panel.getX() + panel.getWidth(); x++) {
                if (panel.findSlotAt(x, y) == slot) {
                    return new float[]{x + POINT_INSET, y + POINT_INSET};
                }
            }
        }

        return Assertions.fail("the slot is in the panel");
    }

    /**
     * Finds a logical position on a command button.
     */
    private static float @NotNull [] findButtonPoint(@NotNull CommandCard card, @NotNull CommandButton button) {
        for (float y = card.getY(); y < card.getY() + card.getHeight(); y++) {
            for (float x = card.getX(); x < card.getX() + card.getWidth(); x++) {
                if (card.findButtonAt(x, y) == button) {
                    return new float[]{x + POINT_INSET, y + POINT_INSET};
                }
            }
        }

        return Assertions.fail("the button is on the card");
    }

    /**
     * Creates the logical screen outline of the map, moved outward by a margin.
     */
    private static @NotNull Path2D.Float createMapOutline(@NotNull Camera camera, float margin) {
        float[] corners = new float[OUTLINE_LENGTH];
        float centerX = 0;
        float centerY = 0;
        Path2D.Float outline = new Path2D.Float();

        camera.getProjection().outlineFootprint(0, 0, camera.getMapWidth(), camera.getMapHeight(), corners);

        for (int index = 0; index < OUTLINE_LENGTH; index += COORDINATES_PER_CORNER) {
            corners[index]     = (corners[index] - camera.getX()) * camera.getZoom();
            corners[index + 1] = (corners[index + 1] - camera.getY()) * camera.getZoom();
            centerX += corners[index] / (OUTLINE_LENGTH / 2f);
            centerY += corners[index + 1] / (OUTLINE_LENGTH / 2f);
        }

        for (int index = 0; index < OUTLINE_LENGTH; index += COORDINATES_PER_CORNER) {
            float x = corners[index] + Math.signum(corners[index] - centerX) * margin;
            float y = corners[index + 1] + Math.signum(corners[index + 1] - centerY) * margin;

            if (index == 0) {
                outline.moveTo(x, y);
            } else {
                outline.lineTo(x, y);
            }
        }

        outline.closePath();

        return outline;
    }

    /**
     * Converts a world position to the frame's pixels through the camera of the screen.
     */
    private static float @NotNull [] toFramePixels(@NotNull GameScreen screen, int worldX, int worldY, float scale) {
        Camera camera = screen.getCamera();
        WorldProjection projection = camera.getProjection();

        return new float[]{(projection.toProjectedX(worldX, worldY) - camera.getX()) * camera.getZoom() * scale,
                           (projection.toProjectedY(worldX, worldY) - camera.getY()) * camera.getZoom() * scale};
    }

    /**
     * Finds a unit type of a faction that fights: no worker, no building, with a weapon.
     */
    private static @NotNull UnitType findFighterType(@NotNull Game game, @NotNull Faction faction) {
        UnitTypeTable table = game.getContent().getUnitTypes();

        for (UnitType type : game.getContent().getDefinition().getUnitTypes().getTypes().getTypes()) {
            if (table.getFaction(type) == faction && !table.hasTrait(type, UnitTrait.WORKER) && !table.hasTrait(type, UnitTrait.BUILDING) &&
                table.getGroundWeapon(type) != null) {
                return type;
            }
        }

        return Assertions.fail("the faction has a fighter");
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 2.0, 1", "1920, 1080, 1.0, 1", "1920, 1080, 2.0, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_draws_the_console_pictures_at_their_own_resolution(int width, int height, float scale, int factionIndex,
                                                                             @TempDir @NotNull Path storage) throws IOException,
                                                                                                                    ManifestException,
                                                                                                                    ModException {
        TextureScaleCheck[] check = new TextureScaleCheck[1];

        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height, canvas -> check[0] = new TextureScaleCheck(canvas))) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            ResourceProvider pack = scenario.getMenu().getDemo().getContent().getPack();
            Font font = scenario.getMenu().getDemo().getFont();
            int multiple = scale > 1 ? DETAIL_MULTIPLE : 1;

            for (String piece : CONSOLE_PIECES) {
                watch(check[0], pack, faction.getDebugName() + '-' + piece);
                watch(check[0], pack, faction.getDebugName() + '-' + piece + DETAIL_SUFFIX);
            }

            for (String piece : BUTTON_PIECES) {
                watch(check[0], pack, faction.getDebugName() + '-' + piece);
                watch(check[0], pack, faction.getDebugName() + '-' + piece + DETAIL_SUFFIX);
            }

            watch(check[0], pack, faction.getDebugName() + '-' + CANCEL_PICTURE);
            watch(check[0], pack, faction.getDebugName() + '-' + CANCEL_PICTURE + DETAIL_SUFFIX);

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit townHall = findTownHall(game, screen.getView(), person);

            game.getEconomy().setAmount(person, scenario.getMenu().getDemo().getContent().getGold(), GOLD);
            engineScreen.getSelection().select(townHall);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            Key train = engineScreen.getCommandCard().getButtons().getFirst().getHotkey();

            Assertions.assertNotNull(train, "the town hall's first button trains a unit");

            for (int entry = 0; entry < QUEUED_ENTRIES; entry++) {
                scenario.press(train);
            }

            scenario.runUpdate((long) DemoGameScene.LOOP_MILLISECONDS * QUEUE_PROGRESS_LOOPS);
            scenario.runFrame();
            scenario.saveFrame("console-" + width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName());

            float logicalWidth = width / scale;

            Assertions.assertEquals(logicalWidth, engineScreen.getConsolePlate().getWidth(), "the console spans the logical width");

            HudBlock selection = engineScreen.getSelectionPanel();
            ProductionQueuePanel queue = screen.getQueuePanel();
            HudBlock card = engineScreen.getCommandCard();
            UnitType townHallType = screen.getView().getUnitView(townHall).getType();

            Assertions.assertNotNull(townHallType);
            Assertions.assertEquals(QUEUED_ENTRIES, game.getProduction().getQueueLength(townHall), "the town hall trains");
            Assertions.assertSame(queue, engineScreen.getQueuePanel(), "the console hosts the queue panel");
            Assertions.assertNotNull(queue.findShownUnit(), "the queue panel shows the town hall's queue");
            Assertions.assertEquals(selection.getX() + selection.getWidth() + GameScreen.SECTION_GAP, queue.getX(),
                                    "the selection panel reaches the queue panel");
            Assertions.assertEquals(queue.getX() + queue.getWidth() + GameScreen.SECTION_GAP, card.getX(),
                                    "the queue panel reaches the command card");
            Assertions.assertEquals(logicalWidth - GameScreen.SECTION_GAP, card.getX() + card.getWidth(), "the command card ends a gap inside");
            Assertions.assertTrue(measureSlot(queue, 0) >= SMALLEST_QUEUE_SLOT, "a queue slot is " + measureSlot(queue, 0) + " wide");
            Assertions.assertTrue(font.measure(DemoClient.getName(townHallType)) <= selection.getWidth() - 2 * SELECTION_PADDING,
                                  "the selected unit's name fits the selection panel");

            for (String piece : CONSOLE_PIECES) {
                String drawn = faction.getDebugName() + '-' + piece + (multiple > 1 ? DETAIL_SUFFIX : "");

                Assertions.assertTrue(check[0].getDrawCount(drawn) > 0, drawn + " is drawn");
                Assertions.assertTrue(check[0].getLargestScaleError(drawn) < LARGEST_SCALE_ERROR, drawn + " is drawn at its own resolution");
            }

            for (String piece : BUTTON_PIECES) {
                String drawn = faction.getDebugName() + '-' + piece + (multiple > 1 ? DETAIL_SUFFIX : "");

                Assertions.assertTrue(!piece.equals(NORMAL_BUTTON) || check[0].getDrawCount(drawn) > 0, drawn + " is drawn");
                Assertions.assertTrue(check[0].getLargestScaleError(drawn) < LARGEST_SCALE_ERROR, drawn + " is drawn at its own resolution");
            }

            String cancel = faction.getDebugName() + '-' + CANCEL_PICTURE;

            // A low console's buttons fit the icon at a size that the base picture serves at any scale.
            Assertions.assertTrue(check[0].getDrawCount(cancel) + check[0].getDrawCount(cancel + DETAIL_SUFFIX) > 0,
                                  cancel + " is drawn on the cancel button");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 0", "1280, 720, 1.5, 1", "1280, 720, 2.0, 1", "1920, 1080, 1.5, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_console_leaves_the_world_view_its_room_at_every_window_size_and_scale(int width, int height, float scale, int factionIndex,
                                                                                                @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                       ManifestException,
                                                                                                                                       ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            Font font = scenario.getMenu().getDemo().getFont();

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit townHall = findTownHall(game, screen.getView(), person);

            game.getEconomy().setAmount(person, scenario.getMenu().getDemo().getContent().getGold(), GOLD);
            engineScreen.getSelection().select(townHall);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            Key train = engineScreen.getCommandCard().getButtons().getFirst().getHotkey();

            Assertions.assertNotNull(train, "the town hall's first button trains a unit");

            for (int entry = 0; entry < QUEUED_ENTRIES; entry++) {
                scenario.press(train);
            }

            scenario.runUpdate((long) DemoGameScene.LOOP_MILLISECONDS * QUEUE_PROGRESS_LOOPS);
            scenario.runFrame();
            scenario.saveFrame("layout-" + width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName());

            int logicalHeight = scenario.getClient().getHeight();
            HudBlock console = engineScreen.getConsolePlate();
            HudBlock minimap = engineScreen.getMinimap();
            HudBlock selection = engineScreen.getSelectionPanel();
            ProductionQueuePanel queue = screen.getQueuePanel();
            CommandCard card = engineScreen.getCommandCard();
            UnitType townHallType = screen.getView().getUnitView(townHall).getType();
            float worldRows = console.getY() - GameScreen.RESOURCE_BAR_HEIGHT;

            Assertions.assertNotNull(townHallType);
            Assertions.assertEquals(logicalHeight, console.getY() + console.getHeight(), "the console stands on the bottom edge");
            Assertions.assertTrue(worldRows >= logicalHeight * SMALLEST_WORLD_SHARE,
                                  "the world view keeps " + worldRows + " of " + logicalHeight + " logical rows");

            for (HudBlock section : List.of(minimap, selection, queue, card)) {
                Assertions.assertTrue(section.getY() >= console.getY() && section.getY() + section.getHeight() <= logicalHeight,
                                      section.getClass().getSimpleName() + " lies on the console");
            }

            Camera camera = engineScreen.getCamera();
            Minimap engineMinimap = engineScreen.getMinimap();
            float mapAspect = (camera.getBoundsRight() - camera.getBoundsLeft()) / (camera.getBoundsBottom() - camera.getBoundsTop());

            Assertions.assertEquals(mapAspect, engineMinimap.getMapAreaWidth() / engineMinimap.getMapAreaHeight(), ASPECT_TOLERANCE,
                                    "the minimap shows the diamond at the map's aspect");
            Assertions.assertTrue(engineMinimap.getMapAreaWidth() >= engineMinimap.getWidth() - 1 ||
                                  engineMinimap.getMapAreaHeight() >= engineMinimap.getHeight() - 1, "the map fills its well along one side");
            Assertions.assertTrue(minimap.getWidth() > minimap.getHeight(), "the well is wider than high for the isometric map");
            Assertions.assertNotNull(queue.findShownUnit(), "the queue panel shows the town hall's queue");
            Assertions.assertTrue(measureSlot(queue, 0) >= SMALLEST_QUEUE_SLOT, "a queue slot is " + measureSlot(queue, 0) + " wide");
            Assertions.assertTrue(font.measure(DemoClient.getName(townHallType)) <= selection.getWidth() - 2 * SELECTION_PADDING,
                                  "the selected unit's name fits the selection panel");

            float buttonHeight = measureButtonHeight(card, card.getButtons().getFirst());

            Assertions.assertTrue(buttonHeight >= SMALLEST_COMMAND_BUTTON, "a command button is " + buttonHeight + " high");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 2.0, 1", "1920, 1080, 1.0, 1", "1920, 1080, 2.0, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_refuses_a_build_button_without_gold_with_a_message(int width, int height, float scale, int factionIndex,
                                                                             @TempDir @NotNull Path storage) throws IOException,
                                                                                                                    ManifestException,
                                                                                                                    ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit worker = findUnits(game, screen.getView(), person, UnitTrait.WORKER).getFirst();

            game.getEconomy().setAmount(person, scenario.getMenu().getDemo().getContent().getGold(), 0);
            engineScreen.getSelection().select(worker);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            CommandButton build = engineScreen.getCommandCard().getButtons().stream()
                                              .filter(button -> button.getAction() instanceof ConstructAction)
                                              .findFirst()
                                              .orElseThrow();
            float[] point = findButtonPoint(engineScreen.getCommandCard(), build);
            Key hotkey = build.getHotkey();

            Assertions.assertNotNull(hotkey, "the build button has a hotkey");

            scenario.getClient().handleInput(InputEvent.createPointerMoved(point[0] * scale, point[1] * scale));
            scenario.runFrame();
            scenario.saveFrame("refusal-" + width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName());
            scenario.press(hotkey);
            scenario.runFrame();

            Assertions.assertNull(engineScreen.getTranslator().getPendingAction(), "a refused build does not wait for its site");
            Assertions.assertTrue(screen.getConsole().getLines().contains(NO_GOLD_MESSAGE), "the refusal shows a message");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 2.0, 1", "1920, 1080, 1.0, 1", "1920, 1080, 2.0, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_shows_the_selection_of_one_unit_and_of_a_group(int width, int height, float scale, int factionIndex,
                                                                         @TempDir @NotNull Path storage) throws IOException, ManifestException, ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName();

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            List<Unit> workers = findUnits(game, screen.getView(), person, UnitTrait.WORKER);

            workers.getFirst().setHitPoints(workers.getFirst().getHitPoints() / 2);
            engineScreen.getSelection().select(workers.getFirst());
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
            scenario.saveFrame("selection-unit-" + suffix);

            engineScreen.getSelection().select(findTownHall(game, screen.getView(), person));

            for (Unit worker : workers) {
                engineScreen.getSelection().add(worker);
            }

            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
            scenario.saveFrame("selection-group-" + suffix);

            Assertions.assertEquals(workers.size() + 1, engineScreen.getSelection().getUnits().size(), "the group is selected");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 2.0, 1", "1920, 1080, 1.0, 1", "1920, 1080, 2.0, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_keeps_the_map_in_view_and_shows_no_terrain_at_its_unexplored_edge(int width, int height, float scale, int factionIndex,
                                                                                            @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                   ManifestException,
                                                                                                                                   ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName();
            int top = Math.round((GameScreen.RESOURCE_BAR_HEIGHT + MESSAGE_ROWS) * scale);

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScreen screen = requireGameScene(scenario).getScreen();

            Assertions.assertNotNull(screen);

            Camera camera = screen.getScreen().getCamera();
            int shownPixels = 0;
            int blackBackdropPixels = 0;
            int brightBackdropPixels = 0;
            int backdropPixels = 0;

            camera.centerOn(0, camera.getMapHeight());
            scenario.runFrame();
            scenario.saveFrame("edge-" + suffix);

            int bottom = Math.round(screen.getScreen().getConsolePlate().getY() * scale);
            Path2D.Float inner = createMapOutline(camera, -OUTLINE_MARGIN);
            Path2D.Float outer = createMapOutline(camera, OUTLINE_MARGIN);

            for (int y = top; y < bottom; y++) {
                for (int x = 0; x < width; x++) {
                    int color = scenario.readColor(x, y);

                    if (inner.contains(x / scale, y / scale)) {
                        shownPixels += color == UNEXPLORED_COLOR ? 0 : 1;
                    } else if (!outer.contains(x / scale, y / scale)) {
                        backdropPixels++;
                        blackBackdropPixels += color == UNEXPLORED_COLOR ? 1 : 0;
                        brightBackdropPixels += Math.max(Math.max(color >>> Short.SIZE & CHANNEL_MASK, color >>> Byte.SIZE & CHANNEL_MASK),
                                                         color & CHANNEL_MASK) > BACKDROP_BRIGHTEST ? 1 : 0;
                    }
                }
            }

            Assertions.assertEquals(0, shownPixels, "the unexplored corner of the map shows nothing");
            Assertions.assertTrue(backdropPixels > 0, "the view shows ground beyond the map");
            Assertions.assertEquals(0, blackBackdropPixels, "the ground beyond the map shows the backdrop, not the black of unexplored ground");
            Assertions.assertEquals(0, brightBackdropPixels, "the backdrop stays darker than the ground");

            camera.moveTo(camera.getBoundsLeft(), camera.getBoundsTop());
            scenario.runFrame();
            scenario.saveFrame("corner-" + suffix);

            Path2D.Float outline = createMapOutline(camera, 0);
            int voidPixels = 0;
            int mapPixels = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (outline.contains(x / scale, y / scale)) {
                        mapPixels++;
                    } else {
                        voidPixels++;
                    }
                }
            }

            Assertions.assertTrue(voidPixels <= (voidPixels + mapPixels) * LARGEST_VOID_SHARE,
                                  "the void takes " + voidPixels + " of " + (voidPixels + mapPixels) + " pixels at the map's corner");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 2.0, 1", "1920, 1080, 1.0, 1", "1920, 1080, 2.0, 0", "2560, 1440, 1.0, 0"})
    void demo_game_screen_shows_a_queue_that_waits_for_supply_and_no_queue_region_without_a_queue(int width, int height, float scale,
                                                                                                  int factionIndex, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                                           ManifestException,
                                                                                                                                                           ModException {
        TextureScaleCheck[] check = new TextureScaleCheck[1];

        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height, canvas -> check[0] = new TextureScaleCheck(canvas))) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            ResourceProvider pack = scenario.getMenu().getDemo().getContent().getPack();
            String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName();
            String slot = faction.getDebugName() + '-' + SLOT_PICTURE + (scale > 1 ? DETAIL_SUFFIX : "");

            watch(check[0], pack, slot);
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            GameView view = screen.getView();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit townHall = findTownHall(game, view, person);
            Unit worker = findUnits(game, view, person, UnitTrait.WORKER).getFirst();
            UnitType workerType = view.getUnitView(worker).getType();
            HudBlock selection = engineScreen.getSelectionPanel();
            HudBlock card = engineScreen.getCommandCard();

            Assertions.assertNotNull(workerType);
            engineScreen.getSelection().select(worker);
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();

            Assertions.assertEquals(selection.getX() + selection.getWidth() + GameScreen.SECTION_GAP, card.getX(),
                                    "without a queue the selection panel reaches the command card");

            while (view.getSupplyUsed(person, faction) < view.getSupplyProvided(person, faction)) {
                Assertions.assertNotNull(((StandardProductionSystem) game.getProduction()).createUnit(person, workerType,
                                                                                                      view.getUnitView(townHall).getX(),
                                                                                                      view.getUnitView(townHall).getY(), 0));
            }

            game.getEconomy().setAmount(person, scenario.getMenu().getDemo().getContent().getGold(), GOLD);
            engineScreen.getSelection().select(townHall);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            Key train = engineScreen.getCommandCard().getButtons().getFirst().getHotkey();

            Assertions.assertNotNull(train, "the town hall's first button trains a unit");
            scenario.press(train);
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            ProductionQueuePanel queue = screen.getQueuePanel();

            Assertions.assertEquals(1, game.getProduction().getQueueLength(townHall), "the town hall queues the unit");
            Assertions.assertEquals(1, screen.getConsole().getLines().stream().filter(NO_SUPPLY_MESSAGE::equals).count(),
                                    "the wait shows its message once");

            scenario.runFrame();

            Assertions.assertEquals(queue.getX() + queue.getWidth() + GameScreen.SECTION_GAP, card.getX(), "the queue region is back");

            int slotsWithoutHover = check[0].getDrawCount(slot);
            float[] point = findSlotPoint(queue, 0);

            scenario.runFrame();
            slotsWithoutHover = check[0].getDrawCount(slot) - slotsWithoutHover;

            int drawn = check[0].getDrawCount(slot);

            scenario.getClient().handleInput(InputEvent.createPointerMoved(point[0] * scale, point[1] * scale));
            scenario.runFrame();
            scenario.saveFrame("supply-" + suffix);

            Assertions.assertTrue(check[0].getDrawCount(slot) - drawn > slotsWithoutHover, "the slot's tooltip is drawn in the skin's frame");
            Assertions.assertTrue(check[0].getLargestScaleError(slot) < LARGEST_SCALE_ERROR, "the tooltip frame is drawn at its own resolution");

            CommandButton trainButton = engineScreen.getCommandCard().getButtons().getFirst();
            float[] trainPoint = findButtonPoint(engineScreen.getCommandCard(), trainButton);

            Assertions.assertNull(trainButton.getAction().findRefusal(engineScreen.getTranslator()), "the train button stays enabled at the cap");
            Assertions.assertSame(CommandRefusal.MISSING_SUPPLY, trainButton.getAction().findWarning(engineScreen.getTranslator()),
                                  "the train button's tooltip warns that a further unit waits for supply too");
            scenario.getClient().handleInput(InputEvent.createPointerMoved(trainPoint[0] * scale, trainPoint[1] * scale));
            scenario.runFrame();
            scenario.saveFrame("supply-warning-" + suffix);
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 0", "1920, 1080, 2.0, 1"})
    void demo_game_screen_shows_the_ghost_of_a_waiting_building_and_the_rally_point_of_a_producer(int width, int height, float scale,
                                                                                                  int factionIndex, @TempDir @NotNull Path storage) throws IOException,
                                                                                                                                                           ManifestException,
                                                                                                                                                           ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName();

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameScreen engineScreen = screen.getScreen();
            GameView view = screen.getView();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Unit townHall = findTownHall(game, view, person);
            Unit worker = findUnits(game, view, person, UnitTrait.WORKER).getFirst();
            UnitView hallView = view.getUnitView(townHall);

            game.getEconomy().setAmount(person, scenario.getMenu().getDemo().getContent().getGold(), GOLD);
            engineScreen.getSelection().select(worker);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            CommandButton build = engineScreen.getCommandCard().getButtons().stream()
                                              .filter(button -> button.getAction() instanceof ConstructAction)
                                              .findFirst()
                                              .orElseThrow();
            Key hotkey = build.getHotkey();

            Assertions.assertNotNull(hotkey, "the build button has a hotkey");
            scenario.press(hotkey);

            float[] blocked = toFramePixels(engineScreen, hallView.getX(), hallView.getY(), scale);

            scenario.getClient().handleInput(InputEvent.createPointerMoved(blocked[0], blocked[1]));
            scenario.runFrame();
            scenario.saveFrame("placement-blocked-" + suffix);

            PlacementLayer placement = engineScreen.getPlacement();

            Assertions.assertSame(((ConstructAction) build.getAction()).getBuildingType(), placement.getType(), "the building waits for its site");
            Assertions.assertInstanceOf(UnitSpriteLayer.class, placement.getGhost(), "the building's sprite is its ghost");
            Assertions.assertFalse(placement.isBuildable(), "the site over the town hall is blocked");

            // Left of the town hall on the screen: the same distance down the map's x axis and up its y axis.
            float[] free = toFramePixels(engineScreen, hallView.getX() - SITE_DISTANCE, hallView.getY() + SITE_DISTANCE, scale);

            scenario.getClient().handleInput(InputEvent.createPointerMoved(free[0], free[1]));
            scenario.runFrame();
            scenario.saveFrame("placement-" + suffix);

            Assertions.assertTrue(placement.isBuildable(), "free ground beside the town hall takes the building");

            scenario.press(Key.ESCAPE);
            engineScreen.getSelection().select(townHall);
            scenario.runUpdate(UPDATE_MILLISECONDS);

            Assertions.assertTrue(engineScreen.getCommandCard().getButtons().stream().anyMatch(button -> button.getAction() instanceof RallyAction),
                                  "the town hall's card has a rally button");

            // Right of the town hall on the screen, towards the gold mines.
            float[] rally = toFramePixels(engineScreen, hallView.getX() + SITE_DISTANCE, hallView.getY() - SITE_DISTANCE, scale);

            scenario.click(PointerButton.RIGHT, rally[0], rally[1]);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);
            scenario.runFrame();
            scenario.saveFrame("rally-" + suffix);

            Assertions.assertTrue(hallView.hasRallyPoint(), "a right press sets the town hall's rally point");
            Assertions.assertTrue(scenario.countPixels(RALLY_RGB, RALLY_TOLERANCE) > 0, "the rally line and marker are drawn");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 0", "1920, 1080, 2.0, 1", "640, 360, 1.0, 0"})
    void demo_game_screen_shows_what_a_selected_worker_does_and_the_gold_it_carries(int width, int height, float scale, int factionIndex,
                                                                                    @TempDir @NotNull Path storage) throws IOException,
                                                                                                                           ManifestException,
                                                                                                                           ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);
            String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName();

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameView view = screen.getView();
            StandardHarvestSystem harvest = (StandardHarvestSystem) game.getModSystem(HarvestSystem.KEY);
            Unit worker = findUnits(game, view, game.getPlayer(DemoGames.FIRST_SLOT), UnitTrait.WORKER).getFirst();
            Unit mine = harvest.findResourceContainer(null, view.getUnitView(worker).getX(), view.getUnitView(worker).getY(), MINE_RANGE);

            Assertions.assertNotNull(mine);
            worker.setOrderType(gameScene.getDemo().getContent().getOrders().getGather());
            worker.setOrderTargetUnit(mine);
            screen.getScreen().getSelection().select(worker);

            for (int update = 0; update < GATHER_UPDATES && harvest.getCarriedAmount(worker) == 0; update++) {
                scenario.runUpdate(UPDATE_MILLISECONDS);
            }

            scenario.runFrame();
            scenario.saveFrame("activity-" + suffix);

            UnitView workerView = view.getUnitView(worker);
            OrderTexts texts = screen.getScreen().getSelectionPanel().getOrderTexts();

            Assertions.assertNotNull(texts);
            Assertions.assertTrue(workerView.getCarriedAmount() > 0, "the worker carries gold");
            Assertions.assertNotNull(texts.findText(workerView.getOrderType()), "the worker's order has a text: " + workerView.getOrderType());

            screen.getScreen().getSelection().select(mine);
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
            scenario.saveFrame("activity-mine-" + suffix);

            Assertions.assertTrue(view.getUnitView(mine).getResourceAmount() > 0, "the mine shows the gold it holds");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 0", "1920, 1080, 2.0, 1"})
    void demo_game_screen_counts_the_kills_of_a_selected_fighter(int width, int height, float scale, int factionIndex,
                                                                 @TempDir @NotNull Path storage) throws IOException,
                                                                                                        ManifestException,
                                                                                                        ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            Faction faction = scenario.getMenu().getDemo().getContent().getFactions().get(factionIndex);

            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            GameView view = screen.getView();
            Player person = game.getPlayer(DemoGames.FIRST_SLOT);
            Player computer = game.getPlayer(DemoGames.SECOND_SLOT);
            Unit townHall = findTownHall(game, view, person);
            UnitType fighterType = findFighterType(game, faction);
            StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
            Unit fighter = production.createUnit(person, fighterType, view.getUnitView(townHall).getX(), view.getUnitView(townHall).getY(), 0);
            Unit victim = findUnits(game, view, computer, UnitTrait.WORKER).getFirst();

            Assertions.assertNotNull(fighter);
            game.getCombat().dealDamage(victim, victim.getHitPoints(), fighter, 0);
            screen.getScreen().getSelection().select(fighter);
            scenario.runUpdate(UPDATE_MILLISECONDS);
            scenario.runFrame();
            scenario.saveFrame("kills-" + width + "x" + height + "-" + Math.round(scale * PERCENT) + "-" + faction.getDebugName());

            Assertions.assertEquals(1, view.getUnitView(fighter).getKillCount(), "the fighter counts its kill");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 0", "1280, 720, 2.0, 1"})
    void demo_game_screen_pings_the_minimap_where_a_unit_of_the_person_takes_damage(int width, int height, float scale, int factionIndex,
                                                                                    @TempDir @NotNull Path storage) throws IOException,
                                                                                                                           ManifestException,
                                                                                                                           ModException {
        try (DemoClientScenario scenario = new DemoClientScenario(storage, width, height)) {
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));
            startGame(scenario, factionIndex);

            DemoGameScene gameScene = requireGameScene(scenario);
            Game game = gameScene.getGame();
            DemoGameScreen screen = gameScene.getScreen();

            Assertions.assertNotNull(game);
            Assertions.assertNotNull(screen);

            Unit worker = findUnits(game, screen.getView(), game.getPlayer(DemoGames.FIRST_SLOT), UnitTrait.WORKER).getFirst();

            scenario.runUpdate(UPDATE_MILLISECONDS);

            Assertions.assertFalse(screen.getScreen().getMinimap().getPings().isAnyActive(), "no ping before the damage");

            worker.setHitPoints(worker.getHitPoints() / 2);
            scenario.runUpdate(DemoGameScene.LOOP_MILLISECONDS);

            Assertions.assertTrue(screen.getScreen().getMinimap().getPings().isAnyActive(), "the damage pings the minimap");
        }
    }
}
