package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeCommandLine;
import org.fogofwarlabs.sc4j.launcher.release.ReleaseNotesUtils;
import org.fogofwarlabs.sc4j.launcher.update.InvalidPackageException;
import org.fogofwarlabs.sc4j.launcher.update.LauncherRelease;
import org.fogofwarlabs.sc4j.launcher.update.LauncherUpdater;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the Update dialog of a newer launcher version. The offer shows the SC4J logo, the running and the new version, the release date, the
 * download size, the release notes as plain text in a scrolling area (left out when the release has none) and that the launcher closes and
 * starts again; its buttons are Skip this version, Later and Update.
 * <p>
 * Update downloads the user package with its size and progress, checks it and unpacks it; Cancel stops the download and returns to the
 * offer. Then Restart starts the updater and closes the launcher, which the updater starts again once the programs are replaced. Restart
 * waits while a game runs, as the update replaces the runtime the game uses. A failed download shows its reason with Retry.
 */
public final class LauncherUpdateDialog {

    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull LauncherUpdater updater;
    private final @NotNull LauncherRelease release;
    private final @NotNull ObservableBooleanValue running;
    private final @NotNull Runnable skip;
    private final @NotNull Runnable close;

    private final @NotNull ModalDialog dialog;
    private final @NotNull Label note;
    private @NotNull AtomicBoolean cancelled;
    private @Nullable Thread work;

    /**
     * Creates a {@link LauncherUpdateDialog} instance.
     *
     * @param shell          shows the dialog
     * @param texts          the launcher texts of the display language
     * @param imageScale     the screen pixels per logical unit the logo is decoded for
     * @param updater        downloads, unpacks and applies the update
     * @param release        the newer version
     * @param runningVersion the running launcher's version
     * @param running        {@code true} while a game runs
     * @param skip           called after Skip this version closed the dialog, to save the skipped version
     * @param close          closes the launcher once the updater runs
     */
    public LauncherUpdateDialog(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, double imageScale,
                                @NotNull LauncherUpdater updater, @NotNull LauncherRelease release, @NotNull String runningVersion,
                                @NotNull ObservableBooleanValue running, @NotNull Runnable skip, @NotNull Runnable close) {
        double logoSize = IconSize.L.getSize();
        Label title = ComponentUtils.label(texts.get("update.offer"), "text-title");
        Label versions = ComponentUtils.label(texts.format("update.versions", runningVersion, release.getVersion()), "text-subtitle");
        HBox header = ComponentUtils.row(Spacing.L,
                                         ComponentUtils.imageView(ThemeUtils.loadImage(ThemeUtils.SC4J_LOGO, logoSize, imageScale), logoSize),
                                         ComponentUtils.column(Spacing.XS, title, versions));
        GridPane facts = ComponentUtils.metaGrid();
        Instant publishedAt = release.getPublishedAt();
        long size = release.getPackageFile().getSize();
        String notes = ReleaseNotesUtils.toPlainText(release.getNotes());

        this.shell   = shell;
        this.texts   = texts;
        this.updater = updater;
        this.release = release;
        this.running = running;
        this.skip    = skip;
        this.close   = close;

        this.dialog    = new ModalDialog(texts.get("update.title"), ModalDialog.Width.WIDE);
        this.note      = ComponentUtils.paragraph(texts.get("update.note"), "text-caption");
        this.cancelled = new AtomicBoolean(false);
        this.work      = null;

        this.dialog.addContent(header);

        if (publishedAt != null) {
            DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(texts.getLocale());

            ComponentUtils.addMetaRow(facts, texts.get("update.date"), dateFormat.format(publishedAt.atZone(ZoneId.systemDefault())));
        }

        if (size > 0) {
            ComponentUtils.addMetaRow(facts, texts.get("update.size"), texts.formatSize(size));
        }

        if (!facts.getChildren().isEmpty()) {
            this.dialog.addContent(facts);
        }

        if (!notes.isEmpty()) {
            TextArea notesArea = ComponentUtils.textArea(notes);

            VBox.setVgrow(notesArea, Priority.ALWAYS);
            this.dialog.addGrowingContent(ComponentUtils.column(Spacing.XS, ComponentUtils.label(texts.get("update.notes"), "text-heading"),
                                                                notesArea));
        }

        this.dialog.addContent(this.note);
    }

