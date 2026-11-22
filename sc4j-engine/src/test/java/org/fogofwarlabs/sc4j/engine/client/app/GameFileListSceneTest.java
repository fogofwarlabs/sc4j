package org.fogofwarlabs.sc4j.engine.client.app;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.community.TableRow;
import org.fogofwarlabs.sc4j.engine.client.dialog.Control;
import org.fogofwarlabs.sc4j.engine.client.dialog.DialogTheme;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.BitmapFont;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplaySummary;
import org.fogofwarlabs.sc4j.engine.resource.dialog.TextAlignment;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tests the engine scene of stored games: it lists the replays of a storage folder that the mod's replay format reads (newest first,
 * unreadable and foreign files left out) as table rows of map, players, game time, date and file name, gives its dialog a centred title and
 * buttons, opens the selected entry with Enter and goes back with Escape; two playing slots read as {@code A vs B} in the mod's names.
 */
final class GameFileListSceneTest {
    private static final @NotNull String FOLDER = "replays";
    private static final @NotNull String MAGIC = "TESTREP ";
    /**
     * The client time of one game loop of the test replays: one second, so that a file of 14 bytes runs {@code 0:14}.
     */
    private static final long LOOP_MILLISECONDS = 1000;
    private static final long OLDER_TIME = 1_700_000_000_000L;
    private static final long NEWER_TIME = 1_700_000_600_000L;
    private static final @NotNull DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /**
     * Reads test replays: the magic, then the map name; the length is the file size.
     */
    private static final class TestReplayFormat implements ReplayFormat {
        @Override
        public @NotNull String getName() {
            return "test replay";
        }

        @Override
        public @NotNull String getFileExtension() {
            return "sc4r";
        }

        @Override
        public boolean isReplay(byte @NotNull [] contents) {
            return new String(contents, StandardCharsets.UTF_8).startsWith(MAGIC);
        }

        @Override
        public @NotNull ReplaySummary readSummary(byte @NotNull [] contents) throws IOException {
            String map = new String(contents, StandardCharsets.UTF_8).substring(MAGIC.length());

            if (map.isBlank()) {
                throw Exceptions.newIOException("The test replay has no map.");
            }

            return new ReplaySummary(map, contents.length, 2);
        }
    }

    /**
     * Records the choices.
     */
    private static final class RecordingListener implements GameFileListScene.Listener {
        private final @NotNull List<@NotNull String> choices;

        private RecordingListener() {
            this.choices = new ArrayList<>();
        }

        @Override
        public void onChosen(@NotNull GameFileEntry entry) {
            this.choices.add(entry.getPath().getFileName() + " " + entry.getMapName());
        }

        @Override
        public void onCancelled() {
            this.choices.add("back");
        }
    }

    private static void press(@NotNull SceneClient client, @NotNull Key key) {
        client.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, key, 0));
    }

    private static @NotNull String formatDate(long time) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    @Test
    void game_file_list_scene_lists_the_readable_replays_and_reports_the_choice(@TempDir @NotNull Path storage) throws IOException,
                                                                                                                       ManifestException,
                                                                                                                       ModException {
        Path folder = Files.createDirectories(storage.resolve(FOLDER));

        Files.writeString(folder.resolve("b-second.sc4r"), MAGIC + "Ford", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("a-first.sc4r"), MAGIC + "Hollow", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(folder.resolve("a-first.sc4r"), FileTime.fromMillis(NEWER_TIME));
        Files.setLastModifiedTime(folder.resolve("b-second.sc4r"), FileTime.fromMillis(OLDER_TIME));
        Files.writeString(folder.resolve("c-damaged.sc4r"), MAGIC + " ", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("notes.txt"), "not a replay", StandardCharsets.UTF_8);

        ImageCanvas canvas = new ImageCanvas(ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT);
        RecordingListener listener = new RecordingListener();

        try (BitmapFont font = ClientLaunchScenario.createFont(canvas);
             SceneClient client = ClientLaunchScenario.createClient(ClientLaunchScenario.createLaunch(List.of(new TestReplayFormat())),
                                                                    ClientLaunchScenario.createStorage(storage), canvas)) {
            GameFileListScene scene = GameFileListScene.createReplayList("Replays", DialogTheme.createDefault(font), FOLDER, LOOP_MILLISECONDS,
                                                                         listener);

            client.start(started -> scene);
            client.runFrame(0, ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT);

            Assertions.assertEquals(List.of(List.of("Hollow", "2 players", "0:14", formatDate(NEWER_TIME), "a-first.sc4r"),
                                            List.of("Ford", "2 players", "0:12", formatDate(OLDER_TIME), "b-second.sc4r")),
                                    scene.getTable().getRows().stream().map(TableRow::getCells).toList(), "newest first");
            Assertions.assertEquals("test replay", scene.getEntries().getFirst().getFormatName());

            for (int id : new int[]{GameFileListScene.TITLE_ID, GameFileListScene.OPEN_ID, GameFileListScene.BACK_ID}) {
                Control control = scene.getDialog().findControl(id);

                Assertions.assertNotNull(control);
                Assertions.assertEquals(TextAlignment.CENTER, control.getDefinition().getTextAlignment(), "control " + id + " is centred");
            }

            press(client, Key.ENTER);
            press(client, Key.ESCAPE);
        }

        Assertions.assertEquals(List.of("a-first.sc4r Hollow", "back"), listener.choices);
    }

    @Test
    void describe_should_name_two_playing_slots_as_one_against_the_other_with_the_game_time() {
        GameFileEntry entry = new GameFileEntry(Path.of("game-7.sc4r"), "test replay", Instant.ofEpochMilli(OLDER_TIME), "Ford", 5667, 2,
                                                List.of(new PlayerSlot(0, ControllerKind.HUMAN, 0, 0, null),
                                                        new PlayerSlot(1, ControllerKind.COMPUTER, 1, 1, null)));

        Assertions.assertEquals(List.of("Ford", "First vs Second", "3:58", formatDate(OLDER_TIME), "game-7.sc4r"),
                                GameFileListScene.describe(entry, 42, slot -> slot.getSlotIndex() == 0 ? "First" : "Second"));
    }
}
