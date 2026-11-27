package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Tests the launcher link address: the token text that the environment carries, the description without the token and the socket path limit.
 */
final class LauncherLinkAddressTest {
    private static final @NotNull String SOCKET_NAME = "link.sock";

    @Test
    void of_should_read_the_token_text_of_a_created_address(@TempDir @NotNull Path directory) {
        LauncherLinkAddress address = LauncherLinkAddress.create(directory.resolve(SOCKET_NAME));
        LauncherLinkAddress read = LauncherLinkAddress.of(address.getSocket(), address.getTokenText());

        Assertions.assertEquals(LauncherLinkAddress.TOKEN_LENGTH, HexFormat.of().parseHex(address.getTokenText()).length);
        Assertions.assertEquals(address.getSocket(), read.getSocket());
        Assertions.assertTrue(read.matchesToken(address.getToken()));
        Assertions.assertFalse(LauncherLinkAddress.create(address.getSocket()).matchesToken(address.getToken()), "each address has its own token");
        Assertions.assertFalse(address.toString().contains(address.getTokenText()), "the description leaves the token out");
    }

    @Test
    void of_should_reject_a_short_or_non_hexadecimal_token(@TempDir @NotNull Path directory) {
        Path socket = directory.resolve(SOCKET_NAME);

        Assertions.assertThrows(IllegalArgumentException.class, () -> LauncherLinkAddress.of(socket, "00ff"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LauncherLinkAddress.of(socket, "not hexadecimal"));
    }

    @Test
    void is_socket_path_supported_should_accept_only_paths_within_the_limit(@TempDir @NotNull Path directory) {
        Assertions.assertTrue(LauncherLinkAddress.isSocketPathSupported(directory.getRoot().resolve(SOCKET_NAME)));
        Assertions.assertFalse(LauncherLinkAddress.isSocketPathSupported(directory.resolve("x".repeat(LauncherLinkAddress.MAXIMUM_SOCKET_PATH_LENGTH))));
    }
}
