package org.fogofwarlabs.sc4j.engine.client.audio;

import org.jetbrains.annotations.NotNull;

/**
 * Chooses the channel of {@link SoundChannels} a new sound plays on: a free one, one whose sound it replaces, or none. The engine defaults are
 * {@link PriorityChannelPolicy} (sound effects) and {@link OldestChannelPolicy} (one-off sounds).
 */
public interface ChannelPolicy {
    /**
     * The result of {@link #selectChannel} when the sound does not play.
     */
    int NO_CHANNEL = -1;

    /**
     * Chooses the channel of a new sound.
     *
     * @param channels the channels, whose state ({@link SoundChannels#isPlaying}, {@link SoundChannels#getCue}, {@link SoundChannels#getStartSequence}) the policy reads
     * @param cue      the new sound
     * @return the channel index, or {@link #NO_CHANNEL} if the sound does not play
     */
    int selectChannel(@NotNull SoundChannels channels, @NotNull SoundCue cue);
}
