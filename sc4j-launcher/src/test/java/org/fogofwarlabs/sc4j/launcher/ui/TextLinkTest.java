package org.fogofwarlabs.sc4j.launcher.ui;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * Tests the web addresses found in chat text: only {@code http} and {@code https}, their start and end in the text, and the punctuation left
 * out of their end.
 */
final class TextLinkTest {
    private static @NotNull List<@NotNull String> findAddresses(@NotNull String text) {
        return TextLink.findLinks(text).stream().map(TextLink::getAddress).toList();
    }

    @Test
    void find_links_should_give_each_address_with_its_start_and_end() {
        String text = "see https://example.com/maps?id=7 and HTTP://Example.org/a#b now";
        List<TextLink> links = TextLink.findLinks(text);

        Assertions.assertEquals(List.of("https://example.com/maps?id=7", "HTTP://Example.org/a#b"), findAddresses(text));
        Assertions.assertEquals(4, links.getFirst().getStart());
        Assertions.assertEquals(33, links.getFirst().getEnd());
        Assertions.assertEquals(links.getLast().getAddress(), text.substring(links.getLast().getStart(), links.getLast().getEnd()));
        Assertions.assertTrue(links.getFirst().contains(4));
        Assertions.assertTrue(links.getFirst().contains(32));
        Assertions.assertFalse(links.getFirst().contains(33));
        Assertions.assertFalse(links.getFirst().contains(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///C:/Windows/notepad.exe", "javascript:alert(1)", "ftp://example.com/file", "mailto:someone@example.com",
                            "www.example.com", "data:text/html,hi", "https:/example.com", "http//example.com", "https://", "https:// example.com",
                            "http:///etc/passwd", "xhttps://example.com", "9http://example.com"})
    void find_links_should_find_no_other_scheme_and_no_address_inside_a_word(@NotNull String text) {
        Assertions.assertEquals(List.of(), findAddresses(text));
    }

    @Test
    void find_links_should_find_an_address_after_punctuation_or_a_letter_other_than_ascii() {
        Assertions.assertEquals(List.of("https://example.com"), findAddresses("notes:https://example.com"));
        Assertions.assertEquals(List.of("https://example.com"), findAddresses("-https://example.com"));
        Assertions.assertEquals(List.of("https://example.com"), findAddresses("주소https://example.com"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "Visit https://example.com.|https://example.com",
            "Is it https://example.com/a?|https://example.com/a",
            "https://example.com/a, https://example.com/b;|https://example.com/a",
            "(see https://example.com/x)|https://example.com/x",
            "https://en.wikipedia.org/wiki/Fog_(film) is a film|https://en.wikipedia.org/wiki/Fog_(film)",
            "[https://example.com/list]|https://example.com/list",
            "'https://example.com/q'|https://example.com/q",
            "<https://example.com/tag>|https://example.com/tag",
            "\"https://example.com/quoted\"|https://example.com/quoted",
            "https://example.com/end!!!|https://example.com/end",
            "https://[::1]:8080/path|https://[::1]:8080/path",
            "주소는https://example.com/한글 입니다|https://example.com/",
            "https://example.com에서 받으세요|https://example.com",
            "请看https://example.com的说明|https://example.com",
            "https://example.com/a\u3000next|https://example.com/a",
            "링크: https://example.com/b。|https://example.com/b"
    })
    void find_links_should_leave_trailing_punctuation_and_unopened_brackets_out(@NotNull String text, @NotNull String address) {
        Assertions.assertEquals(address, findAddresses(text).getFirst());
    }

    @Test
    void find_links_should_stop_at_white_space_and_control_characters() {
        String text = "https://example.com/a\thttp://example.com/b\n";

        Assertions.assertEquals(List.of("https://example.com/a", "http://example.com/b"), findAddresses(text));
        Assertions.assertEquals(List.of("https://example.com/c"), findAddresses("https://example.com/c\u0007tail"));
        Assertions.assertEquals(List.of(), findAddresses(""));
    }

    @Test
    void is_web_address_should_accept_one_http_or_https_address_only() {
        Assertions.assertTrue(TextLink.isWebAddress("https://example.com/maps?id=7"));
        Assertions.assertTrue(TextLink.isWebAddress("http://example.com"));
        Assertions.assertFalse(TextLink.isWebAddress("https://example.com/a b"));
        Assertions.assertFalse(TextLink.isWebAddress("https://example.com."));
        Assertions.assertFalse(TextLink.isWebAddress(" https://example.com"));
        Assertions.assertFalse(TextLink.isWebAddress("file:///C:/Windows"));
        Assertions.assertFalse(TextLink.isWebAddress("javascript:alert(1)"));
        Assertions.assertFalse(TextLink.isWebAddress(""));
    }
}
