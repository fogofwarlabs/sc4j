package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

/**
 * Lists the kinds of channel chat log entries; a screen draws each kind in its own colour.
 */
public enum ChatEntryKind {
    /**
     * A line said in the channel.
     */
    SAY,
    /**
     * An emote in the channel ({@code /me}).
     */
    EMOTE,
    /**
     * A whisper from or to another player.
     */
    WHISPER,
    /**
     * A notice: a join, a departure, a command's answer or the help.
     */
    INFO,
    /**
     * A refused command.
     */
    ERROR
}
