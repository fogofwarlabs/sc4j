package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountLock;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntry;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the Beacon.Link session of the logged-in account in the launcher: one connection of the account, beside the account's games and
 * other devices, that shows the account "in the launcher" (presence mod id {@value BeaconLink#LAUNCHER_MOD_ID}) to its friends.
 * <p>
 * Its chat is the engine's channel chat ({@link ChannelChat}), which runs from {@link #openChat} on: it joins the shared channel
 * {@value ChannelChat#SHARED_CHANNEL} once the session is connected, keeps up to {@value CommunitySession#MAXIMUM_CHANNELS} channels and lists
 * the channels of the launcher users. A connection that fails is opened again after ten seconds, and the chat joins again the channels it was
 * in.
 * <p>
 * The whispers are recorded once, from the community's whisper events: those the account menu and the Chat screen send, with their outcome,
 * and those the account receives, whichever screen is open. The chat's own whisper entries are not kept; its notices after a whisper are.
 * <p>
 * The session is the client the games use ({@link ClientBeaconLink}), without an embedded peer, since the launcher's node is the computer's
 * peer. It runs on a thread of its own, single-threaded as the client requires: the thread uses the account (a shared use of
 * {@link AccountLock}, so that the account cannot be deleted meanwhile), opens the session, then polls it, carries out the commands the
 * launcher queues and, after a poll that changed what the launcher shows, hands new immutable views to the publisher (the JavaFX thread in
 * the launcher), which installs them and calls the change listener. Closing the session ends the thread: the connection publishes a presence
 * that expires within seconds and unregisters, the learned nodes are merged into the shared peers file and the account is released.
 */
public final class LauncherSession implements LauncherSessionView {
    /**
     * The most chat entries the session keeps: the chat's history for each channel it can be in.
     */
    public static final int MAXIMUM_CHAT_ENTRIES = ChannelChat.HISTORY * CommunitySession.MAXIMUM_CHANNELS;

    private static final @NotNull Logger LOGGER = Logger.of(LauncherSession.class);
    private static final @NotNull String THREAD_NAME = "sc4j-launcher-session";
    private static final long POLL_MILLIS = 20L;
    private static final long RECONNECT_MILLIS = 10_000L;
    private static final int MAXIMUM_WHISPERS = 100;
    private static final @NotNull ModVersion DEVELOPMENT_VERSION = ModVersion.parse("0.0.0-development");
    private static final @NotNull Comparator<@NotNull FriendView> FRIEND_ORDER =
            Comparator.comparing((FriendView friend) -> friend.getState() == FriendState.OFFLINE)
                      .thenComparing(FriendView::getTaggedName, String.CASE_INSENSITIVE_ORDER);

    /**
     * Holds a whisper the session shows while the session thread may still change its state.
     */
    private static final class WhisperEntry {
        private final @NotNull FriendCode otherCode;
        private final @NotNull String otherTaggedName;
        private final @NotNull String text;
        private final long timestampMillis;
        private final @Nullable Whisper sent;

        private @NotNull WhisperState state;

        private WhisperEntry(@NotNull FriendCode otherCode, @NotNull String otherTaggedName, @NotNull String text, long timestampMillis,
                             @Nullable Whisper sent, @NotNull WhisperState state) {
            this.otherCode       = otherCode;
            this.otherTaggedName = otherTaggedName;
            this.text            = text;
            this.timestampMillis = timestampMillis;
            this.sent            = sent;

            this.state = state;
        }

        private @NotNull WhisperView createView() {
            return new WhisperView(this.otherCode, this.otherTaggedName, this.text, this.timestampMillis, this.state);
        }
    }

    /**
     * Records the whispers the session receives and the outcomes of the whispers it sent.
     */
    private final class WhisperRecorder implements CommunityListener {
        /**
         * Called when a whisper arrived; whispers of ignored players are not handed out.
         *
         * @param whisper the whisper
         */
        @Override
        public void onWhisper(@NotNull Whisper whisper) {
            FriendCode sender = FriendCode.of(whisper.getOther().getKey());

            if (!whisper.getOtherName().isEmpty()) {
                LauncherSession.this.names.put(sender, whisper.getTaggedOtherName());
            }

            LauncherSession.this.addWhisper(new WhisperEntry(sender, whisper.getTaggedOtherName(), whisper.getText(), whisper.getTimestampMillis(),
                                                             null, WhisperState.RECEIVED));
        }

        /**
         * Called when the outcome of a sent whisper is known.
         *
         * @param whisper the sent whisper
         * @param result  the outcome
         */
        @Override
        public void onWhisperResult(@NotNull Whisper whisper, @NotNull WhisperResult result) {
            WhisperState state = result == WhisperResult.DELIVERED ? WhisperState.DELIVERED : WhisperState.NOT_DELIVERED;
            boolean recorded = false;

            for (WhisperEntry entry : LauncherSession.this.whispers) {
                if (entry.sent == whisper) {
                    entry.state = state;
                    recorded    = true;
                }
            }

            // A whisper the chat sent ("/w", "/r") shows from its outcome on.
            if (!recorded) {
                FriendCode target = FriendCode.of(whisper.getOther().getKey());
                String name = LauncherSession.this.names.getOrDefault(target, whisper.getTaggedOtherName());

                LauncherSession.this.addWhisper(new WhisperEntry(target, name, whisper.getText(), whisper.getTimestampMillis(), whisper, state));
            }
        }
    }

    /**
     * Logs a launcher in: starts the session of an account of an install, with the install's seeds (the user's node list once saved, else the
     * shipped seed list) and its shared peers file, without an embedded peer.
     *
     * @param install         the install
     * @param account         the account
     * @param launcherVersion the launcher's version, shown in the session's presence
     * @param publisher       runs the publication of new views: the JavaFX thread
     * @return the started session
     */
    public static @NotNull LauncherSession open(@NotNull InstallLayout install, @NotNull Account account, @NotNull String launcherVersion,
                                                @NotNull Executor publisher) {
        List<ConfiguredNode> seeds;

        try {
            seeds = KnownNodes.readSeeds(install.getNodesFile(), install.getSeedsFile());
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK: the launcher session of {0} starts without seeds: {1}", account.getTaggedName(), exception.getMessage());
            seeds = List.of();
        }

        ClientSetup setup = ClientSetup.of(account, seeds, install.getBeaconLinkDirectory().resolve(PeerStore.FILE_NAME));

        return start(account, ClientBeaconLink.createWithoutPeer(setup, createIdentity(launcherVersion)), publisher);
    }

    /**
     * Starts the session of an account on its own thread; the session shows {@link SessionState#CONNECTING} until the thread published its
     * first views.
     *
     * @param account   the account
     * @param link      the account's Beacon.Link, whose sessions run no embedded peer; the session closes it
     * @param publisher runs the publication of new views: the JavaFX thread in the launcher
     * @return the started session
     */
    public static @NotNull LauncherSession start(@NotNull Account account, @NotNull ClientBeaconLink link, @NotNull Executor publisher) {
        LauncherSession session = new LauncherSession(account, link, publisher);

        session.thread.start();

        return session;
    }

    /**
     * Creates the mod identity the launcher's sessions show: the launcher's mod id {@value BeaconLink#LAUNCHER_MOD_ID} on the current
     * engine, with the launcher's version, or {@code 0.0.0-development} for a development build without one.
     *
     * @param launcherVersion the launcher's version
     * @return the created {@link ModIdentity} instance
     */
    public static @NotNull ModIdentity createIdentity(@NotNull String launcherVersion) {
        ModVersion version;

        try {
            version = ModVersion.parse(launcherVersion);
        } catch (IllegalArgumentException exception) {
            version = DEVELOPMENT_VERSION;
        }

        return ModIdentity.createWithoutJar(EngineVersion.CURRENT, BeaconLink.LAUNCHER_MOD_ID, version);
    }

    private static @NotNull SessionFailure toSessionFailure(@NotNull LinkFailure failure) {
        return switch (failure) {
            case NO_NODES_CONFIGURED -> SessionFailure.NO_NODES;
            case NO_NODE_ANSWERED -> SessionFailure.NO_ANSWER;
            case ALL_NODES_REFUSED, ROOM_REFUSED -> SessionFailure.REFUSED;
            case TOO_MANY_CONNECTIONS -> SessionFailure.TOO_MANY_DEVICES;
            case SIGNED_OUT -> SessionFailure.SIGNED_OUT;
        };
    }

    private static @Nullable ChatChannel findChannel(@NotNull ChannelChat chat, @NotNull String name) {
        for (ChatChannel joined : chat.getChannels()) {
            if (joined.getName().equals(name)) {
                return joined;
            }
        }

        return null;
    }

    private static @NotNull FriendState toFriendState(@NotNull PlayerPresence presence) {
        return switch (presence.getStatus()) {
            case AVAILABLE -> BeaconLink.LAUNCHER_MOD_ID.equals(presence.getModId()) ? FriendState.IN_LAUNCHER : FriendState.ONLINE;
            case AWAY -> FriendState.AWAY;
            case IN_ROOM -> FriendState.IN_ROOM;
            case IN_GAME -> FriendState.IN_GAME;
        };
    }


    private final @NotNull Account account;
    private final @NotNull ClientBeaconLink link;
    private final @NotNull Executor publisher;
    private final @NotNull FriendCode friendCode;
    private final @NotNull BlockingQueue<@NotNull Runnable> commands;
    private final @NotNull Thread thread;

    private final @NotNull Map<@NotNull FriendCode, @NotNull String> names;
    private final @NotNull List<@NotNull WhisperEntry> whispers;
    private final @NotNull List<@NotNull ChatEntryView> chatEntries;
    private @Nullable BeaconLinkSession connection;
    private @Nullable CommunitySession community;
    private @Nullable ChannelChat chat;
    private @Nullable Locale chatLocale;
    private @NotNull List<@NotNull String> chatChannels;
    private long connectNanos;
    private @NotNull SelfStatus status;
    private @NotNull String awayText;
    private @NotNull SelfView lastSelf;
    private @NotNull List<@NotNull FriendView> lastFriends;
    private @NotNull List<@NotNull WhisperView> lastWhispers;
    private @NotNull List<@NotNull ChannelView> lastChannels;
    private @NotNull List<@NotNull ChannelView> lastKnownChannels;
    private @NotNull List<@NotNull ChatEntryView> lastChatEntries;

    private volatile @NotNull SelfView self;
    private volatile @NotNull List<@NotNull FriendView> friends;
    private volatile @NotNull List<@NotNull WhisperView> whisperViews;
    private volatile @NotNull List<@NotNull ChannelView> channels;
    private volatile @NotNull List<@NotNull ChannelView> knownChannels;
    private volatile @NotNull List<@NotNull ChatEntryView> chatEntryViews;
    private volatile @Nullable Runnable listener;
    private volatile boolean closed;

    private LauncherSession(@NotNull Account account, @NotNull ClientBeaconLink link, @NotNull Executor publisher) {
        this.account    = account;
        this.link       = link;
        this.publisher  = publisher;
        this.friendCode = FriendCode.of(account.getKey().getBytes());
        this.commands   = new LinkedBlockingQueue<>();
        this.thread     = new Thread(this::run, THREAD_NAME);

        this.names             = new HashMap<>();
        this.whispers          = new ArrayList<>();
        this.chatEntries       = new ArrayList<>();
        this.connection        = null;
        this.community         = null;
        this.chat              = null;
        this.chatLocale        = null;
        this.chatChannels      = List.of();
        this.connectNanos      = 0L;
        this.status            = SelfStatus.ONLINE;
        this.awayText          = "";
        this.lastSelf          = new SelfView(SessionState.CONNECTING, SelfStatus.ONLINE, "", 0, null);
        this.lastFriends       = List.of();
        this.lastWhispers      = List.of();
        this.lastChannels      = List.of();
        this.lastKnownChannels = List.of();
        this.lastChatEntries   = List.of();

        this.self           = this.lastSelf;
        this.friends        = this.lastFriends;
        this.whisperViews   = this.lastWhispers;
        this.channels       = this.lastChannels;
        this.knownChannels  = this.lastKnownChannels;
        this.chatEntryViews = this.lastChatEntries;
        this.listener       = null;
        this.closed         = false;

        this.thread.setDaemon(true);
    }

    /**
     * @return the account's own state and status
     */
    @Override
    public @NotNull SelfView getSelf() {
        return this.self;
    }

    /**
     * @return the account's friend code, which another player adds the account by
     */
    @Override
    public @NotNull FriendCode getFriendCode() {
        return this.friendCode;
    }

    /**
     * @return the account's friends in the order they are shown: the friends online first, then by name; empty while the session runs no
     * connection
     */
    @Override
    public @NotNull List<@NotNull FriendView> getFriends() {
        return this.friends;
    }

    /**
     * @return the whispers the account received and sent while logged in, the oldest first; the oldest give way after a hundred
     */
    @Override
    public @NotNull List<@NotNull WhisperView> getWhispers() {
        return this.whisperViews;
    }

    /**
     * @return the channels the account's chat is in, in the order it joined them, with their members; empty until the chat opened and while
     * the session runs no connection
     */
    @Override
    public @NotNull List<@NotNull ChannelView> getChannels() {
        return this.channels;
    }

    /**
     * @return the channels the session knows of among the launcher users and the channels the chat is in, the most members first, without
     * members; empty until the chat opened
     */
    @Override
    public @NotNull List<@NotNull ChannelView> getKnownChannels() {
        return this.knownChannels;
    }

    /**
     * @return the entries of the account's chat, the oldest first: the lines, notices and command answers of every channel it is in, without
     * whispers ({@link #getWhispers()}); the oldest give way after {@value #MAXIMUM_CHAT_ENTRIES}
     */
    @Override
    public @NotNull List<@NotNull ChatEntryView> getChatEntries() {
        return this.chatEntryViews;
    }

    /**
     * Sets the listener called after the session published new views.
     *
     * @param listener the listener, or {@code null} for none
     */
    @Override
    public void setOnChanged(@Nullable Runnable listener) {
        this.listener = listener;
    }


    /**
     * Sets the status other players see of the account in this launcher; games of the account keep their own.
     *
     * @param status   the status
     * @param awayText the away text, empty for none; ignored unless {@code status} is {@link SelfStatus#AWAY}
     */
    @Override
    public void setStatus(@NotNull SelfStatus status, @NotNull String awayText) {
        this.commands.add(() -> {
            CommunitySession opened = this.community;

            this.status   = status;
            this.awayText = status == SelfStatus.AWAY ? awayText : "";

            if (opened != null) {
                opened.setStatus(status == SelfStatus.AWAY ? PlayerStatus.AWAY : PlayerStatus.AVAILABLE);
                opened.setAwayText(this.awayText);
            }
        });
    }

    /**
     * Adds a friend: the session follows the player's presence, and every process of the account keeps the friend.
     *
     * @param friend the player's friend code
     */
    @Override
    public void addFriend(@NotNull FriendCode friend) {
        this.runOnCommunity(opened -> {
            LinkPlayer player = LinkPlayer.of(friend.getKey());

            if (!opened.getFriends().contains(player)) {
                opened.addFriend(player);
            }
        });
    }

    /**
     * Removes a friend, for every process of the account.
     *
     * @param friend the friend's code
     */
    @Override
    public void removeFriend(@NotNull FriendCode friend) {
        this.runOnCommunity(opened -> opened.removeFriend(LinkPlayer.of(friend.getKey())));
    }

    /**
     * Ignores a player or stops ignoring it, for every process of the account: an ignored player's whispers are not shown.
     *
     * @param player  the player's friend code
     * @param ignored whether the account ignores the player
     */
    @Override
    public void setIgnored(@NotNull FriendCode player, boolean ignored) {
        this.runOnCommunity(opened -> {
            if (ignored) {
                opened.ignore(LinkPlayer.of(player.getKey()));
            } else {
                opened.unignore(LinkPlayer.of(player.getKey()));
            }
        });
    }

    /**
     * Whispers to a player whose presence the session holds; the whisper shows in the whispers, with its outcome when it is known.
     *
     * @param player the player's friend code
     * @param text   the text, at most {@value org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel#MAXIMUM_TEXT_LENGTH} UTF-8 bytes
     */
    @Override
    public void whisper(@NotNull FriendCode player, @NotNull String text) {
        this.runOnCommunity(opened -> {
            Whisper sent = opened.whisper(LinkPlayer.of(player.getKey()), text);
            String name = this.names.getOrDefault(player, sent.getTaggedOtherName());

            this.addWhisper(new WhisperEntry(player, name, text, sent.getTimestampMillis(), sent, WhisperState.SENDING));
        });
    }

    /**
     * Runs the account's chat from now on: it joins the shared channel once the session is connected, keeps up to
     * {@value CommunitySession#MAXIMUM_CHANNELS} channels and, when the session connects again after its connection failed, joins again the
     * channels it was in. Later calls do nothing.
     *
     * @param locale the locale of the chat's notices and command answers
     */
    @Override
    public void openChat(@NotNull Locale locale) {
        this.commands.add(() -> {
            if (this.chatLocale == null) {
                this.chatLocale = locale;
            }
        });
    }

    /**
     * Joins a channel; the chat keeps the channels it is in. A refused name or the channel limit shows as an error entry.
     *
     * @param name the channel name
     */
    @Override
    public void joinChannel(@NotNull String name) {
        this.runOnChat(opened -> opened.join(name));
    }

    /**
     * Leaves a channel the chat is in.
     *
     * @param channel the channel's name, as the chat joined it
     */
    @Override
    public void leaveChannel(@NotNull String channel) {
        this.runOnChat(opened -> {
            ChatChannel joined = findChannel(opened, channel);

            if (joined != null) {
                opened.leave(joined);
            }
        });
    }

    /**
     * Runs a line typed in a channel the chat is in: a slash command ({@code /help} lists them) or a line said in the channel.
     *
     * @param channel the channel's name, as the chat joined it
     * @param text    the typed text
     */
    @Override
    public void submitChat(@NotNull String channel, @NotNull String text) {
        this.runOnChat(opened -> {
            ChatChannel joined = findChannel(opened, channel);

            if (joined != null) {
                opened.submit(joined, text);
            }
        });
    }

    /**
     * Signs the account out on its other devices; this launcher stays logged in. Does nothing unless the session is
     * {@link SessionState#CONNECTED}.
     */
    @Override
    public void signOutOtherDevices() {
        this.commands.add(() -> {
            BeaconLinkSession opened = this.connection;

            if (opened != null && opened.getFailure() == null && opened.getObservedAddress() != null) {
                opened.signOutOtherDevices();
            }
        });
    }

    private void runOnCommunity(@NotNull Consumer<@NotNull CommunitySession> command) {
        this.commands.add(() -> {
            CommunitySession opened = this.community;

            if (opened == null) {
                return;
            }

            try {
                command.accept(opened);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                // The account menu checks what it sends; what the session refuses still (its own player, a full list) changes nothing.
                LOGGER.warning("BEACON.LINK: the launcher session refused a command: {0}", exception.getMessage());
            }
        });
    }

    private void runOnChat(@NotNull Consumer<@NotNull ChannelChat> command) {
        this.runOnCommunity(opened -> {
            ChannelChat openedChat = this.chat;

            if (openedChat != null) {
                command.accept(openedChat);
            }
        });
    }

    private void addChatEntry(@NotNull ChatEntry entry) {
        ChatChannel channel = entry.getChannel();
        LinkPlayer player = entry.getPlayer();

        if (entry.getKind() == ChatEntryKind.WHISPER) {
            return;
        }

        if (this.chatEntries.size() >= MAXIMUM_CHAT_ENTRIES) {
            this.chatEntries.removeFirst();
        }

        this.chatEntries.add(new ChatEntryView(entry.getKind(), entry.getPrefix(), entry.getText(), channel == null ? null : channel.getName(),
                                               player == null ? null : FriendCode.of(player.getKey()), entry.getPlayerName()));
    }

    private void addWhisper(@NotNull WhisperEntry entry) {
        if (this.whispers.size() >= MAXIMUM_WHISPERS) {
            this.whispers.removeFirst();
        }

        this.whispers.add(entry);
    }


    private void run() {
        AccountLock use;

        try {
            use = AccountLock.acquire(this.account);
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK: the launcher cannot use the account {0}: {1}", this.account.getTaggedName(), exception.getMessage());
            this.runWithoutConnection();
            this.link.close();

            return;
        }

        try {
            this.openConnection();

            while (!this.closed) {
                this.runCommands();
                this.reconnect();
                this.poll();
                this.openChatWhenConnected();
                this.publish();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            BeaconLinkSession opened = this.connection;
            ChannelChat openedChat = this.chat;

            if (openedChat != null) {
                openedChat.close();
            }

            if (opened != null) {
                opened.close();
            }

            this.link.close();
            use.close();
        }
    }

    /**
     * Keeps the views of a session that could not start until it is closed: the status the user sets is still shown.
     */
    private void runWithoutConnection() {
        try {
            while (!this.closed) {
                this.runCommands();
                this.publish();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void openConnection() {
        this.connectNanos = System.nanoTime();

        try {
            BeaconLinkSession opened = this.link.open();
            CommunitySession openedCommunity = opened.getCommunity();

            openedCommunity.addListener(new WhisperRecorder());

            this.connection = opened;
            this.community  = openedCommunity;
            LOGGER.info("BEACON.LINK: launcher session of {0} opened", this.account.getTaggedName());
        } catch (IOException | RuntimeException exception) {
            LOGGER.exception(LogLevel.WARNING, exception, "BEACON.LINK: the launcher session of {0} could not start.", this.account.getTaggedName());
        }
    }

    /**
     * Opens the connection again {@value #RECONNECT_MILLIS} ms after the last try while the session runs none.
     */
    private void reconnect() {
        if (this.connection == null && System.nanoTime() - this.connectNanos >= TimeUnit.MILLISECONDS.toNanos(RECONNECT_MILLIS)) {
            this.openConnection();
        }
    }

    /**
     * Opens the chat once the session is connected, after {@link #openChat}: in the channels it was in before the connection failed, else in
     * the shared channel.
     */
    private void openChatWhenConnected() {
        BeaconLinkSession opened = this.connection;
        CommunitySession openedCommunity = this.community;
        Locale locale = this.chatLocale;

        if (this.chat != null || locale == null || opened == null || openedCommunity == null || opened.getFailure() != null ||
            opened.getObservedAddress() == null) {
            return;
        }

        ChannelChat created = new ChannelChat(openedCommunity, locale, ChannelChat.SHARED_CHANNEL, true);
        List<String> channels = this.chatChannels.isEmpty() ? List.of(ChannelChat.SHARED_CHANNEL) : this.chatChannels;

        created.addLog(this::addChatEntry, false);
        created.open(channels.getFirst());

        for (String name : channels.subList(1, channels.size())) {
            created.join(name);
        }

        this.chat = created;
    }

    /**
     * Waits for a command at most one poll interval, then carries out the waiting commands in their order.
     */
    private void runCommands() throws InterruptedException {
        Runnable command = this.commands.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);

        while (command != null) {
            command.run();
            command = this.commands.poll();
        }
    }

    private void poll() {
        BeaconLinkSession opened = this.connection;

        if (opened == null) {
            return;
        }

        try {
            opened.poll();
        } catch (IOException exception) {
            ChannelChat openedChat = this.chat;

            LOGGER.exception(LogLevel.WARNING, exception, "BEACON.LINK: the launcher session of {0} stopped.", this.account.getTaggedName());

            if (openedChat != null) {
                this.chatChannels = openedChat.getChannels().stream().map(ChatChannel::getName).toList();
                openedChat.close();
            }

            opened.close();
            this.connection = null;
            this.community  = null;
            this.chat       = null;
        }
    }

    /**
     * Hands the current views to the publisher when they differ from the views handed last.
     */
    private void publish() {
        SelfView currentSelf = this.createSelf();
        List<FriendView> currentFriends = this.createFriends();
        List<WhisperView> currentWhispers = new ArrayList<>(this.whispers.size());
        List<ChannelView> currentChannels = this.createChannels();
        List<ChannelView> currentKnownChannels = this.createKnownChannels();

        for (WhisperEntry entry : this.whispers) {
            currentWhispers.add(entry.createView());
        }

        if (currentSelf.equals(this.lastSelf) && currentFriends.equals(this.lastFriends) && currentWhispers.equals(this.lastWhispers) &&
            currentChannels.equals(this.lastChannels) && currentKnownChannels.equals(this.lastKnownChannels) &&
            this.chatEntries.equals(this.lastChatEntries)) {
            return;
        }

        List<WhisperView> publishedWhispers = List.copyOf(currentWhispers);
        List<ChatEntryView> publishedChatEntries = List.copyOf(this.chatEntries);

        this.lastSelf          = currentSelf;
        this.lastFriends       = currentFriends;
        this.lastWhispers      = publishedWhispers;
        this.lastChannels      = currentChannels;
        this.lastKnownChannels = currentKnownChannels;
        this.lastChatEntries   = publishedChatEntries;

        this.publisher.execute(() -> {
            if (this.closed) {
                return;
            }

            this.self           = currentSelf;
            this.friends        = currentFriends;
            this.whisperViews   = publishedWhispers;
            this.channels       = currentChannels;
            this.knownChannels  = currentKnownChannels;
            this.chatEntryViews = publishedChatEntries;

            Runnable changed = this.listener;

            if (changed != null) {
                changed.run();
            }
        });
    }

    private @NotNull SelfView createSelf() {
        BeaconLinkSession opened = this.connection;
        CommunitySession openedCommunity = this.community;

        if (opened == null || openedCommunity == null) {
            return new SelfView(SessionState.OFFLINE, this.status, this.awayText, 0, SessionFailure.NOT_STARTED);
        }

        LinkFailure failure = opened.getFailure();

        if (failure != null) {
            return new SelfView(SessionState.OFFLINE, this.status, this.awayText, 0, toSessionFailure(failure));
        }

        if (opened.getObservedAddress() == null) {
            return new SelfView(SessionState.CONNECTING, this.status, this.awayText, 0, null);
        }

        return new SelfView(SessionState.CONNECTED, this.status, this.awayText, openedCommunity.getSelf().getDeviceCount(), null);
    }

    private @NotNull List<@NotNull FriendView> createFriends() {
        CommunitySession openedCommunity = this.community;

        if (openedCommunity == null) {
            return List.of();
        }

        List<FriendView> created = new ArrayList<>();

        for (LinkPlayer player : openedCommunity.getFriends()) {
            FriendCode code = FriendCode.of(player.getKey());
            PlayerPresence presence = openedCommunity.getPresence(player);

            if (presence == null) {
                String name = this.names.getOrDefault(code, player.getTagText());

                created.add(new FriendView(code, name, FriendState.OFFLINE, null, "", 0, openedCommunity.isIgnored(player)));

                continue;
            }

            created.add(this.createPlayerView(openedCommunity, presence));
        }

        created.sort(FRIEND_ORDER);

        return List.copyOf(created);
    }

    private @NotNull List<@NotNull ChannelView> createChannels() {
        CommunitySession openedCommunity = this.community;
        ChannelChat openedChat = this.chat;

        if (openedCommunity == null || openedChat == null) {
            return List.of();
        }

        List<ChannelView> created = new ArrayList<>();

        for (ChatChannel joined : openedChat.getChannels()) {
            List<FriendView> members = new ArrayList<>();

            for (PlayerPresence member : openedChat.getMembers(joined)) {
                PlayerPresence merged = openedCommunity.getPresence(member.getPlayer());

                members.add(this.createPlayerView(openedCommunity, merged != null ? merged : member));
            }

            created.add(new ChannelView(joined.getName(), members.size(), members));
        }

        return List.copyOf(created);
    }

    private @NotNull List<@NotNull ChannelView> createKnownChannels() {
        ChannelChat openedChat = this.chat;

        if (openedChat == null) {
            return List.of();
        }

        List<ChannelView> created = new ArrayList<>();

        for (ChannelSummary summary : openedChat.listChannels()) {
            created.add(new ChannelView(summary.getName(), summary.getMemberCount(), List.of()));
        }

        return List.copyOf(created);
    }

    /**
     * Creates the view of a player whose presence the session holds and records the player's name.
     */
    private @NotNull FriendView createPlayerView(@NotNull CommunitySession openedCommunity, @NotNull PlayerPresence presence) {
        FriendCode code = FriendCode.of(presence.getPlayer().getKey());
        String modId = BeaconLink.LAUNCHER_MOD_ID.equals(presence.getModId()) ? null : presence.getModId();

        this.names.put(code, presence.getTaggedName());

        return new FriendView(code, presence.getTaggedName(), toFriendState(presence), modId, presence.getAwayText(), presence.getDeviceCount(),
                              openedCommunity.isIgnored(presence.getPlayer()));
    }


    /**
     * Ends the session: other players stop seeing the account in this launcher. The views do not change after it and the listener is no longer
     * called. Games of the account keep running.
     * <p>
     * It waits for the session's thread, which publishes a presence that expires within seconds, unregisters, merges the learned nodes into the
     * shared peers file and releases the account. Closing a closed session does nothing.
     */
    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }

        this.closed   = true;
        this.listener = null;
        // Wakes the thread from its wait for a command.
        this.commands.add(() -> {
        });

        try {
            this.thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
