package org.fogofwarlabs.sc4j.engine.client.skin;

import org.fogofwarlabs.sc4j.engine.client.SkirmishSessionScenario;
import org.fogofwarlabs.sc4j.engine.client.command.OrderAction;
import org.fogofwarlabs.sc4j.engine.client.command.TrainAction;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.game.GameScreen;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.PictureRenderer;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.client.world.TerrainColors;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.game.SkirmishScenario;
import org.fogofwarlabs.sc4j.engine.order.IssueOrderCommandType;
import org.fogofwarlabs.sc4j.engine.pack.StandardPack;
import org.fogofwarlabs.sc4j.engine.production.TrainUnitCommandType;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests the HUD skin on the offscreen game screen of the skirmish map: a skin table chooses the skin by the local player's faction, and a skin
 * maps orders and production to their button icons.
 */
final class HudSkinTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int LINE_HEIGHT = 6;
    private static final int COMMAND_ID = 0;
    private static final int OPAQUE = 0xFF000000;
    private static final int CARD_COLOR = 0x112233;
    private static final int PRESSED_COLOR = 0x445566;
    private static final int ICON_COLOR = 0xFFCC00;
    private static final int GOLD_COLOR = 0xEEDD00;
    private static final int SUPPLY_COLOR = 0x00DDEE;
    private static final int RGBA_SHIFT = 8;
    private static final int OPAQUE_ALPHA = 0xFF;

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

    private static @NotNull Picture createPicture(int color) {
        return new Picture(1, 1, PixelLayer.ofRgba(new int[]{OPAQUE | color}));
    }

    private static int toRgba(int color) {
        return color << RGBA_SHIFT | OPAQUE_ALPHA;
    }

    @Test
    void hud_skin_table_chooses_the_skin_of_the_local_player_faction() throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        SkirmishScenario scenario = skirmish.getScenario();
        HudSkin wardens = HudSkin.builder().setCommandCardBackground(new ColorBoxStyle(toRgba(CARD_COLOR))).build();
        HudSkinTable table = new HudSkinTable(HudSkin.DEFAULT);
        ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);

        table.setFactionSkin(scenario.getWardens(), wardens);

        Assertions.assertSame(wardens, table.findSkin(scenario.getWardens()));
        Assertions.assertSame(HudSkin.DEFAULT, table.findSkin(scenario.getRaiders()));
        Assertions.assertSame(HudSkin.DEFAULT, table.findSkin(null));
        Assertions.assertThrows(IllegalStateException.class,
                                () -> HudSkin.builder().setOrderIcon(scenario.getMove(), createPicture(ICON_COLOR)).build(),
                                "pictures need a renderer");
        Assertions.assertEquals(HudSkin.DEFAULT_TOOLTIP_PADDING, HudSkin.DEFAULT.getTooltipPadding());
        Assertions.assertEquals(HudSkin.DEFAULT_DIALOG_PADDING, HudSkin.DEFAULT.getDialogPadding());
        Assertions.assertThrows(IllegalArgumentException.class, () -> HudSkin.builder().setTooltip(ColorBoxStyle.NONE, -1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HudSkin.builder().setDialog(ColorBoxStyle.NONE, -1));

        try (GameScreen screen = new GameScreen(skirmish.getSession(), canvas, new EmptyFont(), new SquareProjection(), new TerrainColors(),
                                                ContentType::getDebugName, "supply", ContentType::getDebugName)) {
            screen.setSkins(table);
            screen.update(0);
            screen.draw(UiSurface.of(WIDTH, HEIGHT, DisplayScale.ONE));

            Assertions.assertSame(wardens, screen.getCommandCard().getSkin(), "the local player plays the wardens");
        }
    }

    @Test
    void find_button_icon_should_map_orders_to_their_icons_and_production_to_the_unit_icon(@TempDir @NotNull Path folder) throws IOException {
        SkirmishSessionScenario skirmish = new SkirmishSessionScenario();
        SkirmishScenario scenario = skirmish.getScenario();
        Picture moveIcon = createPicture(ICON_COLOR);
        Picture keeperIcon = createPicture(GOLD_COLOR);
        Picture cancelIcon = createPicture(PRESSED_COLOR);
        Picture rallyIcon = createPicture(SUPPLY_COLOR);

        Files.writeString(folder.resolve("pack.json"), "{\"format\": 1}", StandardCharsets.UTF_8);

        try (StandardPack pack = StandardPack.open(folder);
             PictureRenderer renderer = new PictureRenderer(new ImageCanvas(1, 1), pack)) {
            HudSkin skin = HudSkin.builder(renderer).setOrderIcon(scenario.getMove(), moveIcon).setUnitIcon(scenario.getKeeper(), keeperIcon).build();
            IssueOrderCommandType orders = new IssueOrderCommandType(COMMAND_ID, 0);

            Assertions.assertSame(moveIcon, skin.findButtonIcon(new OrderAction(skirmish.getSession().getGame(), orders, scenario.getMove(), true)));
            Assertions.assertNull(skin.findButtonIcon(new OrderAction(skirmish.getSession().getGame(), orders, scenario.getPatrol(), true)));
            Assertions.assertSame(keeperIcon,
                                  skin.findButtonIcon(new TrainAction(skirmish.getSession().getGame(), new TrainUnitCommandType(COMMAND_ID),
                                                                      scenario.getKeeper())));
            Assertions.assertNull(skin.findCancelIcon());
            Assertions.assertNull(skin.findRallyIcon());

            HudSkin icons = HudSkin.builder(renderer).setCancelIcon(cancelIcon).setRallyIcon(rallyIcon).build();

            Assertions.assertSame(cancelIcon, icons.findCancelIcon());
            Assertions.assertSame(rallyIcon, icons.findRallyIcon());
        }
    }
}
