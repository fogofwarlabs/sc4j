package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shows the About dialog over the launcher: the SC4J logo, the launcher and engine API versions, the publisher, SC4J's licence and fonts, the
 * Beacon.Link privacy statement (what other players see, what this install keeps), then the legal notices the installed mods declare, each
 * mod's notices under its name. The text below the logo scrolls when the window is lower than the dialog.
 */
public final class AboutDialog {
    private static final @NotNull String PUBLISHER_NAME = "Fog of War Labs";
    private static final @NotNull String PUBLISHER_WEBSITE = "https://github.com/fogofwarlabs";

    /**
     * Creates the notices of the mods in the display language (with its fallbacks), each mod's under its name; a mod without notices is left
     * out, and a mod id is shown once.
     *
     * @param texts the launcher texts of the display language
     * @param mods  the manifests of the installed mods, in listed order
     * @return the created column, empty when no mod declares notices
     */
    private static @NotNull VBox createModNotices(@NotNull LauncherTexts texts, @NotNull List<@NotNull ModManifest> mods) {
        VBox column = ComponentUtils.createColumn(Spacing.M);
        Set<String> shownIds = new HashSet<>();

        for (ModManifest mod : mods) {
            if (mod.getNotices().isEmpty() || !shownIds.add(mod.getId())) {
                continue;
            }

            VBox modColumn = ComponentUtils.createColumn(Spacing.XS, ComponentUtils.createLabel(texts.localize(mod.getName()), "text-heading"));

            for (LocalizedText notice : mod.getNotices()) {
                modColumn.getChildren().add(ComponentUtils.createParagraph(texts.localize(notice), "text-caption"));
            }

            column.getChildren().add(modColumn);
        }

        return column;
    }


    private final @NotNull LauncherShell shell;
    private final @NotNull ModalDialog dialog;

    /**
     * Creates a {@link AboutDialog} instance.
     *
     * @param shell       shows the dialog
     * @param texts       the launcher texts of the display language
     * @param version     the launcher version
     * @param imageScale  the screen pixels per logical unit the logos are decoded for
     * @param mods        the manifests of the installed mods, whose notices are shown in listed order
     * @param openWebsite opens a web address in the system browser
     */
    public AboutDialog(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull String version, double imageScale,
                       @NotNull List<@NotNull ModManifest> mods, @NotNull Consumer<@NotNull String> openWebsite) {
        double logoSize = IconSize.L.getSize();
        Hyperlink website = new Hyperlink(PUBLISHER_WEBSITE);
        Label versionLabel = ComponentUtils.createLabel(texts.format("about.version", version, EngineVersion.CURRENT), "text-subtitle");
        Label titleLabel = ComponentUtils.createLabel(texts.get("about.heading"), "text-title");
        HBox header = ComponentUtils.createRow(Spacing.L,
                                               ComponentUtils.createImageView(ThemeUtils.loadImage(ThemeUtils.SC4J_LOGO, logoSize, imageScale), logoSize),
                                               ComponentUtils.createColumn(Spacing.XS, titleLabel, versionLabel));
        Image publisherLogo = ThemeUtils.loadImage(ThemeUtils.FOG_OF_WAR_LABS_LOGO, IconSize.M.getSize(), imageScale);
        HBox publisher = ComponentUtils.createRow(Spacing.M,
                                                  ComponentUtils.createImageView(publisherLogo, IconSize.M.getSize()),
                                                  ComponentUtils.createColumn(Spacing.XS, ComponentUtils.createLabel(PUBLISHER_NAME, "text-subtitle"),
                                                                              website));
        VBox privacy = ComponentUtils.createColumn(Spacing.XS,
                                                   ComponentUtils.createLabel(texts.get("about.privacy.title"), "text-heading"),
                                                   ComponentUtils.createParagraph(texts.get("about.privacy.address"), "text-caption"),
                                                   ComponentUtils.createParagraph(texts.get("about.privacy.shared"), "text-caption"),
                                                   ComponentUtils.createParagraph(texts.get("about.privacy.files"), "text-caption"));
        VBox content = ComponentUtils.createColumn(Spacing.M,
                                                   publisher,
                                                   new Separator(),
                                                   ComponentUtils.createParagraph(texts.get("about.license"), "text-caption"),
                                                   ComponentUtils.createParagraph(texts.get("about.fonts"), "text-caption"),
                                                   new Separator(),
                                                   privacy);
        VBox modNotices = createModNotices(texts, mods);
        ScrollPane scroll = new ScrollPane(content);
        Button close = ComponentUtils.createButton(texts.get("about.close"), ButtonRole.SECONDARY);

        this.shell  = shell;
        this.dialog = new ModalDialog(texts.get("about.title"), ModalDialog.Width.WIDE);

        website.setOnAction(event -> openWebsite.accept(PUBLISHER_WEBSITE));

        if (!modNotices.getChildren().isEmpty()) {
            content.getChildren().addAll(new Separator(), modNotices);
        }

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Sizes the viewport to the content's height at the viewport width.
        scroll.prefViewportHeightProperty().bind(Bindings.createDoubleBinding(() -> content.prefHeight(scroll.getViewportBounds().getWidth()),
                                                                              scroll.viewportBoundsProperty()));
        close.setOnAction(event -> this.shell.closeDialog(this.dialog));

        this.dialog.addContent(header, new Separator(), scroll);
        this.dialog.setButtons(close, null);
    }

    /**
     * Opens the dialog.
     */
    public void open() {
        this.shell.showDialog(this.dialog);
    }
}
