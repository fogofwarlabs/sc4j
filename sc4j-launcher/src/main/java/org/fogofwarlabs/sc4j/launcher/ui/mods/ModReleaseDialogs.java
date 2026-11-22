package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.mods.ModIndex;
import org.fogofwarlabs.sc4j.catalog.mods.ModIndexEntry;
import org.fogofwarlabs.sc4j.catalog.mods.ModRelease;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModIssue;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.install.ModInstallResult;
import org.fogofwarlabs.sc4j.launcher.install.ModInstaller;
import org.fogofwarlabs.sc4j.launcher.install.ModSource;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opens the dialogs of the Mods screen that change {@code mods/}: Install mod, Change version, Update and Remove.
 * <p>
 * Install mod asks for a GitHub repository: typed as {@code owner/name} or the repository's address, or chosen from the mod catalog
 * ({@link ModIndex}), which is read when the dialog opens and lists each mod by its name in the display language; without the catalog the
 * list is empty. The releases of the repository are read next, and a confirmation offers them newest first, the latest chosen, names the
 * jar, the repository and the chosen release and says that the mod runs code on this computer. Change version does the same for the
 * repository an installed mod came from and marks its installed release; the chosen release, the installed one or an older one included,
 * replaces the installed jar. Install, Change version and Update download the jar with the dialog's progress and hand it to
 * {@link ModInstaller}, which puts it into {@code mods/} only when it passes the launcher's checks, in place of an installed jar of the same
 * mod id; a refused jar is not installed and the reasons are shown.
 */
public final class ModReleaseDialogs {
    private static final @NotNull String CATALOG_THREAD_NAME = "launcher-mod-catalog";
    private static final @NotNull String ISSUE_SEPARATOR = " ";

    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull ModInstaller installer;
    private final @NotNull ModIndex index;
    private final @NotNull Supplier<@NotNull ModCatalog> catalog;
    private final @NotNull Consumer<@NotNull String> changed;
    private final @NotNull Map<@NotNull String, @NotNull ModIndexEntry> indexEntries;

    /**
     * Creates a {@link ModReleaseDialogs} instance.
     *
     * @param shell     shows the dialogs
     * @param texts     the launcher texts of the display language
     * @param installer installs, updates and removes the mods
     * @param index     reads the mod catalog
     * @param catalog   gives the mods of the install as currently listed
     * @param changed   called with the jar file name of the mod that was installed, updated or removed
     */
    public ModReleaseDialogs(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull ModInstaller installer, @NotNull ModIndex index,
                             @NotNull Supplier<@NotNull ModCatalog> catalog, @NotNull Consumer<@NotNull String> changed) {
        this.shell        = shell;
        this.texts        = texts;
        this.installer    = installer;
        this.index        = index;
        this.catalog      = catalog;
        this.changed      = changed;
        this.indexEntries = new HashMap<>();
    }

    /**
     * Opens the Install mod dialog.
     */
    public void openInstall() {
        ModalDialog shown = new ModalDialog(this.texts.get("mods.install.title"));
        ComboBox<String> repository = ComponentUtils.createComboBox(ControlWidth.L);
        Button cancel = ComponentUtils.createButton(this.texts.get("mods.cancel"), ButtonRole.SECONDARY);
        Button next = ComponentUtils.createButton(this.texts.get("mods.install.next"), ButtonRole.PRIMARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        repository.setEditable(true);
        repository.setPromptText(this.texts.get("mods.install.prompt"));
        repository.setMaxWidth(Double.MAX_VALUE);
        repository.setCellFactory(view -> new ListItemCell<>(this::createRepositoryItem));
        this.readIndex(repository);

        shown.addContent(ComponentUtils.createField(this.texts.get("mods.install.repository"), repository));
        shown.setButtons(cancel, next);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.shell.closeDialog(shown);
        });
        next.setOnAction(event -> {
            ReleaseRepository parsed = ReleaseRepository.parse(repository.getEditor().getText());

            if (parsed == null) {
                shown.showMessage(this.texts.describeReleaseProblem(ReleaseProblem.INVALID_REPOSITORY));

                return;
            }

            ReleaseClient client = this.installer.getClient();

            repository.setDisable(true);
            next.setDisable(true);
            shown.runWork(this.texts.get("mods.install.finding"), cancelled, progress -> ModRelease.findAll(client, parsed, null), releases -> {
                this.shell.closeDialog(shown);
                this.openConfirmation(this.texts.get("mods.install.title"), release -> this.texts.format("mods.install.confirm",
                                                                                                         release.getJarFile().getName(),
                                                                                                         release.getRepository(), release.getTag()),
                                      this.texts.get("mods.install.start"), releases, null, null);
            }, this::describe, () -> {
                repository.setDisable(false);
                next.setDisable(false);
            });
        });

