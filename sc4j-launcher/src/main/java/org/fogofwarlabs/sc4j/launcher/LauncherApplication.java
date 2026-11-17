package org.fogofwarlabs.sc4j.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.session.LauncherSession;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherWindow;
import org.fogofwarlabs.sc4j.launcher.ui.session.ChatTone;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runs the launcher window as a JavaFX application. {@link LauncherMain} starts it with the install folder as the named
 * parameter {@code --install=<dir>}. The launcher's Beacon.Link node ({@link LauncherNodeRunner}) runs while the application runs, with the
 * install's node settings, unless another launcher of the install runs it; a node that cannot start is logged and shown on the Settings
 * screen, and the launcher runs without it. A logged-in account's Beacon.Link session is a {@link LauncherSession} with the install's seeds
 * and shared peers file, publishing its views on the JavaFX thread; the chat sound is the synthesized {@link ChatTone}. Folders open in the
 * system file manager ({@link FolderOpener}), web addresses through the JavaFX host services. Once the window is shown, the launcher runs its
 * start checks ({@link LauncherWindow#startChecks}).
 */
public final class LauncherApplication extends Application {
    private static final @NotNull String DEVELOPMENT_VERSION = "development";

    private @Nullable LauncherNodeRunner nodeRunner;

    /**
     * Creates a {@link LauncherApplication} instance; JavaFX calls it.
     */
    public LauncherApplication() {
        this.nodeRunner = null;
    }

    /**
     * The main entry point for all JavaFX applications. The start method is called after the init method has returned,
     * and after the system is ready for the application to begin running.
     *
     * @param primaryStage the primary stage for this application, onto which the application scene can be set
     * @throws IllegalStateException if the launcher was started without the install parameter
     */
    @Override
    public void start(@NotNull Stage primaryStage) {
        String installParameter = this.getParameters().getNamed().get(LauncherMain.INSTALL_PARAMETER);
        String version = LauncherApplication.class.getPackage().getImplementationVersion();

        if (installParameter == null) {
            throw Exceptions.newIllegalStateException("The launcher needs the parameter --{0}=<dir>.", LauncherMain.INSTALL_PARAMETER);
        }

        InstallLayout install = InstallLayout.of(Path.of(installParameter));
        LauncherNodeRunner runner = new LauncherNodeRunner(install);
        String launcherVersion = version == null ? DEVELOPMENT_VERSION : version;
        Consumer<String> showDocument = document -> this.getHostServices().showDocument(document);
        FolderOpener folderOpener = new FolderOpener(showDocument);
        LauncherWindow window;

        this.nodeRunner = runner;
        runner.start();
        window = new LauncherWindow(primaryStage, install, launcherVersion, showDocument, folderOpener::open, runner,
                                    account -> LauncherSession.open(install, account, launcherVersion, Platform::runLater), new ChatTone()::play);

        window.show();
        window.startChecks();
    }

    /**
     * This method is called when the application should stop, and provides a convenient place to prepare for application exit and destroy
     * resources.
     */
    @Override
    public void stop() {
        LauncherNodeRunner running = this.nodeRunner;

        this.nodeRunner = null;

        if (running != null) {
            running.close();
        }
    }
}
