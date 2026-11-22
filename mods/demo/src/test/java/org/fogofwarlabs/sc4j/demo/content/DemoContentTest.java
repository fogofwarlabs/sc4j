package org.fogofwarlabs.sc4j.demo.content;

import org.fogofwarlabs.sc4j.engine.animation.*;
import org.fogofwarlabs.sc4j.engine.animation.instruction.PlaySound;
import org.fogofwarlabs.sc4j.engine.animation.instruction.ShowFrame;
import org.fogofwarlabs.sc4j.engine.pack.StandardPack;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.audio.SoundClip;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the demo's standard data pack as the game reads it: every sprite sheet with its layers, the animations of every sheet, the
 * synthesized sound effects and music, the body font, and a shadow on every frame of a sheet with a shadow layer.
 */
final class DemoContentTest {
    private static final @NotNull List<@NotNull String> UNITS = List.of("tiller", "pikeward", "longeye", "grubber", "cleaver", "stonehurler");
    private static final @NotNull List<@NotNull String> BUILDINGS = List.of("hearthstead", "larder", "muster-yard", "ember-lodge", "smokehouse",
                                                                            "brawl-pit");
    private static final @NotNull List<@NotNull String> OTHERS = List.of("gold-mine", "arrow", "hurled-stone", "spark", "dust");
    private static final @NotNull Map<@NotNull String, @NotNull List<@NotNull String>> KINDS = Map.of(
            "unit", List.of("idle", "walk", "attack", "death"),
            "building", List.of("construct", "idle", "death"),
            "other", List.of("idle")
    );
    private static final @NotNull List<@NotNull String> SOUNDS = List.of("attack", "hit", "death", "construction-done", "unit-ready",
                                                                         "under-attack", "click", "refused", "select", "select-building",
                                                                         "hover", "open");
    private static final @NotNull List<@NotNull String> MUSIC = List.of("music-hearth", "music-ford", "music-ridge");
    /**
     * The shortest piece of music in seconds.
     */
    private static final int SHORTEST_MUSIC_SECONDS = 80;
    private static final int SAMPLE_RATE = 22050;
    private static final int MINIMUM_SOUND_PARTS = 10;
    private static final int LOUDNESS_PARTS = 4;
    /**
     * The root mean square level a piece of music reaches at least, as a part of the largest sample.
     */
    private static final int MUSIC_LOUDNESS_PARTS = 10;
    private static final int FACINGS = 8;
    private static final int UNIT_FRAMES = 8;
    private static final int BUILDING_FRAMES = 5;
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int LAST_PRINTABLE = 0x7E;
    private static final int LINE_HEIGHT = 20;
    private static final int ALPHA_SHIFT = 24;

    @Test
    void pack_reads_every_sprite_sheet_with_its_frame_grid_and_layers() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            StandardPack pack = content.getPack();

            Assertions.assertEquals(UNITS.size() + BUILDINGS.size() + OTHERS.size(), pack.getSpriteSheetIds().size());

