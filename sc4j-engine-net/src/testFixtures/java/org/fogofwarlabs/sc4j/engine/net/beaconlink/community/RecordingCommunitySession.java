package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Plays a community session by hand for the channel chat: it records the lines said, the whispers sent and the commands' effects, and lets
 * a test bring members, lines and whispers in, which reach the listeners at once (the real session hands them out in its poll).
 */
public final class RecordingCommunitySession implements CommunitySession {
    /**
     * The time stamp of every line and whisper.
     */
    private static final long TIMESTAMP_MILLISECONDS = 0L;

    /**
     * Records a joined channel: its members, its lines and what this session said in it.
     */
    public final class Channel implements ChatChannel {
        private final @NotNull String name;
        private final @NotNull List<PlayerPresence> members;
        private final @NotNull List<ChatLine> lines;
        private final @NotNull List<String> said;
        private boolean joined;

        private Channel(@NotNull String name) {
            this.name    = name;
            this.members = new ArrayList<>();
            this.lines   = new ArrayList<>();
            this.said    = new ArrayList<>();
            this.joined  = true;
        }

        /**
         * @return the channel name, as this session joined it
         */
        @Override
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return {@code true} while the session takes part in the channel, {@code false} after {@link #leave()}
         */
        @Override
        public boolean isJoined() {
            return this.joined;
        }

        /**
         * @return the players in the channel whose presence the session holds, this session's player first, then by name
         */
        @Override
        public @NotNull List<@NotNull PlayerPresence> getMembers() {
            List<PlayerPresence> listed = new ArrayList<>();

            listed.add(RecordingCommunitySession.this.getSelf());
            listed.addAll(this.members);

            return listed;
        }

        /**
         * @return the recent lines of the channel, oldest first: the backlog the session got when it joined and the lines since, bounded
         */
        @Override
        public @NotNull List<@NotNull ChatLine> getLines() {
            return List.copyOf(this.lines);
        }

        /**
         * Says a line in the channel.
         *
         * @param text the text, at most {@value #MAXIMUM_TEXT_LENGTH} UTF-8 bytes
         * @return {@code true} if the line is sent or queued, {@code false} if the queue of lines over the chat rate is full
         */
        @Override
        public boolean say(@NotNull String text) {
            this.said.add(text);
            this.receiveLine(RecordingCommunitySession.this.getSelf(), ChatLineKind.SAY, text);

            return true;
        }

        /**
         * Sends an emote in the channel ({@code /me}).
         *
         * @param text the text, at most {@value #MAXIMUM_TEXT_LENGTH} UTF-8 bytes
         * @return {@code true} if the emote is sent or queued, {@code false} if the queue of lines over the chat rate is full
         */
        @Override
        public boolean emote(@NotNull String text) {
            this.said.add(text);
            this.receiveLine(RecordingCommunitySession.this.getSelf(), ChatLineKind.EMOTE, text);

            return true;
        }

        /**
         * Leaves the channel: the session's presence there lapses and its home peers stop pushing the channel's lines. Calling it again does
         * nothing.
         */
        @Override
        public void leave() {
            this.joined = false;
            RecordingCommunitySession.this.channels.remove(this);
        }

        /**
         * @return the texts this session said or emoted in the channel, in order
         */
        public @NotNull List<String> getSaid() {
            return this.said;
        }

        /**
         * Brings a player into the channel and tells the listeners.
         *
         * @param member the player's presence
         */
        public void addMember(@NotNull PlayerPresence member) {
            this.members.add(member);
            RecordingCommunitySession.this.notifyListeners(listener -> listener.onMembersChanged(this));
        }

        /**
         * Takes a player out of the channel and tells the listeners.
         *
         * @param member the player's presence
         */
        public void removeMember(@NotNull PlayerPresence member) {
            this.members.remove(member);
            RecordingCommunitySession.this.notifyListeners(listener -> listener.onMembersChanged(this));
        }

        /**
         * Adds a line to the channel and hands it to the listeners.
         *
         * @param author the author's presence
         * @param kind   the line's kind
         * @param text   the text
         */
        public void receiveLine(@NotNull PlayerPresence author, @NotNull ChatLineKind kind, @NotNull String text) {
            ChatLine line = new ChatLine(author.getPlayer(), author.getName(), kind, text, TIMESTAMP_MILLISECONDS);

            this.lines.add(line);
            RecordingCommunitySession.this.notifyListeners(listener -> listener.onChatLine(this, line));
        }
    }

