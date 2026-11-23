package org.fogofwarlabs.sc4j.beaconlink.protocol.file;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;

/**
 * Describes one node of a node list: its host and port as listed ({@code host:port}, an IPv6 host in brackets) and, optionally, the key
 * the node must answer with. A node of the launcher's list may pin its key; a node learned from another node always carries the key that
 * node reported.
 */
public final class ConfiguredNode {
    /**
     * The smallest port number.
     */
    public static final int MINIMUM_PORT = 1;
    /**
     * The largest port number.
     */
    public static final int MAXIMUM_PORT = 0xffff;

    private static final char PORT_SEPARATOR = ':';
    private static final char IPV6_OPEN = '[';
    private static final char IPV6_CLOSE = ']';
    private static final int DECIMAL_RADIX = 10;
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Checks a host as a node list stores it.
     *
     * @param host the host name, IPv4 address or IPv6 address (without brackets)
     * @return {@code true} if the host is not empty and holds no white space and no bracket, {@code false} otherwise
     */
    public static boolean isValidHost(@NotNull String host) {
        return !host.isEmpty() &&
               host.chars().noneMatch(character -> Character.isWhitespace(character) || character == IPV6_OPEN || character == IPV6_CLOSE);
    }

    /**
     * Checks a port number.
     *
     * @param port the port number
     * @return {@code true} if the port is from {@value #MINIMUM_PORT} to {@value #MAXIMUM_PORT}, {@code false} otherwise
     */
    public static boolean isValidPort(int port) {
        return port >= MINIMUM_PORT && port <= MAXIMUM_PORT;
    }

    /**
     * Reads a port number written in decimal digits only.
     *
     * @param text the port text
     * @return the port number, or {@code -1} if the text is not a decimal number from {@value #MINIMUM_PORT} to {@value #MAXIMUM_PORT}
     */
    public static int parsePort(@NotNull String text) {
        if (text.isEmpty()) {
            return -1;
        }

        for (int index = 0; index < text.length(); index++) {
            if (Character.digit(text.charAt(index), DECIMAL_RADIX) < 0) {
                return -1;
            }
        }

        try {
            int port = Integer.parseInt(text, DECIMAL_RADIX);

            return isValidPort(port) ? port : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Creates a {@link ConfiguredNode} instance from its text form.
     *
     * @param text the address, such as {@code beacon.example.org:7455} or {@code [2001:db8::1]:7455}
     * @param key  the key the node must answer with, or {@code null} to accept the key of its first answer
     * @return the created {@link ConfiguredNode} instance
     * @throws IllegalArgumentException if the text is not {@code host:port} with a valid host and port
     */
    public static @NotNull ConfiguredNode parse(@NotNull String text, @Nullable IdentityKey key) {
        String address = text.strip();
        int separator = address.lastIndexOf(PORT_SEPARATOR);

        if (separator <= 0 || separator == address.length() - 1) {
            throw Exceptions.newIllegalArgumentException("Invalid node address (expected host:port): {0}", text);
        }

        String host = address.substring(0, separator);

        if (host.charAt(0) == IPV6_OPEN) {
            if (host.length() < 2 || host.charAt(host.length() - 1) != IPV6_CLOSE) {
                throw Exceptions.newIllegalArgumentException("Invalid node address (expected [IPv6 address]:port): {0}", text);
            }

            host = host.substring(1, host.length() - 1);
        } else if (host.indexOf(PORT_SEPARATOR) >= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid node address (an IPv6 address needs brackets): {0}", text);
        }

        int port = parsePort(address.substring(separator + 1));

        if (port < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid node port in {0}: expected {1} to {2}", text, MINIMUM_PORT, MAXIMUM_PORT);
        }

        return of(host, port, key);
    }

    /**
     * Creates a {@link ConfiguredNode} instance for a node learned from another node: its IP literal, port and key.
     *
     * @param endpoint the node's key and address
     * @return the created {@link ConfiguredNode} instance
     */
    public static @NotNull ConfiguredNode of(@NotNull NodeEndpoint endpoint) {
        InetSocketAddress address = endpoint.getAddress();

        return new ConfiguredNode(address.getAddress().getHostAddress(), address.getPort(), endpoint.getKey());
    }

    /**
     * Creates a {@link ConfiguredNode} instance from a host, a port and an optional pinned key.
     *
     * @param host the host name, IPv4 address or IPv6 address (without brackets)
     * @param port the UDP port
     * @param key  the key the node must answer with, or {@code null} to accept the key of its first answer
     * @return the created {@link ConfiguredNode} instance
     * @throws IllegalArgumentException if the host is empty or holds white space or brackets, or the port is out of range
     */
    public static @NotNull ConfiguredNode of(@NotNull String host, int port, @Nullable IdentityKey key) {
        if (!isValidHost(host)) {
            throw Exceptions.newIllegalArgumentException("Invalid node host: {0}", host);
        }

        if (!isValidPort(port)) {
            throw Exceptions.newIllegalArgumentException("Invalid node port {0}: expected {1} to {2}", port, MINIMUM_PORT, MAXIMUM_PORT);
        }

        return new ConfiguredNode(host, port, key);
    }


    private final @NotNull String host;
    private final int port;
    private final @Nullable IdentityKey key;

    private ConfiguredNode(@NotNull String host, int port, @Nullable IdentityKey key) {
        this.host = host;
        this.port = port;
        this.key  = key;
    }

    /**
     * @return the host name, IPv4 address or IPv6 address (without brackets)
     */
    public @NotNull String getHost() {
        return this.host;
    }

    /**
     * @return the UDP port
     */
    public int getPort() {
        return this.port;
    }

    /**
     * @return the key the node must answer with, or {@code null} if any key is accepted at the first answer
     */
    public @Nullable IdentityKey getKey() {
        return this.key;
    }


    /**
     * Compares the addresses of two nodes, ignoring their keys.
     *
     * @param other the other node
     * @return {@code true} if both nodes have the same host (ignoring case) and port, {@code false} otherwise
     */
    public boolean hasSameAddress(@NotNull ConfiguredNode other) {
        return this.host.equalsIgnoreCase(other.host) && this.port == other.port;
    }

    /**
     * Resolves the host; a host name is looked up, an IP literal is not.
     *
     * @return the node's socket address, or {@code null} if the host cannot be resolved
     */
    public @Nullable InetSocketAddress resolve() {
        InetSocketAddress address = new InetSocketAddress(this.host, this.port);

        return address.isUnresolved() ? null : address;
    }

    /**
     * @param other the other object
     * @return {@code true} if the other object is a node with the same address (host ignoring case) and the same pinned key or none, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ConfiguredNode node && this.hasSameAddress(node) && Objects.equals(this.key, node.key);
    }

    /**
     * @return the hash code of the lower case host, the port and the pinned key, consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        return (this.host.toLowerCase(Locale.ROOT).hashCode() * HASH_MULTIPLIER + this.port) * HASH_MULTIPLIER + Objects.hashCode(this.key);
    }

    /**
     * @return the text form {@code host:port}, with an IPv6 host in brackets
     */
    @Override
    public @NotNull String toString() {
        String host = this.host.indexOf(PORT_SEPARATOR) >= 0 ? IPV6_OPEN + this.host + IPV6_CLOSE : this.host;

        return host + PORT_SEPARATOR + this.port;
    }
}
