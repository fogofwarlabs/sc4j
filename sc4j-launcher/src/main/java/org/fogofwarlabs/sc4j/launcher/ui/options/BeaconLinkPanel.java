package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.BootstrapState;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeStatus;
import org.fogofwarlabs.sc4j.launcher.peer.NodeRole;
import org.fogofwarlabs.sc4j.launcher.peer.NodeSettings;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Shows the Beacon.Link panel of the Settings screen: the node setting ("Help other players connect", the automatic role, with a short help
 * text), the port mapping setting (the router opens the ports of the node and of the games) and the node's UDP port, applied with Apply,
 * which restarts the launcher's node, and the node's status, refreshed every second while the panel is shown: how far it
 * joined the network and its peer counts, in the automatic role also its reachability from the Internet and its local and external
 * addresses, or that another launcher runs the node. Keys are automatic and never shown; the Peers button shows and hides the peer list.
 */
final class BeaconLinkPanel {
    private static final @NotNull Duration REFRESH_INTERVAL = Duration.seconds(1);
    private static final @NotNull List<@NotNull String> TONE_STYLES = List.of("text-success", "text-warning", "text-danger", "text-caption");

    private static void setText(@NotNull Label label, @NotNull String text, @NotNull Tone tone) {
        label.setText(text);
        label.getStyleClass().removeAll(TONE_STYLES);
        label.getStyleClass().add(OptionsScreen.getToneStyle(tone));
    }

