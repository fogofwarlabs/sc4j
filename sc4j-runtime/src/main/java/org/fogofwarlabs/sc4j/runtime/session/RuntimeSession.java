package org.fogofwarlabs.sc4j.runtime.session;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.log.Logging;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountLock;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.engine.client.app.FirstSceneFactory;
import org.fogofwarlabs.sc4j.engine.client.app.WindowClient;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.mod.*;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModContentDigestUtils;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.mod.loading.LoadedMod;
import org.fogofwarlabs.sc4j.engine.mod.loading.ModLoader;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormatFactory;
import org.fogofwarlabs.sc4j.engine.save.SaveFormat;
import org.fogofwarlabs.sc4j.engine.save.SaveFormatFactory;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModArchive;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValueStore;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValues;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesException;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesFile;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.runtime.cli.ExitCode;
import org.fogofwarlabs.sc4j.runtime.cli.RuntimeCommandLine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runs one mod from its jar, in the runtime sequence:
 * <ol>
 * <li>open the log, check the command line and paths, read the account folder the command line names ({@code --account}) and start using
 * it, which keeps it from being deleted while the run lasts but never from other runs, and read the seeds it names ({@code --seeds},
 * {@code --seed});</li>
 * <li>read and validate the manifest, and check the engine version;</li>
 * <li>read the saved settings (the mod's own and the shared ones), fill an empty directory setting that declares {@code detect} with the
 * directory the computer records (which is then saved), and validate them against the manifest's schema;</li>
 * <li>create the mod's class loader;</li>
 * <li>create the {@code entry} class;</li>
 * <li>{@link Mod#initialize} with the settings, storage, logger and install folder;</li>
 * <li>{@link Mod#register};</li>
 * <li>close the registration and compute the mod identity;</li>
 * <li>create the mod's replay and save formats with the identity, find the format of a {@code --replay} file among them, create the run's
 * Beacon.Link for the identity, the account, the seeds and the peers file ({@code --peers}) when the command line names a seed source (it
 * opens nothing until the mod opens a session; without an account it plays as a guest), open the window and the devices and the mod's
 * first scene, passing {@code --replay} (with its format), {@code --join} and the Beacon.Link on in the launch request;</li>
 * <li>run the main loop ({@link WindowClient});</li>
 * <li>shut down in the reverse order of what was opened.</li>
 * </ol>
 * A {@code --headless} run hands the launch request to the mod's {@link HeadlessRunner} instead of steps 9 and 10. A mod with its own
 * application loop registers a {@link ModApplication}; steps 9 and 10 then run it, and it opens its own window and devices. A failure ends
 * the sequence with its {@link ExitCode}: command line and settings errors with {@link ExitCode#CONFIGURATION_ERROR}, a mod that cannot be
 * loaded, validated or that refuses the run with {@link ExitCode#MOD_FAILURE}, and a failure while the application runs with
 * {@link ExitCode#RUN_FAILURE}. A mod's refusal is logged with its message; the stack trace is logged for unexpected exceptions and for the
 * cause a refusal carries.
 */
public final class RuntimeSession {
    private static final @NotNull Logger LOGGER = Logger.of(RuntimeSession.class);

    /**
     * The width of the client window of an engine-only mod, in logical units of its monitor.
     */
    private static final int WINDOW_WIDTH = 1280;
    /**
     * The height of the client window of an engine-only mod, in logical units of its monitor.
     */
    private static final int WINDOW_HEIGHT = 720;

    /**
     * Parses the command line and runs the session it describes; {@code --help} prints the usage.
     *
     * @param arguments the process arguments
     * @return the exit code of the run
     */
    public static @NotNull ExitCode run(@NotNull String @NotNull [] arguments) {
        RuntimeCommandLine commandLine;

        try {
            commandLine = RuntimeCommandLine.parse(arguments);
        } catch (IllegalArgumentException exception) {
            LOGGER.error("COMMAND LINE: {0}", exception.getMessage());
            System.err.print(RuntimeCommandLine.USAGE);

            return ExitCode.CONFIGURATION_ERROR;
        }

        if (commandLine.isHelp()) {
            System.out.print(RuntimeCommandLine.USAGE);

            return ExitCode.SUCCESS;
        }

        return new RuntimeSession(commandLine, RuntimeSession.class.getClassLoader()).run();
    }

    private static void logProblems(@NotNull String subject, @NotNull ValidationResult result) {
        for (ValidationProblem problem : result.getProblems()) {
            LOGGER.error("{0}: {1}", subject, problem.describe());
        }
    }

    private static float readUserScale(@NotNull SettingValues settings) throws SessionFailure {
        if (!settings.hasValue(DisplayScale.SETTING_KEY)) {
            return 1;
        }

        try {
            return DisplayScale.parseUserScale(settings.getChoice(DisplayScale.SETTING_KEY));
        } catch (IllegalArgumentException exception) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The interface scale setting is invalid: {0}",
                                        exception.getMessage());
        }
    }


    private final @NotNull RuntimeCommandLine commandLine;
    private final @NotNull ClassLoader modParent;

    private final @NotNull Deque<IgnoreException.@NotNull Callback> shutdownSteps;

    /**
     * Creates a {@link RuntimeSession} instance.
     *
     * @param commandLine the parsed command line, with a mod jar
     * @param modParent   the parent class loader of the mod, which provides the engine and the runtime's libraries
     */
    public RuntimeSession(@NotNull RuntimeCommandLine commandLine, @NotNull ClassLoader modParent) {
        this.commandLine = commandLine;
        this.modParent   = modParent;

        this.shutdownSteps = new ArrayDeque<>();
    }

    /**
     * Runs the session sequence and shuts down what it opened, in reverse order.
     *
     * @return the exit code of the run
     */
    public @NotNull ExitCode run() {
        ExitCode exitCode;

        try {
            this.runSequence();
            exitCode = ExitCode.SUCCESS;
        } catch (SessionFailure failure) {
            Throwable cause = failure.getCause();

            if (cause != null && failure.getExitCode() != ExitCode.CONFIGURATION_ERROR) {
                LOGGER.exception(LogLevel.ERROR, cause, "RUNTIME FAILURE: {0}", failure.getMessage());
            } else {
                LOGGER.error("RUNTIME FAILURE: {0}", failure.getMessage());
            }

            exitCode = failure.getExitCode();
        } catch (RuntimeException | Error throwable) {
            LOGGER.exception(LogLevel.ERROR, throwable, "RUNTIME FAILURE: unexpected error");
            exitCode = ExitCode.RUN_FAILURE;
        }

        LOGGER.info("RUNTIME END: exit code {0} ({1})", exitCode.getStatus(), exitCode);

        while (!this.shutdownSteps.isEmpty()) {
            IgnoreException.execute(
                    this.shutdownSteps.pop()
            );
        }

        return exitCode;
    }

    private void runSequence() throws SessionFailure {
        Path modJar = this.commandLine.getMod();

        if (modJar == null) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "No mod jar is given (--mod).");
        }

        this.openLog(this.commandLine.getLog(), true);
        LOGGER.info("RUNTIME START: engine {0}, mod {1}", EngineVersion.CURRENT, modJar);

        InstallLayout install = this.resolveInstall();
        ClientSetup beaconLinkSetup = this.readBeaconLinkSetup(this.useAccount());

        if (!Files.isRegularFile(modJar)) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "The mod jar does not exist: {0}", modJar);
        }

        ModArchive archive = this.readArchive(modJar);
        ModManifest manifest = archive.getManifest();

        if (!Logging.isFileEnabled()) {
            this.openLog(install.getLogDirectory(manifest.getId()), false);
        }

        LOGGER.info("MOD: {0} {1} (engine {2}, entry {3})", manifest.getId(), manifest.getVersion(), manifest.getEngine(), manifest.getEntry());

        SettingValues settings = this.readSettings(install, manifest);
        ModStorage storage = this.openStorage(install, manifest.getId());
        LoadedMod loaded = this.loadMod(archive);
        Mod mod = loaded.getMod();

        this.initializeMod(mod, new RuntimeModContext(manifest, new RuntimeModSettings(settings), storage, install.getRoot(),
                                                      this.commandLine.getModArguments(), Logger.of(mod.getClass())));

        RuntimeModRegistration registration = this.registerMod(mod);
        GameDefinition gameDefinition = this.buildGameDefinition(registration);
        ModIdentity identity = this.computeIdentity(archive, registration);

        this.runMod(beaconLinkSetup, registration, identity, gameDefinition, storage, manifest, readUserScale(settings));
    }

    private void openLog(@Nullable Path directory, boolean explicit) throws SessionFailure {
        if (directory == null) {
            return;
        }

        try {
            Logging.enableFile(directory);
            this.shutdownSteps.push(Logging::disableFile);
        } catch (IOException exception) {
            if (explicit) {
                throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The log folder cannot be opened: {0} ({1})", directory,
                                            exception.getMessage());
            }

            LOGGER.warning("LOG FILE DISABLED: {0} ({1})", directory, exception.getMessage());
        }
    }

    /**
     * Reads the account folder the command line names ({@code --account}) and starts using the account for the run, so that it cannot be
     * deleted meanwhile; other runs of the account are welcome.
     *
     * @return the account in use, or {@code null} for a run without an account (a guest)
     */
    private @Nullable Account useAccount() throws SessionFailure {
        Path folder = this.commandLine.getAccount();

        if (folder == null) {
            return null;
        }

        Account account;
        AccountLock use;

        try {
            account = AccountStore.read(folder);
            use     = AccountLock.acquire(account);
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The account folder {0} cannot be used: {1}", folder,
                                        exception.getMessage());
        }

        this.shutdownSteps.push(use::close);
        LOGGER.info("ACCOUNT: {0} ({1})", account, folder);

        return account;
    }

    /**
     * Reads the seeds the command line names, the node list ({@code --seeds}) first, then the nodes named one by one ({@code --seed}).
     *
     * @param account the account in use, or {@code null} for a guest
     * @return the setup of the run's Beacon.Link, or {@code null} if the command line names no seed source, so that the run offers no
     * Beacon.Link
     */
    private @Nullable ClientSetup readBeaconLinkSetup(@Nullable Account account) throws SessionFailure {
        if (!this.commandLine.hasSeedSource()) {
            return null;
        }

        Path seedsFile = this.commandLine.getSeedsFile();
        List<ConfiguredNode> seeds = new ArrayList<>();

        if (seedsFile != null) {
            if (!Files.isRegularFile(seedsFile)) {
                throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "The seed list does not exist: {0}", seedsFile);
            }

            try {
                seeds.addAll(KnownNodes.readSeeds(seedsFile, null));
            } catch (IOException exception) {
                throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The seed list cannot be used: {0}", exception.getMessage());
            }
        }

        seeds.addAll(this.commandLine.getSeeds());
        LOGGER.info("BEACON.LINK: {0} seeds, peers file {1}", seeds.size(), this.commandLine.getPeersFile());

        return ClientSetup.of(account, seeds, this.commandLine.getPeersFile());
    }

    private @NotNull InstallLayout resolveInstall() throws SessionFailure {
        Path configured = this.commandLine.getInstall();
        InstallLayout install = configured != null ? InstallLayout.of(configured)
                                                   : InstallLayout.detect(RuntimeSession.class, InstallLayout.RUNTIME_FOLDER);

        if (install == null) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null,
                                        "The install folder cannot be found from the runtime location; pass --install.");
        }

        if (!Files.isDirectory(install.getRoot())) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "The install folder does not exist: {0}", install.getRoot());
        }

        LOGGER.info("INSTALL: {0}", install.getRoot());

        return install;
    }

    private @NotNull ModArchive readArchive(@NotNull Path modJar) throws SessionFailure {
        ModArchive archive;

        try {
            archive = ModManifestReader.read(modJar);
        } catch (ManifestException exception) {
            logProblems("MANIFEST", exception.getResult());

            throw SessionFailure.create(ExitCode.MOD_FAILURE, null, "The mod jar {0} has an invalid manifest.", modJar);
        }

        ValidationResult engine = archive.getManifest().checkEngine(EngineVersion.CURRENT);

        if (!engine.isValid()) {
            logProblems("MANIFEST", engine);

            throw SessionFailure.create(ExitCode.MOD_FAILURE, null, "The mod {0} needs an unsupported engine version.",
                                        archive.getManifest().getId());
        }

        return archive;
    }

    private @NotNull SettingValues readSettings(@NotNull InstallLayout install, @NotNull ModManifest manifest) throws SessionFailure {
        Path configured = this.commandLine.getSettings();
        SettingValueStore store = new SettingValueStore(manifest.getSettings(),
                                                        new SettingValuesFile(configured != null ? configured
                                                                                                 : install.getSettingsFile(manifest.getId())),
                                                        new SettingValuesFile(install.getSharedSettingsFile()));

        LOGGER.info("SETTINGS: {0}, shared {1}", store.getModFile().getPath(), store.getSharedFile().getPath());

        try {
            return SettingValues.resolve(manifest.getSettings(), store.resolve(InstallationRecords.system()));
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The settings files cannot be read or written: {0}",
                                        exception.getMessage());
        } catch (SettingValuesException exception) {
            logProblems("SETTING", exception.getResult());

            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "The saved settings of {0} are missing or invalid: {1}", manifest.getId(),
                                        store.getModFile().getPath());
        }
    }

    private @NotNull ModStorage openStorage(@NotNull InstallLayout install, @NotNull String modId) throws SessionFailure {
        Path configured = this.commandLine.getStorage();
        Path root = configured != null ? configured : install.getStorageDirectory(modId);

        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The storage folder cannot be created: {0} ({1})", root,
                                        exception.getMessage());
        }

        LOGGER.info("STORAGE: {0}", root);

        return new DirectoryModStorage(root);
    }

    private @NotNull LoadedMod loadMod(@NotNull ModArchive archive) throws SessionFailure {
        LoadedMod loaded;

        try {
            loaded = new ModLoader(this.modParent).load(archive);
        } catch (ModException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception.getCause(), "The mod cannot be loaded: {0}", exception.getMessage());
        }

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();

        this.shutdownSteps.push(loaded::close);
        thread.setContextClassLoader(loaded.getClassLoader());
        this.shutdownSteps.push(() -> thread.setContextClassLoader(previous));

        return loaded;
    }

    private void initializeMod(@NotNull Mod mod, @NotNull RuntimeModContext context) throws SessionFailure {
        try {
            mod.initialize(context);
        } catch (ModException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception.getCause(), "The mod refused the run: {0}", exception.getMessage());
        } catch (RuntimeException | LinkageError exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception, "The mod failed to initialize: {0}", exception.toString());
        }

        this.shutdownSteps.push(mod::close);
    }

    private @NotNull RuntimeModRegistration registerMod(@NotNull Mod mod) throws SessionFailure {
        RuntimeModRegistration registration = new RuntimeModRegistration();

        try {
            mod.register(registration);
        } catch (ModException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception.getCause(), "The mod refused the run: {0}", exception.getMessage());
        } catch (RuntimeException | LinkageError exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception, "The mod failed to register: {0}", exception.toString());
        } finally {
            registration.close();
        }

        return registration;
    }

    private @NotNull GameDefinition buildGameDefinition(@NotNull RuntimeModRegistration registration) throws SessionFailure {
        try {
            return registration.buildGameDefinition();
        } catch (IllegalStateException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception, "The mod registered an invalid simulation: {0}", exception.getMessage());
        }
    }

    private @NotNull ModIdentity computeIdentity(@NotNull ModArchive archive, @NotNull RuntimeModRegistration registration) throws SessionFailure {
        ModIdentity identity;

        try {
            identity = ModIdentity.create(EngineVersion.CURRENT, archive.getManifest(), ModContentDigestUtils.compute(archive.getPath()),
                                          registration.getIdentityData());
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception, "The mod jar cannot be read for its identity: {0}", exception.getMessage());
        }

        LOGGER.info("IDENTITY: {0}", identity.describe());

        return identity;
    }

    private void runMod(@Nullable ClientSetup beaconLinkSetup,
                        @NotNull RuntimeModRegistration registration, @NotNull ModIdentity identity,
                        @NotNull GameDefinition gameDefinition, @NotNull ModStorage storage, @NotNull ModManifest manifest,
                        float userScale) throws SessionFailure {
        ModApplication application = registration.getApplication();
        FirstSceneFactory firstScene = registration.getFirstScene();
        HeadlessRunner headlessRunner = registration.getHeadlessRunner();
        boolean headless = this.commandLine.isHeadless();

        if (application == null && firstScene == null && headlessRunner == null) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, null, "The mod registered no first scene, headless runner or application.");
        }

        if (headless && (application != null ? !application.isHeadlessCapable() : headlessRunner == null)) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, null, "The mod {0} cannot run headless (--headless).", identity.getModId());
        }

        if (!headless && application == null && firstScene == null) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, null, "The mod {0} has no client; run it with --headless.", identity.getModId());
        }

        List<ReplayFormat> replayFormats = new ArrayList<>();
        List<SaveFormat> saveFormats = new ArrayList<>();

        try {
            for (ReplayFormatFactory factory : registration.getReplayFormats()) {
                replayFormats.add(factory.createReplayFormat(identity));
            }

            for (SaveFormatFactory factory : registration.getSaveFormats()) {
                saveFormats.add(factory.createSaveFormat(identity));
            }
        } catch (RuntimeException | LinkageError exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception, "The mod failed to create its formats: {0}", exception.toString());
        }

        Path replay = this.commandLine.getReplay();
        ReplayFormat replayFormat = replay == null ? null : this.findReplayFormat(replay, identity, replayFormats);
        ClientBeaconLink beaconLink = beaconLinkSetup == null ? null
                                                              : ClientBeaconLink.create(beaconLinkSetup, identity, this.commandLine.isPortMapping());

        if (beaconLink != null) {
            this.shutdownSteps.push(beaconLink::close);
        }

        ModLaunch launch = new ModLaunch(headless, replay, replayFormat, this.commandLine.getJoin(), identity, gameDefinition,
                                         registration.getResourceProvider(), replayFormats, saveFormats, beaconLink);

        // Without an application, the checks above leave a headless runner for a headless launch and a first scene otherwise.
        try {
            if (application != null) {
                application.run(launch);
            } else if (headless) {
                headlessRunner.run(launch);
            } else {
                WindowSettings window = WindowSettings.builder(manifest.getName().get(Locale.getDefault().toLanguageTag()), WINDOW_WIDTH, WINDOW_HEIGHT)
                                                      .setScaledToMonitor(true)
                                                      .build();

                WindowClient.run(launch, storage, window, userScale, firstScene);
            }
        } catch (ModException exception) {
            throw SessionFailure.create(ExitCode.MOD_FAILURE, exception.getCause(), "The mod refused the launch: {0}", exception.getMessage());
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.RUN_FAILURE, exception, "The mod failed while running: {0}", exception.toString());
        } catch (RuntimeException | Error exception) {
            throw SessionFailure.create(ExitCode.RUN_FAILURE, exception, "The mod failed while running: {0}", exception.toString());
        }
    }

    private @NotNull ReplayFormat findReplayFormat(@NotNull Path replay, @NotNull ModIdentity identity,
                                                   @NotNull List<@NotNull ReplayFormat> replayFormats) throws SessionFailure {
        byte[] contents;

        try {
            contents = Files.readAllBytes(replay);
        } catch (IOException exception) {
            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, exception, "The replay cannot be read: {0} ({1})", replay,
                                        exception.getMessage());
        }

        ReplayFormat format = ReplayFormat.find(replayFormats, contents);

        if (format == null) {
            List<String> names = new ArrayList<>(replayFormats.size());

            for (ReplayFormat known : replayFormats) {
                names.add(known.getName());
            }

            throw SessionFailure.create(ExitCode.CONFIGURATION_ERROR, null, "The file {0} is no replay of the mod {1}, whose replay formats are {2}.",
                                        replay, identity.getModId(), names);
        }

        LOGGER.info("REPLAY: {0} ({1})", replay, format.getName());

        return format;
    }
}
