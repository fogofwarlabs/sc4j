package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.control.Button;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Shows the dialog that asks before a link from other players opens: the whole address, wrapped at any character, with Open and Cancel. Only
 * Open opens the address in the system browser; only {@code http} and {@code https} addresses ({@link TextLink#isWebAddress}) are taken.
 */
public final class LinkDialog {
    private static final @NotNull String ADDRESS_STYLE_CLASS = "link-address";

    private final @NotNull LauncherShell shell;
    private final @NotNull ModalDialog dialog;

    /**
     * Creates a {@link LinkDialog} instance.
     *
     * @param shell       shows the dialog
     * @param texts       the launcher texts of the display language
     * @param address     the address to open
     * @param openWebsite opens a web address in the system browser
     * @throws IllegalArgumentException if the address is not an {@code http} or {@code https} address
     */
    public LinkDialog(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull String address,
                      @NotNull Consumer<@NotNull String> openWebsite) {
        if (!TextLink.isWebAddress(address)) {
            throw Exceptions.newIllegalArgumentException("Not a web address: {0}", address);
        }

        Text addressText = new Text(address);
        TextFlow addressFlow = new TextFlow(addressText);
        Button cancel = ComponentUtils.createButton(texts.get("link.cancel"), ButtonRole.SECONDARY);
        Button open = ComponentUtils.createButton(texts.get("link.open"), ButtonRole.PRIMARY);

        this.shell  = shell;
        this.dialog = new ModalDialog(texts.get("link.title"));

        addressText.getStyleClass().add("text-mono");
        addressFlow.getStyleClass().add(ADDRESS_STYLE_CLASS);
        this.dialog.addContent(ComponentUtils.createParagraph(texts.get("link.text"), "text-body"), addressFlow);
        this.dialog.setButtons(cancel, open);
        cancel.setOnAction(event -> this.shell.closeDialog(this.dialog));
        open.setOnAction(event -> {
            this.shell.closeDialog(this.dialog);
            openWebsite.accept(address);
        });
    }

    /**
     * Opens the dialog.
     */
    public void open() {
        this.shell.showDialog(this.dialog);
    }
}
