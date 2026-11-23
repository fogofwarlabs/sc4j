package org.fogofwarlabs.sc4j.engine.client.audio;

import org.jetbrains.annotations.NotNull;

/**
 * Plays a sound on the highest-numbered free channel; when every channel plays, it replaces the first playing sound of the lowest priority
 * below its own that is preemptible. A playing sound the new cue conflicts with ({@link SoundCue#conflictsWith}) keeps it from playing. Every
 * channel is examined once, in order.
 */
public final class PriorityChannelPolicy implements ChannelPolicy {
    /**
     * Creates a {@link PriorityChannelPolicy} instance.
     */
    public PriorityChannelPolicy() {
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
        int available = NO_CHANNEL;
        int replacement = NO_CHANNEL;
        int lowestPriority = cue.getPriority();

        for (int channel = 0; channel < channels.getChannelCount(); channel++) {
            if (!channels.isPlaying(channel)) {
                available = channel;

                continue;
            }

            SoundCue playing = channels.getCue(channel);

            if (playing == null) {
                continue;
            }

            if (cue.conflictsWith(playing)) {
                return NO_CHANNEL;
            }

            if (playing.isPreemptible() && playing.getPriority() < lowestPriority) {
                replacement    = channel;
                lowestPriority = playing.getPriority();
            }
        }

        return available != NO_CHANNEL ? available : replacement;
    }
}
