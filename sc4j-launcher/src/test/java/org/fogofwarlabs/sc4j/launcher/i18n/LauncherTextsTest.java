package org.fogofwarlabs.sc4j.launcher.i18n;

import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountProblem;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.BootstrapState;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListProblem;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListProblemKind;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeEntryProblem;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingStatus;
import org.fogofwarlabs.sc4j.launcher.session.FriendState;
import org.fogofwarlabs.sc4j.launcher.session.SessionFailure;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Tests the launcher texts: every language has every text, and problems (also those of the node list) and the states of the account's
 * session and friends are described in the display language.
 */
final class LauncherTextsTest {
    private static final @NotNull String BASE_BUNDLE = "messages.properties";
    private static final @NotNull String KOREAN_BUNDLE = "messages_ko.properties";
    private static final @NotNull List<@NotNull String> REMOVED_KEY_TEXTS = List.of("nodes.key", "nodes.key.prompt", "nodes.pinned",
                                                                                    "nodes.ping.wrong_key", "nodes.entry.key_invalid");
    private static final @NotNull List<@NotNull String> SEED_TEXT_PREFIXES = List.of("nodes.", "beaconlink.", "status.nodes.");
    private static final @NotNull List<@NotNull String> NODE_KEY_WORDS = List.of("key", "키");

    @Test
    void every_language_has_every_text_of_the_base_bundle() throws IOException {
        Properties base = readBundle(BASE_BUNDLE);
        Properties korean = readBundle(KOREAN_BUNDLE);

        Assertions.assertEquals(base.stringPropertyNames(), korean.stringPropertyNames());
    }

    @Test
    void every_problem_code_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (ProblemCode code : ProblemCode.values()) {
                Assertions.assertFalse(texts.describeProblem(new ValidationProblem(code, "subject", "text", null)).isBlank(), code.getCode());
            }
        }
    }

    @Test
    void every_account_problem_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (AccountProblem problem : AccountProblem.values()) {
                Assertions.assertFalse(texts.describeAccountProblem(problem).isBlank(), problem.name());
            }
        }
    }

    @Test
    void every_release_problem_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (ReleaseProblem problem : ReleaseProblem.values()) {
                Assertions.assertFalse(texts.describeReleaseProblem(problem).isBlank(), problem.name());
            }
        }
    }

    @Test
    void every_reachability_and_bootstrap_state_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (Reachability reachability : Reachability.values()) {
                Assertions.assertFalse(texts.describeReachability(reachability, 7455).isBlank(), reachability.name());
            }

            for (BootstrapState state : BootstrapState.values()) {
                Assertions.assertFalse(texts.describeBootstrapState(state).isBlank(), state.name());
            }

            Assertions.assertTrue(texts.describeReachability(Reachability.NOT_REACHABLE, 7455).contains("7455"), "the status names the port");
        }
    }

    @Test
    void every_node_list_problem_kind_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (NodeListProblemKind kind : NodeListProblemKind.values()) {
                String text = texts.describeNodeListProblem(new NodeListProblem(kind, 2, "detail"));

                Assertions.assertTrue(text.contains("detail"), kind.name());
            }

            Assertions.assertTrue(texts.describeNodeListProblem(new NodeListProblem(NodeListProblemKind.INVALID_ENTRY, 2, "detail")).contains("3"));
        }
    }

    @Test
    void every_node_entry_problem_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (NodeEntryProblem problem : NodeEntryProblem.values()) {
                String text = texts.describeNodeEntryProblem(problem);

                Assertions.assertFalse(text.isBlank() || text.contains("{"), problem.name());
            }
        }
    }

    @Test
    void every_node_ping_status_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (NodePingStatus status : NodePingStatus.values()) {
                Assertions.assertFalse(texts.get("nodes.ping." + status.name().toLowerCase(Locale.ROOT)).isBlank(), status.name());
            }
        }
    }

    @Test
    void every_session_failure_and_friend_state_has_a_text_in_every_language() {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            for (SessionFailure failure : SessionFailure.values()) {
                Assertions.assertFalse(texts.get("session.failure." + failure.name().toLowerCase(Locale.ROOT)).isBlank(), failure.name());
            }

            for (FriendState state : FriendState.values()) {
                Assertions.assertFalse(texts.get("session.friend." + state.name().toLowerCase(Locale.ROOT)).isBlank(), state.name());
            }
        }
    }

    @Test
    void no_launcher_text_shows_a_node_key() throws IOException {
        for (String bundle : List.of(BASE_BUNDLE, KOREAN_BUNDLE)) {
            Properties texts = readBundle(bundle);

            for (String removed : REMOVED_KEY_TEXTS) {
                Assertions.assertFalse(texts.containsKey(removed), bundle + " " + removed);
            }

            for (String name : texts.stringPropertyNames()) {
                if (SEED_TEXT_PREFIXES.stream().noneMatch(name::startsWith)) {
                    continue;
                }

                String text = texts.getProperty(name).toLowerCase(Locale.ROOT);

                for (String word : NODE_KEY_WORDS) {
                    Assertions.assertFalse(text.contains(word), bundle + " " + name);
                }
            }
        }
    }

    @Test
    void of_should_not_fall_back_to_the_default_locale() {
        Locale defaultLocale = Locale.getDefault();

        try {
            Locale.setDefault(Locale.KOREA);

            Assertions.assertEquals("Run", LauncherTexts.of(UiLanguage.ENGLISH).get("detail.run"));
            Assertions.assertEquals("실행", LauncherTexts.of(UiLanguage.KOREAN).get("detail.run"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void format_count_should_choose_the_singular_or_the_plural_text() {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        Assertions.assertEquals("1 mod", texts.formatCount("status.mods", 1));
        Assertions.assertEquals("3 mods", texts.formatCount("status.mods", 3));
    }

    private static @NotNull Properties readBundle(@NotNull String name) throws IOException {
        Properties properties = new Properties();

        try (InputStream input = LauncherTexts.class.getResourceAsStream(name)) {
            Assertions.assertNotNull(input, name);
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        return properties;
    }
}
