package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.demo.content.DemoContent;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.demo.net.DemoNetworkSettings;
import org.fogofwarlabs.sc4j.demo.rules.DemoRules;
import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.app.SceneClient;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.SilentAudioDevice;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.game.GameSetupCodec;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.mod.ModLaunch;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.engine.save.EngineSaveFormat;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.runtime.session.DirectoryModStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

/**
 * Runs the demo's client offscreen like the runtime runs it on a window: the demo's content, maps and game definition from the test class
 * loader, a launch request with the engine replay and save formats, a mod storage folder, an image canvas (by default {@value #WIDTH} by
 * {@value #HEIGHT}) and silent audio, the first scene of the demo, and the person's input as engine input events.
 */
public final class DemoClientScenario implements Closeable {
    /**
     * The width of the offscreen frames.
     */
    public static final int WIDTH = 1280;
    /**
     * The height of the offscreen frames.
     */
    public static final int HEIGHT = 720;
    /**
     * The folder of a client's Beacon.Link key and node list in its storage folder.
     */
    public static final @NotNull String BEACON_LINK_FOLDER = "beaconlink";

    private static final @NotNull String MANIFEST = "sc4j-mod.json";
    private static final int DIGEST_LENGTH = 32;
    private static final long FRAME_MILLISECONDS = 16;

    private final @NotNull DemoContent content;
    private final @NotNull DemoMaps maps;
    private final @Nullable ClientBeaconLink beaconLink;
    private final int width;
    private final int height;
    private final @NotNull SceneClient client;
    private final @NotNull MainMenuScene menu;

    private long time;

    /**
     * Creates a {@link DemoClientScenario} instance and starts the demo's first scene, the main menu.
     *
     * @param storage the folder of the mod storage
     * @throws IOException       if the demo's pack, maps or manifest cannot be read
     * @throws ManifestException if the demo's manifest is invalid
     * @throws ModException      if the demo refuses the launch
     */
    public DemoClientScenario(@NotNull Path storage) throws IOException, ManifestException, ModException {
        this(storage, null, null, null, WIDTH, HEIGHT);
    }

    /**
     * Creates a {@link DemoClientScenario} instance whose launch has a Beacon.Link of one node and an account, and starts the demo's first
     * scene, the main menu. The Beacon.Link folder is {@code beaconlink} in the storage folder, which holds the account (its stamp of the test
     * difficulty {@link ClientScenario#STAMP_DIFFICULTY}) and the peers file; the node is the run's seed, and its socket binds the loopback
     * address.
     *
     * @param storage     the folder of the mod storage
     * @param node        the address of the Beacon.Link node, {@code host:port}
     * @param accountName the name of the run's account
     * @throws IOException       if the demo's pack, maps or manifest cannot be read, or the account cannot be written
     * @throws ManifestException if the demo's manifest is invalid
     * @throws ModException      if the demo refuses the launch
     */
    public DemoClientScenario(@NotNull Path storage, @NotNull String node, @NotNull String accountName) throws IOException,
                                                                                                               ManifestException,
                                                                                                               ModException {
        this(storage, node, accountName, null, WIDTH, HEIGHT);
    }

    /**
     * Creates a {@link DemoClientScenario} instance with frames of a given size, and starts the demo's first scene, the main menu.
     *
     * @param storage the folder of the mod storage
     * @param width   the width of the offscreen frames in pixels
     * @param height  the height of the offscreen frames in pixels
     * @throws IOException       if the demo's pack, maps or manifest cannot be read
     * @throws ManifestException if the demo's manifest is invalid
     * @throws ModException      if the demo refuses the launch
     */
    public DemoClientScenario(@NotNull Path storage, int width, int height) throws IOException, ManifestException, ModException {
        this(storage, null, null, null, width, height);
    }

    /**
     * Creates a {@link DemoClientScenario} instance whose launch has a given Beacon.Link, for example one played by hand, with frames of a
     * given size, and starts the demo's first scene, the main menu.
     *
     * @param storage    the folder of the mod storage
     * @param beaconLink the launch's Beacon.Link
     * @param width      the width of the offscreen frames in pixels
     * @param height     the height of the offscreen frames in pixels
     * @throws IOException       if the demo's pack, maps or manifest cannot be read
     * @throws ManifestException if the demo's manifest is invalid
     * @throws ModException      if the demo refuses the launch
     */
    public DemoClientScenario(@NotNull Path storage, @NotNull BeaconLink beaconLink, int width, int height) throws IOException,
                                                                                                                   ManifestException,
                                                                                                                   ModException {
        this(storage, null, null, beaconLink, width, height);
    }

