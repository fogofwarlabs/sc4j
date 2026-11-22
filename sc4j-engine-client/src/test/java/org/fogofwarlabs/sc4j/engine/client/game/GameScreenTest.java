package org.fogofwarlabs.sc4j.engine.client.game;

import org.fogofwarlabs.sc4j.engine.client.HiddenWindowScenario;
import org.fogofwarlabs.sc4j.engine.client.SkirmishSessionScenario;
import org.fogofwarlabs.sc4j.engine.client.command.CommandRefusal;
import org.fogofwarlabs.sc4j.engine.client.command.ConstructAction;
import org.fogofwarlabs.sc4j.engine.client.command.OrderAction;
import org.fogofwarlabs.sc4j.engine.client.command.TrainAction;
import org.fogofwarlabs.sc4j.engine.client.console.ConsoleCommandRegistry;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.hud.CommandButton;
import org.fogofwarlabs.sc4j.engine.client.hud.HudBlock;
import org.fogofwarlabs.sc4j.engine.client.hud.HudListener;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.gl.GlCanvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.BitmapFont;
import org.fogofwarlabs.sc4j.engine.client.text.TextColorHook;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.client.world.Camera;
import org.fogofwarlabs.sc4j.engine.client.world.ContinuousScrollRate;
import org.fogofwarlabs.sc4j.engine.client.world.PlacementLayer;
import org.fogofwarlabs.sc4j.engine.client.world.TerrainColors;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.order.IssueOrderCommandType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ConstructBuildingCommandType;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.TrainUnitCommandType;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests the engine-only client on the skirmish map without installed game data, the base of an engine-only mod's client: the default game screen
 * on a hidden window selects with the pointer and issues commands from the command card, the hotkeys, the world view and the minimap through the
 * local command queue. Drawn on its own, the screen lays out in the logical units of the frame's surface, a press on a button and a refused action
 * reach the HUD listener, and its console lays its sections out a gap apart at any size, with a hosted queue panel between the selection panel and
 * the command card.
 */
