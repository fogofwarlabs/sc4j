package org.fogofwarlabs.sc4j.testmod;

import org.fogofwarlabs.sc4j.engine.mod.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A test mod on the engine-only path that opens a Beacon.Link session headless: its headless runner opens a session with the nodes of the
 * run's command line ({@link BeaconLink#open()}), polls it until a node registered it and writes {@value #RUN_FILE} into its storage at once: the
 * address the node observed, the player key in hexadecimal and the connection number in hexadecimal. With the mod argument
 * {@value #HOLD_ARGUMENT} and a file, the session stays open and polled until that file exists. A session that fails or that no node
 * registers within {@value #REGISTRATION_TIMEOUT_MILLIS} ms, or that is held longer than that, refuses the run with the reason.
 */
public final class BeaconLinkMod implements Mod {
    /**
     * The storage file the headless runner writes once a node registered the session.
     */
    public static final @NotNull String RUN_FILE = "beaconlink-run.txt";
    /**
     * The first word of {@value #RUN_FILE}, followed by the address the node observed.
     */
    public static final @NotNull String REGISTERED = "registered";
    /**
     * The mod argument that keeps the registered session open until the file after it exists.
     */
    public static final @NotNull String HOLD_ARGUMENT = "--hold";

    private static final @NotNull String WRITING_SUFFIX = ".writing";
    private static final long REGISTRATION_TIMEOUT_MILLIS = 60_000L;
    private static final long POLL_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

    private @Nullable ModContext context;

    /**
     * Creates a {@link BeaconLinkMod} instance.
     */
    public BeaconLinkMod() {
        this.context = null;
    }

    /**
     * Opens and validates what the mod needs, such as external data and its version.
     *
     * @param context the settings, storage, logger and paths of the run
     */
    @Override
    public void initialize(@NotNull ModContext context) {
        this.context = context;
    }

    /**
     * Registers what the mod adds to the engine. The registration is closed when this method returns.
     *
     * @param registration receives the mod's registrations
     * @throws ModException if the mod was not initialized
     */
    @Override
    public void register(@NotNull ModRegistration registration) throws ModException {
        ModContext initialized = this.context;

        if (initialized == null) {
            throw ModException.create("The test mod was not initialized.");
        }

        registration.registerHeadlessRunner(launch -> this.openSession(initialized, launch));
    }

    private void openSession(@NotNull ModContext initialized, @NotNull ModLaunch launch) throws ModException {
        BeaconLink beaconLink = launch.getBeaconLink();

        if (beaconLink == null) {
            throw ModException.create("The run offers no Beacon.Link.");
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRATION_TIMEOUT_MILLIS);

        try (BeaconLinkSession session = beaconLink.open()) {
            InetSocketAddress observed = session.getObservedAddress();

            while (observed == null) {
                session.poll();

                LinkFailure failure = session.getFailure();

                if (failure != null) {
                    throw ModException.create("Beacon.Link is not available: {0} ({1})", failure, failure.getDescription());
                }

                if (System.nanoTime() > deadline) {
                    throw ModException.create("No Beacon.Link node registered the session in {0} ms.", REGISTRATION_TIMEOUT_MILLIS);
                }

                LockSupport.parkNanos(POLL_PAUSE_NANOS);
                observed = session.getObservedAddress();
            }

            LinkParticipant participant = session.getParticipant();

            Path runFile = initialized.getStorage().resolve(RUN_FILE);
            Path writtenFile = runFile.resolveSibling(RUN_FILE + WRITING_SUFFIX);

            Files.writeString(writtenFile, REGISTERED + ' ' + observed + ' ' + HexFormat.of().formatHex(participant.getPlayer().getKey()) + ' ' +
                                           Long.toHexString(participant.getConnection()), StandardCharsets.UTF_8);
            Files.move(writtenFile, runFile, StandardCopyOption.ATOMIC_MOVE);

            Path release = findHoldFile(initialized.getArguments());
            long holdDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRATION_TIMEOUT_MILLIS);

            while (release != null && !Files.exists(release)) {
                session.poll();

                if (System.nanoTime() > holdDeadline) {
                    throw ModException.create("The session was held longer than {0} ms.", REGISTRATION_TIMEOUT_MILLIS);
                }

                LockSupport.parkNanos(POLL_PAUSE_NANOS);
            }
        } catch (IOException exception) {
            throw ModException.create(exception, "The Beacon.Link session failed: {0}", exception.getMessage());
        }
    }

    private static @Nullable Path findHoldFile(@NotNull List<@NotNull String> arguments) {
        int index = arguments.indexOf(HOLD_ARGUMENT);

        return index < 0 || index + 1 >= arguments.size() ? null : Path.of(arguments.get(index + 1));
    }

    /**
     * Releases what {@link #initialize} opened, in reverse order.
     */
    @Override
    public void close() {
        this.context = null;
    }
}
