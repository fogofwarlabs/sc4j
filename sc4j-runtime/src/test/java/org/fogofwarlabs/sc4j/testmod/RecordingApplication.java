package org.fogofwarlabs.sc4j.testmod;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.mod.ModApplication;
import org.fogofwarlabs.sc4j.engine.mod.ModLaunch;
import org.fogofwarlabs.sc4j.engine.mod.ModStorage;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormat;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * A test application that writes its launch request to {@value #LAUNCH_FILE} in the mod's storage, and fails when asked
 * to join {@value #FAILING_JOIN}.
 */
public final class RecordingApplication implements ModApplication {
    /**
     * The storage file written by {@link #run}.
     */
    public static final @NotNull String LAUNCH_FILE = "launch.txt";

    /**
     * The join address that makes {@link #run} fail.
     */
    public static final @NotNull String FAILING_JOIN = "fail";

    private final @NotNull ModStorage storage;
    private final boolean headlessCapable;
    private final @NotNull List<@NotNull String> arguments;

    /**
     * Creates a {@link RecordingApplication} instance.
     *
     * @param storage         the mod's storage
     * @param headlessCapable whether the application claims it can run headless
     * @param arguments       the mod arguments
     */
    RecordingApplication(@NotNull ModStorage storage, boolean headlessCapable, @NotNull List<@NotNull String> arguments) {
        this.storage         = storage;
        this.headlessCapable = headlessCapable;
        this.arguments       = List.copyOf(arguments);
    }

    /**
     * @return {@code true} if the application can run without a window, audio and input ({@code --headless}), {@code false} otherwise
     */
    @Override
    public boolean isHeadlessCapable() {
        return this.headlessCapable;
    }

    /**
     * Runs the application until the user or the launch request ends it.
     *
     * @param launch the launch request of the command line and the mod's identity
     */
    @Override
    public void run(@NotNull ModLaunch launch) {
        if (FAILING_JOIN.equals(launch.getJoin())) {
            throw Exceptions.newIllegalStateException("The test application fails as asked.");
        }

        ReplayFormat replayFormat = launch.getReplayFormat();
        String text = "headless=" + launch.isHeadless() + "\nreplay=" + launch.getReplay() + "\njoin=" + launch.getJoin() +
                      "\narguments=" + this.arguments + "\nidentity=" + launch.getIdentity().getModId() +
                      "\nunits=" + launch.getGameDefinition().getLimits().getUnits() +
                      "\nreplayFormat=" + (replayFormat == null ? null : replayFormat.getName()) +
                      "\nsaveFormats=" + launch.getSaveFormats().size() + "\n";

        try {
            Files.writeString(this.storage.resolve(LAUNCH_FILE), text);
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }
}
