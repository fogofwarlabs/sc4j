package org.fogofwarlabs.sc4j.engine.client.hud;

import org.fogofwarlabs.sc4j.engine.client.SkirmishSessionScenario;
import org.fogofwarlabs.sc4j.engine.client.command.CancelAction;
import org.fogofwarlabs.sc4j.engine.client.command.CommandRefusal;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.game.GameScreen;
import org.fogofwarlabs.sc4j.engine.client.game.GameSession;
import org.fogofwarlabs.sc4j.engine.client.game.LocalCommandQueue;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.client.world.TerrainColors;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.SkirmishScenario;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.CancelProductionCommandType;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the queue panel hosted in the engine game screen's console: it shows the queue of the one selected producer of the local player, a
 * press on a filled slot cancels that entry through the game's command queue (the entries behind it move up and the cost comes back), a press
 * beside the filled slots cancels nothing, and with no producer selected it takes no press and the console draws no queue region. In a narrow
 * region the slots wrap into rows of readable slots, and a building under construction shows one slot of the queue slots' size. When a front
 * entry waits for supply, the screen tells its listener once.
 */
final class ProductionQueuePanelTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int LINE_HEIGHT = 6;
    private static final int CANCEL_ID = 3;
    private static final int KEEP_X = 300;
    private static final int KEEP_Y = 300;
    private static final int GOLD = 500;
    private static final int BUILDING_GOLD = 2000;
    private static final int KEEPER_COST = 50;
    private static final @NotNull String SUPPLY_MESSAGE = "Not enough supply.";
    private static final float HALF_DIVISOR = 2;
    private static final float PANEL_WIDTH = 268;
    private static final float NARROW_PANEL_WIDTH = 120;
    /**
     * The smallest side of a queue slot in logical units, at which its unit icon still reads.
     */
    private static final float SMALLEST_SLOT = 36;
    private static final int SITE_TRIES = 16;

    /**
     * Keeps the messages of the producers that start waiting for supply.
     */
    private static final class WaitingListener implements HudListener {
        private final @NotNull List<@NotNull String> messages;

        private WaitingListener() {
            this.messages = new ArrayList<>();
        }

        @Override
        public void onProductionWaiting(@NotNull Unit producer, @NotNull CommandRefusal refusal, @NotNull String message) {
            this.messages.add(message);
        }
    }

    /**
     * Measures nothing and draws nothing.
     */
    private static final class EmptyFont implements Font {
        @Override
        public int getLineHeight() {
            return LINE_HEIGHT;
        }

        @Override
        public @NotNull TextStyle getDefaultStyle() {
            return TextStyle.DEFAULT;
        }

        @Override
        public float measure(@NotNull CharSequence text, @NotNull TextStyle style) {
            return 0;
        }

        @Override
        public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
            return x;
        }
    }

    private static void press(@NotNull GameScreen screen, float x, float y) {
        screen.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, x, y, false));
        screen.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, 0, x, y));
    }

    /**
     * Finds the box of a filled slot by searching the panel for the slot under the pointer.
     *
     * @return the left, top, right and bottom logical position inside the slot
     */
    private static float @NotNull [] findSlot(@NotNull ProductionQueuePanel panel, int slot) {
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -1, -1};

        for (float y = panel.getY(); y < panel.getY() + panel.getHeight(); y++) {
            for (float x = panel.getX(); x < panel.getX() + panel.getWidth(); x++) {
                if (panel.findSlotAt(x, y) == slot) {
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.max(box[2], x);
                    box[3] = Math.max(box[3], y);
                }
            }
        }

        Assertions.assertTrue(box[2] >= 0, "slot " + slot + " is shown");

        return box;
    }

    /**
     * Places a keep under construction on the first free site on a diagonal away from the worker.
     */
    private static @NotNull Unit placeBuilding(@NotNull StandardProductionSystem production, @NotNull Unit worker,
                                               @NotNull SkirmishScenario content) {
        for (int step = 1; step <= SITE_TRIES; step++) {
            int offset = step * SkirmishScenario.TILE_SIZE;
            Unit building = production.placeBuilding(worker, content.getKeep(), KEEP_X + offset, KEEP_Y + offset, 0);

            if (building != null) {
                return building;
            }
        }

        return Assertions.fail("No free site for the keep.");
    }

    @Test
    void a_press_on_a_filled_slot_of_the_hosted_panel_cancels_that_entry() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        SkirmishScenario content = skirmish.getScenario();
        GameSession session = skirmish.getSession();
        Game game = session.getGame();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        LocalCommandQueue queue = (LocalCommandQueue) session.getCommandSink();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);

        Assertions.assertNotNull(player);
        Assertions.assertNotNull(queue);

        Unit keep = production.createUnit(player, content.getKeep(), KEEP_X, KEEP_Y, 0);

        Assertions.assertNotNull(keep);

        game.getEconomy().setAmount(player, content.getGold(), GOLD);
        production.enqueueUnit(keep, content.getKeeper());
        production.enqueueUnit(keep, content.getKeeper());
        production.enqueueUnit(keep, content.getKeeper());

        try (GameScreen screen = new GameScreen(session, canvas, new EmptyFont(), new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            ProductionQueuePanel panel = new ProductionQueuePanel(session.getView(), screen.getSelection(), screen.getTranslator(), new EmptyFont(),
                                                                  ContentType::getDebugName, "supply");

            CancelAction cancel = new CancelAction(game, new CancelProductionCommandType(CANCEL_ID, 0));

            screen.setQueuePanel(panel, PANEL_WIDTH);
            panel.setCancelAction(cancel);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertSame(cancel, panel.getCancelAction());

            Assertions.assertNull(panel.findShownUnit(), "nothing is selected");
            Assertions.assertFalse(panel.isShown());
            Assertions.assertEquals(0, panel.getWidth(), "the console has no queue region");
            Assertions.assertEquals(screen.getSelectionPanel().getX() + screen.getSelectionPanel().getWidth() + GameScreen.SECTION_GAP,
                                    screen.getCommandCard().getX(), "the selection panel takes the queue's region");

            screen.getSelection().select(keep);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            UnitView shown = panel.findShownUnit();

            Assertions.assertNotNull(shown);
            Assertions.assertSame(keep, shown.getUnit());

            float[] firstSlot = findSlot(panel, 0);
            float[] secondSlot = findSlot(panel, 1);
            float besideX = panel.getX() + panel.getWidth() - 1;
            float besideY = panel.getY() + panel.getHeight() - 1;
            float secondX = (secondSlot[0] + secondSlot[2]) / HALF_DIVISOR;
            float secondY = (secondSlot[1] + secondSlot[3]) / HALF_DIVISOR;

            Assertions.assertTrue(firstSlot[0] < secondSlot[0] && firstSlot[1] == secondSlot[1], "the slots stand in a row from the left");
            Assertions.assertEquals(-1, panel.findSlotAt(besideX, besideY));

            press(screen, besideX, besideY);

            Assertions.assertEquals(0, queue.getQueuedCount(), "a press beside the filled slots cancels nothing");

            press(screen, secondX, secondY);

            Assertions.assertEquals(1, queue.getQueuedCount(), "the press on the second slot submits its cancel");

            session.runFrame();

            Assertions.assertEquals(2, production.getQueueLength(keep));
            Assertions.assertEquals(GOLD - 2 * KEEPER_COST, game.getEconomy().getAmount(player, content.getGold()), "the full cost comes back");

            screen.getSelection().clear();

            Assertions.assertNull(panel.findShownUnit());
            Assertions.assertFalse(panel.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, secondX, secondY, false)),
                                   "a hidden panel takes no press");
        }
    }

    @Test
    void a_narrow_panel_wraps_its_slots_into_rows_and_a_construction_slot_has_the_queue_slot_size() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        SkirmishScenario content = skirmish.getScenario();
        GameSession session = skirmish.getSession();
        Game game = session.getGame();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);

        Assertions.assertNotNull(player);

        Unit keep = production.createUnit(player, content.getKeep(), KEEP_X, KEEP_Y, 0);
        Unit worker = production.createUnit(player, content.getKeeper(), KEEP_X, KEEP_Y, 0);

        Assertions.assertNotNull(keep);
        Assertions.assertNotNull(worker);

        game.getEconomy().setAmount(player, content.getGold(), GOLD);
        production.enqueueUnit(keep, content.getKeeper());
        production.enqueueUnit(keep, content.getKeeper());
        production.enqueueUnit(keep, content.getKeeper());
        game.getEconomy().setAmount(player, content.getGold(), BUILDING_GOLD);

        Unit building = placeBuilding(production, worker, content);

        try (GameScreen screen = new GameScreen(session, canvas, new EmptyFont(), new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            ProductionQueuePanel panel = new ProductionQueuePanel(session.getView(), screen.getSelection(), screen.getTranslator(), new EmptyFont(),
                                                                  ContentType::getDebugName, "supply");

            screen.setQueuePanel(panel, NARROW_PANEL_WIDTH);
            screen.getSelection().select(keep);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            float[] firstSlot = findSlot(panel, 0);
            float[] thirdSlot = findSlot(panel, 2);
            float queueSlot = firstSlot[2] - firstSlot[0];

            Assertions.assertTrue(queueSlot >= SMALLEST_SLOT, "a queue slot is " + queueSlot + " wide in a narrow panel");
            Assertions.assertTrue(thirdSlot[1] > firstSlot[3], "the slots wrap into rows");

            screen.getSelection().select(building);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            float[] constructionSlot = findSlot(panel, 0);

            Assertions.assertEquals(queueSlot, constructionSlot[2] - constructionSlot[0], "the construction slot has the queue slot size");
        }
    }

    @Test
    void a_front_entry_that_waits_for_supply_is_told_once() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        SkirmishScenario content = skirmish.getScenario();
        GameSession session = skirmish.getSession();
        Game game = session.getGame();
        Player player = session.getLocalPlayer();
        StandardProductionSystem production = (StandardProductionSystem) game.getProduction();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);
        WaitingListener listener = new WaitingListener();

        Assertions.assertNotNull(player);

        Faction faction = game.getContent().getUnitTypes().getFaction(content.getKeeper());
        Unit keep = production.createUnit(player, content.getKeep(), KEEP_X, KEEP_Y, 0);

        Assertions.assertNotNull(faction);
        Assertions.assertNotNull(keep);

        while (game.getEconomy().getSupplyUsed(player, faction) < game.getEconomy().getSupplyProvided(player, faction)) {
            Assertions.assertNotNull(production.createUnit(player, content.getKeeper(), KEEP_X, KEEP_Y, 0));
        }

        game.getEconomy().setAmount(player, content.getGold(), GOLD);

        try (GameScreen screen = new GameScreen(session, canvas, new EmptyFont(), new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            ProductionQueuePanel panel = new ProductionQueuePanel(session.getView(), screen.getSelection(), screen.getTranslator(), new EmptyFont(),
                                                                  ContentType::getDebugName, "supply");

            screen.setQueuePanel(panel, PANEL_WIDTH);
            screen.setListener(listener);
            screen.getSelection().select(keep);
            production.enqueueUnit(keep, content.getKeeper());
            session.runFrame();
            screen.update(0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertTrue(session.getView().getUnitView(keep).isWaitingForSupply());
            Assertions.assertEquals(List.of(SUPPLY_MESSAGE), listener.messages, "the wait is told");

            session.runFrame();
            screen.update(0);

            Assertions.assertEquals(List.of(SUPPLY_MESSAGE), listener.messages, "the same wait is told once");

            game.getEconomy().setAmount(player, content.getGold(), GOLD);
            Assertions.assertNotNull(production.createUnit(player, content.getKeep(), KEEP_X, KEEP_Y, 0));
            session.runFrame();
            screen.update(0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertFalse(session.getView().getUnitView(keep).isWaitingForSupply(), "a new keep's supply starts the entry");
        }
    }
}