final class GameScreenTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int FIELD_PAGE_SIZE = 64;
    private static final int GLYPH_SIZE = 4;
    private static final int GLYPH_ADVANCE = 5;
    private static final int LINE_HEIGHT = 6;
    private static final char FIRST_GLYPH = '!';
    private static final char LAST_GLYPH = '~';
    private static final int FRAMES = 8;
    private static final int COMMAND_ID = 0;
    private static final int SCOUT_X = 400;
    private static final int SCOUT_Y = 144;
    private static final int GROUND_X = 500;
    private static final int GROUND_Y = 200;
    private static final int DISPLAY_SCALE = 2;
    private static final long KEY_SCROLL_MILLISECONDS = 100;
    private static final float MILLISECONDS_PER_SECOND = 1000;
    /**
     * The position of the first command card button: left of the window's right edge and below the top of the HUD panel.
     */
    private static final int COMMAND_BUTTON_RIGHT_OFFSET = 200;
    private static final int COMMAND_BUTTON_TOP_OFFSET = 20;

    private static final float QUEUE_PANEL_WIDTH = 260;
    private static final int GOLD = 500;
    private static final int TRAIN_ID = 1;
    private static final int KEEP_X = 300;
    private static final int KEEP_Y = 300;
    private static final float COMMAND_CARD_SHARE = 3;
    private static final int CONSTRUCT_ID = 2;
    /**
     * A site of free ground away from the keep and the scout.
     */
    private static final int FREE_SITE_X = 560;
    private static final int FREE_SITE_Y = 420;
    private static final @NotNull TextColorHook NO_COLOR_CODES = new TextColorHook() {
        @Override
        public boolean isColorCode(int codePoint) {
            return false;
        }

        @Override
        public int getColor(int codePoint, int lineColor, int currentColor, int previousColor) {
            return currentColor;
        }
    };

    /**
     * Counts the button presses and keeps the refusal messages and the selections the screen reports.
     */
    private static final class RecordingListener implements HudListener {
        private final @NotNull List<@NotNull String> messages;
        private final @NotNull List<@NotNull List<@NotNull Unit>> selections;
        private int pressCount;

        private RecordingListener() {
            this.messages   = new ArrayList<>();
            this.selections = new ArrayList<>();
            this.pressCount = 0;
        }

        @Override
        public void onButtonPressed() {
            this.pressCount++;
        }

        @Override
        public void onUnitsSelected(@NotNull List<@NotNull Unit> units) {
            this.selections.add(List.copyOf(units));
        }

        @Override
        public void onCommandRefused(@NotNull CommandRefusal refusal, @NotNull String message) {
            this.messages.add(message);
        }
    }

    /**
     * Counts the drawings and the pointer presses of a hosted queue panel.
     */
    private static final class CountingPanel extends HudBlock {
        private int drawCount;
        private int pressCount;

        private CountingPanel() {
            this.drawCount  = 0;
            this.pressCount = 0;
        }

        /**
         * Draws the block: counts the drawing.
         *
         * @param canvas the canvas, between its begin and end
         */
        @Override
        public void draw(@NotNull Canvas canvas) {
            this.drawCount++;
        }

        /**
         * Handles an input event: counts and takes a pointer press inside the rectangle.
         *
         * @param event the event
         * @return {@code true} if the block took the event, {@code false} otherwise
         */
        @Override
        public boolean handleInput(@NotNull InputEvent event) {
            if (event.getType() == InputEvent.Type.BUTTON_PRESSED && this.contains(event.getX(), event.getY())) {
                this.pressCount++;

                return true;
            }

            return false;
        }
    }

    /**
     * Creates a {@link FontFace} instance whose printable ASCII glyphs are all one filled square.
     *
     * @return the created {@link FontFace} instance
     */
    private static @NotNull FontFace createFace() {
        byte[] alphas = new byte[GLYPH_SIZE * GLYPH_SIZE];
        List<FontGlyph> glyphs = new ArrayList<>();

        Arrays.fill(alphas, (byte) -1);
        glyphs.add(new FontGlyph(' ', GLYPH_ADVANCE, 0, 0, 0, 0, 0, 0));

        for (char character = FIRST_GLYPH; character <= LAST_GLYPH; character++) {
            glyphs.add(new FontGlyph(character, GLYPH_ADVANCE, 0, 0, GLYPH_SIZE, GLYPH_SIZE, 0, 0));
        }

        return new FontFace(LINE_HEIGHT, GLYPH_SIZE, PixelLayer.ofAlpha(alphas), glyphs);
    }

    private static void draw(@NotNull GameScreen screen) {
        try {
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));
        } catch (IOException exception) {
            Assertions.fail("A world layer cannot read a resource.", exception);
        }
    }

    private static void click(@NotNull GameScreen screen, @NotNull PointerButton button, float x, float y) {
        screen.handleInput(InputEvent.createButtonPressed(button, 0, x, y, false));
        screen.handleInput(InputEvent.createButtonReleased(button, 0, x, y));
    }

    @Test
    void game_screen_draws_the_skirmish_and_issues_commands_from_the_hud_and_the_world() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        GameSession session = skirmish.getSession();
        GameView view = session.getView();
        LocalCommandQueue queue = (LocalCommandQueue) session.getCommandSink();

        Assertions.assertNotNull(queue);

        skirmish.runFrames(FRAMES);

        Unit scout = view.findUnitAt(SCOUT_X, SCOUT_Y);

        Assertions.assertNotNull(scout);

        UnitView scoutView = view.getUnitView(scout);
        OrderAction move = new OrderAction(session.getGame(), new IssueOrderCommandType(COMMAND_ID, 0), skirmish.getScenario().getMove(), true);

        HiddenWindowScenario.run(WIDTH, HEIGHT, () -> {
            try (GlCanvas canvas = new GlCanvas(FIELD_PAGE_SIZE);
                 BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
                 GameScreen screen = new GameScreen(session, canvas, font, new SquareProjection(), new TerrainColors(), ContentType::getDebugName,
                                                    "supply", ContentType::getDebugName)) {
                ConsoleCommandRegistry registry = new ConsoleCommandRegistry(Integer.MAX_VALUE);

                screen.registerCommands(registry);
                screen.setCommands(List.of(new CommandButton("Move", Key.M, move)), move);
                draw(screen);
                click(screen, PointerButton.LEFT, SCOUT_X, SCOUT_Y);

                Assertions.assertEquals(List.of(scout), screen.getSelection().getUnits());

                draw(screen);

                float buttonX = WIDTH - COMMAND_BUTTON_RIGHT_OFFSET;
                float buttonY = HEIGHT - GameScreen.PANEL_HEIGHT + COMMAND_BUTTON_TOP_OFFSET;

                click(screen, PointerButton.LEFT, buttonX, buttonY);

                Assertions.assertSame(move, screen.getTranslator().getPendingAction(), "the command card button waits for a target");

                click(screen, PointerButton.LEFT, GROUND_X, GROUND_Y);

                Assertions.assertNull(screen.getTranslator().getPendingAction());
                Assertions.assertEquals(1, queue.getQueuedCount(), "a left press on the map issues the pending move");

                screen.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.M, 0));

                Assertions.assertSame(move, screen.getTranslator().getPendingAction(), "the hotkey runs the button's action");

                screen.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.ESCAPE, 0));

                Assertions.assertNull(screen.getTranslator().getPendingAction());

                click(screen, PointerButton.RIGHT, GameScreen.PANEL_HEIGHT / 2f, HEIGHT - GameScreen.PANEL_HEIGHT / 2f);

                Assertions.assertEquals(2, queue.getQueuedCount(), "a right press on the minimap issues the default action");

                click(screen, PointerButton.LEFT, GameScreen.PANEL_HEIGHT / 2f, HEIGHT - GameScreen.PANEL_HEIGHT / 2f);

                Assertions.assertEquals(view.getMapWidth() / 2f, screen.getCamera().getX() + screen.getCamera().getViewWidth() / 2, 1,
                                        "a left press on the minimap centres the camera there");
                Assertions.assertEquals(List.of(scout), screen.getSelection().getUnits(), "presses on the HUD do not select");

                float cameraX = screen.getCamera().getX();

                Assertions.assertTrue(screen.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.RIGHT, 0)));

                screen.update(KEY_SCROLL_MILLISECONDS);

                Assertions.assertEquals(cameraX + ContinuousScrollRate.DEFAULT_KEY_SPEED * KEY_SCROLL_MILLISECONDS / MILLISECONDS_PER_SECOND,
                                        screen.getCamera().getX(), 1, "a held arrow key scrolls the camera");

                screen.handleInput(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, Key.RIGHT, 0));
                cameraX = screen.getCamera().getX();
                screen.update(KEY_SCROLL_MILLISECONDS);

                Assertions.assertEquals(cameraX, screen.getCamera().getX(), 1, "a released arrow key stops the scroll");

                Assertions.assertEquals("Zoom 150%, limits 50% to 200%.", registry.run("/zoom 150"));

                draw(screen);
            }
        });

        session.runFrame();

        Assertions.assertEquals(0, queue.getQueuedCount());
        Assertions.assertSame(skirmish.getScenario().getMove(), scoutView.getOrderType());
    }

    @Test
    void draw_should_lay_the_screen_out_in_the_logical_units_of_the_surface() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        ImageCanvas canvas = new ImageCanvas(WIDTH * DISPLAY_SCALE, HEIGHT * DISPLAY_SCALE);

        skirmish.runFrames(FRAMES);

        try (BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
             GameScreen screen = new GameScreen(skirmish.getSession(), canvas, font, new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            screen.draw(UiSurface.of(WIDTH * DISPLAY_SCALE, HEIGHT * DISPLAY_SCALE, DisplayScale.of(DISPLAY_SCALE, 1)));

            Assertions.assertEquals(WIDTH, screen.getCamera().getScreenWidth());
            Assertions.assertEquals(HEIGHT, screen.getCamera().getScreenHeight());
            Assertions.assertSame(screen.getScrollRate(), screen.getCameraScroll().getRate(), "the camera scroll runs at the screen's rate");
            Assertions.assertEquals(HEIGHT - GameScreen.PANEL_HEIGHT + GameScreen.SECTION_GAP, screen.getMinimap().getY());
            Assertions.assertEquals(GameScreen.PANEL_HEIGHT - 2 * GameScreen.SECTION_GAP, screen.getMinimap().getWidth());
        }
    }

    @ParameterizedTest
    @CsvSource({"1440, 160", "1080, 160", "720, 160", "540, 160", "480, 144", "360, 108"})
    void get_console_height_should_keep_the_panel_height_and_take_at_most_its_share_of_a_low_screen(int height, int consoleHeight) {
        Assertions.assertEquals(consoleHeight, GameScreen.getConsoleHeight(height));
    }

    @ParameterizedTest
    @CsvSource({"640, 360", "1280, 720", "1920, 1080", "2560, 1440"})
    void set_queue_panel_should_host_the_panel_between_the_selection_panel_and_the_command_card(int width, int height) throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        ImageCanvas canvas = new ImageCanvas(width, height);
        CountingPanel panel = new CountingPanel();

        try (BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
             GameScreen screen = new GameScreen(skirmish.getSession(), canvas, font, new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            HudBlock[] sections = {screen.getMinimap(), screen.getSelectionPanel(), panel, screen.getCommandCard()};
            float sectionTop = height - GameScreen.getConsoleHeight(height) + GameScreen.SECTION_GAP;

            screen.setQueuePanel(panel, QUEUE_PANEL_WIDTH);
            screen.draw(UiSurface.of(width, height, DisplayScale.ONE));

            Assertions.assertSame(panel, screen.getQueuePanel());
            Assertions.assertEquals(1, panel.drawCount, "the console draws the hosted panel");
            Assertions.assertSame(screen.getCommandCard().getSkin(), panel.getSkin(), "the panel wears the HUD skin");
            Assertions.assertEquals(0, screen.getConsolePlate().getX());
            Assertions.assertEquals(height - GameScreen.getConsoleHeight(height), screen.getConsolePlate().getY());
            Assertions.assertEquals(width, screen.getConsolePlate().getWidth(), "the console spans the whole width");
            Assertions.assertEquals(GameScreen.SECTION_GAP, sections[0].getX());
            Assertions.assertEquals(width - GameScreen.SECTION_GAP, screen.getCommandCard().getX() + screen.getCommandCard().getWidth());

            for (int index = 0; index < sections.length; index++) {
                Assertions.assertEquals(sectionTop, sections[index].getY(), "section " + index + " stands a gap inside the console");
                Assertions.assertTrue(sections[index].getWidth() > 0, "section " + index + " has room");

                if (index > 0) {
                    float previousRight = sections[index - 1].getX() + sections[index - 1].getWidth();

                    Assertions.assertEquals(previousRight + GameScreen.SECTION_GAP, sections[index].getX(),
                                            "section " + index + " stands a gap right of the one before");
                }
            }

            float cardRoom = width - sections[0].getWidth() - 4 * GameScreen.SECTION_GAP;

            Assertions.assertTrue(screen.getCommandCard().getWidth() <= cardRoom / COMMAND_CARD_SHARE,
                                  "the command card takes at most a third of the room right of the minimap");
            Assertions.assertTrue(panel.getWidth() <= QUEUE_PANEL_WIDTH, "the panel is at most as wide as asked");
            Assertions.assertTrue(panel.getWidth() <= screen.getSelectionPanel().getWidth() + GameScreen.SECTION_GAP,
                                  "the panel takes at most half the room between the minimap and the command card");

            click(screen, PointerButton.LEFT, panel.getX() + panel.getWidth() / 2, panel.getY() + panel.getHeight() / 2);

            Assertions.assertEquals(1, panel.pressCount, "a press on the panel goes to the panel");
            Assertions.assertTrue(screen.getSelection().isEmpty());

            float selectionWidth = screen.getSelectionPanel().getWidth();

            screen.setQueuePanel(null, QUEUE_PANEL_WIDTH);
            screen.draw(UiSurface.of(width, height, DisplayScale.ONE));

            Assertions.assertNull(screen.getQueuePanel());
            Assertions.assertEquals(1, panel.drawCount, "a removed panel is not drawn");
            Assertions.assertEquals(selectionWidth + panel.getWidth() + GameScreen.SECTION_GAP, screen.getSelectionPanel().getWidth(),
                                    "the selection panel takes the region back");
        }
    }

    @Test
    void game_screen_reports_each_selection_by_the_player_but_none_by_code() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        GameSession session = skirmish.getSession();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) session.getGame().getProduction();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);
        RecordingListener listener = new RecordingListener();

        Assertions.assertNotNull(player);

        Unit keep = production.createUnit(player, skirmish.getScenario().getKeep(), KEEP_X, KEEP_Y, 0);

        Assertions.assertNotNull(keep);

        skirmish.runFrames(FRAMES);

        try (BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
             GameScreen screen = new GameScreen(session, canvas, font, new SquareProjection(), new TerrainColors(), ContentType::getDebugName,
                                                "supply", ContentType::getDebugName)) {
            screen.setListener(listener);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));
            screen.getSelection().select(keep);
            screen.getSelection().clear();

            Assertions.assertTrue(listener.selections.isEmpty(), "a selection changed by code is not reported");

            UnitView keepView = session.getView().getUnitView(keep);
            float keepX = keepView.getX() - screen.getCamera().getX();
            float keepY = keepView.getY() - screen.getCamera().getY();

            click(screen, PointerButton.LEFT, keepX, keepY);

            Assertions.assertEquals(List.of(List.of(keep)), listener.selections, "the click on the keep selects it");

            click(screen, PointerButton.LEFT, keepX, keepY);

            Assertions.assertEquals(List.of(List.of(keep), List.of(keep)), listener.selections, "each click on the keep is reported");

            screen.handleInput(InputEvent.createPointerMoved(keepX + 1, keepY));

            Assertions.assertEquals(2, listener.selections.size(), "a pointer move selects nothing");
        }
    }

    @Test
    void game_screen_reports_button_presses_and_refusals() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        GameSession session = skirmish.getSession();
        Game game = session.getGame();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);
        RecordingListener listener = new RecordingListener();

        Assertions.assertNotNull(player);

        Unit keep = production.createUnit(player, skirmish.getScenario().getKeep(), KEEP_X, KEEP_Y, 0);
        TrainAction train = new TrainAction(game, new TrainUnitCommandType(TRAIN_ID), skirmish.getScenario().getKeeper());

        Assertions.assertNotNull(keep);

        try (BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
             GameScreen screen = new GameScreen(session, canvas, font, new SquareProjection(), new TerrainColors(), ContentType::getDebugName,
                                                "supply", ContentType::getDebugName)) {
            screen.setListener(listener);
            screen.setCommands(List.of(new CommandButton("Keeper", Key.K, train)), null);
            screen.getSelection().select(keep);
            game.getEconomy().setAmount(player, skirmish.getScenario().getGold(), 0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));
            click(screen, PointerButton.LEFT, WIDTH - COMMAND_BUTTON_RIGHT_OFFSET, HEIGHT - GameScreen.PANEL_HEIGHT + COMMAND_BUTTON_TOP_OFFSET);
            screen.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.K, 0));

            Assertions.assertEquals(List.of("Not enough gold.", "Not enough gold."), listener.messages, "the press and the hotkey are refused");
            Assertions.assertEquals(0, listener.pressCount, "a refused press is no button press");
            Assertions.assertEquals(0, production.getQueueLength(keep));

            game.getEconomy().setAmount(player, skirmish.getScenario().getGold(), GOLD);
            click(screen, PointerButton.LEFT, WIDTH - COMMAND_BUTTON_RIGHT_OFFSET, HEIGHT - GameScreen.PANEL_HEIGHT + COMMAND_BUTTON_TOP_OFFSET);

            Assertions.assertEquals(1, listener.pressCount, "the press runs the button");
            Assertions.assertEquals(2, listener.messages.size());
        }
    }

    @Test
    void game_screen_shows_the_ghost_of_a_waiting_building_red_over_a_building_and_green_on_free_ground() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        GameSession session = skirmish.getSession();
        Game game = session.getGame();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        UnitType keepType = skirmish.getScenario().getKeep();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);
        List<String> ghosts = new ArrayList<>();

        Assertions.assertNotNull(player);

        Unit keep = production.createUnit(player, keepType, KEEP_X, KEEP_Y, 0);
        Unit worker = production.createUnit(player, skirmish.getScenario().getKeeper(), GROUND_X, GROUND_Y, 0);
        ConstructAction construct = new ConstructAction(game, new ConstructBuildingCommandType(CONSTRUCT_ID, 0), keepType,
                                                        production.getFootprintWidth(keepType), production.getFootprintHeight(keepType));

        Assertions.assertNotNull(keep);
        Assertions.assertNotNull(worker);

        game.getEconomy().setAmount(player, skirmish.getScenario().getGold(), GOLD);
        // A frame lets the player see the new units.
        session.runFrame();

        try (BitmapFont font = new BitmapFont(canvas, createFace(), NO_COLOR_CODES);
             GameScreen screen = new GameScreen(session, canvas, font, new SquareProjection(), new TerrainColors(), ContentType::getDebugName,
                                                "supply", ContentType::getDebugName)) {
            PlacementLayer placement = screen.getPlacement();

            placement.setGhost((viewport, type, x, y, color) -> ghosts.add(type.getDebugName() + " " + Integer.toHexString(color)));
            screen.getSelection().select(worker);
            screen.getTranslator().run(construct, false);
            screen.update(0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));
            ghosts.clear();

            Camera camera = screen.getCamera();

            // The keep stands at the free position nearest to its point.
            screen.handleInput(InputEvent.createPointerMoved((keep.getPositionX() - camera.getX()) * camera.getZoom(),
                                                             (keep.getPositionY() - camera.getY()) * camera.getZoom()));
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertSame(keepType, placement.getType(), "the waiting construction shows the building's footprint");
            Assertions.assertFalse(placement.isBuildable(), "a site over the standing keep is blocked");
            Assertions.assertEquals(List.of(keepType.getDebugName() + " " + Integer.toHexString(PlacementLayer.BLOCKED_GHOST_COLOR)), ghosts);

            ghosts.clear();
            screen.handleInput(InputEvent.createPointerMoved((FREE_SITE_X - camera.getX()) * camera.getZoom(),
                                                             (FREE_SITE_Y - camera.getY()) * camera.getZoom()));
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertTrue(placement.isBuildable(), "free buildable ground takes the building");
            Assertions.assertEquals(List.of(keepType.getDebugName() + " " + Integer.toHexString(PlacementLayer.GHOST_COLOR)), ghosts);
        }
    }
}
