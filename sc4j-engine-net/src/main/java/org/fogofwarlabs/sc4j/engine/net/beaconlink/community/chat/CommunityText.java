package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Lists the engine's texts of the Beacon.Link community screens, in English and Korean: the notice of a run without an account, player
 * states, the channel chat's notices, errors, input prefix and {@code /help}, the block actions and the room filter's choices. A text with
 * {@code %s} or {@code %d} takes its values through {@link #format}.
 */
public enum CommunityText {
    /**
     * The placeholder of an unknown name or value, also shown for an empty list.
     */
    UNKNOWN("-", "-"),
    /**
     * The notice of a run without a Beacon.Link account.
     */
    NEEDS_ACCOUNT("Beacon.Link needs an account.", "Beacon.Link는 계정이 필요합니다."),

    /**
     * The status of an available player.
     */
    STATUS_AVAILABLE("Online", "온라인"),
    /**
     * The status of an away player without an away text.
     */
    STATUS_AWAY("Away", "자리 비움"),
    /**
     * The status of an away player with an away text.
     */
    STATUS_AWAY_TEXT("Away: %s", "자리 비움: %s"),
    /**
     * The status of a player in a room.
     */
    STATUS_IN_ROOM("In room", "대기실"),
    /**
     * The status of a player in a game.
     */
    STATUS_IN_GAME("In game", "게임 중"),
    /**
     * The status of an offline player.
     */
    STATUS_OFFLINE("Offline", "오프라인"),
    /**
     * The mark of a player or room of another version of the mod.
     */
    OTHER_VERSION("Other version", "다른 버전"),

    /**
     * The filter choice that passes every value.
     */
    ANY("Any", "전체"),
    /**
     * The label of the room filter's latency choice.
     */
    LATENCY("Latency", "지연 시간"),
    /**
     * The room filter's choice of a good latency.
     */
    LATENCY_GOOD("Good", "좋음"),
    /**
     * The room filter's choice of a fair latency.
     */
    LATENCY_FAIR("Fair", "보통"),
    /**
     * The label of the room filter's largest capacity choice.
     */
    MAXIMUM_PLAYERS("Max players", "최대 인원"),
    /**
     * The label of the rooms of other versions of the mod.
     */
    OTHER_VERSIONS("Other versions", "다른 버전"),
    /**
     * The label of the room filter's closed rooms choice.
     */
    CLOSED_GAMES("Closed games", "닫힌 게임"),
    /**
     * The choice that lists the closed rooms.
     */
    SHOW("Show", "표시"),
    /**
     * The choice that hides the closed rooms.
     */
    HIDE("Hide", "숨김"),
    /**
     * The notice of an empty room list.
     */
    NO_GAMES("No games", "게임 없음"),

    /**
     * The notice of a player who came into the channel.
     */
    JOINED("%s joined", "%s 입장"),
    /**
     * The notice of a player who left the channel.
     */
    LEFT("%s left", "%s 퇴장"),
    /**
     * The notice of the channel the player entered.
     */
    CHANNEL("Channel: %s", "채널: %s"),
    /**
     * The notice of the channel the player left.
     */
    CHANNEL_LEFT("Left %s", "%s 나감"),
    /**
     * The error of a line or command that needs a channel while the player is in none.
     */
    NO_CHANNEL("No channel", "채널 없음"),
    /**
     * The prefix of an incoming whisper.
     */
    WHISPER_FROM("From %s: ", "%s의 귓속말: "),
    /**
     * The prefix of a sent whisper and of the input while it whispers.
     */
    WHISPER_TO("To %s: ", "%s에게: "),
    /**
     * The error of a whisper to a player who is not online.
     */
    NOT_ONLINE("%s: offline", "%s: 오프라인"),
    /**
     * The error of a name that names no player the chat finds.
     */
    UNKNOWN_PLAYER("Unknown user: %s", "알 수 없는 사용자: %s"),
    /**
     * The error of an unknown slash command.
     */
    UNKNOWN_COMMAND("Unknown command: %s", "알 수 없는 명령: %s"),
    /**
     * The error of {@code /r} without an incoming whisper.
     */
    NO_REPLY("No whisper to reply to", "답장할 귓속말 없음"),
    /**
     * The error of a line longer than a channel line takes.
     */
    TOO_LONG("Too long", "너무 김"),
    /**
     * The error of a line over the chat rate.
     */
    TOO_FAST("Too fast", "너무 빠름"),
    /**
     * The error of a reached channel or friend limit.
     */
    LIMIT("Limit reached", "한도 초과"),
    /**
     * The error of a refused channel name.
     */
    INVALID_NAME("Invalid name", "잘못된 이름"),
    /**
     * The notice of going away without an away text.
     */
    AWAY_ON("Away", "자리 비움"),
    /**
     * The notice of coming back from away.
     */
    AWAY_OFF("Back", "돌아옴"),
    /**
     * The notice that a whispered player is away without an away text.
     */
    IS_AWAY("%s is away", "%s 자리 비움"),
    /**
     * The notice that a whispered player is away, with the player's away text.
     */
    IS_AWAY_TEXT("%s is away: %s", "%s 자리 비움: %s"),
    /**
     * The notice of an ignored player.
     */
    IGNORED("Ignored: %s", "무시: %s"),
    /**
     * The notice of a player no longer ignored.
     */
    UNIGNORED("Unignored: %s", "무시 해제: %s"),
    /**
     * The notice of an added friend.
     */
    FRIEND_ADDED("Friend added: %s", "친구 추가: %s"),
    /**
     * The notice of a removed friend.
     */
    FRIEND_REMOVED("Friend removed: %s", "친구 삭제: %s"),
    /**
     * The answer of {@code /friends list}.
     */
    FRIENDS("Friends: %s", "친구: %s"),
    /**
     * The answer of {@code /who}: the channel, its member count and its members.
     */
    USERS("%s (%d): %s", "%s (%d): %s"),
    /**
     * The answer of {@code /whois}: the tagged name, the mod and the status.
     */
    USER_INFO("%s: %s, %s", "%s: %s, %s"),
    /**
     * The error of a command without its arguments, with the command's help line.
     */
    USAGE("Usage: %s", "사용법: %s"),
    /**
     * The prefix of the input while it says lines in a channel.
     */
    INPUT_CHANNEL("[%s]: ", "[%s]: "),
    /**
     * The action that blocks a player.
     */
    BLOCK("Block", "차단"),
    /**
     * The action that stops blocking a player.
     */
    UNBLOCK("Unblock", "차단 해제"),
    /**
     * The notice of a blocked player.
     */
    BLOCKED("Blocked: %s", "차단: %s"),
    /**
     * The notice of a player no longer blocked.
     */
    UNBLOCKED("Unblocked: %s", "차단 해제: %s"),


    /**
     * The title of {@code /help}.
     */
    HELP_TITLE("Commands", "명령어"),
    /**
     * The help line of {@code /join}.
     */
    HELP_JOIN("/join <channel>  Join a channel", "/join <채널>  채널 입장"),
    /**
     * The help line of {@code /leave}.
     */
    HELP_LEAVE("/leave  Leave the channel", "/leave  채널 나가기"),
    /**
     * The help line of {@code /w}.
     */
    HELP_WHISPER("/w <name#tag> <text>  Whisper", "/w <이름#태그> <내용>  귓속말"),
    /**
     * The help line of {@code /r}.
     */
    HELP_REPLY("/r <text>  Reply", "/r <내용>  답장"),
    /**
     * The help line of {@code /me}.
     */
    HELP_EMOTE("/me <text>  Emote", "/me <내용>  감정 표현"),
    /**
     * The help line of {@code /who}.
     */
    HELP_WHO("/who  Channel users", "/who  채널 사용자"),
    /**
     * The help line of {@code /whois}.
     */
    HELP_WHOIS("/whois <name#tag>  User info", "/whois <이름#태그>  사용자 정보"),
    /**
     * The help line of {@code /ignore}.
     */
    HELP_IGNORE("/ignore <name#tag>  Ignore", "/ignore <이름#태그>  무시"),
    /**
     * The help line of {@code /unignore}.
     */
    HELP_UNIGNORE("/unignore <name#tag>  Unignore", "/unignore <이름#태그>  무시 해제"),
    /**
     * The help line of {@code /friends}.
     */
    HELP_FRIENDS("/friends add|remove|list [name#tag]  Friends", "/friends add|remove|list [이름#태그]  친구"),
    /**
     * The help line of {@code /away}.
     */
    HELP_AWAY("/away [text]  Away on or off", "/away [내용]  자리 비움 전환"),
    /**
     * The help line of {@code /help}.
     */
    HELP_HELP("/help  Commands", "/help  명령어");

    /**
     * The language whose texts are Korean.
     */
    private static final @NotNull String KOREAN_LANGUAGE = "ko";

    private final @NotNull String english;
    private final @NotNull String korean;

    CommunityText(@NotNull String english, @NotNull String korean) {
        this.english = english;
        this.korean  = korean;
    }

    /**
     * Returns the text of a locale.
     *
     * @param locale the locale of the texts
     * @return the Korean text for a Korean locale, the English text otherwise
     */
    public @NotNull String get(@NotNull Locale locale) {
        return KOREAN_LANGUAGE.equals(locale.getLanguage()) ? this.korean : this.english;
    }

    /**
     * Returns the text of a locale with its values.
     *
     * @param locale the locale of the texts
     * @param values the values of the text's {@code %s} and {@code %d}
     * @return the formatted text
     */
    public @NotNull String format(@NotNull Locale locale, @NotNull Object @NotNull ... values) {
        return String.format(Locale.ROOT, this.get(locale), values);
    }
}