    /**
     * Creates a {@link PlayerPresence} instance of a player whose key is a byte repeated.
     *
     * @param keyByte          the byte the player's key repeats
     * @param name             the player's name
     * @param modId            the mod id
     * @param modVersion       the mod version
     * @param playableTogether whether the player can play with this session's player
     * @param status           the status
     * @return the created {@link PlayerPresence} instance
     */
    public static @NotNull PlayerPresence createPresence(int keyByte,
                                                         @NotNull String name,
                                                         @NotNull String modId, @NotNull String modVersion,
                                                         boolean playableTogether,
                                                         @NotNull PlayerStatus status) {
        return createPresence(keyByte, name, modId, modVersion, playableTogether, status, LatencyEstimate.UNKNOWN);
    }

    /**
     * Creates a {@link PlayerPresence} instance of a player whose key is a byte repeated, with an estimated latency.
     *
     * @param keyByte          the byte the player's key repeats
     * @param name             the player's name
     * @param modId            the mod id
     * @param modVersion       the mod version
     * @param playableTogether whether the player can play with this session's player
     * @param status           the status
     * @param latency          the estimated round trip time to the player
     * @return the created {@link PlayerPresence} instance
     */
    public static @NotNull PlayerPresence createPresence(int keyByte,
                                                         @NotNull String name,
                                                         @NotNull String modId, @NotNull String modVersion,
                                                         boolean playableTogether,
                                                         @NotNull PlayerStatus status,
                                                         @NotNull LatencyEstimate latency) {
        byte[] key = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(key, (byte) keyByte);

        return createPresence(LinkPlayer.of(key), name, modId, modVersion, playableTogether, status, "", null, latency);
    }

    /**
     * Creates a {@link PlayerPresence} instance; the one place the recording session constructs presences.
     *
     * @param player           the player
     * @param name             the player's name
     * @param modId            the mod id
     * @param modVersion       the mod version
     * @param playableTogether whether the player can play with this session's player
     * @param status           the status
     * @param awayText         the text the player shows while away, empty for none
     * @param channel          the channel the presence names, or {@code null}
     * @param latency          the estimated round trip time to the player
     * @return the created {@link PlayerPresence} instance
     */
    private static @NotNull PlayerPresence createPresence(@NotNull LinkPlayer player,
                                                          @NotNull String name,
                                                          @NotNull String modId, @NotNull String modVersion,
                                                          boolean playableTogether,
                                                          @NotNull PlayerStatus status, @NotNull String awayText,
                                                          @Nullable String channel,
                                                          @NotNull LatencyEstimate latency) {
        return new PlayerPresence(player, name, modId, modVersion, playableTogether, status, awayText, channel, latency, 1);
    }

    /**
     * Creates a {@link PlayerPresence} instance of a player whose key is a byte repeated, away with an away text.
     *
     * @param keyByte    the byte the player's key repeats
     * @param name       the player's display name
     * @param modId      the id of the mod the player runs
     * @param modVersion the version of the mod the player runs
     * @param awayText   the text the player shows while away
     * @return the created {@link PlayerPresence} instance
     */
    public static @NotNull PlayerPresence createAwayPresence(int keyByte, @NotNull String name, @NotNull String modId, @NotNull String modVersion,
                                                             @NotNull String awayText) {
        PlayerPresence presence = createPresence(keyByte, name, modId, modVersion, true, PlayerStatus.AWAY);

        return createPresence(presence.getPlayer(), name, modId, modVersion, true, PlayerStatus.AWAY, awayText, null, LatencyEstimate.UNKNOWN);
    }

    private final @NotNull PlayerPresence self;
    private @NotNull PlayerStatus status;
    private @NotNull String awayText;

    private final @NotNull List<Channel> channels;
    private final @NotNull List<ChannelSummary> summaries;
    private @Nullable Consumer<Channel> channelSetup;

