package org.fogofwarlabs.sc4j.runtime.cli;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holds the parsed command line of the runtime:
 * {@snippet lang = "text":
 * sc4j-runtime --mod <jar> [--install <dir>] [--settings <json>] [--storage <dir>] [--log <dir>]
 *              [--account <dir>] [--seeds <file>] [--seed <host:port>]... [--peers <file>] [--no-port-mapping]
 *              [--headless] [--replay <file>] [--join <address>] [-- <mod arguments>]
 *}
 * Paths are made absolute against the working directory. Every argument after {@code --} belongs to the mod. The settings, storage and log
 * options that are not given take their defaults from the install layout once the mod id is known. Beacon.Link takes nothing from the install
 * layout: its account folder, seeds and peers file are what the command line names, and a run without any seed source offers no Beacon.Link.
 * Its sessions map their socket on the router unless {@code --no-port-mapping} is given. Only {@code --seed} may be given more than once.
 */
public final class RuntimeCommandLine {
    /**
     * The usage text printed for {@code --help} and after a command line error.
     */
    public static final @NotNull String USAGE = """
                                                Usage: sc4j-runtime --mod <jar> [options] [-- <mod arguments>]
                                                  --mod <jar>          the mod jar to run (required)
                                                  --install <dir>      the SC4J install folder (default: found from the runtime location)
                                                  --settings <json>    the settings value file (default: <install>/userdata/settings/<modId>.json)
                                                  --storage <dir>      the mod's storage folder (default: <install>/userdata/mods/<modId>)
                                                  --log <dir>          the log folder (default: <install>/userdata/logs/<modId>)
                                                  --account <dir>      the Beacon.Link account folder (default: a guest for this run only)
                                                  --seeds <file>       a Beacon.Link node list to contact first
                                                  --seed <host:port>   a Beacon.Link node to contact first (repeatable)
                                                  --peers <file>       the Beacon.Link peers file shared with the computer's other programs (default: none kept)
                                                  --no-port-mapping    do not ask the router to open the Beacon.Link port
                                                  --headless           run without window, audio and input
                                                  --replay <file>      play a replay (passed to the mod)
                                                  --join <address>     join a game (passed to the mod)
                                                  --help               print this text
                                                Beacon.Link is offered only with --seeds, --seed or --peers.
                                                Exit codes: 0 normal, 1 run failure, 2 command line or settings error, 3 mod load or validation failure.
                                                """;

    private static final @NotNull String MOD_OPTION = "--mod";
    private static final @NotNull String INSTALL_OPTION = "--install";
    private static final @NotNull String SETTINGS_OPTION = "--settings";
    private static final @NotNull String STORAGE_OPTION = "--storage";
    private static final @NotNull String LOG_OPTION = "--log";
    private static final @NotNull String ACCOUNT_OPTION = "--account";
    private static final @NotNull String SEEDS_OPTION = "--seeds";
    private static final @NotNull String SEED_OPTION = "--seed";
    private static final @NotNull String PEERS_OPTION = "--peers";
    private static final @NotNull String NO_PORT_MAPPING_OPTION = "--no-port-mapping";
    private static final @NotNull String HEADLESS_OPTION = "--headless";
    private static final @NotNull String REPLAY_OPTION = "--replay";
    private static final @NotNull String JOIN_OPTION = "--join";
    private static final @NotNull String HELP_OPTION = "--help";
    private static final @NotNull String SHORT_HELP_OPTION = "-h";
    private static final @NotNull String MOD_ARGUMENTS_SEPARATOR = "--";

    /**
     * Creates a {@link RuntimeCommandLine} instance from the runtime's process arguments. Every option but {@code --seed} may be given once,
     * and {@code --mod} is required unless {@code --help} is given.
     *
     * @param arguments the process arguments
     * @return the created {@link RuntimeCommandLine} instance
     * @throws IllegalArgumentException if an option is unknown, repeated or lacks its value, a path or node address is invalid, or {@code --mod} is missing
     */
    public static @NotNull RuntimeCommandLine parse(@NotNull String @NotNull [] arguments) {
        Set<String> seen = new HashSet<>();
        List<String> modArguments = new ArrayList<>();
        Path mod = null;
        Path install = null;
        Path settings = null;
        Path storage = null;
        Path log = null;
        Path account = null;
        Path seedsFile = null;
        List<ConfiguredNode> seeds = new ArrayList<>();
        Path peersFile = null;
        boolean portMapping = true;
        Path replay = null;
        String join = null;
        boolean headless = false;
        boolean help = false;

        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];

