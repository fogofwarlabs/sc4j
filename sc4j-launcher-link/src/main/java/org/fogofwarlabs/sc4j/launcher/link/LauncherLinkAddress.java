package org.fogofwarlabs.sc4j.launcher.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Names a launcher link: the Unix domain socket of the server and the random token a client proves itself with. The launcher passes the socket
 * on the runtime's command line and the token in the environment variable {@value #TOKEN_ENVIRONMENT_VARIABLE}, which other processes cannot
 * read. The token never appears in {@link #toString()}.
 */
public final class LauncherLinkAddress {
    /**
     * The environment variable that carries the token text ({@link #getTokenText()}) to a started runtime.
     */
    public static final @NotNull String TOKEN_ENVIRONMENT_VARIABLE = "SC4J_LAUNCHER_LINK_TOKEN";
    /**
     * The longest socket path a link uses, in UTF-8 bytes: below the platform limits of Unix domain socket paths (104 bytes on macOS, 108 on
     * Linux and Windows, the terminating zero included).
     */
    public static final int MAXIMUM_SOCKET_PATH_LENGTH = 100;
    /**
     * The length of a token, in bytes.
     */
    public static final int TOKEN_LENGTH = 32;

    /**
     * Creates a {@link LauncherLinkAddress} instance for a socket with a new random token.
     *
     * @param socket the socket path
     * @return the created {@link LauncherLinkAddress} instance
     */
    public static @NotNull LauncherLinkAddress create(@NotNull Path socket) {
        byte[] token = new byte[TOKEN_LENGTH];

        new SecureRandom().nextBytes(token);

        return new LauncherLinkAddress(socket.toAbsolutePath().normalize(), token);
    }

    /**
     * Creates a {@link LauncherLinkAddress} instance from a socket and the text of its token.
     *
     * @param socket    the socket path
     * @param tokenText the token as {@value #TOKEN_LENGTH} bytes in hexadecimal digits ({@link #getTokenText()})
     * @return the created {@link LauncherLinkAddress} instance
     * @throws IllegalArgumentException if the token text is not {@value #TOKEN_LENGTH} bytes in hexadecimal digits
     */
    public static @NotNull LauncherLinkAddress of(@NotNull Path socket, @NotNull String tokenText) {
        byte[] token;

        try {
            token = HexFormat.of().parseHex(tokenText);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The launcher link token is not hexadecimal.");
        }

        if (token.length != TOKEN_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The launcher link token has {0} bytes instead of {1}.", token.length, TOKEN_LENGTH);
        }

        return new LauncherLinkAddress(socket.toAbsolutePath().normalize(), token);
    }

    /**
     * Checks that a socket path fits the platform limits of Unix domain sockets.
     *
     * @param socket the socket path
     * @return {@code true} if the absolute path has at most {@value #MAXIMUM_SOCKET_PATH_LENGTH} UTF-8 bytes, {@code false} otherwise
     */
    public static boolean isSocketPathSupported(@NotNull Path socket) {
        return socket.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_SOCKET_PATH_LENGTH;
    }


    private final @NotNull Path socket;
    private final byte @NotNull [] token;

    /**
     * Creates a {@link LauncherLinkAddress} instance.
     *
     * @param socket the absolute socket path
     * @param token  the token, which the address keeps
     */
    private LauncherLinkAddress(@NotNull Path socket, byte @NotNull [] token) {
        this.socket = socket;
        this.token  = token;
    }

    /**
     * @return the absolute path of the server's socket
     */
    public @NotNull Path getSocket() {
        return this.socket;
    }

    /**
     * @return the token in hexadecimal digits, the value of {@value #TOKEN_ENVIRONMENT_VARIABLE}
     */
    public @NotNull String getTokenText() {
        return HexFormat.of().formatHex(this.token);
    }

    /**
     * @return a copy of the token
     */
    byte @NotNull [] getToken() {
        return this.token.clone();
    }

    /**
     * Compares a token with this address's token in time that does not depend on where they differ.
     *
     * @param candidate the token a client sent
     * @return {@code true} if the tokens are equal, {@code false} otherwise
     */
    boolean matchesToken(byte @NotNull [] candidate) {
        return MessageDigest.isEqual(this.token, candidate);
    }

    /**
     * Describes the address by its socket; the token is left out.
     *
     * @return the socket path
     */
    @Override
    public @NotNull String toString() {
        return this.socket.toString();
    }
}
