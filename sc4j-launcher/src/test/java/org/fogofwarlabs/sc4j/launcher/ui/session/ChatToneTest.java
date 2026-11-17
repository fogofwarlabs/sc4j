package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Tests the synthesized chat sound without playing it: two notes of their lengths as 16-bit mono samples, quiet, starting and ending silent.
 */
final class ChatToneTest {
    private static final int LOUDEST_ALLOWED = Short.MAX_VALUE / 2;

    @Test
    void create_samples_should_hold_two_quiet_notes_that_start_and_end_silent() {
        ShortBuffer samples = ByteBuffer.wrap(ChatTone.createSamples()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int expectedCount = (int) (ChatTone.SAMPLE_RATE * ChatTone.FIRST_NOTE_SECONDS) + (int) (ChatTone.SAMPLE_RATE * ChatTone.SECOND_NOTE_SECONDS);
        int loudest = 0;

        for (int index = 0; index < samples.limit(); index++) {
            loudest = Math.max(loudest, Math.abs(samples.get(index)));
        }

        Assertions.assertEquals(expectedCount, samples.limit());
        Assertions.assertTrue(loudest > 0, "the sound is not silent");
        Assertions.assertTrue(loudest < LOUDEST_ALLOWED, "the sound is quiet: " + loudest);
        Assertions.assertEquals(0, samples.get(0));
        Assertions.assertEquals(0, samples.get(samples.limit() - 1));
    }
}
