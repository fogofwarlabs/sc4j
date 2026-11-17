package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Shows the mod list: one cell per jar of {@code mods/} with its icon, name, id, version, authors, the first reason of a
 * warning or error, and the status badge. The footer opens the folder, scans it again and installs a mod.
 */
public final class ModListPanel {
    private static final @NotNull String SEPARATOR = " | ";

    private final @NotNull LauncherTexts texts;
    private final @NotNull ModArt art;
    private final @NotNull ListView<ModEntry> list;
    private final @NotNull VBox root;

    /**
     * Creates a {@link ModListPanel} instance.
     *
     * @param catalog      the scanned mods
     * @param texts        the launcher texts of the display language
     * @param art          the decoded mod images
     * @param onSelect     called with the entry the user selects
     * @param onOpenFolder called when the user opens the mods folder
     * @param onRefresh    called when the user asks to scan the folder again
     * @param onInstall    called when the user installs a mod
     */
    public ModListPanel(@NotNull ModCatalog catalog, @NotNull LauncherTexts texts, @NotNull ModArt art, @NotNull Consumer<@NotNull ModEntry> onSelect,
                        @NotNull Runnable onOpenFolder, @NotNull Runnable onRefresh, @NotNull Runnable onInstall) {
        Button openFolder = ComponentUtils.smallButton(texts.get("mods.open.folder"), ButtonRole.SECONDARY);
        Button refresh = ComponentUtils.smallButton(texts.get("mods.refresh"), ButtonRole.SECONDARY);
        Button install = ComponentUtils.smallButton(texts.get("mods.install"), ButtonRole.SECONDARY);

        this.texts = texts;
        this.art   = art;
        this.list  = ComponentUtils.listView(ListItemSize.REGULAR);

        this.list.getItems().setAll(catalog.getEntries());
        this.list.setCellFactory(view -> new ListItemCell<>(this::createItem));
        this.list.setPlaceholder(ComponentUtils.paragraph(texts.format("mods.empty", catalog.getModsDirectory()), "text-caption"));
        this.list.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                onSelect.accept(current);
            }
        });
        VBox.setVgrow(this.list, Priority.ALWAYS);

        openFolder.setOnAction(event -> onOpenFolder.run());
        refresh.setOnAction(event -> onRefresh.run());
        install.setOnAction(event -> onInstall.run());

        this.root = ComponentUtils.panelWithFooter(texts.get("mods.title"), ComponentUtils.flushPanelBody(this.list),
                                                   ComponentUtils.panelFooter(openFolder, ComponentUtils.growingSpacer(), refresh, install));
        ComponentUtils.setColumnWidth(this.root, ColumnWidth.WIDE);
    }

    /**
     * @return the panel's root node
     */
    public @NotNull VBox getRoot() {
        return this.root;
    }

    /**
     * Selects an entry, which calls the selection callback.
     *
     * @param entry the entry of the list
     */
    public void select(@NotNull ModEntry entry) {
        this.list.getSelectionModel().select(entry);
        this.list.scrollTo(entry);
    }

    /**
     * Creates the list item of a mod: its icon, its name over its id, version and authors and its first issue, and its status badge.
     */
    private @NotNull ListItem createItem(@NotNull ModEntry entry) {
        ModManifest manifest = entry.getManifest();
        ListItem item = new ListItem(ModArt.getName(entry, this.texts)).setThumbnail(this.art.getIcon(entry, ListItem.THUMBNAIL_SIZE.getSize()));

        if (manifest != null) {
            item.addCaption(manifest.getId() + SEPARATOR + manifest.getVersion() + SEPARATOR + String.join(", ", manifest.getAuthors()));
        } else {
            item.addCaption(this.texts.get("mods.no.manifest"));
        }

        if (!entry.getIssues().isEmpty()) {
            ModIssue issue = entry.getIssues().getFirst();

            item.addCaption(issue.describe(this.texts), issue.getStatus() == ModStatus.ERROR ? "text-danger" : "text-warning");
        }

        return item.setBadge(ModStatusUtils.createBadge(entry.getStatus(), this.texts));
    }
}
