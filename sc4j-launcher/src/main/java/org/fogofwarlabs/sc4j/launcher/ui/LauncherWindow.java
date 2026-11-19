package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountBackup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListFile;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListProblem;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListReading;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.mods.ModIndex;
import org.fogofwarlabs.sc4j.catalog.mods.ModRelease;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.install.ModInstaller;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeCommandLine;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeListener;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeProcess;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeRun;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePinger;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.session.FriendCode;
import org.fogofwarlabs.sc4j.launcher.session.LauncherSessionView;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettings;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettingsFile;
import org.fogofwarlabs.sc4j.launcher.settings.WindowState;
import org.fogofwarlabs.sc4j.launcher.ui.accounts.LoginScreen;
import org.fogofwarlabs.sc4j.launcher.ui.mods.ModArt;
import org.fogofwarlabs.sc4j.launcher.ui.mods.ModReleaseDialogs;
import org.fogofwarlabs.sc4j.launcher.ui.mods.ModsScreen;
import org.fogofwarlabs.sc4j.launcher.ui.options.OptionsScreen;
import org.fogofwarlabs.sc4j.launcher.ui.options.UpdateView;
import org.fogofwarlabs.sc4j.launcher.ui.run.RunScreen;
import org.fogofwarlabs.sc4j.launcher.ui.session.AccountMenu;
import org.fogofwarlabs.sc4j.launcher.ui.session.ChatAlerts;
import org.fogofwarlabs.sc4j.launcher.ui.session.ChatScreen;
import org.fogofwarlabs.sc4j.launcher.ui.session.Conversations;
import org.fogofwarlabs.sc4j.launcher.update.LauncherRelease;
import org.fogofwarlabs.sc4j.launcher.update.LauncherUpdater;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValueStore;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesException;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the launcher window: it reads the launcher settings, the mod list, the node list (the shipped seed list until the user saves a node
 * list) and the accounts of the install, shows the login step and, once logged in, the screens in the shell, saves the settings form and the
 * node list, pings the nodes when asked, starts the runtime with the logged-in account, and records the run. A ping runs on its own thread
 * and opens its socket only for the ping.
 * <p>
 * The launcher starts on the login step, with the most recently used account selected; without any account it opens the New account dialog
 * there. Logging in opens the account's session ({@link LauncherSessionView}), whose chat runs from then on whichever screen is shown, and
 * the Mods screen; the account and its menu are at the right end of the app bar, and the Chat screen shows the session's channels and
 * whispers; Whisper in the menu's Friends opens the friend's conversation there. The Chat entry of the navigation shows a dot while a line or
 * a whisper is unread ({@link Conversations#hasUnread()}), and a line or whisper that arrives in a conversation the user is not looking at
 * plays the chat sound unless the launcher settings mute it ({@link ChatAlerts}). Log out, from that menu, closes the session and returns to
 * the login step. Closing the window asks first: Minimize, the default, minimizes the window and keeps the session and its chat running; Quit
 * closes the session and the launcher; Cancel keeps the window. A running game keeps running after a log out or a quit.
 * <p>
 * While the runtime runs, the window is minimized; it comes back when the runtime ends and shows the run's report. Closing
 * the launcher does not stop a running runtime.
 * <p>
 * Updates come from GitHub releases ({@link #startChecks}): the Update dialog ({@link LauncherUpdateDialog}) of a newer launcher version opens
 * by itself once per run ({@link #isUpdateOffered}), and the version is shown in a notice above every screen, unless the player skipped it,
 * and on the Settings screen, which also checks on demand and then always opens the dialog. The dialog replaces the programs and starts the
 * launcher again. Mods are installed, updated and removed from the Mods screen ({@link ModReleaseDialogs}); a newer release of a mod
 * installed from a release is shown in its details.
 * <p>
 * Sizes are JavaFX logical units, which grow with the display scale. The window opens at its saved bounds when they are on a screen, else
 * centred on the primary screen; its size and smallest size are cut to the free area of that screen, so that it fits at any display scale.
 * Images are decoded for the largest display scale of the screens ({@link ThemeUtils#getImageScale}). When the display changes while the
 * window is open (a monitor is plugged in or out, a screen's scale changes, or the window moves to a screen of another scale), the images are
 * decoded again for the new scale and the window is fitted to the screen it is on, without a restart ({@link #fitDisplay}).
 */
public final class LauncherWindow {
    private static final @NotNull LauncherShell.Section FIRST_SECTION = LauncherShell.Section.CHAT;
    private static final double DEFAULT_WIDTH = 1280;
    private static final double DEFAULT_HEIGHT = 800;
    private static final double MINIMUM_WIDTH = 1100;
    private static final double MINIMUM_HEIGHT = 720;
    private static final double WINDOW_ICON_SIZE = 64;
    /**
     * The height of the strip along the top of a saved window that must be on a screen for the saved bounds to be used: the title bar.
     */
    private static final double TITLE_BAR_HEIGHT = 32;
    private static final double HALF = 2;
    private static final @NotNull String PING_THREAD_NAME = "launcher-node-ping";
    private static final @NotNull String UPDATE_CHECK_THREAD_NAME = "launcher-update-check";
    private static final @NotNull String BACKUP_FILE_PATTERN = "*" + AccountBackup.FILE_EXTENSION;
    private static final @NotNull String KEY_FILE_PATTERN = "*.key";
    private static final @NotNull String ALL_FILES_PATTERN = "*.*";

    /**
     * Passes the events of a runtime process from its output thread to the user interface thread, in order and in
     * batches, so a fast output does not flood the event queue.
     */
    private final class RunEvents implements RuntimeListener {
        private final @NotNull RuntimeRun run;
        private final @NotNull Queue<@NotNull String> lines;
        private final @NotNull AtomicBoolean scheduled;

        private volatile boolean ended;
        private volatile int status;

        /**
         * Creates a {@link RunEvents} instance.
         *
         * @param run the run the events belong to
         */
        private RunEvents(@NotNull RuntimeRun run) {
            this.run       = run;
            this.lines     = new ConcurrentLinkedQueue<>();
            this.scheduled = new AtomicBoolean(false);

            this.ended  = false;
            this.status = 0;
        }

        /**
         * Called when the runtime prints a line on its standard output or standard error.
         *
         * @param line the line without its line terminator
         */
        @Override
        public void onOutput(@NotNull String line) {
            this.lines.add(line);
            this.schedule();
        }

        /**
         * Called once when the runtime process has ended, after its last output line.
         *
         * @param status the process exit status
         */
        @Override
        public void onExit(int status) {
            this.status = status;
            this.ended  = true;
            this.schedule();
        }

        private void schedule() {
            if (!this.scheduled.getAndSet(true)) {
                Platform.runLater(this::deliver);
            }
        }

        private void deliver() {
            this.scheduled.set(false);

            String line = this.lines.poll();

            while (line != null) {
                LauncherWindow.this.showOutput(this.run, line);
                line = this.lines.poll();
            }

            if (this.ended && this.lines.isEmpty() && this.run.isRunning()) {
                LauncherWindow.this.finishRun(this.run, this.status);
            }
        }
    }


    /**
     * Finds the screen whose free area (the visual bounds) the title bar of a saved window touches.
     *
     * @param saved   the saved window state
     * @param screens the visual bounds of the screens, in JavaFX screen coordinates
     * @return the visual bounds of the first such screen, or {@code null} when the saved window is on no screen
     */
    static @Nullable Rectangle2D findScreen(@NotNull WindowState saved, @NotNull List<@NotNull Rectangle2D> screens) {
        for (Rectangle2D screen : screens) {
            if (screen.intersects(saved.getX(), saved.getY(), saved.getWidth(), TITLE_BAR_HEIGHT)) {
                return screen;
            }
        }

        return null;
    }

    /**
     * Places the window on a screen: at the saved bounds, or at the default size centred, cut to the screen's free area and moved inside it.
     *
     * @param saved  the saved window state on this screen, or {@code null} for the default bounds
     * @param screen the free area (visual bounds) of the screen, in JavaFX screen coordinates
     * @return the window bounds
     */
    static @NotNull Rectangle2D placeWindow(@Nullable WindowState saved, @NotNull Rectangle2D screen) {
        double width = Math.min(saved == null ? DEFAULT_WIDTH : Math.max(MINIMUM_WIDTH, saved.getWidth()), screen.getWidth());
        double height = Math.min(saved == null ? DEFAULT_HEIGHT : Math.max(MINIMUM_HEIGHT, saved.getHeight()), screen.getHeight());
        double x = saved == null ? screen.getMinX() + (screen.getWidth() - width) / HALF : saved.getX();
        double y = saved == null ? screen.getMinY() + (screen.getHeight() - height) / HALF : saved.getY();

        return new Rectangle2D(Math.clamp(x, screen.getMinX(), screen.getMaxX() - width), Math.clamp(y, screen.getMinY(), screen.getMaxY() - height),
                               width, height);
    }

    /**
     * Tells whether the launcher opens the Update dialog of a newer version by itself: once per run, while an account is logged in and no
     * game runs, and not for the version the player chose to skip.
     *
     * @param update   the newer version the check found, or {@code null} when the launcher is up to date
     * @param settings the launcher settings, which name the skipped version
     * @param loggedIn whether an account is logged in
     * @param running  whether a game runs
     * @param offered  whether the dialog was opened in this run already
     * @return {@code true} if the dialog opens, {@code false} otherwise
     */
    static boolean isUpdateOffered(@Nullable LauncherRelease update, @NotNull LauncherSettings settings, boolean loggedIn, boolean running,
                                   boolean offered) {
        return update != null && loggedIn && !running && !offered && !settings.isSkipped(update.getVersion());
    }


    private final @NotNull Stage stage;
    private final @NotNull InstallLayout install;
    private final @NotNull String version;
    private final @NotNull Consumer<@NotNull String> showDocument;
    private final @NotNull Consumer<@NotNull Path> openFolder;
    private final @NotNull LauncherNodeRunner nodeRunner;
    private final @NotNull Function<@NotNull Account, @NotNull LauncherSessionView> openSession;
    private final @NotNull Runnable playChatSound;
    private final @NotNull LauncherSettingsFile settingsFile;
    private final @NotNull NodeListFile nodesFile;
    private final @NotNull NodeListFile seedsFile;
    private final @NotNull AccountStore accountStore;
    private final @NotNull LauncherUpdater launcherUpdater;
    private final @NotNull ModInstaller modInstaller;
    private final @NotNull ModIndex modIndex;
    private final @NotNull Scene scene;
    private final @NotNull VBox notices;
    private final @NotNull SimpleBooleanProperty running;

    private double imageScale;

    private @Nullable String settingsProblem;
    private @NotNull LauncherSettings settings;
    private @Nullable NodeListProblem nodesProblem;
    private @NotNull List<@NotNull ConfiguredNode> nodes;
    private @Nullable String catalogProblem;
    private @NotNull ModCatalog catalog;
    private @Nullable Account account;
    private @Nullable LauncherSessionView session;
    private @Nullable Conversations conversations;
    private @Nullable ChatAlerts chatAlerts;

    private @Nullable LauncherRelease launcherUpdate;
    private boolean updateOffered;
    private boolean updateChecking;
    private @Nullable String updateStatus;
    private @NotNull Tone updateStatusTone;
    private @Nullable String updateFailure;
    private @NotNull Map<@NotNull String, @NotNull ModRelease> modUpdates;

    private @NotNull LauncherTexts texts;
    private @NotNull LauncherShell shell;
    private @NotNull ModsScreen modsScreen;
    private @NotNull LoginScreen loginScreen;
    private @Nullable AccountMenu accountMenu;
    private @NotNull LauncherShell.Section section;
    private @Nullable UpdateView updateView;
    private @Nullable RunScreen runScreen;
    private @Nullable ChatScreen chatScreen;
    private @Nullable ModalDialog closeDialog;
    private @Nullable RuntimeRun run;
    private @Nullable String selectedJar;

    /**
     * Creates a {@link LauncherWindow} instance, reads the install's launcher settings, node list, mods and accounts, and lays out the login
     * step in the window's scene.
     *
     * @param stage         the primary stage
     * @param install       the install
     * @param version       the launcher version
     * @param showDocument  opens a web address in the system browser
     * @param openFolder    opens a folder in the system file manager
     * @param nodeRunner    the launcher's Beacon.Link node, shown and set up on the Settings screen
     * @param openSession   opens the session of the account the user logs in to
     * @param playChatSound plays the chat notification sound
     */
    public LauncherWindow(@NotNull Stage stage, @NotNull InstallLayout install, @NotNull String version,
                          @NotNull Consumer<@NotNull String> showDocument, @NotNull Consumer<@NotNull Path> openFolder,
                          @NotNull LauncherNodeRunner nodeRunner, @NotNull Function<@NotNull Account, @NotNull LauncherSessionView> openSession,
                          @NotNull Runnable playChatSound) {
        ReleaseClient releaseClient = new ReleaseClient(ReleaseClient.GITHUB_API);

        this.stage           = stage;
        this.install         = install;
        this.version         = version;
        this.showDocument    = showDocument;
        this.openFolder      = openFolder;
        this.nodeRunner      = nodeRunner;
        this.openSession     = openSession;
        this.playChatSound   = playChatSound;
        this.settingsFile    = new LauncherSettingsFile(install.getLauncherSettingsFile());
        this.nodesFile       = new NodeListFile(install.getNodesFile());
        this.seedsFile       = new NodeListFile(install.getSeedsFile());
        this.accountStore    = AccountStore.of(install.getBeaconLinkDirectory());
        this.launcherUpdater = new LauncherUpdater(install, releaseClient);
        this.modInstaller    = new ModInstaller(install, releaseClient);
        this.modIndex        = new ModIndex(releaseClient, ModIndex.DEFAULT_ADDRESS);
        this.scene           = new Scene(new StackPane(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        this.notices         = ComponentUtils.createColumn(Spacing.M);
        this.running         = new SimpleBooleanProperty(false);

        this.imageScale = ThemeUtils.getImageScale();

        this.settingsProblem = null;
        this.settings        = this.readSettings();
        this.nodesProblem    = null;
        this.nodes           = this.readNodes();
        this.catalogProblem  = null;
        this.catalog         = this.scanCatalog();
        this.account         = null;
        this.session         = null;
        this.conversations   = null;
        this.chatAlerts      = null;

        this.launcherUpdate   = null;
        this.updateOffered    = false;
        this.updateChecking   = false;
        this.updateStatus     = null;
        this.updateStatusTone = Tone.NEUTRAL;
        this.updateFailure    = null;
        this.modUpdates       = new LinkedHashMap<>();

        this.texts       = LauncherTexts.of(UiLanguage.select(this.settings.getLanguage(), Locale.getDefault()));
        this.shell       = this.createShell();
        this.modsScreen  = this.createModsScreen();
        this.loginScreen = this.createLoginScreen();
        this.accountMenu = null;
        this.section     = LauncherShell.Section.MODS;
        this.updateView  = null;
        this.runScreen   = null;
        this.chatScreen  = null;
        this.closeDialog = null;
        this.run         = null;
        this.selectedJar = this.settings.getLastMod();

        this.stage.setScene(this.scene);
        this.stage.setOnCloseRequest(event -> {
            event.consume();
            this.openCloseDialog();
        });
        this.installViews();
    }

    /**
     * Shows the window with the saved window state.
     */
    public void show() {
        WindowState saved = this.settings.getWindow();
        List<Rectangle2D> screens = new ArrayList<>();

        for (Screen screen : Screen.getScreens()) {
            screens.add(screen.getVisualBounds());
        }

        Rectangle2D savedScreen = saved == null ? null : findScreen(saved, screens);
        WindowState placed = savedScreen == null ? null : saved;
        Rectangle2D area = savedScreen != null ? savedScreen : Screen.getPrimary().getVisualBounds();
        Rectangle2D bounds = placeWindow(placed, area);

        this.stage.getIcons().setAll(ThemeUtils.loadImage(ThemeUtils.SC4J_LOGO, WINDOW_ICON_SIZE, this.imageScale));
        this.stage.setMinWidth(Math.min(MINIMUM_WIDTH, area.getWidth()));
        this.stage.setMinHeight(Math.min(MINIMUM_HEIGHT, area.getHeight()));
        this.stage.setX(bounds.getMinX());
        this.stage.setY(bounds.getMinY());
        this.stage.setWidth(bounds.getWidth());
        this.stage.setHeight(bounds.getHeight());

        if (placed != null) {
            this.stage.setMaximized(placed.isMaximized());
        }

        this.stage.show();

        Screen.getScreens().addListener((Observable changed) -> this.onDisplayChanged());
        this.stage.outputScaleXProperty().addListener((property, oldScale, newScale) -> this.onDisplayChanged());
        this.stage.outputScaleYProperty().addListener((property, oldScale, newScale) -> this.onDisplayChanged());
    }

    /**
     * Runs the checks of the launcher's start, once per run: shows why the last update failed, removes what downloads and updates left, and,
     * on threads of their own and quietly on failure, looks for a newer version of the launcher (release builds only,
     * {@link LauncherUpdater#isAutomaticCheckAllowed}) and for newer releases of the mods installed from releases.
     */
    public void startChecks() {
        this.updateFailure = this.launcherUpdater.takeFailure();
        this.launcherUpdater.cleanUp();
        this.updateNotices();

        if (LauncherUpdater.isAutomaticCheckAllowed(ReleaseVersion.parse(this.version))) {
            this.checkLauncherUpdate(this.settings.getUpdateRepository(), false);
        }

        this.checkModUpdates();
    }

    /**
     * Fits the window to a changed display: images are decoded again when the image scale changed, and the window's smallest size and,
     * when the window does not fit, its bounds are cut to the free area of the screen it is on.
     *
     * @param imageScale the screen pixels per logical unit the images are decoded for ({@link ThemeUtils#getImageScale})
     * @param screen     the free area (visual bounds) of the screen the window is on, in JavaFX screen coordinates
     */
    void fitDisplay(double imageScale, @NotNull Rectangle2D screen) {
        this.stage.setMinWidth(Math.min(MINIMUM_WIDTH, screen.getWidth()));
        this.stage.setMinHeight(Math.min(MINIMUM_HEIGHT, screen.getHeight()));

        if (!this.stage.isMaximized() && (this.stage.getWidth() > screen.getWidth() || this.stage.getHeight() > screen.getHeight())) {
            WindowState current = new WindowState((int) this.stage.getX(), (int) this.stage.getY(),
                                                  (int) this.stage.getWidth(), (int) this.stage.getHeight(),
                                                  false);
            Rectangle2D bounds = placeWindow(current, screen);

            this.stage.setX(bounds.getMinX());
            this.stage.setY(bounds.getMinY());
            this.stage.setWidth(bounds.getWidth());
            this.stage.setHeight(bounds.getHeight());
        }

        if (imageScale != this.imageScale) {
            this.imageScale = imageScale;
            this.stage.getIcons().setAll(ThemeUtils.loadImage(ThemeUtils.SC4J_LOGO, WINDOW_ICON_SIZE, imageScale));
            this.rebuildViews();
        }
    }

    private void onDisplayChanged() {
        double centerX = this.stage.getX() + this.stage.getWidth() / HALF;
        double centerY = this.stage.getY() + this.stage.getHeight() / HALF;
        List<Screen> screens = Screen.getScreensForRectangle(centerX, centerY, 1, 1);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();

        this.fitDisplay(ThemeUtils.getImageScale(), screen.getVisualBounds());
    }

    private @NotNull LauncherSettings readSettings() {
        try {
            return this.settingsFile.read();
        } catch (IOException exception) {
            this.settingsProblem = exception.getMessage();

            return LauncherSettings.DEFAULT;
        }
    }

    private @NotNull List<@NotNull ConfiguredNode> readNodes() {
        NodeListReading reading = this.nodesFile.selectExistingOr(this.seedsFile).read();

        this.nodesProblem = reading.getProblem();

        return reading.getNodes();
    }

    private @NotNull ModCatalog scanCatalog() {
        try {
            this.catalogProblem = null;

            return ModCatalog.scan(this.install, InstallationRecords.system());
        } catch (IOException exception) {
            this.catalogProblem = exception.getMessage();

            return ModCatalog.createEmpty(this.install.getModsDirectory());
        }
    }

    private @NotNull List<@NotNull Account> listAccounts() {
        try {
            return this.accountStore.list();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private @NotNull LauncherShell createShell() {
        return new LauncherShell(this.texts, this.install, this.imageScale, this::showSection, this::showAbout, this::openAccountMenu);
    }

    private @NotNull LoginScreen createLoginScreen() {
        return new LoginScreen(this.accountStore, this.texts, new LoginScreen.Actions() {
            /**
             * Called when the user logs in to an account.
             *
             * @param account the account, with its use recorded
             */
            @Override
            public void onLogIn(@NotNull Account account) {
                LauncherWindow.this.logIn(account);
            }

            /**
             * Asks the user for the file to import.
             *
             * @return the chosen file, or {@code null} if the user cancelled
             */
            @Override
            public @Nullable Path chooseImportFile() {
                FileChooser chooser = LauncherWindow.this.createAccountFileChooser(LauncherWindow.this.texts.get("accounts.import.title"));
                String allFiles = LauncherWindow.this.texts.get("accounts.file.all");

                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(allFiles, ALL_FILES_PATTERN));

                File file = chooser.showOpenDialog(LauncherWindow.this.stage);

                return file == null ? null : file.toPath();
            }

            /**
             * Asks the user where to save a backup.
             *
             * @param fileName the suggested file name
             * @return the chosen file, or {@code null} if the user cancelled
             */
            @Override
            public @Nullable Path chooseExportFile(@NotNull String fileName) {
                FileChooser chooser = LauncherWindow.this.createAccountFileChooser(LauncherWindow.this.texts.get("accounts.export.title"));

                chooser.setInitialFileName(fileName);

                File file = chooser.showSaveDialog(LauncherWindow.this.stage);

                return file == null ? null : file.toPath();
            }
        });
    }

    private @NotNull FileChooser createAccountFileChooser(@NotNull String title) {
        FileChooser chooser = new FileChooser();

        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(this.texts.get("accounts.file.backup"),
                                                                          BACKUP_FILE_PATTERN, KEY_FILE_PATTERN));

        return chooser;
    }

    /**
     * Logs in: opens the account's session, shows the account and its menu in the app bar and opens the Mods screen with the mod selected
     * last.
     */
    private void logIn(@NotNull Account loggedIn) {
        LauncherSessionView opened = this.openSession.apply(loggedIn);
        Conversations openedConversations = new Conversations(opened);

        this.account       = loggedIn;
        this.session       = opened;
        this.conversations = openedConversations;
        this.chatAlerts    = new ChatAlerts(opened);
        this.accountMenu   = this.createAccountMenu(loggedIn, opened);
        this.section       = FIRST_SECTION;

        openedConversations.setOnRead(this::updateChatDot);
        opened.setOnChanged(this::onSessionChanged);
        opened.openChat(this.texts.getLocale());
        this.showSection(FIRST_SECTION);
        this.selectLastMod();
        this.offerLauncherUpdate();
    }

    /**
     * Shows the session's new views: in the account menu, on the Chat screen and in the dot of the Chat entry, and plays the chat sound for
     * what arrived where the user is not looking ({@link ChatAlerts}).
     */
    private void onSessionChanged() {
        AccountMenu shownMenu = this.accountMenu;
        ChatScreen shownChat = this.chatScreen;
        ChatAlerts alerts = this.chatAlerts;
        boolean looking = shownChat != null && this.section == LauncherShell.Section.CHAT && this.stage.isFocused();

        if (alerts != null && alerts.decide(looking ? shownChat.getShownChannel() : null, looking ? shownChat.getShownPlayer() : null,
                                            this.settings.isChatSoundMuted(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))) {
            this.playChatSound.run();
        }

        if (shownMenu != null) {
            shownMenu.update();
        }

        if (shownChat != null) {
            shownChat.update();
        }

        this.updateChatDot();
    }

    /**
     * Shows the dot of the Chat entry while a line or a whisper is unread.
     */
    private void updateChatDot() {
        Conversations openedConversations = this.conversations;

        this.shell.setNavigationDotShown(LauncherShell.Section.CHAT, openedConversations != null && openedConversations.hasUnread());
    }

    /**
     * Logs out: closes the account's session and returns to the login step.
     */
    private void logOut() {
        this.closeSession();
        this.showLogin();
    }

    private void closeSession() {
        LauncherSessionView closed = this.session;

        this.accountMenu   = null;
        this.chatScreen    = null;
        this.conversations = null;
        this.chatAlerts    = null;
        this.session       = null;
        this.account       = null;

        if (closed != null) {
            closed.setOnChanged(null);
            closed.close();
        }
    }

    private @NotNull AccountMenu createAccountMenu(@NotNull Account loggedIn, @NotNull LauncherSessionView opened) {
        AccountMenu.Actions actions = new AccountMenu.Actions() {
            /**
             * Called when the user logs out.
             */
            @Override
            public void onLogOut() {
                LauncherWindow.this.logOut();
            }

            /**
             * Called when the user copies text, the own friend code.
             *
             * @param text the text for the clipboard
             */
            @Override
            public void onCopy(@NotNull String text) {
                LauncherWindow.this.copy(text);
            }

            /**
             * Called when the user whispers to a friend.
             *
             * @param player     the friend's friend code
             * @param taggedName the friend's name and tag
             */
            @Override
            public void onWhisper(@NotNull FriendCode player, @NotNull String taggedName) {
                LauncherWindow.this.showWhispers(player, taggedName);
            }
        };
        AccountMenu created = new AccountMenu(this.shell, this.texts, loggedIn.getTaggedName(), opened, this::findModName, actions);

        created.update();

        return created;
    }

    /**
     * Finds the display name of a mod of the install by its id, or the id itself when the install has no such mod.
     */
    private @NotNull String findModName(@NotNull String modId) {
        for (ModEntry entry : this.catalog.getEntries()) {
            ModManifest manifest = entry.getManifest();

            if (manifest != null && manifest.getId().equals(modId)) {
                return this.texts.localize(manifest.getName());
            }
        }

        return modId;
    }

    /**
     * Shows a player's whisper conversation on the Chat screen, which it shows first if another screen is shown.
     */
    private void showWhispers(@NotNull FriendCode player, @NotNull String taggedName) {
        if (this.section != LauncherShell.Section.CHAT || this.chatScreen == null) {
            this.showSection(LauncherShell.Section.CHAT);
        }

        ChatScreen shown = this.chatScreen;

        if (shown != null) {
            shown.showWhispers(player, taggedName);
        }
    }

    private void openAccountMenu() {
        AccountMenu shown = this.accountMenu;

        if (shown != null) {
            shown.open();
        }
    }

    private @NotNull ModsScreen createModsScreen() {
        ModsScreen created = new ModsScreen(this.install, this.catalog, this.texts, this.imageScale, this.running, new ModsScreen.Actions() {
            /**
             * Called when the user selects a mod.
             *
             * @param entry the mod
             */
            @Override
            public void onSelect(@NotNull ModEntry entry) {
                LauncherWindow.this.selectedJar = entry.getJarName();
            }

            /**
             * Called when the user opens the mods folder.
             */
            @Override
            public void onOpenModsFolder() {
                LauncherWindow.this.openFolder.accept(LauncherWindow.this.catalog.getModsDirectory());
            }

            /**
             * Called when the user asks to scan the mods folder again.
             */
            @Override
            public void onRefresh() {
                LauncherWindow.this.refreshCatalog();
                LauncherWindow.this.checkModUpdates();
            }

            /**
             * Called when the user installs a mod.
             */
            @Override
            public void onInstall() {
                LauncherWindow.this.createModReleaseDialogs().openInstall();
            }

            /**
             * Called when the user updates the mod to its newer release.
             *
             * @param entry the mod
             */
            @Override
            public void onUpdate(@NotNull ModEntry entry) {
                ModRelease release = LauncherWindow.this.modUpdates.get(entry.getJarName());

                if (release != null) {
                    LauncherWindow.this.createModReleaseDialogs().openUpdate(entry, release);
                }
            }

            /**
             * Called when the user removes the mod.
             *
             * @param entry the mod
             */
            @Override
            public void onRemove(@NotNull ModEntry entry) {
                LauncherWindow.this.createModReleaseDialogs().openRemove(entry);
            }

            /**
             * Called when the user presses Run with a valid form.
             *
             * @param entry the mod
             * @param form  the mod's settings form, to be saved before the start
             */
            @Override
            public void onRun(@NotNull ModEntry entry, @NotNull SettingsForm form) {
                LauncherWindow.this.startRun(entry, form);
            }

            /**
             * Called when the user copies text, such as the problems of a mod.
             *
             * @param text the text for the clipboard
             */
            @Override
            public void onCopy(@NotNull String text) {
                LauncherWindow.this.copy(text);
            }

            /**
             * Called when the user opens the mod's website.
             *
             * @param address the website address of the manifest
             */
            @Override
            public void onOpenWebsite(@NotNull String address) {
                LauncherWindow.this.showDocument.accept(address);
            }
        });

        created.setUpdates(this.getModUpdateTags());

        return created;
    }

    private @NotNull Map<@NotNull String, @NotNull String> getModUpdateTags() {
        Map<String, String> tags = new LinkedHashMap<>();

        for (Map.Entry<String, ModRelease> update : this.modUpdates.entrySet()) {
            tags.put(update.getKey(), update.getValue().getTag());
        }

        return tags;
    }

    private @NotNull ModReleaseDialogs createModReleaseDialogs() {
        return new ModReleaseDialogs(this.shell, this.texts, this.modInstaller, this.modIndex, () -> this.catalog, this::onModChanged);
    }

    /**
     * Lists the mods again after a mod was installed, updated or removed, with that mod selected while it is listed.
     */
    private void onModChanged(@NotNull String jarName) {
        this.modUpdates.remove(jarName);
        this.selectedJar = jarName;
        this.refreshCatalog();
    }

    /**
     * Looks for newer releases of the mods installed from releases on a thread of its own and shows them in the mods' details; a repository
     * that cannot be read, or a record that cannot be read, shows none.
     */
    private void checkModUpdates() {
        ModCatalog checked = this.catalog;
        Thread thread = new Thread(() -> {
            Map<String, ModRelease> found;

            try {
                found = this.modInstaller.findUpdates(checked);
            } catch (IOException exception) {
                return;
            }

            Platform.runLater(() -> {
                this.modUpdates = new LinkedHashMap<>(found);
                this.modsScreen.setUpdates(this.getModUpdateTags());
            });
        }, UPDATE_CHECK_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Looks for a newer launcher version on a thread of its own. A check the user asked for saves the update source, shows its result on the
     * Settings screen and opens the Update dialog of a newer version; the check of the start fails quietly and offers a newer version
     * ({@link #isUpdateOffered}).
     */
    private void checkLauncherUpdate(@NotNull String source, boolean asked) {
        ReleaseRepository repository = ReleaseRepository.parse(source);

        if (repository == null) {
            this.updateStatus     = this.texts.format("update.failed", this.texts.describeReleaseProblem(ReleaseProblem.INVALID_REPOSITORY));
            this.updateStatusTone = Tone.DANGER;
            this.showUpdateStatus();

            return;
        }

        if (asked) {
            this.settings = this.settings.withUpdateRepository(repository.toString());
            this.saveSettings();
        }

        this.updateChecking = true;
        this.showUpdateStatus();

        Thread thread = new Thread(() -> {
            try {
                LauncherRelease found = this.launcherUpdater.findUpdate(repository, ReleaseVersion.parse(this.version));

                Platform.runLater(() -> this.finishLauncherUpdateCheck(found, null, asked));
            } catch (ReleaseException exception) {
                Platform.runLater(() -> this.finishLauncherUpdateCheck(null, exception, asked));
            }
        }, UPDATE_CHECK_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    private void finishLauncherUpdateCheck(@Nullable LauncherRelease found, @Nullable ReleaseException failure, boolean asked) {
        this.updateChecking = false;

        if (failure == null) {
            this.launcherUpdate   = found;
            this.updateStatus     = found == null && asked ? this.texts.get("update.current") : null;
            this.updateStatusTone = Tone.SUCCESS;
        } else if (asked) {
            this.updateStatus     = this.texts.format("update.failed", this.texts.describeReleaseProblem(failure.getProblem()));
            this.updateStatusTone = Tone.DANGER;
        }

        this.showUpdateStatus();
        this.updateNotices();

        if (asked && found != null) {
            this.openLauncherUpdate();
        } else {
            this.offerLauncherUpdate();
        }
    }

    private void offerLauncherUpdate() {
        if (isUpdateOffered(this.launcherUpdate, this.settings, this.account != null, this.running.get(), this.updateOffered)) {
            this.openLauncherUpdate();
        }
    }

    private void showUpdateStatus() {
        UpdateView shown = this.updateView;
        LauncherRelease available = this.launcherUpdate;

        if (shown == null) {
            return;
        }

        if (this.updateChecking) {
            shown.showChecking();
        } else if (available != null) {
            shown.showStatus(this.texts.format("update.available", available.getVersion()), Tone.ACCENT, true);
        } else {
            shown.showStatus(this.updateStatus, this.updateStatusTone, false);
        }
    }

    private void openLauncherUpdate() {
        LauncherRelease available = this.launcherUpdate;

        if (available == null) {
            return;
        }

        this.updateOffered = true;
        new LauncherUpdateDialog(this.shell, this.texts, this.imageScale, this.launcherUpdater, available, this.version, this.running,
                                 () -> this.skipLauncherUpdate(available), this::closeForUpdate).open();
    }

    private void skipLauncherUpdate(@NotNull LauncherRelease skipped) {
        this.settings = this.settings.withSkippedVersion(skipped.getVersion());
        this.saveSettings();
        this.updateNotices();
    }

    /**
     * Closes the launcher for the updater: quits ({@link #quit}) and ends the application.
     */
    private void closeForUpdate() {
        this.quit();
        Platform.exit();
    }

    /**
     * Quits the launcher: saves the window state, closes the session and closes the window.
     */
    private void quit() {
        this.saveWindowState();
        this.closeSession();
        this.stage.close();
    }

    /**
     * Opens the dialog of a close request over the window, brought back from the task bar: Minimize, the default action, minimizes the
     * window and keeps the session and its chat running; Quit quits ({@link #quit}); Cancel or Escape keeps the window. A request while the
     * dialog is shown does nothing.
     */
    private void openCloseDialog() {
        ModalDialog shown = this.closeDialog;

        if (shown != null && shown.getRoot().getScene() != null) {
            return;
        }

        ModalDialog dialog = new ModalDialog(this.texts.get("close.title"));
        Button minimize = ComponentUtils.createButton(this.texts.get("close.minimize"), ButtonRole.PRIMARY);
        Button quit = ComponentUtils.createButton(this.texts.get("close.quit"), ButtonRole.TEXT_ONLY);
        Button cancel = ComponentUtils.createButton(this.texts.get("close.cancel"), ButtonRole.SECONDARY);

        dialog.addContent(ComponentUtils.createParagraph(this.texts.get("close.text")));
        dialog.setButtons(List.of(quit), cancel, minimize);
        minimize.setOnAction(event -> {
            this.shell.closeDialog(dialog);
            this.stage.setIconified(true);
        });
        quit.setOnAction(event -> {
            this.shell.closeDialog(dialog);
            this.quit();
        });
        cancel.setOnAction(event -> this.shell.closeDialog(dialog));

        this.closeDialog = dialog;
        this.stage.setIconified(false);
        this.shell.showDialog(dialog);
        minimize.requestFocus();
    }

    private void rebuildViews() {
        Account loggedIn = this.account;
        LauncherSessionView opened = this.session;

        this.texts       = LauncherTexts.of(UiLanguage.select(this.settings.getLanguage(), Locale.getDefault()));
        this.shell       = this.createShell();
        this.modsScreen  = this.createModsScreen();
        this.loginScreen = this.createLoginScreen();
        this.accountMenu = loggedIn == null || opened == null ? null : this.createAccountMenu(loggedIn, opened);

        this.installViews();
    }

    /**
     * Puts the shell into the scene and shows the current view: the login step, or the current screen with the mod selected last.
     */
    private void installViews() {
        this.scene.setRoot(this.shell.getRoot());
        ThemeUtils.apply(this.scene, this.texts.getLanguage());
        this.stage.setTitle(this.texts.get("app.title"));
        this.updateStatus();

        if (this.account == null) {
            this.showLogin();
        } else {
            this.showSection(this.section);
            this.selectLastMod();
        }
    }

    private void updateStatus() {
        int count = this.catalog.getEntries().size();
        String catalogText = this.texts.formatCount("status.mods", count);
        int errors = this.catalog.countStatus(ModStatus.ERROR);

        if (errors > 0) {
            catalogText = catalogText + ", " + this.texts.formatCount("status.errors", errors);
        }

        this.shell.setStatus(catalogText, this.texts.formatCount("status.nodes", this.nodes.size()));
    }

    /**
     * Shows the login step; without any account it opens the New account dialog there.
     */
    private void showLogin() {
        this.runScreen = null;
        this.loginScreen.refresh();
        this.shell.showLogin(this.addNotices(this.loginScreen.getRoot()));

        if (this.listAccounts().isEmpty()) {
            this.loginScreen.openNewAccount();
        }
    }

    private void showSection(@NotNull LauncherShell.Section shown) {
        Node content = switch (shown) {
            case MODS -> this.modsScreen.getRoot();
            case RUN -> this.createRunScreen().getRoot();
            case CHAT -> this.createChatScreen().getRoot();
            case SETTINGS -> this.createOptionsScreen().getRoot();
        };

        this.section = shown;

        if (shown != LauncherShell.Section.RUN) {
            this.runScreen = null;
        }

        if (shown != LauncherShell.Section.CHAT) {
            this.chatScreen = null;
        }

        if (shown != LauncherShell.Section.SETTINGS) {
            this.updateView = null;
        }

        this.shell.show(shown, this.addNotices(content));
        this.updateChatDot();
    }

    /**
     * Puts the launcher's notices above a view's content ({@link #updateNotices}).
     */
    private @NotNull Node addNotices(@NotNull Node content) {
        VBox column = ComponentUtils.createColumn(Spacing.M, this.notices, content);

        VBox.setVgrow(content, Priority.ALWAYS);
        this.updateNotices();

        return column;
    }

    /**
     * Shows the launcher's notices: a settings file problem, why the last update failed and, once logged in, a newer launcher version that
     * the player did not skip with its Update button and, on the Mods screen, a mods folder problem.
     */
    private void updateNotices() {
        List<Node> shown = new ArrayList<>();
        boolean loggedIn = this.account != null;
        LauncherRelease available = this.launcherUpdate;

        if (this.settingsProblem != null) {
            shown.add(ComponentUtils.createNotice(Tone.WARNING, this.texts.format("notice.settings", this.settingsProblem)));
        }

        if (this.updateFailure != null) {
            shown.add(ComponentUtils.createNotice(Tone.DANGER, this.texts.format("notice.update.failed", this.updateFailure)));
        }

        if (available != null && loggedIn && !this.settings.isSkipped(available.getVersion())) {
            Button update = ComponentUtils.createSmallButton(this.texts.get("update.start"), ButtonRole.PRIMARY);

            update.setOnAction(event -> this.openLauncherUpdate());
            shown.add(ComponentUtils.createNotice(Tone.ACCENT, this.texts.format("update.available", available.getVersion()), update));
        }

        if (this.catalogProblem != null && loggedIn && this.section == LauncherShell.Section.MODS) {
            shown.add(ComponentUtils.createNotice(Tone.DANGER, this.texts.format("notice.catalog", this.catalogProblem)));
        }

        this.notices.getChildren().setAll(shown);
        this.notices.setVisible(!shown.isEmpty());
        this.notices.setManaged(!shown.isEmpty());
    }

    private @NotNull RunScreen createRunScreen() {
        RunScreen screen = new RunScreen(this.run, this.texts, new RunScreen.Actions() {
            /**
             * Called when the user opens the run's log folder.
             *
             * @param shown the run
             */
            @Override
            public void onOpenLogFolder(@NotNull RuntimeRun shown) {
                LauncherWindow.this.openFolder.accept(shown.getCommandLine().getLogDirectory());
            }

            /**
             * Called when the user copies the run's report.
             *
             * @param text the report text
             */
            @Override
            public void onCopy(@NotNull String text) {
                LauncherWindow.this.copy(text);
            }

            /**
             * Called when the user stops the running runtime.
             *
             * @param shown the run
             */
            @Override
            public void onStop(@NotNull RuntimeRun shown) {
                RuntimeProcess process = shown.getProcess();

                if (process != null) {
                    process.stop();
                }
            }

            /**
             * Called when the user wants to run the mod again; the launcher shows its settings.
             *
             * @param shown the run
             */
            @Override
            public void onRunAgain(@NotNull RuntimeRun shown) {
                LauncherWindow.this.showSection(LauncherShell.Section.MODS);
            }
        });

        this.runScreen = screen;

        return screen;
    }

    /**
     * Creates the Chat screen of the logged-in account and opens its chat.
     */
    private @NotNull ChatScreen createChatScreen() {
        Account loggedIn = this.account;
        LauncherSessionView opened = this.session;
        Conversations openedConversations = this.conversations;

        if (loggedIn == null || opened == null || openedConversations == null) {
            throw Exceptions.newIllegalStateException("The Chat screen needs a logged-in account.");
        }

        ChatScreen screen = new ChatScreen(this.shell, this.texts, loggedIn.getTaggedName(), opened, openedConversations, this::findModName,
                                           this::copy);

        this.chatScreen = screen;
        screen.update();

        return screen;
    }

    private @NotNull OptionsScreen createOptionsScreen() {
        OptionsScreen.Actions actions = new OptionsScreen.Actions() {
            /**
             * Called when the user chooses another UI language.
             *
             * @param language the chosen language, or {@code null} to follow the operating system
             */
            @Override
            public void onLanguageChanged(@Nullable UiLanguage language) {
                LauncherWindow.this.changeLanguage(language);
            }

            /**
             * Called when the user mutes the chat notification sound or turns it on again.
             *
             * @param muted whether the chat notification sound is muted
             */
            @Override
            public void onChatSoundMutedChanged(boolean muted) {
                LauncherWindow.this.settings = LauncherWindow.this.settings.withChatSoundMuted(muted);
                LauncherWindow.this.saveSettings();
            }

            /**
             * Called when the user changes the node list; the launcher saves it.
             *
             * @param changed the new node list
             * @return the reason the list could not be saved, or {@code null} when it was saved
             */
            @Override
            public @Nullable String onNodesChanged(@NotNull List<@NotNull ConfiguredNode> changed) {
                return LauncherWindow.this.saveNodes(changed);
            }

            /**
             * Called when the user pings the listed nodes; the launcher pings them away from the user interface thread and delivers
             * the results on it.
             *
             * @param pinged  the nodes to ping
             * @param results receives one result per node, in the order of the nodes, on the user interface thread
             */
            @Override
            public void onPingNodes(@NotNull List<@NotNull ConfiguredNode> pinged,
                                    @NotNull Consumer<@NotNull List<@NotNull NodePingResult>> results) {
                LauncherWindow.this.pingNodes(pinged, results);
            }
        };
        UpdateView shownUpdate = new UpdateView(this.texts, this.version, this.settings.getUpdateRepository(), new UpdateView.Actions() {
            /**
             * Called when the user checks for a newer version.
             *
             * @param source the update source as typed
             */
            @Override
            public void onCheck(@NotNull String source) {
                LauncherWindow.this.checkLauncherUpdate(source, true);
            }

            /**
             * Called when the user updates to the newer version.
             */
            @Override
            public void onUpdate() {
                LauncherWindow.this.openLauncherUpdate();
            }
        });

        this.updateView = shownUpdate;
        this.showUpdateStatus();

        return new OptionsScreen(this.texts, this.settings.getLanguage(), this.settings.isChatSoundMuted(), shownUpdate,
                                 this.nodes, this.nodesProblem, this.nodeRunner, actions);
    }

    private void showAbout() {
        List<ModManifest> mods = new ArrayList<>();

        for (ModEntry entry : this.catalog.getEntries()) {
            ModManifest manifest = entry.getManifest();

            if (manifest != null) {
                mods.add(manifest);
            }
        }

        new AboutDialog(this.shell, this.texts, this.version, this.imageScale, mods, this.showDocument).open();
    }

    private void selectLastMod() {
        ModEntry entry = this.selectedJar == null ? null : this.catalog.findByJarName(this.selectedJar);

        if (entry == null && !this.catalog.getEntries().isEmpty()) {
            entry = this.catalog.getEntries().getFirst();
        }

        if (entry != null) {
            this.modsScreen.select(entry);
        }
    }

    private void refreshCatalog() {
        this.catalog    = this.scanCatalog();
        this.modsScreen = this.createModsScreen();

        this.updateStatus();

        if (this.account != null && this.section == LauncherShell.Section.MODS) {
            this.showSection(LauncherShell.Section.MODS);
        }

        this.selectLastMod();
    }

    private void changeLanguage(@Nullable UiLanguage language) {
        this.settings = this.settings.withLanguage(language);
        this.saveSettings();
        this.rebuildViews();
    }

    private @Nullable String saveNodes(@NotNull List<@NotNull ConfiguredNode> changed) {
        try {
            this.nodesFile.write(changed);
        } catch (IOException exception) {
            return String.valueOf(exception.getMessage());
        }

        this.nodes        = List.copyOf(changed);
        this.nodesProblem = null;
        this.updateStatus();

        return null;
    }

    private void pingNodes(@NotNull List<@NotNull ConfiguredNode> pinged,
                           @NotNull Consumer<@NotNull List<@NotNull NodePingResult>> results) {
        Thread thread = new Thread(() -> {
            List<NodePingResult> answered = NodePinger.createDefault().ping(pinged);

            Platform.runLater(() -> results.accept(answered));
        }, PING_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    private void saveSettings() {
        try {
            this.settingsFile.write(this.settings);
            this.settingsProblem = null;
        } catch (IOException exception) {
            this.settingsProblem = exception.getMessage();
        }
    }

    private void saveWindowState() {
        WindowState window = new WindowState((int) this.stage.getX(), (int) this.stage.getY(),
                                             (int) this.stage.getWidth(), (int) this.stage.getHeight(),
                                             this.stage.isMaximized());

        this.settings = this.settings.withWindow(window).withLastMod(this.selectedJar);
        this.saveSettings();
    }

    private void startRun(@NotNull ModEntry entry, @NotNull SettingsForm form) {
        ModManifest manifest = entry.getManifest();
        Account chosen = this.account;

        if (manifest == null || chosen == null || this.running.get()) {
            return;
        }

        RuntimeCommandLine commandLine = RuntimeCommandLine.create(this.install, manifest.getId(), entry.getJar(), chosen.getDirectory(),
                                                                   RuntimeCommandLine.getLauncherJavaHome(), RuntimeCommandLine.isWindows());
        String failure = null;
        String settingsText = "";

        try {
            form.save(new SettingValueStore(manifest.getSettings(), new SettingValuesFile(commandLine.getSettingsFile()),
                                            new SettingValuesFile(this.install.getSharedSettingsFile())));
            settingsText = Files.readString(commandLine.getSettingsFile(), StandardCharsets.UTF_8);
        } catch (IOException | SettingValuesException exception) {
            failure = this.texts.format("run.settings.failed", commandLine.getSettingsFile(), exception.getMessage());
        }

        try {
            if (failure == null) {
                this.account = this.accountStore.markUsed(chosen, System.currentTimeMillis());
            }
        } catch (IOException exception) {
            failure = this.texts.format("accounts.failed", exception.getMessage());
        }

        RuntimeRun started = new RuntimeRun(ModArt.getName(entry, this.texts), manifest.getId(), manifest.getVersion().toString(),
                                            commandLine, settingsText, Instant.now());

        this.run         = started;
        this.selectedJar = entry.getJarName();
        this.settings    = this.settings.withLastMod(this.selectedJar);
        this.saveSettings();

        if (failure == null && commandLine.getMissingProgram() != null) {
            failure = this.texts.format("run.program.missing", commandLine.getMissingProgram());
        }

        if (failure == null) {
            try {
                started.setProcess(RuntimeProcess.start(commandLine, new RunEvents(started)));
            } catch (IOException exception) {
                failure = String.valueOf(exception.getMessage());
            }
        }

        if (failure != null) {
            started.failStart(failure, Instant.now());
        } else {
            this.running.set(true);
        }

        this.refreshCatalog();
        this.showSection(LauncherShell.Section.RUN);

        if (this.running.get()) {
            this.stage.setIconified(true);
        }
    }

    private void showOutput(@NotNull RuntimeRun target, @NotNull String line) {
        target.addOutput(line);

        if (this.runScreen != null && this.run == target) {
            this.runScreen.appendOutput(line);
        }
    }

    private void finishRun(@NotNull RuntimeRun target, int status) {
        target.finish(status, Instant.now());
        this.running.set(false);
        this.stage.setIconified(false);
        this.stage.toFront();

        if (this.account == null) {
            this.loginScreen.refresh();
        } else if (this.run == target) {
            this.showSection(LauncherShell.Section.RUN);
        }
    }

    private void copy(@NotNull String text) {
        ClipboardContent content = new ClipboardContent();

        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
