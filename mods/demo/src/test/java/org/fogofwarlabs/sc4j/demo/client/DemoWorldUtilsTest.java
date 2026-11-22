package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.demo.game.DemoGameScenario;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.projection.IsometricProjection;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.StandardGameView;
import org.fogofwarlabs.sc4j.engine.client.world.UnitSprite;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameSetup;
import org.fogofwarlabs.sc4j.engine.map.MapModel;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileShape;
import org.fogofwarlabs.sc4j.engine.spatial.TableUnitBounds;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests the demo's world: Ashen Ford in the isometric projection with its diamond tileset, and one detail picture each for its tiles and the
 * town hall's sprite sheet.
 */
final class DemoWorldUtilsTest {
    private static final int TILE_WIDTH = 64;
    private static final int TILE_HEIGHT = 32;
    private static final long SEED = 7;
    private static final int @NotNull [] TEAM_COLORS = {0x3C78D8, 0xD8503C, 0xC8C8C8};
    private static final @NotNull List<@NotNull String> TOWN_HALLS = List.of("hearthstead", "ember-lodge");

    private static @NotNull Unit findTownHall(@NotNull Game game) {
        for (String typeId : TOWN_HALLS) {
            Unit unit = DemoGameScenario.findUnit(game, game.getPlayer(DemoGames.FIRST_SLOT), typeId);

            if (unit != null) {
                return unit;
            }
        }

        return Assertions.fail("The first player has no town hall.");
    }

    @Test
    void ashen_ford_uses_the_isometric_projection_with_its_diamond_tiles() throws IOException {
        try (DemoGameScenario scenario = new DemoGameScenario()) {
            GameSetup setup = scenario.createSetup(SEED);
            MapModel map = scenario.getMaps().require(setup);
            DetailSet<TileGraphics> tiles = DemoWorldUtils.loadTileGraphics(scenario.getContent(), map);
            WorldProjection projection = DemoWorldUtils.createProjection(map, tiles);

            Assertions.assertEquals(TileShape.DIAMOND, tiles.getResource().getShape());
            Assertions.assertEquals(TILE_WIDTH, tiles.getResource().getTileWidth());
            Assertions.assertEquals(TILE_HEIGHT, tiles.getResource().getTileHeight());
            Assertions.assertInstanceOf(IsometricProjection.class, projection);
        }
    }

    @Test
    void the_tiles_and_the_town_hall_sheet_have_one_detail_picture() throws IOException {
        try (DemoGameScenario scenario = new DemoGameScenario()) {
            GameSetup setup = scenario.createSetup(SEED);
            Game game = scenario.getGames().createGame(setup);
            MapModel map = scenario.getMaps().require(setup);
            DetailSet<TileGraphics> tiles = DemoWorldUtils.loadTileGraphics(scenario.getContent(), map);
            GameView view = new StandardGameView(game, null, map.getTerrain(), game.getModSystem(TableUnitBounds.KEY),
                                                 StandardCombatSystem.FRACTION_BITS, scenario.getContent().getResourceKinds(), TEAM_COLORS);
            DemoSprites sprites = DemoSprites.load(scenario.getContent(), game.getDefinition().getContent());
            UnitType townHallType = view.getUnitView(findTownHall(game)).getType();

            Assertions.assertNotNull(townHallType);

            UnitSprite townHallSprite = sprites.findSprite(townHallType);

            Assertions.assertNotNull(townHallSprite);
            Assertions.assertEquals(1, tiles.getDetailCount());
            Assertions.assertEquals(1, townHallSprite.getSheets().getDetailCount());
        }
    }
}
