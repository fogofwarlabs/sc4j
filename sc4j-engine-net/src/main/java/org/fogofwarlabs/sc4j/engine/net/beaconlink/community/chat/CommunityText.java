package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Lists the engine's texts of the Beacon.Link community screens, in English, Korean and Simplified Chinese: the notice of a run without an
 * account, player states, the channel chat's notices, errors, input prefix and {@code /help}, the block actions, the room filter's choices
 * and the notice of a join that cannot connect. A text with {@code %s} or {@code %d} takes its values through {@link #format}.
 */
public enum CommunityText {
    /**
     * The placeholder of an unknown name or value, also shown for an empty list.
     */
    UNKNOWN("-", "-", "-"),
    /**
     * The notice of a run without a Beacon.Link account.
     */
    NEEDS_ACCOUNT("Beacon.Link needs an account.", "Beacon.Link는 계정이 필요합니다.", "Beacon.Link 需要账号。"),

    /**
     * The status of an available player.
     */
    STATUS_AVAILABLE("Online", "온라인", "在线"),
    /**
     * The status of an away player without an away text.
     */
    STATUS_AWAY("Away", "자리 비움", "离开"),
    /**
     * The status of an away player with an away text.
     */
    STATUS_AWAY_TEXT("Away: %s", "자리 비움: %s", "离开：%s"),
    /**
     * The status of a player in a room.
     */
    STATUS_IN_ROOM("In room", "대기실", "在房间中"),
    /**
     * The status of a player in a game.
     */
    STATUS_IN_GAME("In game", "게임 중", "游戏中"),
    /**
     * The status of an offline player.
     */
    STATUS_OFFLINE("Offline", "오프라인", "离线"),
    /**
     * The mark of a player or room of another version of the mod.
     */
    OTHER_VERSION("Other version", "다른 버전", "其他版本"),

    /**
     * The filter choice that passes every value.
     */
    ANY("Any", "전체", "全部"),
    /**
     * The label of the room filter's latency choice.
     */
    LATENCY("Latency", "지연 시간", "延迟"),
    /**
     * The room filter's choice of a good latency.
     */
    LATENCY_GOOD("Good", "좋음", "良好"),
    /**
     * The room filter's choice of a fair latency.
     */
    LATENCY_FAIR("Fair", "보통", "一般"),
    /**
     * The label of the room filter's largest capacity choice.
     */
    MAXIMUM_PLAYERS("Max players", "최대 인원", "最大人数"),
    /**
     * The label of the rooms of other versions of the mod.
     */
    OTHER_VERSIONS("Other versions", "다른 버전", "其他版本"),
    /**
     * The label of the room filter's closed rooms choice.
     */
    CLOSED_GAMES("Closed games", "닫힌 게임", "已关闭的游戏"),
    /**
     * The choice that lists the closed rooms.
     */
    SHOW("Show", "표시", "显示"),
    /**
     * The choice that hides the closed rooms.
     */
    HIDE("Hide", "숨김", "隐藏"),
    /**
     * The notice of an empty room list.
     */
    NO_GAMES("No games", "게임 없음", "没有游戏"),
    /**
     * The notice of a join whose channel to the host found no path ({@code ChannelFailure.PAIR_CANNOT_CONNECT}).
     */
    CANNOT_CONNECT("The two players cannot connect. Let another player create the room.",
                   "두 플레이어가 서로 연결할 수 없습니다. 다른 플레이어가 방을 만들게 하세요.", "双方无法连接。请让其他玩家创建房间。"),

    /**
     * The notice of a player who came into the channel.
     */
    JOINED("%s joined", "%s 입장", "%s 加入"),
    /**
     * The notice of a player who left the channel.
     */
    LEFT("%s left", "%s 퇴장", "%s 离开"),
    /**
     * The notice of the channel the player entered.
     */
    CHANNEL("Channel: %s", "채널: %s", "频道：%s"),
    /**
     * The notice of the channel the player left.
     */
    CHANNEL_LEFT("Left %s", "%s 나감", "已离开 %s"),
    /**
     * The error of a line or command that needs a channel while the player is in none.
     */
    NO_CHANNEL("No channel", "채널 없음", "没有频道"),
    /**
     * The prefix of an incoming whisper.
     */
    WHISPER_FROM("From %s: ", "%s의 귓속말: ", "来自 %s："),
    /**
     * The prefix of a sent whisper and of the input while it whispers.
     */
    WHISPER_TO("To %s: ", "%s에게: ", "发给 %s："),
    /**
     * The error of a whisper to a player who is not online.
     */
    NOT_ONLINE("%s: offline", "%s: 오프라인", "%s：离线"),
    /**
     * The error of a name that names no player the chat finds.
     */
    UNKNOWN_PLAYER("Unknown user: %s", "알 수 없는 사용자: %s", "未知用户：%s"),
    /**
     * The error of an unknown slash command.
     */
    UNKNOWN_COMMAND("Unknown command: %s", "알 수 없는 명령: %s", "未知命令：%s"),
    /**
     * The error of {@code /r} without an incoming whisper.
     */
    NO_REPLY("No whisper to reply to", "답장할 귓속말 없음", "没有可回复的私聊"),
    /**
     * The error of a line longer than a channel line takes.
     */
    TOO_LONG("Too long", "너무 김", "太长"),
    /**
     * The error of a line over the chat rate.
     */
    TOO_FAST("Too fast", "너무 빠름", "太快"),
    /**
     * The error of a reached channel or friend limit.
     */
    LIMIT("Limit reached", "한도 초과", "已达上限"),
    /**
     * The error of a refused channel name.
     */
    INVALID_NAME("Invalid name", "잘못된 이름", "名称无效"),
    /**
     * The notice of going away without an away text.
     */
    AWAY_ON("Away", "자리 비움", "离开"),
    /**
     * The notice of coming back from away.
     */
    AWAY_OFF("Back", "돌아옴", "已回来"),
    /**
     * The notice that a whispered player is away without an away text.
     */
    IS_AWAY("%s is away", "%s 자리 비움", "%s 暂时离开"),
    /**
     * The notice that a whispered player is away, with the player's away text.
     */
    IS_AWAY_TEXT("%s is away: %s", "%s 자리 비움: %s", "%s 暂时离开：%s"),
    /**
     * The notice of an ignored player.
     */
    IGNORED("Ignored: %s", "무시: %s", "已屏蔽：%s"),
    /**
     * The notice of a player no longer ignored.
     */
    UNIGNORED("Unignored: %s", "무시 해제: %s", "已取消屏蔽：%s"),
    /**
     * The notice of an added friend.
     */
    FRIEND_ADDED("Friend added: %s", "친구 추가: %s", "已添加好友：%s"),
    /**
     * The notice of a removed friend.
     */
    FRIEND_REMOVED("Friend removed: %s", "친구 삭제: %s", "已删除好友：%s"),
    /**
     * The answer of {@code /friends list}.
     */
    FRIENDS("Friends: %s", "친구: %s", "好友：%s"),
    /**
     * The answer of {@code /who}: the channel, its member count and its members.
     */
    USERS("%s (%d): %s", "%s (%d): %s", "%s (%d): %s"),
    /**
     * The answer of {@code /whois}: the tagged name, the mod and the status.
     */
    USER_INFO("%s: %s, %s", "%s: %s, %s", "%s: %s, %s"),
    /**
     * The error of a command without its arguments, with the command's help line.
     */
    USAGE("Usage: %s", "사용법: %s", "用法：%s"),
    /**
     * The prefix of the input while it says lines in a channel.
     */
    INPUT_CHANNEL("[%s]: ", "[%s]: ", "[%s]: "),
    /**
     * The action that blocks a player.
     */
    BLOCK("Block", "차단", "拉黑"),
    /**
     * The action that stops blocking a player.
     */
    UNBLOCK("Unblock", "차단 해제", "取消拉黑"),
    /**
     * The notice of a blocked player.
     */
    BLOCKED("Blocked: %s", "차단: %s", "已拉黑：%s"),
    /**
     * The notice of a player no longer blocked.
     */
    UNBLOCKED("Unblocked: %s", "차단 해제: %s", "已取消拉黑：%s"),


    /**
     * The title of {@code /help}.
     */
    HELP_TITLE("Commands", "명령어", "命令"),
    /**
     * The help line of {@code /join}.
     */
    HELP_JOIN("/join <channel>  Join a channel", "/join <채널>  채널 입장", "/join <频道>  加入频道"),
    /**
     * The help line of {@code /leave}.
     */
    HELP_LEAVE("/leave  Leave the channel", "/leave  채널 나가기", "/leave  离开频道"),
    /**
     * The help line of {@code /w}.
     */
    HELP_WHISPER("/w <name#tag> <text>  Whisper", "/w <이름#태그> <내용>  귓속말", "/w <名称#标签> <内容>  私聊"),
    /**
     * The help line of {@code /r}.
     */
    HELP_REPLY("/r <text>  Reply", "/r <내용>  답장", "/r <内容>  回复"),
    /**
     * The help line of {@code /me}.
     */
    HELP_EMOTE("/me <text>  Emote", "/me <내용>  감정 표현", "/me <内容>  动作"),
    /**
     * The help line of {@code /who}.
     */
    HELP_WHO("/who  Channel users", "/who  채널 사용자", "/who  频道用户"),
    /**
     * The help line of {@code /whois}.
     */
    HELP_WHOIS("/whois <name#tag>  User info", "/whois <이름#태그>  사용자 정보", "/whois <名称#标签>  用户信息"),
    /**
     * The help line of {@code /ignore}.
     */
    HELP_IGNORE("/ignore <name#tag>  Ignore", "/ignore <이름#태그>  무시", "/ignore <名称#标签>  屏蔽"),
    /**
     * The help line of {@code /unignore}.
     */
    HELP_UNIGNORE("/unignore <name#tag>  Unignore", "/unignore <이름#태그>  무시 해제", "/unignore <名称#标签>  取消屏蔽"),
    /**
     * The help line of {@code /friends}.
     */
    HELP_FRIENDS("/friends add|remove|list [name#tag]  Friends", "/friends add|remove|list [이름#태그]  친구", "/friends add|remove|list [名称#标签]  好友"),
    /**
     * The help line of {@code /away}.
     */
    HELP_AWAY("/away [text]  Away on or off", "/away [내용]  자리 비움 전환", "/away [内容]  切换离开状态"),
    /**
     * The help line of {@code /help}.
     */
    HELP_HELP("/help  Commands", "/help  명령어", "/help  命令");

    /**
     * The language whose texts are Korean.
     */
    private static final @NotNull String KOREAN_LANGUAGE = "ko";
    /**
     * The language whose texts are Simplified Chinese.
     */
    private static final @NotNull String CHINESE_LANGUAGE = "zh";

    private final @NotNull String english;
    private final @NotNull String korean;
    private final @NotNull String chinese;

    CommunityText(@NotNull String english, @NotNull String korean, @NotNull String chinese) {
        this.english = english;
        this.korean  = korean;
        this.chinese = chinese;
    }

    /**
     * Returns the text of a locale.
     *
     * @param locale the locale of the texts
     * @return the Korean text for a Korean locale, the Simplified Chinese text for a Chinese locale, the English text otherwise
     */
    public @NotNull String get(@NotNull Locale locale) {
        return switch (locale.getLanguage()) {
            case KOREAN_LANGUAGE -> this.korean;
            case CHINESE_LANGUAGE -> this.chinese;
            default -> this.english;
        };
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