    private final @NotNull List<LinkPlayer> friends;
    private final @NotNull Set<LinkPlayer> ignored;
    private final @NotNull List<Whisper> whispers;
    private final @NotNull List<CommunityListener> listeners;

    /**
     * Creates a {@link RecordingCommunitySession} instance without channels.
     *
     * @param self the session's own player
     */
    public RecordingCommunitySession(@NotNull PlayerPresence self) {
        this.self     = self;
        this.status   = self.getStatus();
        this.awayText = self.getAwayText();

        this.channels     = new ArrayList<>();
        this.summaries    = new ArrayList<>();
        this.channelSetup = null;

        this.friends   = new ArrayList<>();
        this.ignored   = new LinkedHashSet<>();
        this.whispers  = new ArrayList<>();
        this.listeners = new ArrayList<>();
    }

    /**
     * @return this session's own presence as the others see it
     */
    @Override
    public @NotNull PlayerPresence getSelf() {
        return createPresence(this.self.getPlayer(), this.self.getName(), this.self.getModId(), this.self.getModVersion(), true, this.status,
                              this.awayText, this.channels.isEmpty() ? null : this.channels.getFirst().getName(), LatencyEstimate.UNKNOWN);
    }

    /**
     * @return the channels the session takes part in, in the order it joined them
     */
    @Override
    public @NotNull List<@NotNull ChatChannel> getChannels() {
        return List.copyOf(this.channels);
    }

    /**
     * Lists the channels the session knows of: the channels named in the presences of the players of its mod and of its channels, and its
     * own channels.
     *
     * @return the channels, the most members first, then by name
     */
    @Override
    public @NotNull List<@NotNull ChannelSummary> listChannels() {
        return List.copyOf(this.summaries);
    }

    /**
     * Finds the presence of a player the session sees in any of its topics.
     *
     * @param player the player
     * @return the presence, or {@code null} if the session holds none
     */
    @Override
    public @Nullable PlayerPresence getPresence(@NotNull LinkPlayer player) {
        for (Channel channel : this.channels) {
            for (PlayerPresence member : channel.getMembers()) {
                if (member.getPlayer().equals(player)) {
                    return member;
                }
            }
        }

        return null;
    }

    /**
     * @return the friends: players whose presence the session follows in their player topics, in the order they were added
     */
    @Override
    public @NotNull List<@NotNull LinkPlayer> getFriends() {
        return List.copyOf(this.friends);
    }

    /**
     * @param player the player
     * @return {@code true} if the account ignores the player, {@code false} otherwise
     */
    @Override
    public boolean isIgnored(@NotNull LinkPlayer player) {
        return this.ignored.contains(player);
    }

    /**
     * @return the players the account ignores, the oldest first
     */
    @Override
    public @NotNull List<@NotNull LinkPlayer> getIgnored() {
        return List.copyOf(this.ignored);
    }

    /**
     * Joins a channel, or returns the channel the session already takes part in under that name (names that differ in case or white space
     * only are one channel).
     *
     * @param name the channel name, at most 32 UTF-8 bytes
     * @return the channel
     * @throws IllegalArgumentException if the name is empty
     */
    @Override
    public @NotNull ChatChannel joinChannel(@NotNull String name) {
        if (name.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("A channel name is not empty");
        }

        for (Channel channel : this.channels) {
            if (channel.getName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return channel;
            }
        }

        Channel joined = new Channel(name);
        Consumer<Channel> setup = this.channelSetup;

        this.channels.add(joined);

        if (setup != null) {
            setup.accept(joined);
        }

        return joined;
    }

    /**
     * Sets the status the session's presence shows.
     *
     * @param status the status
     */
    @Override
    public void setStatus(@NotNull PlayerStatus status) {
        this.status = status;
    }

    /**
     * Sets the text the session's presence shows while it is away ({@link PlayerStatus#AWAY}); the others see it in their user lists and when
     * they whisper to this player.
     *
     * @param text the away text, cut to {@value #MAXIMUM_AWAY_TEXT_LENGTH} UTF-8 bytes; empty for none
     */
    @Override
    public void setAwayText(@NotNull String text) {
        this.awayText = text;
    }

