package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.SettingsIssue;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.fogofwarlabs.sc4j.launcher.ui.form.SettingsFormView;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the selected mod. A mod that can be started shows its banner, the details of its manifest, its screenshots and
 * the generated settings form with the Run button; a mod with an error shows the reasons and cannot be started. The
 * reasons of a warning are shown above the form. Either view offers Change version and Remove, and a newer release of a mod installed from a
 * release is shown with its Update button.
 */
public final class ModDetailView {
    private static final @NotNull BackgroundSize BANNER_COVER = new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, true, true, false, true);
    private static final double ICON_SIZE = IconSize.XL.getSize();
    private static final double BANNER_ICON_SIZE = IconSize.L.getSize();
    private static final double SCREENSHOT_WIDTH = 92;
    private static final double SCREENSHOT_HEIGHT = 52;
    private static final @NotNull String LINE_SEPARATOR = "\n";

    /**
     * Receives the actions of the detail view.
     */
    public interface Actions {
        /**
         * Called when the user presses Run with a valid form.
         *
         * @param entry the mod
         * @param form  the mod's settings form, to be saved before the start
         */
        void onRun(@NotNull ModEntry entry, @NotNull SettingsForm form);

        /**
         * Called when the user copies text, such as the problems of a mod.
         *
         * @param text the text for the clipboard
         */
        void onCopy(@NotNull String text);

        /**
         * Called when the user opens the mod's website.
         *
         * @param address the website address of the manifest
         */
        void onOpenWebsite(@NotNull String address);

        /**
         * Called when the user updates the mod to its newer release.
         *
         * @param entry the mod
         */
        void onUpdate(@NotNull ModEntry entry);

        /**
         * Called when the user installs another release of the mod, or the installed one again.
         *
         * @param entry the mod
         */
        void onChangeVersion(@NotNull ModEntry entry);

        /**
         * Called when the user removes the mod.
         *
         * @param entry the mod
         */
        void onRemove(@NotNull ModEntry entry);
    }


    private final @NotNull ModEntry entry;
    private final @Nullable String updateRelease;
    private final @NotNull LauncherTexts texts;
    private final @NotNull ModArt art;
    private final @NotNull Runnable onSettingsChanged;
    private final @NotNull Actions actions;
    private final @NotNull Region root;

    /**
     * Creates a {@link ModDetailView} instance.
     *
     * @param entry             the mod
     * @param updateRelease     the tag of the mod's newer release, or {@code null} when none is known
     * @param form              the mod's settings form, or {@code null} for a mod with an error
     * @param texts             the launcher texts of the display language
     * @param art               the decoded mod images
     * @param running           {@code true} while a run is in progress, which keeps Run disabled
     * @param onSettingsChanged called after the user changed a field of the settings form or reset it to the defaults
     * @param actions           receives the actions
     */
    public ModDetailView(@NotNull ModEntry entry, @Nullable String updateRelease, @Nullable SettingsForm form, @NotNull LauncherTexts texts,
                         @NotNull ModArt art, @NotNull ObservableBooleanValue running, @NotNull Runnable onSettingsChanged, @NotNull Actions actions) {
        this.entry             = entry;
        this.updateRelease     = updateRelease;
        this.texts             = texts;
        this.art               = art;
        this.onSettingsChanged = onSettingsChanged;
        this.actions           = actions;
        this.root              = form != null && entry.getStatus().isStartable() ? this.createUsableView(form, running) : this.createUnusableView();
    }

    /**
     * @return the view's root node
     */
    public @NotNull Region getRoot() {
        return this.root;
    }

    private @NotNull Region createUsableView(@NotNull SettingsForm form, @NotNull ObservableBooleanValue running) {
        VBox aboutPanel = this.createAboutPanel();
        VBox settingsPanel = this.createSettingsPanel(form, running);
        HBox lower = ComponentUtils.createRow(Spacing.L, aboutPanel, settingsPanel);
        VBox detail;

        ComponentUtils.setColumnWidth(aboutPanel, ColumnWidth.WIDE);
        HBox.setHgrow(settingsPanel, Priority.ALWAYS);
        lower.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(lower, Priority.ALWAYS);

        detail = ComponentUtils.createColumn(Spacing.L, this.createBanner());

        if (this.updateRelease != null) {
            detail.getChildren().add(this.createUpdateNotice(this.updateRelease));
        }

        detail.getChildren().add(lower);
        HBox.setHgrow(detail, Priority.ALWAYS);

        return detail;
    }

    private @NotNull HBox createUpdateNotice(@NotNull String release) {
        Button update = ComponentUtils.createSmallButton(this.texts.get("mods.update.start"), ButtonRole.PRIMARY);

        update.setOnAction(event -> this.actions.onUpdate(this.entry));

        return ComponentUtils.createNotice(Tone.ACCENT, this.texts.format("mods.update.available", release), update);
    }

    private @NotNull Button createVersionButton() {
        Button version = ComponentUtils.createButton(this.texts.get("mods.version.change"), ButtonRole.SECONDARY);

        version.setOnAction(event -> this.actions.onChangeVersion(this.entry));

        return version;
    }

    private @NotNull Button createRemoveButton() {
        Button remove = ComponentUtils.createButton(this.texts.get("mods.remove"), ButtonRole.DANGER);

        remove.setOnAction(event -> this.actions.onRemove(this.entry));

        return remove;
    }

    private @NotNull StackPane createBanner() {
        ModManifest manifest = this.requireManifest();
        Image bannerImage = this.art.getBanner(this.entry);
        VBox heading = ComponentUtils.createColumn(Spacing.XS,
                                                   ComponentUtils.createLabel(this.texts.localize(manifest.getName()), "text-display"),
                                                   ComponentUtils.createLabel(this.texts.format("detail.version.line", manifest.getVersion(),
                                                                                                String.join(", ", manifest.getAuthors())),
                                                                              "text-subtitle", "text-muted"));
        HBox caption = ComponentUtils.createRow(Spacing.L,
                                                ComponentUtils.createThumbnail(this.art.getIcon(this.entry, BANNER_ICON_SIZE), BANNER_ICON_SIZE, BANNER_ICON_SIZE),
                                                heading,
                                                ComponentUtils.createGrowingSpacer(),
                                                ModStatusUtils.createBadge(this.entry.getStatus(), this.texts));
        StackPane banner = new StackPane();

        heading.getStyleClass().add("banner-heading");

        if (bannerImage != null) {
            Region background = new Region();

            // The banner covers the card at any width: scaled to cover, centred and cropped.
            background.setBackground(new Background(new BackgroundImage(bannerImage, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                                                                        BackgroundPosition.CENTER, BANNER_COVER)));
            banner.getChildren().add(background);
        }

        caption.getStyleClass().add("banner-caption");
        caption.setMaxHeight(Region.USE_PREF_SIZE);
        banner.getChildren().add(caption);
        StackPane.setAlignment(caption, Pos.BOTTOM_LEFT);
        banner.getStyleClass().add("banner");
        banner.setMaxWidth(Double.MAX_VALUE);

        return banner;
    }

    private @NotNull VBox createAboutPanel() {
        ModManifest manifest = this.requireManifest();
        GridPane meta = ComponentUtils.createMetaGrid();
        LocalizedText description = manifest.getDescription();
        String website = manifest.getWebsite();
        VBox body;
        VBox panel;

        ComponentUtils.addMetaRow(meta, this.texts.get("detail.id"), manifest.getId());
        ComponentUtils.addMetaRow(meta, this.texts.get("detail.version"), manifest.getVersion().toString());
        ComponentUtils.addMetaRow(meta, this.texts.get("detail.engine"), manifest.getEngine().toString());
        ComponentUtils.addMetaRow(meta, this.texts.get("detail.authors"), String.join(", ", manifest.getAuthors()));

        if (manifest.getLicense() != null) {
            ComponentUtils.addMetaRow(meta, this.texts.get("detail.license"), manifest.getLicense());
        }

        ComponentUtils.addMetaRow(meta, this.texts.get("detail.file"), this.entry.getJarName());

        if (website != null) {
            Hyperlink link = new Hyperlink(website);

            link.setOnAction(event -> this.actions.onOpenWebsite(website));
            ComponentUtils.addMetaRow(meta, this.texts.get("detail.website"), link);
        }

        body = ComponentUtils.createPanelBody();

        if (description != null) {
            body.getChildren().add(ComponentUtils.createParagraph(this.texts.localize(description), "text-body"));
        }

        body.getChildren().add(meta);

        if (!manifest.getImages().getScreenshots().isEmpty()) {
            HBox screenshots = ComponentUtils.createRow(Spacing.S);

            for (String screenshot : manifest.getImages().getScreenshots()) {
                screenshots.getChildren().add(ComponentUtils.createThumbnail(this.art.getImage(this.entry, screenshot, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT),
                                                                             SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT));
            }

            body.getChildren().addAll(ComponentUtils.createLabel(this.texts.get("detail.screenshots"), "text-heading"), screenshots);
        }

        panel = ComponentUtils.createPanelWithFooter(this.texts.get("detail.about"), body,
                                                     ComponentUtils.createPanelFooter(ComponentUtils.createGrowingSpacer(), this.createVersionButton(),
                                                                                      this.createRemoveButton()));
        ComponentUtils.fillHeight(panel);

        return panel;
    }

    private @NotNull VBox createSettingsPanel(@NotNull SettingsForm form, @NotNull ObservableBooleanValue running) {
        SettingsFormView formView = new SettingsFormView(form, this.texts, this.onSettingsChanged);
        ScrollPane scroll = new ScrollPane(formView.getRoot());
        VBox body = ComponentUtils.createPanelBody();
        Button reset = ComponentUtils.createButton(this.texts.get("detail.reset"), ButtonRole.SECONDARY);
        Button run = ComponentUtils.createButton(this.texts.get("detail.run"), ButtonRole.PRIMARY);
        Label blocker = ComponentUtils.createLabel("");
        Region blockerIcon = new Region();
        VBox panel;

        for (ModIssue issue : this.entry.getIssues()) {
            if (issue instanceof SettingsIssue settingsIssue && settingsIssue.getDefinition() == null) {
                body.getChildren().add(ComponentUtils.createNotice(Tone.WARNING, issue.describe(this.texts)));
            }
        }

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        body.getChildren().add(scroll);

        reset.setOnAction(event -> formView.resetToDefaults());
        run.disableProperty().bind(formView.invalidCountProperty().greaterThan(0).or(running));
        run.setOnAction(event -> {
            formView.validate();

            if (form.isValid()) {
                this.actions.onRun(this.entry, form);
            }
        });

        blockerIcon.getStyleClass().add("field-message-icon");
        blocker.getStyleClass().addAll("text-caption", "text-danger");
        blocker.setGraphic(blockerIcon);
        blocker.textProperty().bind(Bindings.createStringBinding(() -> this.texts.formatCount("detail.blocked", formView.invalidCountProperty().get()),
                                                                 formView.invalidCountProperty()));
        blocker.visibleProperty().bind(formView.invalidCountProperty().greaterThan(0));

        panel = ComponentUtils.createPanelWithFooter(this.texts.get("detail.settings"), body,
                                                     ComponentUtils.createPanelFooter(blocker, ComponentUtils.createGrowingSpacer(), reset, run));
        ComponentUtils.fillHeight(panel);

        return panel;
    }

    private @NotNull Region createUnusableView() {
        ModManifest manifest = this.entry.getManifest();
        VBox identityText = ComponentUtils.createColumn(Spacing.XS,
                                                        ComponentUtils.createLabel(ModArt.getName(this.entry, this.texts), "text-title"),
                                                        ComponentUtils.createLabel(this.entry.getJar().toString(), "text-mono", "text-muted"));
        HBox badgeRow = ComponentUtils.createRow(Spacing.S, ModStatusUtils.createBadge(this.entry.getStatus(), this.texts));
        HBox identity = ComponentUtils.createRow(Spacing.L,
                                                 ComponentUtils.createThumbnail(this.art.getIcon(this.entry, ICON_SIZE), ICON_SIZE, ICON_SIZE),
                                                 identityText);
        VBox report = ComponentUtils.createDangerPanel(ComponentUtils.createLabel(this.texts.get("detail.error.title"), "text-subtitle", "text-danger"));
        List<String> details = new ArrayList<>();
        Button copy = ComponentUtils.createButton(this.texts.get("detail.copy.problems"), ButtonRole.SECONDARY);
        Button run = ComponentUtils.createButton(this.texts.get("detail.run"), ButtonRole.PRIMARY);
        VBox body;
        VBox panel;

        if (manifest != null) {
            String identityLine = this.texts.format("detail.identity.line", manifest.getId(), manifest.getVersion(), manifest.getEngine());

            badgeRow.getChildren().add(ComponentUtils.createLabel(identityLine, "text-caption"));
        }

        identityText.getChildren().add(badgeRow);
        details.add(this.entry.getJar().toString());

        for (ModIssue issue : this.entry.getIssues()) {
            Node reason = ComponentUtils.createColumn(Spacing.XS, ComponentUtils.createParagraph(issue.describe(this.texts)),
                                                      ComponentUtils.createParagraph(issue.describeDetail(), "text-caption", "text-mono"));
            Node marker = ComponentUtils.createStatusMarker(ModStatusUtils.getTone(issue.getStatus()));

            report.getChildren().add(ComponentUtils.createRow(Spacing.S, marker, reason));
            details.add(issue.describeDetail());
        }

        copy.setOnAction(event -> this.actions.onCopy(String.join(LINE_SEPARATOR, details)));
        run.setDisable(true);

        body = ComponentUtils.createPanelBody(identity);

        if (this.updateRelease != null) {
            body.getChildren().add(this.createUpdateNotice(this.updateRelease));
        }

        body.getChildren().add(report);
        panel = ComponentUtils.createPanelWithFooter(this.texts.get("detail.title"), body,
                                                     ComponentUtils.createPanelFooter(copy, this.createRemoveButton(), ComponentUtils.createGrowingSpacer(),
                                                                                      this.createVersionButton(), run));
        HBox.setHgrow(panel, Priority.ALWAYS);
        ComponentUtils.fillHeight(panel);

        return panel;
    }

    private @NotNull ModManifest requireManifest() {
        ModManifest manifest = this.entry.getManifest();

        if (manifest == null) {
            throw Exceptions.newIllegalStateException("The mod {0} has no valid manifest.", this.entry.getJar());
        }

        return manifest;
    }
}