            if (argument.equals(MOD_ARGUMENTS_SEPARATOR)) {
                modArguments.addAll(List.of(arguments).subList(index + 1, arguments.length));

                break;
            }

            if (!seen.add(argument) && !argument.equals(SEED_OPTION)) {
                throw Exceptions.newIllegalArgumentException("The option {0} is given twice.", argument);
            }

            switch (argument) {
                case HEADLESS_OPTION -> headless = true;
                case NO_PORT_MAPPING_OPTION -> portMapping = false;
                case HELP_OPTION, SHORT_HELP_OPTION -> help = true;
                case MOD_OPTION, INSTALL_OPTION, SETTINGS_OPTION, STORAGE_OPTION, LOG_OPTION, ACCOUNT_OPTION, SEEDS_OPTION, SEED_OPTION, PEERS_OPTION,
                     REPLAY_OPTION, JOIN_OPTION -> {
                    if (index + 1 >= arguments.length) {
                        throw Exceptions.newIllegalArgumentException("The option {0} needs a value.", argument);
                    }

                    String value = arguments[++index];

                    switch (argument) {
                        case MOD_OPTION -> mod = parsePath(argument, value);
                        case INSTALL_OPTION -> install = parsePath(argument, value);
                        case SETTINGS_OPTION -> settings = parsePath(argument, value);
                        case STORAGE_OPTION -> storage = parsePath(argument, value);
                        case LOG_OPTION -> log = parsePath(argument, value);
                        case ACCOUNT_OPTION -> account = parsePath(argument, value);
                        case SEEDS_OPTION -> seedsFile = parsePath(argument, value);
                        case SEED_OPTION -> seeds.add(ConfiguredNode.parse(value, null));
                        case PEERS_OPTION -> peersFile = parsePath(argument, value);
                        case REPLAY_OPTION -> replay = parsePath(argument, value);
                        default -> join = value;
                    }
                }
                default -> throw Exceptions.newIllegalArgumentException("Unknown option {0}; mod arguments follow --.", argument);
            }
        }

        if (mod == null && !help) {
            throw Exceptions.newIllegalArgumentException("The option {0} is required.", MOD_OPTION);
        }

        return new RuntimeCommandLine(help, mod,
                                      install, settings, storage, log,
                                      account, seedsFile, seeds, peersFile, portMapping,
                                      headless, replay, join,
                                      modArguments);
    }

    private static @NotNull Path parsePath(@NotNull String option, @NotNull String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The option {0} has an invalid path: {1}", option, value);
        }
    }


    private final boolean help;
    private final @Nullable Path mod;

    private final @Nullable Path install;
    private final @Nullable Path settings;
    private final @Nullable Path storage;
    private final @Nullable Path log;

    private final @Nullable Path account;
    private final @Nullable Path seedsFile;
    private final @NotNull List<@NotNull ConfiguredNode> seeds;
    private final @Nullable Path peersFile;
    private final boolean portMapping;

    private final boolean headless;
    private final @Nullable Path replay;
    private final @Nullable String join;

    private final @NotNull List<@NotNull String> modArguments;

    /**
     * Creates a {@link RuntimeCommandLine} instance.
     *
     * @param help         whether {@code --help} was given
     * @param mod          the mod jar, or {@code null} with {@code --help}
     * @param install      the install folder, or {@code null} for the detected one
     * @param settings     the settings value file, or {@code null} for the default
     * @param storage      the storage folder, or {@code null} for the default
     * @param log          the log folder, or {@code null} for the default
     * @param account      the account folder of the run, or {@code null} for a guest
     * @param seedsFile    the node list of seeds, or {@code null} for none
     * @param seeds        the seeds named one by one, in command line order
     * @param peersFile    the shared peers file, or {@code null} for none
     * @param portMapping  whether the Beacon.Link sessions map their socket on the router
     * @param headless     whether the run has no window, audio and input
     * @param replay       the replay to play, or {@code null}
     * @param join         the address of the game to join, or {@code null}
     * @param modArguments the arguments after {@code --}
     */
    private RuntimeCommandLine(boolean help, @Nullable Path mod,
                               @Nullable Path install, @Nullable Path settings, @Nullable Path storage, @Nullable Path log,
                               @Nullable Path account, @Nullable Path seedsFile, @NotNull List<@NotNull ConfiguredNode> seeds, @Nullable Path peersFile,
                               boolean portMapping,
                               boolean headless, @Nullable Path replay, @Nullable String join,
                               @NotNull List<@NotNull String> modArguments) {
        this.help = help;
        this.mod  = mod;

        this.install  = install;
        this.settings = settings;
        this.storage  = storage;
        this.log      = log;

        this.account     = account;
        this.seedsFile   = seedsFile;
        this.seeds       = List.copyOf(seeds);
        this.peersFile   = peersFile;
        this.portMapping = portMapping;

        this.headless = headless;
        this.replay   = replay;
        this.join     = join;

        this.modArguments = List.copyOf(modArguments);
    }

    /**
     * @return {@code true} if {@code --help} was given, {@code false} otherwise
     */
    public boolean isHelp() {
        return this.help;
    }

    /**
     * @return the absolute mod jar path ({@code --mod}), or {@code null} only with {@code --help}
     */
    public @Nullable Path getMod() {
        return this.mod;
    }

    /**
     * @return the install folder ({@code --install}), or {@code null} to detect it from the runtime location
     */
    public @Nullable Path getInstall() {
        return this.install;
    }

    /**
     * @return the settings value file ({@code --settings}), or {@code null} for the install layout's default
     */
    public @Nullable Path getSettings() {
        return this.settings;
    }

    /**
     * @return the mod's storage folder ({@code --storage}), or {@code null} for the install layout's default
     */
    public @Nullable Path getStorage() {
        return this.storage;
    }

    /**
     * @return the log folder ({@code --log}), or {@code null} for the install layout's default
     */
    public @Nullable Path getLog() {
        return this.log;
    }

    /**
     * @return the absolute account folder of the run ({@code --account}), or {@code null} for a guest
     */
    public @Nullable Path getAccount() {
        return this.account;
    }

    /**
     * @return the absolute node list of seeds ({@code --seeds}), or {@code null} if none is given
     */
    public @Nullable Path getSeedsFile() {
        return this.seedsFile;
    }

    /**
     * @return the seeds named one by one ({@code --seed}), in command line order
     */
    public @NotNull List<@NotNull ConfiguredNode> getSeeds() {
        return this.seeds;
    }

    /**
     * @return the absolute shared peers file ({@code --peers}), or {@code null} if none is given
     */
    public @Nullable Path getPeersFile() {
        return this.peersFile;
    }

    /**
     * @return {@code true} if the Beacon.Link sessions map their socket on the router, {@code false} if {@code --no-port-mapping} was given
     */
    public boolean isPortMapping() {
        return this.portMapping;
    }

    /**
     * @return {@code true} if the command line names a seed source ({@code --seeds}, {@code --seed} or {@code --peers}), {@code false} otherwise
     */
    public boolean hasSeedSource() {
        return this.seedsFile != null || !this.seeds.isEmpty() || this.peersFile != null;
    }

    /**
     * @return {@code true} if {@code --headless} was given, {@code false} otherwise
     */
    public boolean isHeadless() {
        return this.headless;
    }

    /**
     * @return the replay to play ({@code --replay}), or {@code null}
     */
    public @Nullable Path getReplay() {
        return this.replay;
    }

    /**
     * @return the address of the game to join ({@code --join}), or {@code null}
     */
    public @Nullable String getJoin() {
        return this.join;
    }

    /**
     * @return the arguments after {@code --}, for the mod
     */
    public @NotNull List<@NotNull String> getModArguments() {
        return this.modArguments;
    }
}
