package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.demo.game.DemoGameScenario;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.render.WorldView;
import org.fogofwarlabs.sc4j.engine.client.render.WorldViewport;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.view.ProjectileView;
import org.fogofwarlabs.sc4j.engine.client.view.StandardGameView;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.client.world.*;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameSetup;
import org.fogofwarlabs.sc4j.engine.map.MapModel;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.fogofwarlabs.sc4j.engine.spatial.TableUnitBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the demo's sprites in a computer game drawn offscreen: a building under construction shows the foundation, the timber frame and the
 * half walls over the thirds of its build time, then the done building; a projectile's flight ends in its impact effect.
 */
final class DemoSpritesTest {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final long SEED = 7;
    private static final int @NotNull [] TEAM_COLORS = {0x3C78D8, 0xD8503C, 0xC8C8C8};
    private static final int OPAQUE_BLACK = 0x000000FF;
    private static final int FRAME_LIMIT = 40000;
    private static final int STAGES = 3;
    private static final int EARLY_PROGRESS = UnitView.COMPLETE / 20;
    private static final int DONE_COLUMN = 3;
    private static final int UP = 0;
    private static final double MINIMUM_STAGE_SHARE = 0.25;
    private static final double MAXIMUM_STAGE_SHARE = 0.42;
    private static final int IMPACT_LOOPS = 30;

    private static @NotNull GameView createView(@NotNull DemoGameScenario scenario, @NotNull Game game, @NotNull MapModel map) {
        return new StandardGameView(game, null, map.getTerrain(), game.getModSystem(TableUnitBounds.KEY), StandardCombatSystem.FRACTION_BITS,
                                    scenario.getContent().getResourceKinds(), TEAM_COLORS);
    }

    private static @Nullable UnitView findNewConstruction(@NotNull Game game, @NotNull GameView view) {
        for (Unit unit : view.getUnits()) {
            UnitView unitView = view.getUnitView(unit);
            UnitType type = unitView.getType();

            if (type != null && game.getContent().getUnitTypes().hasTrait(type, UnitTrait.BUILDING) &&
                unitView.getConstructionProgress() < EARLY_PROGRESS) {
                return unitView;
            }
        }

        return null;
    }

    private static void draw(@NotNull ImageCanvas canvas, @NotNull WorldViewport viewport, @NotNull WorldView world) throws IOException {
        canvas.begin(WIDTH, HEIGHT);
        canvas.fillRectangle(0, 0, WIDTH, HEIGHT, OPAQUE_BLACK);
        world.draw(viewport);
        canvas.end();
    }

    @Test
    void construction_shows_foundation_frame_and_half_walls_over_the_thirds_of_the_build_then_the_done_building() throws IOException {
        try (DemoGameScenario scenario = new DemoGameScenario()) {
            GameSetup setup = scenario.createSetup(SEED);
            Game game = scenario.getGames().createGame(setup);
            GameView view = createView(scenario, game, scenario.getMaps().require(setup));
            DemoSprites sprites = DemoSprites.load(scenario.getContent(), game.getDefinition().getContent());
            UnitView building = null;

            for (int frame = 0; frame < FRAME_LIMIT && building == null && !DemoGames.isOver(game); frame++) {
                game.getFrameRunner().runFrame();
                building = findNewConstruction(game, view);
            }

            Assertions.assertNotNull(building, "a computer player starts a building");

            UnitType type = building.getType();

            Assertions.assertNotNull(type);

            UnitSprite sprite = sprites.findSprite(type);
            List<Integer> columns = new ArrayList<>();

            Assertions.assertNotNull(sprite);

            while (building.getConstructionProgress() < UnitView.COMPLETE && building.isPlaced() && building.getType() == type) {
                columns.add(sprite.selectFrame(building, UP, view.getFrame()));
                game.getFrameRunner().runFrame();
            }

            Assertions.assertEquals(DONE_COLUMN, sprite.selectFrame(building, UP, view.getFrame()), "the done building");

            for (int column = 0; column < STAGES; column++) {
                int stage = column;
                double share = columns.stream().filter(shown -> shown == stage).count() / (double) columns.size();

                Assertions.assertTrue(share >= MINIMUM_STAGE_SHARE && share <= MAXIMUM_STAGE_SHARE,
                                      "stage " + column + " shows for a third of the build, not " + share);
            }

            for (int index = 1; index < columns.size(); index++) {
                Assertions.assertTrue(columns.get(index) >= columns.get(index - 1), "the stages follow each other");
            }
        }
    }

    @Test
    void projectile_flight_ends_in_its_impact_effect() throws IOException {
        try (DemoGameScenario scenario = new DemoGameScenario()) {
            GameSetup setup = scenario.createSetup(SEED);
            Game game = scenario.getGames().createGame(setup);
            MapModel map = scenario.getMaps().require(setup);
            GameView view = createView(scenario, game, map);
            DemoSprites sprites = DemoSprites.load(scenario.getContent(), game.getDefinition().getContent());
            DetailSet<TileGraphics> tiles = DemoWorldUtils.loadTileGraphics(scenario.getContent(), map);
            WorldProjection projection = DemoWorldUtils.createProjection(map, tiles);
            ImageCanvas canvas = new ImageCanvas(WIDTH, HEIGHT);

            for (int frame = 0; frame < FRAME_LIMIT && view.getProjectiles().isEmpty() && !DemoGames.isOver(game); frame++) {
                game.getFrameRunner().runFrame();
            }

            Assertions.assertFalse(view.getProjectiles().isEmpty(), "a ranged unit shoots");

            ProjectileView flying = view.getProjectiles().getFirst();
            Camera camera = new Camera(projection, view.getMapWidth(), view.getMapHeight());
            WorldViewport viewport = new WorldViewport(projection);

            camera.setScreenSize(WIDTH, HEIGHT);
            camera.centerOn(flying.getX(), flying.getY());
            camera.applyTo(viewport);

            try (TilesetLayer terrain = DemoWorldUtils.createTerrainLayer(canvas, view, scenario.getContent(), map, tiles);
                 UnitSpriteLayer units = new UnitSpriteLayer(canvas, view, sprites::findSprite);
                 ProjectileSpriteLayer projectiles = new ProjectileSpriteLayer(canvas, view, sprites::findProjectileSprite)) {
                WorldView world = new WorldView(List.of(terrain, units, projectiles));

                draw(canvas, viewport, world);

                for (int loop = 0; loop < IMPACT_LOOPS && projectiles.getImpactCount() == 0; loop++) {
                    game.getFrameRunner().runFrame();
                    draw(canvas, viewport, world);
                }

                Assertions.assertTrue(projectiles.getImpactCount() > 0, "a flight ends in its impact effect");
            }
        }
    }
}
