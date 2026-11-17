package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the frame shared by every launcher view: the grid backdrop under the fog vignette, the app bar, the content area and the status bar.
 * On the login step the app bar holds the brand and About only; once logged in it also holds the navigation and, at its right end, the
 * account with its status, which opens the account menu. The menu opens below the app bar over the whole window, a context menu at the
 * pointer; either closes on a click outside it or Escape. Dialogs open over the whole content area.
 */
public final class LauncherShell {
    /**
     * The style class of the margin between the content area's edges and a screen.
     */
    public static final @NotNull String CONTENT_MARGIN_STYLE_CLASS = "app-content";

    private static final @NotNull String PUBLISHER_NAME = "Fog of War Labs";
    private static final @NotNull String PRODUCT_NAME = "SC4J";

    /**
     * Lists the screens of the navigation.
     */
    public enum Section {
        /**
         * The mod list with the selected mod's details and settings.
         */
        MODS("nav.mods"),
        /**
         * The last run: its state, failure report and output.
         */
        RUN("nav.run"),
        /**
         * The Beacon.Link chat: channels, whispers and channel members.
         */
        CHAT("nav.chat"),
        /**
         * The launcher's own settings: UI language and Beacon.Link nodes.
         */
        SETTINGS("nav.settings");

        private final @NotNull String textKey;

        /**
         * Creates a {@link Section} instance.
         *
         * @param textKey the key of the navigation text
         */
        Section(@NotNull String textKey) {
            this.textKey = textKey;
        }

        /**
         * @return the key of the navigation text
         */
        public @NotNull String getTextKey() {
            return this.textKey;
        }
    }

    private static void setShown(@NotNull Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }


    private final @NotNull StackPane root;
    private final @NotNull StackPane contentArea;
    private final @NotNull StackPane screenArea;
    private final @NotNull ScrollPane screenScroll;
    private final @NotNull HBox navigation;
    private final @NotNull Map<@NotNull Section, @NotNull ToggleButton> navigationButtons;
    private final @NotNull Button accountButton;
    private final @NotNull Label catalogStatus;
    private final @NotNull Label nodeStatus;
    private final @NotNull HBox appBar;

    private @Nullable Pane menuLayer;

    /**
     * Creates a {@link LauncherShell} instance, showing the login step's app bar until {@link #show} shows a screen.
     *
     * @param texts       the launcher texts of the display language
     * @param install     the install, named in the status bar
     * @param imageScale  the screen pixels per logical unit the logos are decoded for
     * @param navigate    called with the section a navigation button selects
     * @param about       called when the About button is pressed
     * @param accountMenu called when the account button is pressed, to open the account menu
     */
    public LauncherShell(@NotNull LauncherTexts texts, @NotNull InstallLayout install, double imageScale,
                         @NotNull Consumer<@NotNull Section> navigate, @NotNull Runnable about, @NotNull Runnable accountMenu) {
        BorderPane frame = new BorderPane();
        Region fog = new Region();

        this.root              = new StackPane(fog, frame);
        this.contentArea       = new StackPane();
        this.screenArea        = new StackPane();
        this.screenScroll      = new ScrollPane(this.screenArea);
        this.navigation        = new HBox();
        this.navigationButtons = new EnumMap<>(Section.class);
        this.accountButton     = ComponentUtils.createSmallButton("", ButtonRole.SECONDARY);
        this.catalogStatus     = ComponentUtils.createLabel("");
        this.nodeStatus        = ComponentUtils.createLabel("");
        this.appBar            = this.createAppBar(texts, imageScale, navigate, about, accountMenu);

        this.menuLayer = null;

        fog.getStyleClass().add("app-fog");
        fog.setMouseTransparent(true);
        this.root.getStyleClass().add("app-backdrop");
        this.screenArea.getStyleClass().add(CONTENT_MARGIN_STYLE_CLASS);
        // A screen fills the area between the app bar and the status bar and scrolls when it needs more height than the window gives it.
        this.screenScroll.setFitToWidth(true);
        this.screenScroll.setFitToHeight(true);
        this.screenScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        frame.setTop(this.appBar);
        frame.setCenter(this.contentArea);
        frame.setBottom(this.createStatusBar(texts, install, imageScale));
        this.setLoggedIn(false);
    }

