package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs the Beacon.Link channel chat over the engine's community session: it keeps the player in one channel, or in up to
 * {@value CommunitySession#MAXIMUM_CHANNELS} channels at once when it keeps channels, turns the channels' lines, members, whispers and the typed
 * slash commands into log entries ({@link ChatEntry}), orders a channel's members for the user list, lists the known channels and manages the
 * friends and the blocked players: the community session's ignore list, which the account's sessions share (their lines and whispers are hidden,
 * their rooms not listed; {@code /ignore} and blocking are one list). The input either says lines in the current channel or whispers to a player
 * ({@link #getWhisperTarget}). The slash commands are the {@link ChatCommand}s.
 * <p>
 * An entry names the channel it belongs to ({@link ChatEntry#getChannel()}): a line, a member notice and the notices of entering and leaving
 * name their channel, a whisper names none, and the other entries, the notices after a sent whisper included, name the current channel.
 * <p>
 * A client keeps one chat per session: it keeps listening and logging while the player is in a room or a game, and keeps the last
 * {@value #HISTORY} entries, which a log can take first when it is added ({@link #addLog}). Several logs take the entries at once. The
 * screens draw it; it holds no drawing state.
 */
public final class ChannelChat implements CommunityListener {
    /**
     * The most members a default channel takes before the next numbered channel is chosen.
     */
    public static final int DEFAULT_CHANNEL_CAPACITY = 64;
    /**
     * The channel every mod's players meet in.
     */
    public static final @NotNull String SHARED_CHANNEL = "SC4J";
    /**
     * The most log entries the chat keeps for the next visit of the chat screen.
     */
    public static final int HISTORY = 100;

    /**
     * The first number of the default channels ({@code <base>-1}, {@code <base>-2}, ...).
     */
    private static final int FIRST_CHANNEL_NUMBER = 1;
    /**
     * The most default channels the chat tries before it takes the last one.
     */
    private static final int MAXIMUM_CHANNEL_NUMBER = 99;
    private static final @NotNull String CHANNEL_NUMBER_SEPARATOR = "-";
    private static final @NotNull String WORD_SEPARATOR = " ";
    private static final @NotNull String LIST_SEPARATOR = ", ";
    private static final @NotNull String NAME_SEPARATOR = ": ";
    private static final @NotNull String FRIENDS_ADD = "add";
    private static final @NotNull String FRIENDS_REMOVE = "remove";
    private static final @NotNull String FRIENDS_LIST = "list";

    /**
     * Returns the default channel of a channel base name: the first numbered channel ({@code <base>-1}, {@code <base>-2}, ...) with fewer
     * members than {@link #DEFAULT_CHANNEL_CAPACITY} among the known channels.
     *
     * @param base     the base name, for example the mod's name
     * @param channels the known channels ({@link CommunitySession#listChannels()})
     * @return the channel name
     */
    public static @NotNull String findDefaultChannel(@NotNull String base, @NotNull List<@NotNull ChannelSummary> channels) {
        String name = base + CHANNEL_NUMBER_SEPARATOR + FIRST_CHANNEL_NUMBER;

        for (int number = FIRST_CHANNEL_NUMBER; number <= MAXIMUM_CHANNEL_NUMBER; number++) {
            name = base + CHANNEL_NUMBER_SEPARATOR + number;

            if (countMembers(name, channels) < DEFAULT_CHANNEL_CAPACITY) {
                return name;
            }
        }

        return name;
    }

    private static int countMembers(@NotNull String name, @NotNull List<@NotNull ChannelSummary> channels) {
        ChannelSummary channel = findChannel(name, channels);

        return channel == null ? 0 : channel.getMemberCount();
    }

    private static @Nullable ChannelSummary findChannel(@NotNull String name, @NotNull List<@NotNull ChannelSummary> channels) {
        for (ChannelSummary channel : channels) {
            if (channel.getName().equalsIgnoreCase(name)) {
                return channel;
            }
        }

        return null;
    }

    /**
     * Returns the length of a text as the community's limits count it ({@link ChatChannel#MAXIMUM_TEXT_LENGTH},
     * {@link CommunitySession#MAXIMUM_AWAY_TEXT_LENGTH}): its UTF-8 bytes.
     *
     * @param text the text
     * @return the number of UTF-8 bytes
     */
    public static int getTextLength(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isTextTooLong(@NotNull String text) {
        return getTextLength(text) > ChatChannel.MAXIMUM_TEXT_LENGTH;
    }

    private final @NotNull CommunitySession community;
    private final @NotNull Locale locale;
    private final @NotNull String channelBase;
    private final boolean keepChannels;

    private @Nullable ChatChannel channel;
    /**
     * The channels the chat is in, in the order it entered them; the current channel is one of them.
     */
    private final @NotNull List<@NotNull ChatChannel> channels;
    /**
     * The tagged names of the members of each channel the chat is in, by player, for the join and departure notices.
     */
    private final @NotNull Map<@NotNull ChatChannel, @NotNull Map<@NotNull LinkPlayer, @NotNull String>> members;
    /**
     * The last tagged name seen of each player, for the friends whose presence the session does not hold.
     */
    private final @NotNull Map<@NotNull LinkPlayer, @NotNull String> knownNames;
    /**
     * The player of the last incoming whisper, for {@code /r}.
     */
    private @Nullable LinkPlayer replyTarget;
    /**
     * The player the input whispers to, or {@code null} while it says lines in the channel.
     */
    private @Nullable LinkPlayer whisperTarget;

    /**
     * The recent log entries, oldest first, and the logs that take the new ones.
     */
    private final @NotNull List<@NotNull ChatEntry> history;
    private final @NotNull List<@NotNull Consumer<@NotNull ChatEntry>> logs;

    /**
     * Creates a {@link ChannelChat} instance that keeps the player in one channel: joining another channel leaves the current one;
     * {@link #open()} joins its channel.
     *
     * @param community   the community session, whose ignore list holds the blocked players
     * @param locale      the locale of the texts
     * @param channelBase the base name of the default channels
     */
    public ChannelChat(@NotNull CommunitySession community, @NotNull Locale locale, @NotNull String channelBase) {
        this(community, locale, channelBase, false);
    }

    /**
     * Creates a {@link ChannelChat} instance; {@link #open()} joins its channel.
     *
     * @param community    the community session, whose ignore list holds the blocked players
     * @param locale       the locale of the texts
     * @param channelBase  the base name of the default channels
     * @param keepChannels whether joining a channel keeps the channels the chat is in, up to {@value CommunitySession#MAXIMUM_CHANNELS}, instead
     *                     of leaving the current one
     */
    public ChannelChat(@NotNull CommunitySession community, @NotNull Locale locale, @NotNull String channelBase, boolean keepChannels) {
        this.community    = community;
        this.locale       = locale;
        this.channelBase  = channelBase;
        this.keepChannels = keepChannels;

        this.channel       = null;
        this.channels      = new ArrayList<>();
        this.members       = new HashMap<>();
        this.knownNames    = new HashMap<>();
        this.replyTarget   = null;
        this.whisperTarget = null;

        this.history = new ArrayList<>();
        this.logs    = new ArrayList<>();
    }

    /**
     * @return the current channel: the one the input says lines in, or {@code null} after {@code /leave} left the last channel
     */
    public @Nullable ChatChannel getChannel() {
        return this.channel;
    }

    /**
     * @return the channels the chat is in, in the order it entered them; the current channel is one of them
     */
    public @NotNull List<@NotNull ChatChannel> getChannels() {
        return List.copyOf(this.channels);
    }

    /**
     * Returns the current channel's members in the user list's order ({@link #getMembers(ChatChannel)}).
     *
     * @return the members, empty without a channel
     */
    public @NotNull List<@NotNull PlayerPresence> getMembers() {
        ChatChannel current = this.channel;

        return current == null ? List.of() : this.getMembers(current);
    }

    /**
     * Returns a channel's members in the user list's order: this player first, then friends, then players of this mod who can play with this
     * player, then the others, each group by tagged name.
     *
     * @param chatChannel the channel
     * @return the members
     */
    public @NotNull List<@NotNull PlayerPresence> getMembers(@NotNull ChatChannel chatChannel) {
        LinkPlayer self = this.community.getSelf().getPlayer();
        List<LinkPlayer> friends = this.community.getFriends();
        String modId = this.community.getSelf().getModId();
        List<PlayerPresence> sorted = new ArrayList<>(chatChannel.getMembers());

        sorted.sort(Comparator.<PlayerPresence>comparingInt(member -> member.getPlayer().equals(self) ? 0 : 1)
                              .thenComparingInt(member -> friends.contains(member.getPlayer()) ? 0 : 1)
                              .thenComparingInt(member -> member.getModId().equals(modId) && member.canPlayTogether() ? 0 : 1)
                              .thenComparing(member -> member.getTaggedName().toLowerCase(Locale.ROOT)));

        return sorted;
    }

    /**
     * @param member a member
     * @return {@code true} if the member is one of this player's friends, {@code false} otherwise
     */
    public boolean isFriend(@NotNull PlayerPresence member) {
        return this.community.getFriends().contains(member.getPlayer());
    }

    /**
     * @param member a member
     * @return {@code true} if the member is this player, {@code false} otherwise
     */
    public boolean isSelf(@NotNull PlayerPresence member) {
        return this.community.getSelf().getPlayer().equals(member.getPlayer());
    }

    /**
     * @return {@code true} if this player shows away, {@code false} otherwise
     */
    public boolean isAway() {
        return this.community.getSelf().getStatus() == PlayerStatus.AWAY;
    }

    /**
     * @param player a player
     * @return {@code true} if the account blocks (ignores) the player, {@code false} otherwise
     */
    public boolean isBlocked(@NotNull LinkPlayer player) {
        return this.community.isIgnored(player);
    }

    /**
     * @return the player the input whispers to, or {@code null} while it says lines in the channel
     */
    public @Nullable LinkPlayer getWhisperTarget() {
        return this.whisperTarget;
    }

    /**
     * Sets the player the input whispers to.
     *
     * @param player the player, or {@code null} to say lines in the channel again
     */
    public void setWhisperTarget(@Nullable LinkPlayer player) {
        this.whisperTarget = player;
    }

    /**
     * Returns the text shown before the input: the whisper target while the input whispers, else the current channel.
     *
     * @return the prefix, empty without a channel
     */
    public @NotNull String getInputPrefix() {
        LinkPlayer target = this.whisperTarget;
        ChatChannel current = this.channel;

        if (target != null) {
            return CommunityText.WHISPER_TO.format(this.locale, this.getTaggedName(target));
        }

        return current == null ? "" : CommunityText.INPUT_CHANNEL.format(this.locale, current.getName());
    }

    /**
     * Returns the start of a whisper to a player, as the input takes it.
     *
     * @param player the player
     * @return the whisper command with the player's tagged name and a space
     */
    public @NotNull String createWhisperStart(@NotNull LinkPlayer player) {
        return ChatCommand.WHISPER.getNames().getFirst() + WORD_SEPARATOR + this.getTaggedName(player) + WORD_SEPARATOR;
    }

    /**
     * Returns a player's tagged name: from the presence the session holds, else the last one the chat saw, else an unknown name with the
     * player's tag.
     *
     * @param player the player
     * @return the tagged name
     */
    public @NotNull String getTaggedName(@NotNull LinkPlayer player) {
        PlayerPresence presence = this.community.getPresence(player);

        if (presence != null) {
            return presence.getTaggedName();
        }

        String known = this.knownNames.get(player);

        return known != null ? known : player.formatTaggedName(CommunityText.UNKNOWN.get(this.locale));
    }

    /**
     * Lists the channels for the channel list: the channels the session knows of ({@link CommunitySession#listChannels()}), then the current
     * channel and the shared channel when the session knows of neither.
     *
     * @return the channels
     */
    public @NotNull List<@NotNull ChannelSummary> listChannels() {
        List<ChannelSummary> channels = new ArrayList<>(this.community.listChannels());
        ChatChannel current = this.channel;

        if (current != null && findChannel(current.getName(), channels) == null) {
            channels.add(new ChannelSummary(current.getName(), current.getMembers().size()));
        }

        if (findChannel(SHARED_CHANNEL, channels) == null) {
            channels.add(new ChannelSummary(SHARED_CHANNEL, 0));
        }

        return channels;
    }

    /**
     * @return the friends, in the order they were added
     */
    public @NotNull List<@NotNull LinkPlayer> getFriends() {
        return this.community.getFriends();
    }

    /**
     * Finds the presence of a player the session sees.
     *
     * @param player the player
     * @return the presence, or {@code null} if the player is offline or the session holds none
     */
    public @Nullable PlayerPresence findPresence(@NotNull LinkPlayer player) {
        return this.community.getPresence(player);
    }


    /**
     * Enters a named channel and starts listening to the community; the channel's recent lines come first in the log. A refused name leaves
     * the chat as it was.
     *
     * @param name the channel name
     * @throws IllegalArgumentException if the name is empty, too long or has a control character
     * @throws IllegalStateException    if the session takes part in {@value CommunitySession#MAXIMUM_CHANNELS} channels
     */
    public void open(@NotNull String name) {
        ChatChannel joined = this.community.joinChannel(name);

        this.community.addListener(this);
        this.enter(joined);
    }

    /**
     * Enters the channels the session is already in, or the default channel of the base name, and starts listening to the community; the
     * channels' recent lines come first in the log. A chat that keeps channels enters every joined channel, the last one current; else it
     * enters the last joined one.
     */
    public void open() {
        List<ChatChannel> joined = new ArrayList<>();

        for (ChatChannel candidate : this.community.getChannels()) {
            if (candidate.isJoined()) {
                joined.add(candidate);
            }
        }

        if (joined.isEmpty()) {
            joined.add(this.community.joinChannel(findDefaultChannel(this.channelBase, this.community.listChannels())));
        } else if (!this.keepChannels) {
            joined = List.of(joined.getLast());
        }

        this.community.addListener(this);

        for (ChatChannel entered : joined) {
            this.enter(entered);
        }
    }

    /**
     * Shows the player available again after a room or a game; an away player stays away.
     */
    public void showAvailable() {
        PlayerStatus status = this.community.getSelf().getStatus();

        if (status == PlayerStatus.IN_ROOM || status == PlayerStatus.IN_GAME) {
            this.community.setStatus(PlayerStatus.AVAILABLE);
        }
    }

    /**
     * Shows this player away or available, as the status choice sets it, and notes the change in the log; the same status changes nothing.
     * The away text stays.
     *
     * @param away {@code true} to show away, {@code false} to show available
     */
    public void setAway(boolean away) {
        if (away == this.isAway()) {
            return;
        }

        this.community.setStatus(away ? PlayerStatus.AWAY : PlayerStatus.AVAILABLE);
        this.addAwayInfo(away);
    }

    /**
     * Runs {@code /away}: without a text it shows this player away or available again; with a text it shows the player away with that text
     * (others see it in their user lists and when they whisper to the player), also while already away.
     *
     * @param text the typed text after the command, empty for none
     */
    private void toggleAway(@NotNull String text) {
        if (text.isEmpty()) {
            this.setAway(!this.isAway());

            return;
        }

        this.community.setAwayText(text);
        this.community.setStatus(PlayerStatus.AWAY);
        this.addAwayInfo(true);
    }

    private void addAwayInfo(boolean away) {
        String text = this.community.getSelf().getAwayText();

        this.addInfo(!away ? CommunityText.AWAY_OFF.get(this.locale)
                           : text.isEmpty() ? CommunityText.AWAY_ON.get(this.locale) : CommunityText.STATUS_AWAY_TEXT.format(this.locale, text));
    }

    /**
     * Adds a log that takes the new entries, beside the logs added before.
     *
     * @param log           takes the entries in order
     * @param replayHistory whether the log takes the recent entries, at most {@value #HISTORY}, first
     */
    public void addLog(@NotNull Consumer<@NotNull ChatEntry> log, boolean replayHistory) {
        this.logs.add(log);

        if (replayHistory) {
            for (ChatEntry entry : this.history) {
                log.accept(entry);
            }
        }
    }

    /**
     * Removes a log; the other logs keep taking the entries.
     *
     * @param log the log, as it was added
     */
    public void removeLog(@NotNull Consumer<@NotNull ChatEntry> log) {
        this.logs.remove(log);
    }

    /**
     * Takes a typed whisper start out of the input's text: a whisper command, the name of a player the chat finds ({@link #findPlayer}) and a
     * space make that player the whisper target ({@link #getWhisperTarget}).
     *
     * @param typed the input's text
     * @return the text after the whisper start, or the text unchanged if it does not start with one
     */
    public @NotNull String takeWhisperStart(@NotNull String typed) {
        int commandEnd = typed.indexOf(' ');

        if (commandEnd < 0 || ChatCommand.find(typed) != ChatCommand.WHISPER) {
            return typed;
        }

        int nameEnd = typed.indexOf(' ', commandEnd + 1);

        if (nameEnd <= commandEnd + 1) {
            return typed;
        }

        PlayerPresence target = this.findPlayer(typed.substring(commandEnd + 1, nameEnd));

        if (target == null || this.isSelf(target)) {
            return typed;
        }

        this.whisperTarget = target.getPlayer();

        return typed.substring(nameEnd + 1);
    }

    /**
     * Blocks a player: the player's lines and whispers are hidden and the player's rooms are not listed, in every session of the account (the
     * community session's ignore list).
     *
     * @param player the player; this player is never blocked
     */
    public void block(@NotNull LinkPlayer player) {
        if (player.equals(this.community.getSelf().getPlayer())) {
            return;
        }

        if (player.equals(this.whisperTarget)) {
            this.whisperTarget = null;
        }

        this.community.ignore(player);
        this.addInfo(CommunityText.BLOCKED.format(this.locale, this.getTaggedName(player)));
    }

    /**
     * Stops blocking a player, in every session of the account.
     *
     * @param player the player
     */
    public void unblock(@NotNull LinkPlayer player) {
        this.community.unignore(player);
        this.addInfo(CommunityText.UNBLOCKED.format(this.locale, this.getTaggedName(player)));
    }

    /**
     * Removes the rooms of blocked players.
     *
     * @param rooms the found rooms
     * @return the rooms whose host is not blocked, in their order
     */
    public @NotNull List<@NotNull FoundRoom> listUnblockedRooms(@NotNull List<@NotNull FoundRoom> rooms) {
        List<FoundRoom> unblocked = new ArrayList<>(rooms.size());

        for (FoundRoom room : rooms) {
            if (!this.community.isIgnored(room.getHost().getPlayer())) {
                unblocked.add(room);
            }
        }

        return unblocked;
    }

    /**
     * Runs a typed line in a channel the chat is in: makes the channel the current one, as {@link #selectChannel} does, and runs the line
     * ({@link #submit(String)}). A channel the chat is not in logs an error.
     *
     * @param chatChannel the channel
     * @param typed       the typed text
     */
    public void submit(@NotNull ChatChannel chatChannel, @NotNull String typed) {
        if (!this.channels.contains(chatChannel)) {
            this.addError(CommunityText.NO_CHANNEL.get(this.locale));

            return;
        }

        this.channel       = chatChannel;
        this.whisperTarget = null;
        this.submit(typed);
    }

    /**
     * Runs a typed line: a slash command ({@link ChatCommand}), or a line said in the channel or whispered to the whisper target
     * ({@link #getWhisperTarget}).
     *
     * @param typed the typed text
     */
    public void submit(@NotNull String typed) {
        String text = typed.strip();
        LinkPlayer target = this.whisperTarget;

        if (text.isEmpty()) {
            return;
        }

        if (!ChatCommand.isCommand(text)) {
            if (target != null) {
                this.sendWhisper(target, text);
            } else {
                this.say(text, false);
            }

            return;
        }

        int space = text.indexOf(' ');
        String rest = space < 0 ? "" : text.substring(space + 1).strip();
        ChatCommand command = ChatCommand.find(text);

        if (command == null) {
            this.addError(CommunityText.UNKNOWN_COMMAND.format(this.locale, ChatCommand.getName(text)));

            return;
        }

        switch (command) {
            case JOIN -> this.join(rest);
            case LEAVE -> this.leave();
            case WHISPER -> this.whisper(rest);
            case REPLY -> this.reply(rest);
            case EMOTE -> this.say(rest, true);
            case WHO -> this.listMembers();
            case WHOIS -> this.describe(rest);
            case IGNORE -> this.ignore(rest, true);
            case UNIGNORE -> this.ignore(rest, false);
            case FRIENDS -> this.runFriends(rest);
            case AWAY -> this.toggleAway(rest);
            case HELP -> this.showHelp();
        }
    }

    private void say(@NotNull String text, boolean emote) {
        ChatChannel current = this.channel;

        if (text.isEmpty()) {
            // Only an emote can be empty: a typed line without a command is never empty.
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_EMOTE.get(this.locale)));

            return;
        }

        if (current == null) {
            this.addError(CommunityText.NO_CHANNEL.get(this.locale));

            return;
        }

        if (isTextTooLong(text)) {
            this.addError(CommunityText.TOO_LONG.get(this.locale));

            return;
        }

        // The channel hands the session's own line back to the listener, which logs it.
        boolean sent = emote ? current.emote(text) : current.say(text);

        if (!sent) {
            this.addError(CommunityText.TOO_FAST.get(this.locale));
        }
    }

    private void whisper(@NotNull String arguments) {
        int space = arguments.indexOf(' ');
        String text = space < 0 ? "" : arguments.substring(space + 1).strip();

        if (text.isEmpty()) {
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_WHISPER.get(this.locale)));

            return;
        }

        String name = arguments.substring(0, space);
        PlayerPresence target = this.findPlayer(name);

        if (target == null) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, name));

            return;
        }

        this.sendWhisper(target.getPlayer(), text);
    }

    private void reply(@NotNull String text) {
        LinkPlayer target = this.replyTarget;

        if (target == null) {
            this.addError(CommunityText.NO_REPLY.get(this.locale));

            return;
        }

        if (text.isEmpty()) {
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_REPLY.get(this.locale)));

            return;
        }

        this.sendWhisper(target, text);
    }

    private void sendWhisper(@NotNull LinkPlayer target, @NotNull String text) {
        if (target.equals(this.community.getSelf().getPlayer())) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, this.community.getSelf().getTaggedName()));

            return;
        }

        if (isTextTooLong(text)) {
            this.addError(CommunityText.TOO_LONG.get(this.locale));

            return;
        }

        // The sent whisper shows once its outcome is known (onWhisperResult).
        this.community.whisper(target, text);
    }

    /**
     * Joins a channel and makes it the current one; a chat that does not keep channels leaves the previous current one. A refused name or
     * the channel limit is logged as an error.
     *
     * @param name the channel name
     */
    public void join(@NotNull String name) {
        if (name.isEmpty()) {
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_JOIN.get(this.locale)));

            return;
        }

        ChatChannel previous = this.channel;
        ChatChannel joined;

        try {
            joined = this.community.joinChannel(name);
        } catch (IllegalArgumentException exception) {
            this.addError(CommunityText.INVALID_NAME.get(this.locale));

            return;
        } catch (IllegalStateException exception) {
            this.addError(CommunityText.LIMIT.get(this.locale));

            return;
        }

        if (this.keepChannels && this.channels.contains(joined)) {
            this.selectChannel(joined.getName());

            return;
        }

        if (!this.keepChannels && previous != null && previous != joined) {
            previous.leave();
            this.channels.remove(previous);
            this.members.remove(previous);
        }

        this.enter(joined);
    }

    /**
     * Makes a channel the chat is in the current one and lets the input say lines there.
     *
     * @param name the channel's name, as the session joined it
     * @return {@code true} if the chat is in the channel, {@code false} otherwise
     */
    public boolean selectChannel(@NotNull String name) {
        for (ChatChannel candidate : this.channels) {
            if (candidate.getName().equals(name)) {
                this.channel       = candidate;
                this.whisperTarget = null;

                return true;
            }
        }

        return false;
    }

    /**
     * Leaves the current channel ({@code /leave}); without a channel it logs an error.
     */
    public void leave() {
        ChatChannel current = this.channel;

        if (current == null) {
            this.addError(CommunityText.NO_CHANNEL.get(this.locale));

            return;
        }

        this.leave(current);
    }

    /**
     * Leaves a channel the chat is in; when it is the current one, the channel entered last of the others becomes the current one. A channel
     * the chat is not in logs an error.
     *
     * @param chatChannel the channel
     */
    public void leave(@NotNull ChatChannel chatChannel) {
        if (!this.channels.contains(chatChannel)) {
            this.addError(CommunityText.NO_CHANNEL.get(this.locale));

            return;
        }

        chatChannel.leave();
        this.channels.remove(chatChannel);
        this.members.remove(chatChannel);

        if (this.channel == chatChannel) {
            this.channel = this.channels.isEmpty() ? null : this.channels.getLast();
        }

        this.addChannelInfo(chatChannel, CommunityText.CHANNEL_LEFT.format(this.locale, chatChannel.getName()), null);
    }

    /**
     * Makes a channel the current one: announces it, logs its recent lines and takes its members without notices.
     *
     * @param joined the joined channel
     */
    private void enter(@NotNull ChatChannel joined) {
        Map<LinkPlayer, String> known = new LinkedHashMap<>();

        this.channel = joined;

        if (!this.channels.contains(joined)) {
            this.channels.add(joined);
        }

        this.members.put(joined, known);

        this.addChannelInfo(joined, CommunityText.CHANNEL.format(this.locale, joined.getName()), null);

        for (ChatLine line : joined.getLines()) {
            this.addLine(joined, line);
        }

        for (PlayerPresence member : joined.getMembers()) {
            known.put(member.getPlayer(), member.getTaggedName());
            this.knownNames.put(member.getPlayer(), member.getTaggedName());
        }
    }

    private void listMembers() {
        ChatChannel current = this.channel;

        if (current == null) {
            this.addError(CommunityText.NO_CHANNEL.get(this.locale));

            return;
        }

        List<String> names = new ArrayList<>();

        for (PlayerPresence member : this.getMembers()) {
            names.add(member.getTaggedName());
        }

        this.addInfo(CommunityText.USERS.format(this.locale, current.getName(), names.size(), String.join(LIST_SEPARATOR, names)));
    }

    private void describe(@NotNull String name) {
        if (name.isEmpty()) {
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_WHOIS.get(this.locale)));

            return;
        }

        PlayerPresence player = this.findPlayer(name);

        if (player == null) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, name));

            return;
        }

        this.addInfo(CommunityText.USER_INFO.format(this.locale, player.getTaggedName(), this.describeMod(player), this.describeStatus(player)));
    }

    /**
     * Returns a player's mod as the user list shows it: the mod id and version, with the mark of another version.
     *
     * @param player the player
     * @return the mod text
     */
    public @NotNull String describeMod(@NotNull PlayerPresence player) {
        String mod = player.getModId() + WORD_SEPARATOR + player.getModVersion();

        return player.canPlayTogether() ? mod : mod + LIST_SEPARATOR + CommunityText.OTHER_VERSION.get(this.locale);
    }

    /**
     * Returns a player's status text, with the channel of a player in a channel.
     *
     * @param player the player
     * @return the status text
     */
    public @NotNull String describeStatus(@NotNull PlayerPresence player) {
        String status = this.getStatusText(player);
        String channelName = player.getChannel();

        return channelName == null ? status : status + LIST_SEPARATOR + channelName;
    }

    /**
     * Returns a player's status word.
     *
     * @param player the player's presence, or {@code null} for a player who is offline
     * @return the status word
     */
    public @NotNull String getStatusText(@Nullable PlayerPresence player) {
        if (player == null) {
            return CommunityText.STATUS_OFFLINE.get(this.locale);
        }

        return switch (player.getStatus()) {
            case AVAILABLE -> CommunityText.STATUS_AVAILABLE.get(this.locale);
            case AWAY -> player.getAwayText().isEmpty() ? CommunityText.STATUS_AWAY.get(this.locale)
                                                        : CommunityText.STATUS_AWAY_TEXT.format(this.locale, player.getAwayText());
            case IN_ROOM -> CommunityText.STATUS_IN_ROOM.get(this.locale);
            case IN_GAME -> CommunityText.STATUS_IN_GAME.get(this.locale);
        };
    }

    private void ignore(@NotNull String name, boolean ignored) {
        if (name.isEmpty()) {
            CommunityText usage = ignored ? CommunityText.HELP_IGNORE : CommunityText.HELP_UNIGNORE;

            this.addError(CommunityText.USAGE.format(this.locale, usage.get(this.locale)));

            return;
        }

        PlayerPresence player = this.findPlayer(name);

        if (player == null || this.isSelf(player)) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, name));

            return;
        }

        if (ignored) {
            this.community.ignore(player.getPlayer());
        } else {
            this.community.unignore(player.getPlayer());
        }

        this.addInfo((ignored ? CommunityText.IGNORED : CommunityText.UNIGNORED).format(this.locale, player.getTaggedName()));
    }

    private void runFriends(@NotNull String arguments) {
        int space = arguments.indexOf(' ');
        String action = (space < 0 ? arguments : arguments.substring(0, space)).toLowerCase(Locale.ROOT);
        String name = space < 0 ? "" : arguments.substring(space + 1).strip();

        if (action.isEmpty() || action.equals(FRIENDS_LIST)) {
            List<String> names = new ArrayList<>();

            for (LinkPlayer friend : this.community.getFriends()) {
                names.add(this.getTaggedName(friend));
            }

            this.addInfo(CommunityText.FRIENDS.format(this.locale, names.isEmpty() ? CommunityText.UNKNOWN.get(this.locale)
                                                                                   : String.join(LIST_SEPARATOR, names)));

            return;
        }

        boolean add = action.equals(FRIENDS_ADD);

        if (!add && !action.equals(FRIENDS_REMOVE) || name.isEmpty()) {
            this.addError(CommunityText.USAGE.format(this.locale, CommunityText.HELP_FRIENDS.get(this.locale)));

            return;
        }

        if (add) {
            this.addFriend(name);

            return;
        }

        PlayerPresence player = this.findPlayer(name);

        if (player == null || this.isSelf(player)) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, name));

            return;
        }

        this.removeFriend(player.getPlayer());
    }

    /**
     * Adds a friend by tagged name ({@code Name#tag}) or by a name one player the session sees has; an unknown name or the friend limit is
     * logged as an error.
     *
     * @param name the tagged name or the name
     * @return {@code true} if the player is a friend now, {@code false} otherwise
     */
    public boolean addFriend(@NotNull String name) {
        String stripped = name.strip();
        PlayerPresence player = this.findPlayer(stripped);

        if (player == null || this.isSelf(player)) {
            this.addError(CommunityText.UNKNOWN_PLAYER.format(this.locale, stripped));

            return false;
        }

        if (this.community.getFriends().contains(player.getPlayer())) {
            return true;
        }

        try {
            this.community.addFriend(player.getPlayer());
        } catch (IllegalStateException exception) {
            this.addError(CommunityText.LIMIT.get(this.locale));

            return false;
        }

        this.knownNames.put(player.getPlayer(), player.getTaggedName());
        this.addInfo(CommunityText.FRIEND_ADDED.format(this.locale, player.getTaggedName()));

        return true;
    }

    /**
     * Removes a friend.
     *
     * @param player the friend
     */
    public void removeFriend(@NotNull LinkPlayer player) {
        String name = this.getTaggedName(player);

        this.community.removeFriend(player);
        this.addInfo(CommunityText.FRIEND_REMOVED.format(this.locale, name));
    }

    private void showHelp() {
        this.addInfo(CommunityText.HELP_TITLE.get(this.locale));

        for (ChatCommand command : ChatCommand.values()) {
            this.addInfo(command.getHelp().get(this.locale));
        }
    }

    /**
     * Finds a player the session sees: a member of a joined channel or a friend, by tagged name ({@code Name#tag}, case ignored), or by name
     * alone when one player has it.
     *
     * @param name the tagged name or the name
     * @return the player's presence, or {@code null} if none or several match
     */
    public @Nullable PlayerPresence findPlayer(@NotNull String name) {
        List<PlayerPresence> known = new ArrayList<>();

        known.add(this.community.getSelf());

        for (ChatChannel joined : this.community.getChannels()) {
            known.addAll(joined.getMembers());
        }

        for (LinkPlayer friend : this.community.getFriends()) {
            PlayerPresence presence = this.community.getPresence(friend);

            if (presence != null) {
                known.add(presence);
            }
        }

        boolean tagged = name.indexOf(LinkPlayer.TAG_SEPARATOR) >= 0;
        PlayerPresence found = null;

        for (PlayerPresence presence : known) {
            String candidate = tagged ? presence.getTaggedName() : presence.getName();

            if (!candidate.equalsIgnoreCase(name) || found != null && found.getPlayer().equals(presence.getPlayer())) {
                continue;
            }

            if (found != null) {
                return null;
            }

            found = presence;
        }

        return found;
    }

    private void addLine(@NotNull ChatChannel chatChannel, @NotNull ChatLine line) {
        if (this.community.isIgnored(line.getAuthor())) {
            return;
        }

        String name = line.getTaggedAuthorName();

        if (line.getKind() == ChatLineKind.EMOTE) {
            this.addEntry(new ChatEntry(ChatEntryKind.EMOTE, "", name + WORD_SEPARATOR + line.getText(), chatChannel, line.getAuthor(), name));
        } else {
            this.addEntry(new ChatEntry(ChatEntryKind.SAY, name + NAME_SEPARATOR, line.getText(), chatChannel, line.getAuthor(), name));
        }
    }

    private void addChannelInfo(@NotNull ChatChannel chatChannel, @NotNull String text, @Nullable LinkPlayer player) {
        this.addEntry(new ChatEntry(ChatEntryKind.INFO, "", text, chatChannel, player, player == null ? null : this.getTaggedName(player)));
    }

    private void addInfo(@NotNull String text) {
        this.addEntry(new ChatEntry(ChatEntryKind.INFO, "", text, this.channel, null, null));
    }

    private void addError(@NotNull String text) {
        this.addEntry(new ChatEntry(ChatEntryKind.ERROR, "", text, this.channel, null, null));
    }

    /**
     * Keeps an entry in the history, the oldest going once it is full, and hands it to the logs.
     *
     * @param entry the entry
     */
    private void addEntry(@NotNull ChatEntry entry) {
        this.history.add(entry);

        while (this.history.size() > HISTORY) {
            this.history.removeFirst();
        }

        for (Consumer<ChatEntry> log : List.copyOf(this.logs)) {
            log.accept(entry);
        }
    }


    /**
     * Called when a joined channel got a new line, the session's own lines included; lines of ignored players are not handed out.
     * <p>
     * Logs the lines of the channels the chat is in; a blocked player's lines are hidden.
     *
     * @param chatChannel the channel
     * @param line        the line
     */
    @Override
    public void onChatLine(@NotNull ChatChannel chatChannel, @NotNull ChatLine line) {
        if (this.channels.contains(chatChannel)) {
            this.addLine(chatChannel, line);
        }
    }

    /**
     * Called when a player came into a joined channel, left it, or its presence changed (status, mod, name).
     * <p>
     * Logs the players who came into or left the channels the chat is in.
     *
     * @param chatChannel the channel
     */
    @Override
    public void onMembersChanged(@NotNull ChatChannel chatChannel) {
        Map<LinkPlayer, String> known = this.members.get(chatChannel);

        if (known == null) {
            return;
        }

        Map<LinkPlayer, String> previous = new LinkedHashMap<>(known);

        known.clear();

        for (PlayerPresence member : chatChannel.getMembers()) {
            known.put(member.getPlayer(), member.getTaggedName());
            this.knownNames.put(member.getPlayer(), member.getTaggedName());

            if (previous.remove(member.getPlayer()) == null) {
                this.addChannelInfo(chatChannel, CommunityText.JOINED.format(this.locale, member.getTaggedName()), member.getPlayer());
            }
        }

        for (Map.Entry<LinkPlayer, String> left : previous.entrySet()) {
            this.addChannelInfo(chatChannel, CommunityText.LEFT.format(this.locale, left.getValue()), left.getKey());
        }
    }

    /**
     * Called when a whisper arrived; whispers of ignored players are not handed out.
     * <p>
     * Logs the whisper and makes its sender the target of {@code /r}; a blocked player's whisper is hidden.
     *
     * @param whisper the whisper
     */
    @Override
    public void onWhisper(@NotNull Whisper whisper) {
        if (this.community.isIgnored(whisper.getOther())) {
            return;
        }

        this.replyTarget = whisper.getOther();
        this.knownNames.put(whisper.getOther(), whisper.getTaggedOtherName());

        String prefix = CommunityText.WHISPER_FROM.format(this.locale, whisper.getTaggedOtherName());

        this.addEntry(ChatEntry.createWhisper(prefix, whisper.getText(), whisper.getOther(), whisper.getTaggedOtherName(), false));
    }

    /**
     * Called when the outcome of a sent whisper is known.
     * <p>
     * Logs a delivered whisper, followed by the target's away notice while the target shows away, or that its target is offline. The away
     * notice names no player, as the answer of a command does; it and the offline error belong to the current channel.
     *
     * @param whisper the sent whisper
     * @param result  the outcome
     */
    @Override
    public void onWhisperResult(@NotNull Whisper whisper, @NotNull WhisperResult result) {
        String name = whisper.getTaggedOtherName();

        if (result != WhisperResult.DELIVERED) {
            String text = CommunityText.NOT_ONLINE.format(this.locale, name);

            this.addEntry(new ChatEntry(ChatEntryKind.ERROR, "", text, this.channel, whisper.getOther(), name));

            return;
        }

        String prefix = CommunityText.WHISPER_TO.format(this.locale, name);
        PlayerPresence target = this.community.getPresence(whisper.getOther());

        this.addEntry(ChatEntry.createWhisper(prefix, whisper.getText(), whisper.getOther(), name, true));

        if (target != null && target.getStatus() == PlayerStatus.AWAY) {
            this.addInfo(target.getAwayText().isEmpty() ? CommunityText.IS_AWAY.format(this.locale, name)
                                                        : CommunityText.IS_AWAY_TEXT.format(this.locale, name, target.getAwayText()));
        }
    }


    /**
     * Stops listening to the community when the network closes; the player stays in the channel until the network leaves it.
     */
    public void close() {
        this.community.removeListener(this);
    }
}
