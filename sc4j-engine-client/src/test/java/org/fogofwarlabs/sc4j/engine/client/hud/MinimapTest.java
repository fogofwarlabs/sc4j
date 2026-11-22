package org.fogofwarlabs.sc4j.engine.client.hud;

import org.fogofwarlabs.sc4j.engine.client.SkirmishSessionScenario;
import org.fogofwarlabs.sc4j.engine.client.command.CommandTarget;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.projection.IsometricProjection;
import org.fogofwarlabs.sc4j.engine.client.projection.SquareProjection;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.client.world.Camera;
import org.fogofwarlabs.sc4j.engine.client.world.TerrainColors;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests the minimap mapping between its rectangle and the map in both engine projections: the rectangle shows the map's projected bounds at their own
 * aspect, centred (a square from above, a diamond twice as wide as high in the isometric projection), a press moves the camera there, and pings
 * move on with the time.
 */
final class MinimapTest {
    private static final float MINIMAP_LEFT = 10;
    private static final float MINIMAP_TOP = 20;
    private static final float MINIMAP_SIZE = 128;
    private static final int ISOMETRIC_TILE_WIDTH = 64;
    private static final int ISOMETRIC_TILE_HEIGHT = 32;
    private static final int SCREEN_SIZE = 400;
    private static final float DELTA = 1;
    private static final int PING_X = 96;
    private static final int PING_Y = 160;
    private static final int PING_COLOR = 0x20C020FF;

    private static @NotNull Minimap createMinimap(@NotNull GameView view, @NotNull Camera camera) {
        Minimap minimap = new Minimap(view, camera, new TerrainColors());

        camera.setScreenSize(SCREEN_SIZE, SCREEN_SIZE);
        minimap.setBounds(MINIMAP_LEFT, MINIMAP_TOP, MINIMAP_SIZE, MINIMAP_SIZE);

        return minimap;
    }

    @Test
    void minimap_maps_its_rectangle_to_the_map_from_above() throws IOException {
        GameView view = new SkirmishSessionScenario().getSession().getView();
        Camera camera = new Camera(new SquareProjection(), view.getMapWidth(), view.getMapHeight());
        Minimap minimap = createMinimap(view, camera);
        CommandTarget centre = minimap.pick(MINIMAP_LEFT + MINIMAP_SIZE / 2, MINIMAP_TOP + MINIMAP_SIZE / 2);

        Assertions.assertNotNull(centre);
        Assertions.assertEquals(view.getMapWidth() / 2f, centre.getX(), DELTA);
        Assertions.assertEquals(view.getMapHeight() / 2f, centre.getY(), DELTA);
        Assertions.assertEquals(0, minimap.toWorldX(MINIMAP_LEFT, MINIMAP_TOP));
        Assertions.assertEquals(view.getMapWidth() - 1, minimap.toWorldX(MINIMAP_LEFT + MINIMAP_SIZE, MINIMAP_TOP));
        Assertions.assertEquals(view.getTerrain().getWidth(), new TerrainColors().getImageWidth(view.getTerrain(), camera.getProjection()),
                                "one pixel per tile from above");
    }

    @Test
    void minimap_shows_the_isometric_diamond_and_moves_the_camera_there() throws IOException {
        GameView view = new SkirmishSessionScenario().getSession().getView();
        WorldProjection projection = new IsometricProjection(view.getTerrain().getTileSize(), ISOMETRIC_TILE_WIDTH, ISOMETRIC_TILE_HEIGHT);
        Camera camera = new Camera(projection, view.getMapWidth(), view.getMapHeight());
        Minimap minimap = createMinimap(view, camera);
        float top = minimap.getMapAreaTop();

        Assertions.assertEquals(0, minimap.toWorldX(MINIMAP_LEFT + MINIMAP_SIZE / 2, top), "the top corner is the world origin");
        Assertions.assertEquals(0, minimap.toWorldY(MINIMAP_LEFT + MINIMAP_SIZE / 2, top));
        Assertions.assertEquals(view.getMapWidth() / 2f, minimap.toWorldX(MINIMAP_LEFT + MINIMAP_SIZE / 2, MINIMAP_TOP + MINIMAP_SIZE / 2), DELTA);
        Assertions.assertEquals(view.getMapHeight() / 2f, minimap.toWorldY(MINIMAP_LEFT + MINIMAP_SIZE / 2, MINIMAP_TOP + MINIMAP_SIZE / 2),
                                DELTA);

        minimap.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0,
                                                           MINIMAP_LEFT + MINIMAP_SIZE / 2, MINIMAP_TOP + MINIMAP_SIZE / 2, false));

        Assertions.assertEquals(view.getMapWidth() / 2f, camera.toWorldX(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f), DELTA);
        Assertions.assertEquals(view.getMapHeight() / 2f, camera.toWorldY(SCREEN_SIZE / 2f, SCREEN_SIZE / 2f), DELTA);
    }

    @Test
    void minimap_fills_its_map_area_at_the_aspect_of_the_projected_map_centred_in_its_rectangle() throws IOException {
        GameView view = new SkirmishSessionScenario().getSession().getView();
        WorldProjection projection = new IsometricProjection(view.getTerrain().getTileSize(), ISOMETRIC_TILE_WIDTH, ISOMETRIC_TILE_HEIGHT);
        Camera camera = new Camera(projection, view.getMapWidth(), view.getMapHeight());
        Minimap minimap = createMinimap(view, camera);
        float aspect = (camera.getBoundsRight() - camera.getBoundsLeft()) / (camera.getBoundsBottom() - camera.getBoundsTop());

        Assertions.assertTrue(aspect > 1, "the isometric map is wider than high");
        Assertions.assertEquals(MINIMAP_SIZE, minimap.getMapAreaWidth(), DELTA, "the map area takes the rectangle's width");
        Assertions.assertEquals(MINIMAP_SIZE / aspect, minimap.getMapAreaHeight(), DELTA);
        Assertions.assertEquals(MINIMAP_TOP + (MINIMAP_SIZE - minimap.getMapAreaHeight()) / 2, minimap.getMapAreaTop(), DELTA, "the area is centred");
    }

    @Test
    void update_should_move_pings_on_by_the_frame_interval() throws IOException {
        GameView view = new SkirmishSessionScenario().getSession().getView();
        Minimap minimap = createMinimap(view, new Camera(new SquareProjection(), view.getMapWidth(), view.getMapHeight()));

        minimap.addPing(PING_X, PING_Y, PING_COLOR);
        minimap.update(Minimap.PING_FRAME_INTERVAL - 1);

        Assertions.assertEquals(0, minimap.getPings().getFrame(0));
        Assertions.assertEquals(PING_X, minimap.getPings().getX(0));

        minimap.update(1);

        Assertions.assertEquals(1, minimap.getPings().getFrame(0));

        minimap.addPing(PING_X, PING_Y, new Player(1));

        Assertions.assertTrue(minimap.getPings().isPlayerColor(1), "a player's ping shows the player's colour");
        Assertions.assertEquals(1, minimap.getPings().getColor(1));
        Assertions.assertFalse(minimap.getPings().isPlayerColor(0));

        minimap.update(Minimap.PING_FRAME_INTERVAL * Minimap.PING_FRAMES);

        Assertions.assertFalse(minimap.getPings().isAnyActive(), "the ping ends after its frames");
        Assertions.assertInstanceOf(StandardMinimapStyle.class, minimap.getStyle());
    }
}