    /**
     * @return the root node of the window's scene
     */
    public @NotNull StackPane getRoot() {
        return this.root;
    }

    /**
     * Shows a screen of the logged-in launcher: shows the navigation and the account, marks the screen's navigation button and replaces the
     * content.
     *
     * @param section the screen's section
     * @param content the screen's content
     */
    public void show(@NotNull Section section, @NotNull Node content) {
        for (Map.Entry<Section, ToggleButton> entry : this.navigationButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == section);
        }

        this.setLoggedIn(true);
        this.screenArea.getChildren().setAll(content);
        this.contentArea.getChildren().setAll(this.screenScroll);
    }

    /**
     * Shows the login step: hides the navigation, the account and its menu, and replaces the content. The login step fills the whole
     * content area, so that its own dialogs dim all of it; it keeps the content margin itself ({@link #CONTENT_MARGIN_STYLE_CLASS}).
     *
     * @param content the login step's content
     */
    public void showLogin(@NotNull Node content) {
        this.closeMenu();
        this.setLoggedIn(false);
        this.contentArea.getChildren().setAll(content);
    }

    /**
     * Shows the logged-in account in the app bar.
     *
     * @param taggedName the account's name and tag ({@code Name#abcd12})
     * @param tone       the colour of the account's status
     */
    public void setAccount(@NotNull String taggedName, @NotNull Tone tone) {
        this.accountButton.setText(taggedName);
        this.accountButton.setGraphic(ComponentUtils.createStatusMarker(tone));
    }

    /**
     * Sets the status bar texts.
     *
     * @param catalogText the summary of the mod list
     * @param nodeText    the summary of the Beacon.Link node list
     */
    public void setStatus(@NotNull String catalogText, @NotNull String nodeText) {
        this.catalogStatus.setText(catalogText);
        this.nodeStatus.setText(nodeText);
    }

    /**
     * Opens a menu under the right end of the app bar, over the whole window, replacing an open menu. A click outside the menu or Escape
     * closes it.
     *
     * @param menu the menu
     */
    public void openMenu(@NotNull Region menu) {
        StackPane layer = new StackPane(menu);

        this.closeMenu();
        menu.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        layer.setAlignment(Pos.TOP_RIGHT);
        layer.paddingProperty().bind(Bindings.createObjectBinding(() -> new Insets(this.appBar.getHeight(), this.appBar.getPadding().getRight(), 0, 0),
                                                                  this.appBar.heightProperty(), this.appBar.paddingProperty()));
        this.showMenuLayer(layer);
    }

    /**
     * Opens a context menu at a point of the window, over the whole window, replacing an open menu; a menu that would cross the window's
     * right or bottom edge opens to the left of or above the point. A click outside the menu or Escape closes it.
     *
     * @param menu   the menu
     * @param sceneX the point's x in the window's scene
     * @param sceneY the point's y in the window's scene
     */
    public void openMenu(@NotNull Region menu, double sceneX, double sceneY) {
        Pane layer = new Pane(menu);

        this.closeMenu();
        layer.layoutBoundsProperty().addListener((observable, previous, current) -> {
            double width = menu.prefWidth(-1);
            double height = menu.prefHeight(width);

            menu.relocate(sceneX + width > current.getWidth() ? Math.max(0, sceneX - width) : sceneX,
                          sceneY + height > current.getHeight() ? Math.max(0, sceneY - height) : sceneY);
        });
        menu.relocate(sceneX, sceneY);
        this.showMenuLayer(layer);
    }

    /**
     * Shows a menu's layer over the whole window; a click on the layer outside the menu or Escape closes it.
     */
    private void showMenuLayer(@NotNull Pane layer) {
        layer.getStyleClass().add("menu-layer");
        layer.setOnMousePressed(event -> {
            if (event.getTarget() == layer) {
                this.closeMenu();
            }
        });
        layer.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                this.closeMenu();
            }
        });

        this.menuLayer = layer;
        this.root.getChildren().add(layer);
        layer.requestFocus();
    }

    /**
     * Closes the open menu, if any.
     */
    public void closeMenu() {
        Pane layer = this.menuLayer;

        if (layer != null) {
            this.menuLayer = null;
            this.root.getChildren().remove(layer);
        }
    }

    /**
     * Shows a dialog over the whole content area, its margin included; showing another screen removes it.
     *
     * @param dialog the dialog
     */
    public void showDialog(@NotNull ModalDialog dialog) {
        this.contentArea.getChildren().add(dialog.getRoot());
    }

    /**
     * Removes a dialog from the content area.
     *
     * @param dialog the dialog
     */
    public void closeDialog(@NotNull ModalDialog dialog) {
        this.contentArea.getChildren().remove(dialog.getRoot());
    }

    private void setLoggedIn(boolean loggedIn) {
        setShown(this.navigation, loggedIn);
        setShown(this.accountButton, loggedIn);
    }

    private @NotNull HBox createAppBar(@NotNull LauncherTexts texts, double imageScale,
                                       @NotNull Consumer<@NotNull Section> navigate, @NotNull Runnable about, @NotNull Runnable accountMenu) {
        VBox brand = new VBox(ComponentUtils.createLabel(PRODUCT_NAME, "app-title"), ComponentUtils.createLabel(texts.get("app.subtitle"), "app-subtitle"));
        Region gap = new Region();
        ToggleGroup group = new ToggleGroup();
        Button aboutButton = ComponentUtils.createSmallButton(texts.get("app.about"), ButtonRole.SECONDARY);
        HBox bar;

        brand.getStyleClass().add("app-brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setMinWidth(Region.USE_PREF_SIZE);
        gap.getStyleClass().add("app-bar-gap");

        for (Section section : Section.values()) {
            ToggleButton button = new ToggleButton(texts.get(section.getTextKey()));

            button.getStyleClass().add("nav-button");
            button.setToggleGroup(group);
            // A toggle button would deselect itself on a second click; navigation keeps the current screen selected instead.
            button.setOnAction(event -> navigate.accept(section));
            this.navigation.getChildren().add(button);
            this.navigationButtons.put(section, button);
        }

        aboutButton.setOnAction(event -> about.run());
        this.accountButton.getStyleClass().add("account-button");
        this.accountButton.setMinWidth(Region.USE_PREF_SIZE);
        this.accountButton.setOnAction(event -> accountMenu.run());

        bar = new HBox(ComponentUtils.createImageView(ThemeUtils.loadImage(ThemeUtils.SC4J_LOGO, IconSize.M.getSize(), imageScale), IconSize.M.getSize()),
                       brand, gap, this.navigation,
                       ComponentUtils.createGrowingSpacer(), ComponentUtils.createLabel(texts.format("app.engine", EngineVersion.CURRENT), "text-caption"),
                       aboutButton, this.accountButton);
        bar.getStyleClass().add("app-bar");

        return bar;
    }

    private @NotNull HBox createStatusBar(@NotNull LauncherTexts texts, @NotNull InstallLayout install, double imageScale) {
        double logoSize = IconSize.S.getSize();
        HBox publisher = ComponentUtils.createRow(Spacing.XS,
                                                  ComponentUtils.createImageView(ThemeUtils.loadImage(ThemeUtils.FOG_OF_WAR_LABS_LOGO, logoSize, imageScale),
                                                                                 logoSize),
                                                  ComponentUtils.createLabel(PUBLISHER_NAME));
        HBox bar = new HBox(ComponentUtils.createLabel(texts.format("status.install", install.getRoot())), this.catalogStatus, this.nodeStatus,
                            ComponentUtils.createGrowingSpacer(), publisher);

        bar.getStyleClass().add("status-bar");

        return bar;
    }
}
