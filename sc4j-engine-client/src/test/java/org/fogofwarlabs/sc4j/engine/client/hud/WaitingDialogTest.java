package org.fogofwarlabs.sc4j.engine.client.hud;

import org.fogofwarlabs.sc4j.engine.client.SkirmishSessionScenario;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.game.GameScreen;
import org.fogofwarlabs.sc4j.engine.client.game.GameSession;
import org.fogofwarlabs.sc4j.engine.client.game.GameSessionFactory;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.client.world.TerrainColors;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests the network waiting dialog of the engine game screen: a local session has none; a network session shows it at the world's centre
 * only while its barrier has overdue participants, with their names and the countdown, and a press on its drop button requests their drop at
 * the transport and counts as a button press.
 */
final class WaitingDialogTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int LINE_HEIGHT = 6;
    private static final float CHARACTER_WIDTH = 5;
    private static final long OVERDUE_MILLISECONDS = 2000;
    private static final long DROP_MILLISECONDS = 10000;
    private static final long LATER_MILLISECONDS = 3500;
    private static final int COUNTDOWN_SECONDS = 7;
    private static final int REMOTE_SLOT = 0;
    private static final float HALF_DIVISOR = 2;
    private static final @NotNull String REMOTE_NAME = "Morgan#a1b2c3";

    /**
     * Stalls at the barrier: it never receives a turn, and its waiting state is the test's.
     */
    private static final class StalledTransport implements LockstepTransport {
        private final @NotNull WaitingPeers waiting;

        private StalledTransport() {
            this.waiting = new WaitingPeers(OVERDUE_MILLISECONDS, DROP_MILLISECONDS);
        }

        @Override
        public int getTurnsInTransit() {
            return 1;
        }

        @Override
        public void sendTurn(byte @NotNull [] turn) {
            // The turn goes nowhere.
        }

        @Override
        public @Nullable List<@NotNull PeerTurn> receiveTurns() {
            return null;
        }

        @Override
        public @NotNull WaitingPeers getWaitingPeers() {
            return this.waiting;
        }
    }

    /**
     * Counts the button presses.
     */
    private static final class PressListener implements HudListener {
        private int pressCount;

        private PressListener() {
            this.pressCount = 0;
        }

        @Override
        public void onButtonPressed() {
            this.pressCount++;
        }
    }

    /**
     * Measures {@value #CHARACTER_WIDTH} per character and draws nothing.
     */
    private static final class FixedFont implements Font {
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
            return text.length() * CHARACTER_WIDTH;
        }

        @Override
        public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
            return x + this.measure(text, style);
        }
    }

    private static void press(@NotNull GameScreen screen, float x, float y) {
        screen.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, x, y, false));
        screen.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, 0, x, y));
    }

    @Test
    void waiting_dialog_shows_the_overdue_players_and_drops_them_on_a_press() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        Game game = skirmish.getSession().getGame();
        GameSessionFactory factory = new GameSessionFactory(skirmish.getMap().getTerrain(), skirmish.getScenario().getBounds(),
                                                            StandardCombatSystem.FRACTION_BITS, skirmish.getScenario().getResourceKinds(),
                                                            SkirmishSessionScenario.TEAM_COLORS);
        CommandRegistry<CommandType<?>> commands = new CommandRegistry<>(1);
        LockstepPeer local = new LockstepPeer(0);
        LockstepPeer remote = new LockstepPeer(1);
        Player localPlayer = game.getPlayer(SkirmishSessionScenario.LOCAL_SLOT);
        StalledTransport transport = new StalledTransport();
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);
        PressListener listener = new PressListener();

        commands.freeze();

        LockstepSession lockstep = new LockstepSession(transport, commands, local, List.of(localPlayer, game.getPlayer(REMOTE_SLOT)),
                                                       new TurnSchedule(1, 1), null, null);
        GameSession session = factory.createNetwork(game, localPlayer, lockstep, List.of());

        try (GameScreen localScreen = new GameScreen(skirmish.getSession(), canvas, new FixedFont(), new SquareProjection(), new TerrainColors(),
                                                     ContentType::getDebugName, "supply", ContentType::getDebugName);
             GameScreen screen = new GameScreen(session, canvas, new FixedFont(), new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            WaitingDialog dialog = screen.getWaitingDialog();

            Assertions.assertNull(localScreen.getWaitingDialog(), "a local game has no waiting dialog");
            Assertions.assertNotNull(dialog);

            screen.setListener(listener);
            dialog.setPeerNames(peer -> peer == remote ? REMOTE_NAME : "");
            transport.getWaitingPeers().update(List.of(remote), 0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertFalse(dialog.isShown(), "an awaited player is not overdue at once");
            Assertions.assertEquals(0, dialog.getWidth());

            transport.getWaitingPeers().update(List.of(remote), LATER_MILLISECONDS);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertTrue(dialog.isShown());
            Assertions.assertEquals(COUNTDOWN_SECONDS, transport.getWaitingPeers().getCountdownSeconds());
            Assertions.assertEquals(WIDTH / HALF_DIVISOR, dialog.getX() + dialog.getWidth() / HALF_DIVISOR,
                                    "the dialog stands at the world's centre");
            Assertions.assertEquals((GameScreen.RESOURCE_BAR_HEIGHT + HEIGHT - GameScreen.PANEL_HEIGHT) / HALF_DIVISOR,
                                    dialog.getY() + dialog.getHeight() / HALF_DIVISOR);
            Assertions.assertTrue(dialog.getWidth() >=
                                  REMOTE_NAME.length() * CHARACTER_WIDTH + 2 * screen.getCommandCard().getSkin().getDialogPadding());

            float insideX = dialog.getX() + dialog.getSkin().getDialogPadding();
            float insideY = dialog.getY() + dialog.getSkin().getDialogPadding();

            press(screen, insideX, insideY);

            Assertions.assertEquals(0, listener.pressCount, "a press beside the button presses nothing");
            Assertions.assertTrue(screen.getSelection().isEmpty(), "the press does not reach the world");

            float buttonX = dialog.getX() + dialog.getWidth() - dialog.getSkin().getDialogPadding() - 2;
            float buttonY = dialog.getY() + dialog.getHeight() - dialog.getSkin().getDialogPadding() - 2;

            Assertions.assertTrue(dialog.isDropButtonAt(buttonX, buttonY));

            press(screen, buttonX, buttonY);

            Assertions.assertEquals(1, listener.pressCount, "the drop button counts as a button press");
            Assertions.assertEquals(List.of(remote), transport.getWaitingPeers().takeDueDrops(), "the press requests the overdue player's drop");

            transport.getWaitingPeers().clear();
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertFalse(dialog.isShown(), "the dialog goes once nobody is awaited");
        }
    }
}