    /**
     * Turns the session's presence off or on: an offline-appearing session publishes no presence (whispers to it then fail), while its
     * channels, rooms and games still work.
     * <p>
     * The recording session publishes no presence, so the call changes nothing.
     *
     * @param offline whether the session appears offline
     */
    @Override
    public void setAppearOffline(boolean offline) {
    }

    /**
     * Sends a whisper to a player whose presence the session holds; the outcome comes to the listeners at once: delivered to a player the
     * session sees, not online otherwise.
     *
     * @param player the target
     * @param text   the text, at most {@value ChatChannel#MAXIMUM_TEXT_LENGTH} UTF-8 bytes
     * @return the sent whisper
     */
    @Override
    public @NotNull Whisper whisper(@NotNull LinkPlayer player, @NotNull String text) {
        PlayerPresence target = this.getPresence(player);
        Whisper sent = new Whisper(player, target == null ? player.toString() : target.getName(), false, text, TIMESTAMP_MILLISECONDS);
        WhisperResult result = target == null ? WhisperResult.NOT_ONLINE : WhisperResult.DELIVERED;

        this.whispers.add(sent);
        this.notifyListeners(listener -> listener.onWhisperResult(sent, result));

        return sent;
    }

    /**
     * Ignores a player: its lines and whispers are no longer handed out, and mods hide its rooms. The recorded session keeps the list in
     * memory.
     *
     * @param player the player
     */
    @Override
    public void ignore(@NotNull LinkPlayer player) {
        this.ignored.add(player);
    }

    /**
     * Stops ignoring a player.
     *
     * @param player the player
     */
    @Override
    public void unignore(@NotNull LinkPlayer player) {
        this.ignored.remove(player);
    }

    /**
     * Adds a friend: the session follows the player's presence in its player topic. The install keeps its friends.
     *
     * @param player the player
     */
    @Override
    public void addFriend(@NotNull LinkPlayer player) {
        this.friends.add(player);
    }

    /**
     * Removes a friend.
     *
     * @param player the player
     */
    @Override
    public void removeFriend(@NotNull LinkPlayer player) {
        this.friends.remove(player);
    }

    /**
     * Adds a listener; it is called on the session's thread.
     *
     * @param listener the listener
     */
    @Override
    public void addListener(@NotNull CommunityListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    @Override
    public void removeListener(@NotNull CommunityListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * @return the whispers sent, in order
     */
    public @NotNull List<Whisper> getWhispers() {
        return this.whispers;
    }

    /**
     * @return the number of listeners
     */
    public int getListenerCount() {
        return this.listeners.size();
    }

    /**
     * Sets what fills every channel the session joins from now on, for example its members and lines.
     *
     * @param setup takes each joined channel
     */
    public void setChannelSetup(@NotNull Consumer<Channel> setup) {
        this.channelSetup = setup;
    }

    /**
     * Adds a channel the session knows of.
     *
     * @param name        the channel name
     * @param memberCount its member count
     */
    public void addSummary(@NotNull String name, int memberCount) {
        this.summaries.add(new ChannelSummary(name, memberCount));
    }

    /**
     * Finds a channel the session takes part in.
     *
     * @param name the channel name as joined
     * @return the channel, or {@code null} if the session is not in it
     */
    public @Nullable Channel findChannel(@NotNull String name) {
        for (Channel channel : this.channels) {
            if (channel.getName().equals(name)) {
                return channel;
            }
        }

        return null;
    }

    /**
     * Hands an incoming whisper to the listeners.
     *
     * @param sender the sender's presence
     * @param text   the text
     */
    public void receiveWhisper(@NotNull PlayerPresence sender, @NotNull String text) {
        Whisper whisper = new Whisper(sender.getPlayer(), sender.getName(), true, text, TIMESTAMP_MILLISECONDS);

        this.notifyListeners(listener -> listener.onWhisper(whisper));
    }

    private void notifyListeners(@NotNull Consumer<CommunityListener> call) {
        for (CommunityListener listener : List.copyOf(this.listeners)) {
            call.accept(listener);
        }
    }
}
