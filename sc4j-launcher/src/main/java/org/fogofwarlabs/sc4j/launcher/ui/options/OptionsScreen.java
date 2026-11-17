package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListProblem;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeEntryCheck;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeEntryProblem;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeListEditor;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Shows the launcher's Settings screen: the UI language (the system language or an explicit choice) and the update ({@link UpdateView}),
 * the Beacon.Link panel
 * ({@link BeaconLinkPanel}: the node setting and the launcher node's status; keys are automatic and not shown) and, behind its Seeds button,
 * the seed list editor (the node list). The editor lists the seeds in the order the client contacts them and has the host and port fields
 * under the list: Add, Update and Remove change entries, Move up and Move down change the order. Field problems are shown with their
 * reasons; every change is saved at once through {@link Actions#onNodesChanged(List)}. Ping checks every listed seed and shows each result
 * in the seed's row: the round-trip delay, the node version and the address the node observed, or why it did not answer.
 */
public final class OptionsScreen {
    /**
     * The width of a port field.
     */

    private static final @NotNull String LANGUAGE_NAME_KEY_PREFIX = "language.";
    private static final @NotNull String MESSAGE_SEPARATOR = " ";

    /**
     * Receives the changes made on the Settings screen.
     */
    public interface Actions {
        /**
         * Called when the user chooses another UI language.
         *
         * @param language the chosen language, or {@code null} to follow the operating system
         */
        void onLanguageChanged(@Nullable UiLanguage language);

        /**
         * Called when the user changes the node list; the launcher saves it.
         *
         * @param nodes the new node list
         * @return the reason the list could not be saved, or {@code null} when it was saved
         */
        @Nullable String onNodesChanged(@NotNull List<@NotNull ConfiguredNode> nodes);

        /**
         * Called when the user pings the listed nodes; the launcher pings them away from the user interface thread and delivers the results
         * on it.
         *
         * @param nodes   the nodes to ping
         * @param results receives one result per node, in the order of the nodes, on the user interface thread
         */
        void onPingNodes(@NotNull List<@NotNull ConfiguredNode> nodes, @NotNull Consumer<@NotNull List<@NotNull NodePingResult>> results);
    }

    /**
     * Holds one entry of the language choice: the system language or an explicit language.
     */
    private static final class LanguageOption {
        private final @Nullable UiLanguage language;
        private final @NotNull String name;

        /**
         * Creates a {@link LanguageOption} instance.
         *
         * @param language the explicit language, or {@code null} for the system language
         * @param name     the shown name
         */
        private LanguageOption(@Nullable UiLanguage language, @NotNull String name) {
            this.language = language;
            this.name     = name;
        }

        /**
         * {@return a string representation of the object}
         *
         * @implSpec Returns the shown name.
         */
        @Override
        public @NotNull String toString() {
            return this.name;
        }
    }

    /**
     * Returns the text style of a colour role.
     *
     * @param tone the colour role
     * @return the style class of a caption text in that colour
     */
    static @NotNull String getToneStyle(@NotNull Tone tone) {
        return switch (tone) {
            case SUCCESS -> "text-success";
            case WARNING -> "text-warning";
            case DANGER -> "text-danger";
            case NEUTRAL, ACCENT -> "text-caption";
        };
    }


    private final @NotNull LauncherTexts texts;
    private final @NotNull Actions actions;
    private final @NotNull NodeListEditor editor;
    private final @NotNull ListView<ConfiguredNode> nodeList;
    private final @NotNull TextField host;
    private final @NotNull TextField port;
    private final @NotNull Label message;
    private final @NotNull Label nodeCount;
    private final @NotNull Button ping;
    private final @NotNull VBox root;

    private final @NotNull Map<@NotNull ConfiguredNode, @NotNull NodePingResult> pingResults;
    private boolean pinging;

    /**
     * Creates a {@link OptionsScreen} instance.
     *
     * @param texts            the launcher texts of the display language
     * @param language         the language chosen in the launcher settings, or {@code null} when the launcher follows the
     *                         operating system
     * @param updateView       the update part of the launcher panel
     * @param nodes            the saved node list, or the shipped seed list while the user has saved none
     * @param nodesLoadProblem why that list could not be read, or {@code null}
     * @param nodeRunner       the launcher's Beacon.Link node
     * @param actions          receives the changes
     */
    public OptionsScreen(@NotNull LauncherTexts texts, @Nullable UiLanguage language, @NotNull UpdateView updateView,
                         @NotNull List<@NotNull ConfiguredNode> nodes, @Nullable NodeListProblem nodesLoadProblem, @NotNull LauncherNodeRunner nodeRunner,
                         @NotNull Actions actions) {
        VBox nodesPanel;

        this.texts     = texts;
        this.actions   = actions;
        this.editor    = new NodeListEditor(nodes);
        this.nodeList  = ComponentUtils.listView(ListItemSize.REGULAR);
        this.host      = new TextField();
        this.port      = new TextField();
        this.message   = ComponentUtils.label("", "field-message");
        this.nodeCount = ComponentUtils.label(texts.formatCount("nodes.count", nodes.size()), "text-caption");
        this.ping      = ComponentUtils.smallButton(texts.get("nodes.ping"), ButtonRole.SECONDARY);

        this.nodeList.getItems().setAll(nodes);
        this.nodeList.setCellFactory(view -> new ListItemCell<>(this::createNodeItem));
        this.nodeList.setPlaceholder(ComponentUtils.paragraph(texts.get("nodes.empty"), "text-caption"));
        this.nodeList.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                this.host.setText(current.getHost());
                this.port.setText(Integer.toString(current.getPort()));
            }
        });
        VBox.setVgrow(this.nodeList, Priority.ALWAYS);

        nodesPanel = this.createNodesPanel(nodesLoadProblem);
        VBox.setVgrow(nodesPanel, Priority.ALWAYS);

        this.root = ComponentUtils.column(Spacing.L, this.createLauncherPanel(language, updateView),
                                          new BeaconLinkPanel(texts, nodeRunner, nodesPanel).getRoot(), nodesPanel);

        this.pingResults = new HashMap<>();
        this.pinging     = false;
    }

    /**
     * @return the screen's root node
     */
    public @NotNull VBox getRoot() {
        return this.root;
    }

    /**
     * Shows the results of a ping in the rows of their nodes and enables Ping again.
     *
     * @param results the ping results, one per pinged node
     */
    public void showPingResults(@NotNull List<@NotNull NodePingResult> results) {
        this.pinging = false;
        this.ping.setDisable(false);

        for (NodePingResult result : results) {
            this.pingResults.put(result.getNode(), result);
        }

        this.nodeList.refresh();
    }

    private @NotNull VBox createLauncherPanel(@Nullable UiLanguage language, @NotNull UpdateView updateView) {
        ComboBox<LanguageOption> choice = ComponentUtils.comboBox(ControlWidth.M);
        UiLanguage system = UiLanguage.select(null, Locale.getDefault());
        LanguageOption systemOption = new LanguageOption(null, this.texts.format("language.system", this.getLanguageName(system)));
        HBox row;

        choice.getItems().add(systemOption);
        choice.setValue(systemOption);

        for (UiLanguage item : UiLanguage.values()) {
            LanguageOption option = new LanguageOption(item, this.getLanguageName(item));

            choice.getItems().add(option);

            if (item == language) {
                choice.setValue(option);
            }
        }

        choice.valueProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                this.actions.onLanguageChanged(current.language);
            }
        });

        row = ComponentUtils.row(Spacing.XL, ComponentUtils.field(this.texts.get("settings.language"), choice), updateView.getRoot());
        row.setAlignment(Pos.BOTTOM_LEFT);

        return ComponentUtils.panel(this.texts.get("settings.launcher"), ComponentUtils.panelBody(row));
    }

    private @NotNull String getLanguageName(@NotNull UiLanguage language) {
        return this.texts.get(LANGUAGE_NAME_KEY_PREFIX + language.getCode());
    }

    private @NotNull VBox createNodesPanel(@Nullable NodeListProblem nodesLoadProblem) {
        VBox body = ComponentUtils.flushPanelBody(this.nodeList);
        VBox panel;

        if (nodesLoadProblem != null) {
            String problem = this.texts.describeNodeListProblem(nodesLoadProblem);

            body.getChildren().addFirst(ComponentUtils.notice(Tone.DANGER, this.texts.format("nodes.load.failed", problem)));
        }

        this.ping.setDisable(this.editor.getNodes().isEmpty());
        this.ping.setOnAction(event -> this.pingNodes());
        panel = ComponentUtils.panelWithFooter(this.texts.get("nodes.title"), body, ComponentUtils.panelFooter(this.createEditor()),
                                               this.nodeCount, this.ping);
        ComponentUtils.fillHeight(panel);

        return panel;
    }

    private @NotNull VBox createEditor() {
        VBox hostField = ComponentUtils.field(this.texts.get("nodes.host"), this.host);
        VBox portField = ComponentUtils.field(this.texts.get("nodes.port"), this.port);
        Button add = ComponentUtils.button(this.texts.get("nodes.add"), ButtonRole.PRIMARY);
        Button update = ComponentUtils.button(this.texts.get("nodes.update"), ButtonRole.SECONDARY);
        Button remove = ComponentUtils.button(this.texts.get("nodes.remove"), ButtonRole.DANGER);
        Button moveUp = ComponentUtils.button(this.texts.get("nodes.move.up"), ButtonRole.SECONDARY);
        Button moveDown = ComponentUtils.button(this.texts.get("nodes.move.down"), ButtonRole.SECONDARY);
        HBox fields = ComponentUtils.row(Spacing.S, hostField, portField);
        HBox buttons = ComponentUtils.row(Spacing.S, add, update, remove, ComponentUtils.growingSpacer(), moveUp, moveDown);
        VBox editorView = ComponentUtils.column(Spacing.S, fields, buttons, this.message);

        ComponentUtils.setControlWidth(this.port, ControlWidth.S);
        this.message.setWrapText(true);
        this.message.setVisible(false);
        this.message.setManaged(false);
        update.disableProperty().bind(this.nodeList.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(this.nodeList.getSelectionModel().selectedItemProperty().isNull());
        moveUp.disableProperty().bind(this.nodeList.getSelectionModel().selectedItemProperty().isNull());
        moveDown.disableProperty().bind(this.nodeList.getSelectionModel().selectedItemProperty().isNull());

        add.setOnAction(event -> {
            ConfiguredNode node = this.readEntry(NodeListEditor.NEW_ENTRY);

            if (node != null) {
                this.saveNodes(this.editor.add(node), this.editor.getNodes().size());
            }
        });
        update.setOnAction(event -> {
            int index = this.nodeList.getSelectionModel().getSelectedIndex();
            ConfiguredNode node = index < 0 ? null : this.readEntry(index);

            if (node != null) {
                this.saveNodes(this.editor.replace(index, node), index);
            }
        });
        remove.setOnAction(event -> {
            int index = this.nodeList.getSelectionModel().getSelectedIndex();

            if (index >= 0) {
                this.saveNodes(this.editor.remove(index), -1);
            }
        });
        moveUp.setOnAction(event -> this.moveSelected(-1));
        moveDown.setOnAction(event -> this.moveSelected(1));

        HBox.setHgrow(hostField, Priority.ALWAYS);
        HBox.setHgrow(editorView, Priority.ALWAYS);
        fields.setAlignment(Pos.BOTTOM_LEFT);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return editorView;
    }

    private void pingNodes() {
        List<ConfiguredNode> nodes = this.editor.getNodes();

        if (this.pinging || nodes.isEmpty()) {
            return;
        }

        this.pinging = true;
        this.ping.setDisable(true);
        this.pingResults.clear();
        this.nodeList.refresh();
        this.actions.onPingNodes(nodes, this::showPingResults);
    }

    /**
     * Creates the list item of a node: its address and the result of the last ping.
     */
    private @NotNull ListItem createNodeItem(@NotNull ConfiguredNode node) {
        NodePingResult result = this.pingResults.get(node);
        Tone tone = this.getPingTone(result);
        ListItem item = new ListItem(node.toString()).setMonospaced().setMarker(tone);

        if (result != null) {
            item.addCaption(this.texts.describeNodePing(result), getToneStyle(tone));
        } else if (this.pinging) {
            item.addCaption(this.texts.get("nodes.ping.running"));
        }

        return item;
    }

    private @NotNull Tone getPingTone(@Nullable NodePingResult result) {
        if (result == null) {
            return this.pinging ? Tone.ACCENT : Tone.NEUTRAL;
        }

        return switch (result.getStatus()) {
            case REACHABLE -> Tone.SUCCESS;
            case NOT_ANSWERING, UNRESOLVED -> Tone.WARNING;
            case FAILED -> Tone.DANGER;
        };
    }

    private void moveSelected(int offset) {
        int index = this.nodeList.getSelectionModel().getSelectedIndex();

        if (this.editor.canMove(index, index + offset)) {
            this.saveNodes(this.editor.move(index, index + offset), index + offset);
        }
    }

    private @Nullable ConfiguredNode readEntry(int editedIndex) {
        NodeEntryCheck check = this.editor.check(this.host.getText(), this.port.getText(), editedIndex);
        List<NodeEntryProblem> problems = check.getProblems();
        List<String> reasons = new ArrayList<>(problems.size());

        for (NodeEntryProblem problem : problems) {
            reasons.add(this.texts.describeNodeEntryProblem(problem));
        }

        this.host.pseudoClassStateChanged(ThemeUtils.INVALID, problems.contains(NodeEntryProblem.HOST_INVALID) ||
                                                              problems.contains(NodeEntryProblem.DUPLICATE));
        this.port.pseudoClassStateChanged(ThemeUtils.INVALID, problems.contains(NodeEntryProblem.PORT_INVALID) ||
                                                              problems.contains(NodeEntryProblem.DUPLICATE));
        this.showMessage(reasons.isEmpty() ? null : String.join(MESSAGE_SEPARATOR, reasons));

        return check.getNode();
    }

    private void saveNodes(@NotNull List<@NotNull ConfiguredNode> nodes, int selectedIndex) {
        String problem = this.actions.onNodesChanged(nodes);

        if (problem != null) {
            this.showMessage(this.texts.format("nodes.save.failed", problem));

            return;
        }

        this.editor.setNodes(nodes);
        this.pingResults.keySet().retainAll(nodes);
        this.ping.setDisable(this.pinging || nodes.isEmpty());
        this.nodeList.getItems().setAll(nodes);
        this.nodeCount.setText(this.texts.formatCount("nodes.count", nodes.size()));
        this.showMessage(null);

        if (selectedIndex >= 0 && selectedIndex < nodes.size()) {
            this.nodeList.getSelectionModel().select(selectedIndex);
        }
    }

    private void showMessage(@Nullable String text) {
        this.message.setText(text == null ? "" : text);
        this.message.setVisible(text != null);
        this.message.setManaged(text != null);
    }
}