            for (String id : pack.getSpriteSheetIds()) {
                SpriteSheet sheet = pack.loadSpriteSheet(ResourceKey.of(id));
                boolean team = UNITS.contains(id) || BUILDINGS.contains(id);

                Assertions.assertEquals(team, sheet.getTeamMask() != null, id);
                Assertions.assertEquals(sheet.getWidth() / 2, sheet.getAnchorX(), id);

                if (UNITS.contains(id)) {
                    Assertions.assertEquals(FACINGS * UNIT_FRAMES, sheet.getFrameCount(), id);
                    Assertions.assertNotNull(sheet.getShadow(), id);
                }

                if (BUILDINGS.contains(id)) {
                    Assertions.assertEquals(BUILDING_FRAMES, sheet.getFrameCount(), id);
                    Assertions.assertNotNull(sheet.getShadow(), id);
                }

                for (int frameIndex = 0; frameIndex < sheet.getFrameCount(); frameIndex++) {
                    Assertions.assertTrue(countCovered(sheet, frameIndex) > 0, id + " frame " + frameIndex + " is empty");
                }
            }
        }
    }

    @Test
    void pack_animations_give_every_sheet_the_programs_of_its_kind() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            StandardPack pack = content.getPack();
            AnimationLibrary library = pack.loadAnimations();

            Assertions.assertEquals(pack.getSpriteSheetIds().size(), library.getSets().size());

            for (int setIndex = 0; setIndex < library.getSets().size(); setIndex++) {
                String id = pack.getSpriteSheetIds().get(setIndex);
                AnimationSet set = library.getSet(setIndex);
                SpriteSheet sheet = pack.loadSpriteSheet(ResourceKey.of(id));
                String role = UNITS.contains(id) ? "unit" : BUILDINGS.contains(id) ? "building" : "other";

                for (String kindName : KINDS.get(role)) {
                    AnimationKind kind = library.findKind(kindName);

                    Assertions.assertNotNull(kind, kindName);

                    AnimationProgram program = set.getProgram(kind);

                    Assertions.assertNotNull(program, id + " " + kindName);

                    int shownFrames = 0;

                    for (int position = set.getStartPosition(kind); position < program.getLength(); position++) {
                        AnimationInstruction instruction = program.get(position);

                        if (instruction instanceof ShowFrame frame) {
                            Assertions.assertTrue(frame.getFrame() < sheet.getFrameCount(), id + " " + kindName);

                            shownFrames++;
                        }

                        if (instruction instanceof PlaySound sound) {
                            Assertions.assertTrue(pack.getSoundIds().contains(sound.getSound().getDebugName()), id + " " + kindName);
                        }
                    }

                    Assertions.assertTrue(shownFrames > 0, id + " " + kindName);
                }
            }
        }
    }

    @Test
    void pack_decodes_every_sound_effect() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            StandardPack pack = content.getPack();

            List<String> ids = new ArrayList<>(SOUNDS);

            ids.addAll(MUSIC);
            Assertions.assertEquals(ids, pack.getSoundIds());

            for (String id : SOUNDS) {
                SoundClip sound = pack.loadSound(ResourceKey.of(id));
                int loudest = 0;

                for (int index = 0; index < sound.getFrameCount(); index++) {
                    loudest = Math.max(loudest, Math.abs(sound.getSample(index)));
                }

                Assertions.assertEquals(SAMPLE_RATE, sound.getSampleRate(), id);
                Assertions.assertEquals(1, sound.getChannelCount(), id);
                Assertions.assertTrue(sound.getFrameCount() > SAMPLE_RATE / MINIMUM_SOUND_PARTS, id);
                Assertions.assertTrue(loudest > Short.MAX_VALUE / LOUDNESS_PARTS, id + " is too quiet: " + loudest);
                Assertions.assertEquals(0, sound.getSample(0), id);
            }
        }
    }

    @Test
    void pack_decodes_every_piece_of_music_long_and_loud_enough_to_repeat() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            StandardPack pack = content.getPack();

            for (String id : MUSIC) {
                SoundClip music = pack.loadSound(ResourceKey.of(id));
                long energy = 0;

                for (int index = 0; index < music.getFrameCount(); index++) {
                    energy += (long) music.getSample(index) * music.getSample(index);
                }

                Assertions.assertEquals(SAMPLE_RATE, music.getSampleRate(), id);
                Assertions.assertEquals(1, music.getChannelCount(), id);
                Assertions.assertTrue(music.getFrameCount() >= SAMPLE_RATE * SHORTEST_MUSIC_SECONDS, id);
                Assertions.assertTrue(Math.sqrt((double) energy / music.getFrameCount()) > (double) Short.MAX_VALUE / MUSIC_LOUDNESS_PARTS,
                                      id + " is too quiet");
            }
        }
    }

    @Test
    void pack_reads_the_body_font_with_every_printable_character() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            FontFace font = content.getPack().loadFont(ResourceKey.of("body"));

            Assertions.assertEquals(LAST_PRINTABLE - FIRST_PRINTABLE + 1, font.getGlyphs().size());
            Assertions.assertEquals(LINE_HEIGHT, font.getLineHeight());

            for (int codePoint = FIRST_PRINTABLE + 1; codePoint <= LAST_PRINTABLE; codePoint++) {
                FontGlyph glyph = font.findGlyph(codePoint);

                Assertions.assertNotNull(glyph, Character.toString(codePoint));
                Assertions.assertTrue(glyph.getWidth() > 0 && glyph.getHeight() > 0, Character.toString(codePoint));
                Assertions.assertTrue(glyph.getAdvance() > glyph.getWidth(), Character.toString(codePoint));
            }
        }
    }

    @Test
    void sprite_sheets_with_a_shadow_layer_have_a_shadow_on_every_frame() throws IOException {
        try (DemoContent content = DemoContent.open(DemoContentTest.class.getClassLoader())) {
            StandardPack pack = content.getPack();

            for (String id : pack.getSpriteSheetIds()) {
                SpriteSheet sheet = pack.loadSpriteSheet(ResourceKey.of(id));

                for (int frameIndex = 0; frameIndex < sheet.getFrameCount() && sheet.getShadow() != null; frameIndex++) {
                    Assertions.assertTrue(countShadowed(sheet, frameIndex) > 0, id + " frame " + frameIndex + " has no shadow");
                }
            }
        }
    }

    private static int countShadowed(@NotNull SpriteSheet sheet, int frameIndex) {
        SpriteFrame frame = sheet.getFrame(frameIndex);
        PixelLayer shadow = sheet.getShadow();
        int shadowed = 0;

        for (int index = 0; shadow != null && index < frame.getWidth() * frame.getHeight(); index++) {
            if (shadow.getValue(frame.getPixelOffset() + index) != 0) {
                shadowed++;
            }
        }

        return shadowed;
    }

    private static int countCovered(@NotNull SpriteSheet sheet, int frameIndex) {
        SpriteFrame frame = sheet.getFrame(frameIndex);
        int covered = 0;

        for (int index = 0; index < frame.getWidth() * frame.getHeight(); index++) {
            if (sheet.getColor().getValue(frame.getPixelOffset() + index) >>> ALPHA_SHIFT != 0) {
                covered++;
            }
        }

        return covered;
    }
}