    private DemoClientScenario(@NotNull Path storage, @Nullable String node, @Nullable String accountName, @Nullable BeaconLink givenLink,
                               int width, int height) throws IOException,
                                                             ManifestException,
                                                             ModException {
        ClassLoader loader = DemoClientScenario.class.getClassLoader();
        GameDefinition.Builder builder = new GameDefinition.Builder();
        byte[] manifestBytes;

        try (InputStream input = loader.getResourceAsStream(MANIFEST)) {
            Assertions.assertNotNull(input, "the demo manifest is on the test class path");
            manifestBytes = input.readAllBytes();
        }

        DirectoryModStorage modStorage = new DirectoryModStorage(Files.createDirectories(storage));

        this.content = DemoContent.open(loader);
        this.maps    = DemoMaps.load(loader, this.content, modStorage.getMapFolders());

        new DemoRules(this.content, this.maps).define(builder);

        ModManifest manifest = ModManifestReader.parse(manifestBytes);
        ModIdentity identity = ModIdentity.create(EngineVersion.CURRENT, manifest, new byte[DIGEST_LENGTH], List.of());
        GameSetupCodec setupCodec = new GameSetupCodec(this.content.getFactions(), List.of());
        EngineReplayFormat replayFormat = new EngineReplayFormat(identity, this.content.getOrders().getCommands(), setupCodec);
        ClientBeaconLink link = null;

        if (node != null) {
            Path folder = Files.createDirectories(storage.resolve(BEACON_LINK_FOLDER));
            IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());
            Account account = accountName == null ? null
                                                  : AccountStore.of(folder).create(keyPair, PlayerStamp.solve(keyPair.getPublicKey(), accountName,
                                                                                                              ClientScenario.STAMP_DIFFICULTY),
                                                                                   ClientScenario.STAMP_DIFFICULTY, 0L);

            ClientSetup setup = ClientSetup.of(account, List.of(ConfiguredNode.parse(node, null)), folder.resolve(PeerStore.FILE_NAME));

            link = new ClientBeaconLink(setup, identity, ClientConfiguration.createDefault(),
                                        new InetSocketAddress(DemoNetworkSettings.LOOPBACK, DemoNetworkSettings.ANY_PORT), ProtocolClock.SYSTEM);
        }

        this.beaconLink = link;

        ModLaunch launch = new ModLaunch(false, null, null, null, identity, builder.build(), this.content.getPack(), List.of(replayFormat),
                                         List.of(new EngineSaveFormat(identity, setupCodec)), link != null ? link : givenLink);
        ClientScene[] first = new ClientScene[1];

        this.width  = width;
        this.height = height;
        this.client = new SceneClient(launch, modStorage, new ImageCanvas(width, height), new AudioMixer(new SilentAudioDevice(() -> this.time)));
        this.time   = 0;

        this.client.start(started -> first[0] = MainMenuScene.createFirstScene(started, this.content, this.maps));
        this.menu = (MainMenuScene) first[0];
    }

    /**
     * @return the client
     */
    public @NotNull SceneClient getClient() {
        return this.client;
    }

    /**
     * @return the main menu, the first scene
     */
    public @NotNull MainMenuScene getMenu() {
        return this.menu;
    }

    /**
     * @return the current scene
     */
    public @NotNull ClientScene getScene() {
        return this.client.getScenes().requireScene();
    }

    /**
     * Runs one drawn frame {@value #FRAME_MILLISECONDS} milliseconds after the last.
     *
     * @throws IOException if a scene cannot draw
     */
    public void runFrame() throws IOException {
        this.time += FRAME_MILLISECONDS;
        this.client.runFrame(this.time, this.width, this.height);
    }

    /**
     * Runs a client update without drawing: applies the requested scene and updates the scenes after the given time.
     *
     * @param milliseconds the client time since the last frame or update
     */
    public void runUpdate(long milliseconds) {
        this.time += milliseconds;
        this.client.getScenes().applyPendingScene(this.time);
        this.client.getScenes().update(this.time);
    }

    /**
     * Presses and releases a key.
     *
     * @param key the key
     */
    public void press(@NotNull Key key) {
        this.client.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, key, 0));
        this.client.handleInput(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, key, 0));
    }

    /**
     * Types a text, one typed character after another.
     *
     * @param text the text
     */
    public void type(@NotNull String text) {
        text.codePoints().forEach(codePoint -> this.client.handleInput(InputEvent.createCharacterTyped(codePoint)));
    }

    /**
     * Moves the pointer to a framebuffer position and presses and releases a button there.
     *
     * @param button the button
     * @param x      the framebuffer column
     * @param y      the framebuffer row
     */
    public void click(@NotNull PointerButton button, float x, float y) {
        this.client.handleInput(InputEvent.createPointerMoved(x, y));
        this.client.handleInput(InputEvent.createButtonPressed(button, 0, x, y, false));
        this.client.handleInput(InputEvent.createButtonReleased(button, 0, x, y));
    }

    /**
     * Closes the client, its scenes and the demo's pack.
     *
     * @throws IOException if the pack cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.client.close();

        if (this.beaconLink != null) {
            this.beaconLink.close();
        }

        this.content.close();
    }
}
