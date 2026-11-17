package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.ui.ListItem;
import org.fogofwarlabs.sc4j.launcher.ui.Tone;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Describes the account and the other players the way the account menu and the Chat screen show them: the colour of a state, the state
 * line with the away text, the mod, the devices and the ignore mark, and the list item of a player.
 */
final class PlayerTexts {
    private static final @NotNull String FRIEND_STATE_KEY_PREFIX = "session.friend.";
    private static final @NotNull String FAILURE_KEY_PREFIX = "session.failure.";
    private static final @NotNull String PART_SEPARATOR = " | ";

    /**
     * Returns the colour of the account's own state: green online, amber away, plain while not connected.
     *
     * @param self the account's own state
     * @return the colour role
     */
    static @NotNull Tone getTone(@NotNull SelfView self) {
        if (self.getState() != SessionState.CONNECTED) {
            return Tone.NEUTRAL;
        }

        return self.getStatus() == SelfStatus.AWAY ? Tone.WARNING : Tone.SUCCESS;
    }

    /**
     * Returns the colour of a player's state: plain offline, amber away, green online and cyan in a room or a game.
     *
     * @param state the player's state
     * @return the colour role
     */
    static @NotNull Tone getTone(@NotNull FriendState state) {
        return switch (state) {
            case OFFLINE -> Tone.NEUTRAL;
            case AWAY -> Tone.WARNING;
            case IN_LAUNCHER, ONLINE -> Tone.SUCCESS;
            case IN_ROOM, IN_GAME -> Tone.ACCENT;
        };
    }


    private final @NotNull LauncherTexts texts;
    private final @NotNull Function<@NotNull String, @NotNull String> modNames;

    /**
     * Creates a {@link PlayerTexts} instance.
     *
     * @param texts    the launcher texts of the display language
     * @param modNames finds the display name of a mod by its id, the id itself for a mod the install does not have
     */
    PlayerTexts(@NotNull LauncherTexts texts, @NotNull Function<@NotNull String, @NotNull String> modNames) {
        this.texts    = texts;
        this.modNames = modNames;
    }

    /**
     * Returns the account's state line: why it is offline, connecting, or its status.
     *
     * @param self the account's own state
     * @return the state line
     */
    @NotNull String describe(@NotNull SelfView self) {
        SessionFailure failure = self.getFailure();

        return switch (self.getState()) {
            case OFFLINE -> failure == null ? this.texts.get("session.state.offline")
                                            : this.texts.get(FAILURE_KEY_PREFIX + failure.name().toLowerCase(Locale.ROOT));
            case CONNECTING -> this.texts.get("session.state.connecting");
            case CONNECTED -> self.getStatus() == SelfStatus.ONLINE ? this.texts.get("session.status.online") : this.describeAway(self.getAwayText());
        };
    }

    /**
     * Returns a player's state line: the state or the away text, the mod's name, the devices and the ignore mark.
     *
     * @param player the player
     * @return the state line
     */
    @NotNull String describe(@NotNull FriendView player) {
        FriendState state = player.getState();
        String modId = player.getModId();
        List<String> parts = new ArrayList<>();

        parts.add(state == FriendState.AWAY ? this.describeAway(player.getAwayText())
                                            : this.texts.get(FRIEND_STATE_KEY_PREFIX + state.name().toLowerCase(Locale.ROOT)));

        if (modId != null) {
            parts.add(this.modNames.apply(modId));
        }

        if (player.getDeviceCount() > 1) {
            parts.add(this.texts.format("session.friend.devices", player.getDeviceCount()));
        }

        if (player.isIgnored()) {
            parts.add(this.texts.get("session.friend.ignored"));
        }

        return String.join(PART_SEPARATOR, parts);
    }

    /**
     * Creates the list item of a player, a friend or a channel member: the state marker, the name and tag, and the state line under it.
     *
     * @param player the player
     * @return the created list item
     */
    @NotNull ListItem createItem(@NotNull FriendView player) {
        return new ListItem(player.getTaggedName()).setMarker(getTone(player.getState())).addCaption(this.describe(player));
    }

    private @NotNull String describeAway(@NotNull String awayText) {
        return awayText.isEmpty() ? this.texts.get("session.status.away") : this.texts.format("session.status.away.line", awayText);
    }
}