        this.shell.showDialog(shown);
        repository.requestFocus();
    }

    /**
     * Opens the Change version dialog of a mod installed from a release: the releases of its repository are read and offered, its installed
     * release marked.
     *
     * @param entry  the mod
     * @param source the repository and release the mod was installed from
     */
    public void openVersions(@NotNull ModEntry entry, @NotNull ModSource source) {
        ModalDialog shown = new ModalDialog(this.texts.get("mods.version.title"));
        Button cancel = ComponentUtils.createButton(this.texts.get("mods.cancel"), ButtonRole.SECONDARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ReleaseClient client = this.installer.getClient();
        String name = ModArt.getName(entry, this.texts);

        shown.setButtons(cancel, null);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.shell.closeDialog(shown);
        });

        this.shell.showDialog(shown);
        cancel.requestFocus();
        shown.runWork(this.texts.get("mods.install.finding"), cancelled, progress -> ModRelease.findAll(client, source.getRepository(), entry.getJarName()),
                      releases -> {
                          this.shell.closeDialog(shown);
                          this.openConfirmation(this.texts.get("mods.version.title"),
                                                release -> this.texts.format("mods.version.confirm", name, release.getRepository(), release.getTag()),
                                                this.texts.get("mods.install.start"), releases, source.getRelease(), entry);
                      }, this::describe, () -> {
                });
    }

    /**
     * Opens the Update dialog of an installed mod.
     *
     * @param entry   the mod
     * @param release the newer release of the mod
     */
    public void openUpdate(@NotNull ModEntry entry, @NotNull ModRelease release) {
        String name = ModArt.getName(entry, this.texts);

        this.openConfirmation(this.texts.get("mods.update.title"),
                              shownRelease -> this.texts.format("mods.update.confirm", name, shownRelease.getRepository(), shownRelease.getTag()),
                              this.texts.get("mods.update.start"), List.of(release), null, entry);
    }

    /**
     * Opens the Remove dialog of an installed mod.
     *
     * @param entry the mod
     */
    public void openRemove(@NotNull ModEntry entry) {
        ModalDialog shown = new ModalDialog(this.texts.get("mods.remove.title"));
        Button cancel = ComponentUtils.createButton(this.texts.get("mods.cancel"), ButtonRole.SECONDARY);
        Button remove = ComponentUtils.createButton(this.texts.get("mods.remove"), ButtonRole.DANGER);

        shown.addContent(ComponentUtils.createParagraph(this.texts.format("mods.remove.text", ModArt.getName(entry, this.texts)), "text-body"));
        shown.setButtons(cancel, remove);
        cancel.setOnAction(event -> this.shell.closeDialog(shown));
        remove.setOnAction(event -> {
            try {
                this.installer.remove(entry);
            } catch (IOException exception) {
                shown.showMessage(this.describe(exception));

                return;
            }

            this.shell.closeDialog(shown);
            this.changed.accept(entry.getJarName());
        });

        this.shell.showDialog(shown);
        cancel.requestFocus();
    }

    /**
     * Opens the confirmation of an install, a version change or an update, whose action downloads the jar of the chosen release and installs
     * it. With more than one release a choice offers them in the given order, the first chosen; the text follows the choice.
     *
     * @param releases     the releases, newest first
     * @param installedTag the tag of the installed release, marked in the choice, or {@code null}
     * @param replaced     the installed mod the jar replaces, or {@code null} for the installed jar of the same id, if there is one
     */
    private void openConfirmation(@NotNull String title, @NotNull Function<@NotNull ModRelease, @NotNull String> describeRelease,
                                  @NotNull String actionText, @NotNull List<@NotNull ModRelease> releases, @Nullable String installedTag,
                                  @Nullable ModEntry replaced) {
        ModalDialog shown = new ModalDialog(title);
        Button cancel = ComponentUtils.createButton(this.texts.get("mods.cancel"), ButtonRole.SECONDARY);
        Button action = ComponentUtils.createButton(actionText, ButtonRole.PRIMARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ComboBox<String> version = ComponentUtils.createComboBox(ControlWidth.M);
        Map<String, ModRelease> releasesByTag = new LinkedHashMap<>();
        ModRelease latest = releases.getFirst();
        Label text = ComponentUtils.createParagraph(describeRelease.apply(latest), "text-body");

        for (ModRelease release : releases) {
            releasesByTag.putIfAbsent(release.getTag(), release);
        }

        version.getItems().setAll(releasesByTag.keySet());
        version.setValue(latest.getTag());
        version.setCellFactory(view -> new ListItemCell<>(tag -> this.createVersionItem(tag, latest.getTag(), installedTag)));
        version.valueProperty().addListener((property, previous, chosen) -> {
            ModRelease release = chosen == null ? null : releasesByTag.get(chosen);

            if (release != null) {
                text.setText(describeRelease.apply(release));
            }
        });

        shown.addContent(text);

        if (releasesByTag.size() > 1) {
            shown.addContent(ComponentUtils.createField(this.texts.get("mods.version.release"), version));
        }

        shown.addContent(ComponentUtils.createNotice(Tone.WARNING, this.texts.get("mods.install.code")));
        shown.setButtons(cancel, action);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.shell.closeDialog(shown);
        });
        action.setOnAction(event -> {
            ModRelease release = releasesByTag.getOrDefault(version.getValue(), latest);

            action.setDisable(true);
            version.setDisable(true);
            shown.runWork(this.texts.get("mods.install.downloading"), cancelled, progress -> this.installer.download(release, progress), jar -> {
                ModInstallResult result = this.installer.install(release, jar, this.catalog.get(), replaced);
                String jarName = result.getJarName();

                if (jarName == null) {
                    shown.showMessage(this.texts.format("mods.install.refused", this.describe(result.getIssues())));

                    return;
                }

                this.shell.closeDialog(shown);
                this.changed.accept(jarName);
            }, this::describe, () -> {
                action.setDisable(false);
                version.setDisable(false);
            });
        });

        this.shell.showDialog(shown);
        action.requestFocus();
    }

    /**
     * Creates the list item of a release of the version choice: its tag, marked as the latest and as the installed release.
     */
    private @NotNull ListItem createVersionItem(@NotNull String tag, @NotNull String latestTag, @Nullable String installedTag) {
        ListItem item = new ListItem(tag);

        if (tag.equals(latestTag)) {
            item.addCaption(this.texts.get("mods.version.latest"));
        }

        if (tag.equals(installedTag)) {
            item.addCaption(this.texts.get("mods.version.installed"));
        }

        return item;
    }

    /**
     * Creates the list item of a catalog entry of the repository choice: the mod's name over its repository.
     */
    private @NotNull ListItem createRepositoryItem(@NotNull String repository) {
        ModIndexEntry entry = this.indexEntries.get(repository);

        return new ListItem(entry == null ? repository : this.texts.localize(entry.getName())).addCaption(repository);
    }

    /**
     * Reads the mod catalog on a thread of its own and lists its entries in the repository choice; a catalog that cannot be read leaves
     * the list empty.
     */
    private void readIndex(@NotNull ComboBox<String> repository) {
        Thread thread = new Thread(() -> {
            List<ModIndexEntry> entries;

            try {
                entries = this.index.read();
            } catch (IOException exception) {
                return;
            }

            Platform.runLater(() -> {
                List<String> repositories = new ArrayList<>(entries.size());

                for (ModIndexEntry entry : entries) {
                    String name = entry.getRepository().toString();

                    if (this.indexEntries.putIfAbsent(name, entry) == null) {
                        repositories.add(name);
                    }
                }

                repository.getItems().setAll(repositories);
            });
        }, CATALOG_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    private @NotNull String describe(@NotNull Exception exception) {
        return exception instanceof ReleaseException problem ? this.texts.describeReleaseProblem(problem.getProblem())
                                                             : this.texts.format("mods.failed", exception.getMessage());
    }

    private @NotNull String describe(@NotNull List<@NotNull ModIssue> issues) {
        List<String> reasons = new ArrayList<>(issues.size());

        for (ModIssue issue : issues) {
            reasons.add(issue.describe(this.texts));
        }

        return String.join(ISSUE_SEPARATOR, reasons);
    }
}
