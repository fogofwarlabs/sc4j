package org.fogofwarlabs.sc4j.engine.client.audio;

import org.jetbrains.annotations.NotNull;

/**
 * Plays a sound on the first free channel; when every channel plays, it replaces the sound that started first. Cues do not matter.
 */
public final class OldestChannelPolicy implements ChannelPolicy {
    /**
     * Creates an {@link OldestChannelPolicy} instance.
     */
    public OldestChannelPolicy() {
    }

    /**
     * Chooses the channel of a new sound.
     *
     * @param channels the channels, whose state ({@link SoundChannels#isPlaying}, {@link SoundChannels#getCue}, {@link SoundChannels#getStartSequence}) the policy reads
     * @param cue      the new sound
     * @return the channel index, or {@link #NO_CHANNEL} if the sound does not play
     */
    @Override
    public int selectChannel(@NotNull SoundChannels channels, @NotNull SoundCue cue) {
        int oldest = 0;

        for (int channel = 0; channel < channels.getChannelCount(); channel++) {
            if (!channels.isPlaying(channel)) {
                return channel;
            }

            if (channels.getStartSequence(channel) < channels.getStartSequence(oldest)) {
                oldest = channel;
            }
        }

        return channels.getChannelCount() == 0 ? NO_CHANNEL : oldest;
    }
}