    private static void setShown(@NotNull Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static @NotNull Tone getTone(@NotNull BootstrapState state) {
        return switch (state) {
            case CONNECTED -> Tone.SUCCESS;
            case CONNECTING -> Tone.ACCENT;
            case NO_PEERS_KNOWN -> Tone.WARNING;
            case NO_PEER_REACHABLE -> Tone.DANGER;
        };
    }

    private static @NotNull Tone getTone(@NotNull Reachability reachability) {
        return switch (reachability) {
            case REACHABLE -> Tone.SUCCESS;
            case NOT_VERIFIED -> Tone.WARNING;
            case NOT_REACHABLE -> Tone.DANGER;
        };
    }


    private final @NotNull LauncherTexts texts;
    private final @NotNull LauncherNodeRunner runner;
    private final @NotNull Node advanced;

    private final @NotNull CheckBox helping;
    private final @NotNull CheckBox mapping;
    private final @NotNull TextField port;
    private final @NotNull Label message;
    private final @NotNull Label state;
    private final @NotNull Label reachability;
    private final @NotNull Label addresses;
    private final @NotNull Label peers;
    private final @NotNull Button advancedToggle;
    private final @NotNull VBox root;
    private final @NotNull Timeline refresh;

    /**
     * Creates a {@link BeaconLinkPanel} instance.
     *
     * @param texts    the launcher texts of the display language
     * @param runner   the launcher's node
     * @param advanced the seed list view that the Peers button shows and hides; hidden at first
     */
    BeaconLinkPanel(@NotNull LauncherTexts texts, @NotNull LauncherNodeRunner runner, @NotNull Node advanced) {
        NodeSettings saved = runner.getSettings();
        Button apply = ComponentUtils.createButton(texts.get("beaconlink.apply"), ButtonRole.PRIMARY);
        Label help = ComponentUtils.createParagraph(texts.get("beaconlink.node.help"), "text-caption");
        HBox portRow;
        VBox settings;
        VBox status;

        this.texts    = texts;
        this.runner   = runner;
        this.advanced = advanced;

        this.helping        = new CheckBox(texts.get("beaconlink.node"));
        this.mapping        = new CheckBox(texts.get("beaconlink.port.mapping"));
        this.port           = new TextField(Integer.toString(saved.getPort()));
        this.message        = ComponentUtils.createParagraph("", "field-message");
        this.state          = ComponentUtils.createParagraph("", "list-title");
        this.reachability   = ComponentUtils.createParagraph("", "text-caption");
        this.addresses      = ComponentUtils.createLabel("", "text-caption", "text-mono");
        this.peers          = ComponentUtils.createLabel("", "text-caption");
        this.advancedToggle = ComponentUtils.createSmallButton(texts.get("beaconlink.advanced.show"), ButtonRole.SECONDARY);

        this.helping.setSelected(saved.getRole() == NodeRole.AUTOMATIC);
        this.mapping.setSelected(saved.isPortMapping());
        ComponentUtils.setControlWidth(this.port, ControlWidth.S);
        setShown(this.message, false);
        setShown(this.advanced, false);
        apply.setOnAction(event -> this.apply());
        this.advancedToggle.setOnAction(event -> this.toggleAdvanced());

        portRow = ComponentUtils.createRow(Spacing.M, ComponentUtils.createLabel(texts.get("beaconlink.port"), "field-label"), this.port, apply);
        portRow.setAlignment(Pos.CENTER_LEFT);
        settings = ComponentUtils.createColumn(Spacing.S, this.helping, help, this.mapping, portRow);
        status   = ComponentUtils.createColumn(Spacing.XS, this.state, this.reachability, this.addresses, this.peers);

        this.root = ComponentUtils.createPanel(texts.get("beaconlink.title"),
                                               ComponentUtils.createPanelBody(settings, this.message, status),
                                               this.advancedToggle);

        this.refresh = new Timeline(new KeyFrame(REFRESH_INTERVAL, event -> this.showStatus()));

        this.refresh.setCycleCount(Timeline.INDEFINITE);
        this.root.sceneProperty().addListener((observable, previous, current) -> {
            if (current == null) {
                this.refresh.stop();
            } else {
                this.refresh.play();
            }
        });
        this.showStatus();
    }

    /**
     * @return the panel's root node
     */
    @NotNull VBox getRoot() {
        return this.root;
    }


    private void apply() {
        int chosenPort = ConfiguredNode.parsePort(this.port.getText().strip());

        this.port.pseudoClassStateChanged(ThemeUtils.INVALID, chosenPort < 0);

        if (chosenPort < 0) {
            this.showMessage(this.texts.format("beaconlink.port.invalid", ConfiguredNode.MINIMUM_PORT, ConfiguredNode.MAXIMUM_PORT));

            return;
        }

        NodeRole role = this.helping.isSelected() ? NodeRole.AUTOMATIC : NodeRole.OFF;
        String problem = this.runner.changeSettings(new NodeSettings(role, chosenPort, this.mapping.isSelected()));

        this.showMessage(problem == null ? null : this.texts.format("beaconlink.apply.failed", problem));
        this.showStatus();
    }

    private void toggleAdvanced() {
        boolean shown = !this.advanced.isVisible();

        setShown(this.advanced, shown);
        this.advancedToggle.setText(this.texts.get(shown ? "beaconlink.advanced.hide" : "beaconlink.advanced.show"));
    }

    private void showStatus() {
        this.showStatus(this.runner.getStatus(), this.runner.isHeldElsewhere(), this.runner.getProblem());
    }

    /**
     * Shows a state of the launcher's node: how far it joined the network and its peer counts, and in the automatic role also its
     * reachability and its addresses; or that another launcher runs the node, or why it does not run.
     *
     * @param status        the node's state, or {@code null} if the node does not run in this launcher
     * @param heldElsewhere whether another launcher of the install runs the node
     * @param problem       why the node does not run, or {@code null}
     */
    void showStatus(@Nullable LauncherNodeStatus status, boolean heldElsewhere, @Nullable String problem) {
        if (status == null) {
            if (heldElsewhere) {
                setText(this.state, this.texts.get("beaconlink.elsewhere"), Tone.NEUTRAL);
            } else {
                setText(this.state, this.texts.format("beaconlink.stopped", problem == null ? "" : problem), Tone.DANGER);
            }

            setShown(this.reachability, false);
            setShown(this.addresses, false);
            setShown(this.peers, false);

            return;
        }

        boolean automatic = status.getSettings().getRole() == NodeRole.AUTOMATIC;

        setText(this.state, this.texts.describeBootstrapState(status.getBootstrapState()), getTone(status.getBootstrapState()));
        this.peers.setText(this.texts.format("beaconlink.peers", status.getLivePeerCount(), status.getLearnedPeerCount()));
        setShown(this.peers, true);
        setShown(this.reachability, automatic);
        setShown(this.addresses, automatic);

        if (!automatic) {
            return;
        }

        InetSocketAddress observed = status.getObservedAddress();
        String observedText = observed == null ? this.texts.get("beaconlink.observed.unknown") : LauncherTexts.describeAddress(observed);

        setText(this.reachability, this.texts.describeReachability(status.getReachability(), status.getLocalAddress().getPort()),
                getTone(status.getReachability()));
        this.addresses.setText(this.texts.format("beaconlink.addresses", LauncherTexts.describeAddress(status.getLocalAddress()), observedText));
    }

    private void showMessage(@Nullable String text) {
        this.message.setText(text == null ? "" : text);
        setShown(this.message, text != null);
    }
}