    /**
     * Opens the dialog with the offer.
     */
    public void open() {
        this.showOffer();
        this.shell.showDialog(this.dialog);
    }

    private void showOffer() {
        Button skipButton = ComponentUtils.button(this.texts.get("update.skip"), ButtonRole.SECONDARY);
        Button later = ComponentUtils.button(this.texts.get("update.later"), ButtonRole.SECONDARY);
        Button update = ComponentUtils.button(this.texts.get("update.start"), ButtonRole.PRIMARY);

        this.dialog.setButtons(List.of(skipButton), later, update);
        skipButton.setOnAction(event -> {
            this.shell.closeDialog(this.dialog);
            this.skip.run();
        });
        later.setOnAction(event -> this.shell.closeDialog(this.dialog));
        update.setOnAction(event -> this.download());
        update.requestFocus();
    }

    private void download() {
        AtomicBoolean downloadCancelled = new AtomicBoolean(false);
        Button cancel = ComponentUtils.button(this.texts.get("update.cancel"), ButtonRole.SECONDARY);
        long size = this.release.getPackageFile().getSize();

        this.cancelled = downloadCancelled;
        this.dialog.setButtons(cancel, null);
        cancel.setOnAction(event -> this.cancel());
        this.work = this.dialog.runWork(share -> this.describeProgress(share, size), downloadCancelled,
                                        progress -> this.updater.download(this.release, progress),
                                        packageFile -> this.unpack(downloadCancelled, packageFile), this::describe, this::showRetry);
        cancel.requestFocus();
    }

    private @NotNull String describeProgress(double share, long size) {
        if (share < 0 || size <= 0) {
            return this.texts.get("update.downloading");
        }

        return this.texts.format("update.downloading.size", this.texts.formatSize((long) (share * size)), this.texts.formatSize(size));
    }

    private void unpack(@NotNull AtomicBoolean unpackCancelled, @NotNull Path packageFile) {
        this.work = this.dialog.runWork(this.texts.get("update.unpacking"), unpackCancelled,
                                        progress -> this.updater.unpack(this.release, packageFile),
                                        this::showReady, this::describe, this::showRetry);
    }

    private void cancel() {
        Thread stopped = this.work;

        this.cancelled.set(true);
        this.work = null;

        if (stopped != null) {
            stopped.interrupt();
        }

        this.dialog.showProgress(null, 0);
        this.showOffer();
    }

    private void showRetry() {
        Button later = ComponentUtils.button(this.texts.get("update.later"), ButtonRole.SECONDARY);
        Button retry = ComponentUtils.button(this.texts.get("update.retry"), ButtonRole.PRIMARY);

        this.work = null;
        this.dialog.setButtons(later, retry);
        later.setOnAction(event -> this.shell.closeDialog(this.dialog));
        retry.setOnAction(event -> this.download());
        retry.requestFocus();
    }

    private void showReady(@NotNull Path packageRoot) {
        Button later = ComponentUtils.button(this.texts.get("update.later"), ButtonRole.SECONDARY);
        Button restart = ComponentUtils.button(this.texts.get("update.restart"), ButtonRole.PRIMARY);

        this.work = null;
        this.note.setText(this.texts.format("update.ready", this.release.getVersion()));
        this.dialog.setButtons(later, restart);
        later.setOnAction(event -> this.shell.closeDialog(this.dialog));
        restart.setOnAction(event -> this.restart(packageRoot));
        restart.requestFocus();
    }

    private void restart(@NotNull Path packageRoot) {
        if (this.running.get()) {
            this.dialog.showMessage(this.texts.get("update.running"));

            return;
        }

        try {
            this.updater.startUpdater(packageRoot, ProcessHandle.current().pid(), RuntimeCommandLine.isWindows());
        } catch (IOException exception) {
            this.dialog.showMessage(this.describe(exception));

            return;
        }

        this.close.run();
    }

    private @NotNull String describe(@NotNull Exception exception) {
        if (exception instanceof InvalidPackageException) {
            return this.texts.get("update.invalid_package");
        }

        return exception instanceof ReleaseException problem ? this.texts.describeReleaseProblem(problem.getProblem())
                                                             : this.texts.format("update.failed", exception.getMessage());
    }
}
