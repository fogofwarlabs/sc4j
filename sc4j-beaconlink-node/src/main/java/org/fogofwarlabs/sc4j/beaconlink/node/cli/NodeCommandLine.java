package org.fogofwarlabs.sc4j.beaconlink.node.cli;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holds the parsed command line of the node executable:
 * {@snippet lang = "text":
 * sc4j-beaconlink-node --state <dir> --bind <ip>:<port> [--peer <host>:<port>]...
 *}
 * The bind address is an IP literal and has no default, so a node never listens on an address its operator did not name. Peers are seeds:
 * host names or IP literals, resolved when the node starts, with any key. IPv6 addresses are written in brackets
 * ({@code [2001:db8::1]:7455}).
 */
public final class NodeCommandLine {
    /**
     * The usage text printed for {@code --help} and after a command line error.
     */
    public static final @NotNull String USAGE = """
                                                Usage: sc4j-beaconlink-node --state <dir> --bind <ip>:<port> [--peer <host>:<port>]...
                                                  --state <dir>        state folder: key, learned peers, logs (created if absent)
                                                  --bind <ip>:<port>   IP address and UDP port to listen on, for example 203.0.113.7:7455
                                                  --peer <host>:<port> seed to join through; repeat for more
                                                  --help               print this text
                                                A headless Beacon.Link peer, the same peer as the launcher's node. No game traffic.
                                                Exit codes: 0 normal, 1 run failure, 2 command line error.
                                                """;

    private static final @NotNull String STATE_OPTION = "--state";
    private static final @NotNull String BIND_OPTION = "--bind";
    private static final @NotNull String PEER_OPTION = "--peer";
    private static final @NotNull String HELP_OPTION = "--help";
    private static final @NotNull String SHORT_HELP_OPTION = "-h";
    private static final int MAXIMUM_PORT = 0xffff;

    /**
     * Parses the node's command line. Only {@code --peer} may be repeated; {@code --state} and {@code --bind} are required without
     * {@code --help}.
     *
     * @param arguments the process arguments
     * @return the parsed command line
     * @throws IllegalArgumentException if an option is unknown, repeated or lacks its value, a value is invalid, or a required option is missing
     */
    public static @NotNull NodeCommandLine parse(@NotNull String @NotNull [] arguments) {
        Set<String> seen = new HashSet<>();
        List<ConfiguredNode> peers = new ArrayList<>();
        Path state = null;
        InetSocketAddress bind = null;
        boolean help = false;

        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];

            if (!argument.equals(PEER_OPTION) && !seen.add(argument)) {
                throw Exceptions.newIllegalArgumentException("The option {0} is given twice.", argument);
            }

            switch (argument) {
                case HELP_OPTION, SHORT_HELP_OPTION -> help = true;
                case STATE_OPTION, BIND_OPTION, PEER_OPTION -> {
                    if (index + 1 >= arguments.length) {
                        throw Exceptions.newIllegalArgumentException("The option {0} needs a value.", argument);
                    }

                    String value = arguments[++index];

                    switch (argument) {
                        case STATE_OPTION -> state = parsePath(value);
                        case BIND_OPTION -> bind = parseBindAddress(value);
                        default -> peers.add(parsePeer(value));
                    }
                }
                default -> throw Exceptions.newIllegalArgumentException("Unknown option {0}.", argument);
            }
        }

        if (!help && (state == null || bind == null)) {
            throw Exceptions.newIllegalArgumentException("The options {0} and {1} are required.", STATE_OPTION, BIND_OPTION);
        }

        return new NodeCommandLine(help, state, bind, peers);
    }

    private static @NotNull Path parsePath(@NotNull String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The option {0} has an invalid path: {1}", STATE_OPTION, value);
        }
    }

    private static @NotNull InetSocketAddress parseBindAddress(@NotNull String value) {
        String host;
        String port;

        if (value.startsWith("[")) {
            int end = value.indexOf("]:");

            if (end < 0) {
                throw Exceptions.newIllegalArgumentException("The option {0} needs [<ipv6>]:<port>, not {1}.", BIND_OPTION, value);
            }

            host = value.substring(1, end);
            port = value.substring(end + 2);
        } else {
            int separator = value.lastIndexOf(':');

            if (separator <= 0 || value.indexOf(':') != separator) {
                throw Exceptions.newIllegalArgumentException("The option {0} needs <ip>:<port>, not {1}.", BIND_OPTION, value);
            }

            host = value.substring(0, separator);
            port = value.substring(separator + 1);
        }

        int number;

        try {
            number = Integer.parseInt(port);
        } catch (NumberFormatException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The option {0} has an invalid port: {1}", BIND_OPTION, value);
        }

        if (number < 0 || number > MAXIMUM_PORT) {
            throw Exceptions.newIllegalArgumentException("The option {0} has a port outside its range: {1}", BIND_OPTION, value);
        }

        try {
            return new InetSocketAddress(InetAddress.ofLiteral(host), number);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The option {0} needs an IP address, not {1}.", BIND_OPTION, host);
        }
    }

    private static @NotNull ConfiguredNode parsePeer(@NotNull String value) {
        try {
            return ConfiguredNode.parse(value, null);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The option {0} needs <host>:<port>, not {1}.", PEER_OPTION, value);
        }
    }


    private final boolean help;
    private final @Nullable Path state;
    private final @Nullable InetSocketAddress bind;
    private final @NotNull List<@NotNull ConfiguredNode> peers;

    private NodeCommandLine(boolean help, @Nullable Path state, @Nullable InetSocketAddress bind, @NotNull List<@NotNull ConfiguredNode> peers) {
        this.help  = help;
        this.state = state;
        this.bind  = bind;
        this.peers = List.copyOf(peers);
    }

    /**
     * @return {@code true} if {@code --help} was given, {@code false} otherwise
     */
    public boolean isHelp() {
        return this.help;
    }

    /**
     * @return the absolute state folder ({@code --state}), or {@code null} only with {@code --help}
     */
    public @Nullable Path getState() {
        return this.state;
    }

    /**
     * @return the bind address ({@code --bind}), resolved, or {@code null} only with {@code --help}
     */
    public @Nullable InetSocketAddress getBind() {
        return this.bind;
    }

    /**
     * @return the seeds ({@code --peer}) in their order, without a pinned key; a host name is resolved when the node starts
     */
    public @NotNull List<@NotNull ConfiguredNode> getPeers() {
        return this.peers;
    }
}
