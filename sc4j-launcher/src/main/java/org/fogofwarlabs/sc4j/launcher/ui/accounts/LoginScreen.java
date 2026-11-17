package org.fogofwarlabs.sc4j.launcher.ui.accounts;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerNameUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shows the login step, the launcher's first view and the only place to manage accounts: the accounts of the install on the left
 * ({@code Name#tag}, last use, the accounts a running game uses), the most recently used one selected, and the selected account on the right
 * with Export, Delete and Log in. New account and Import add an account and select it; Log in (or a double click on the account) logs in to
 * the selected account. An account a running game uses can be logged in to but not deleted. An account's name is fixed when it is made (its
 * player stamp binds the name to the key): another name is another account.
 * <p>
 * The work that takes time (the player stamp of a new key, about two to the power of its difficulty hashes, and the password key of a
 * protected backup) runs on a thread of its own; its dialog shows the progress and Cancel stops it. Its result (the new account, the imported
 * account, the saved backup) is written on the user interface thread only while the dialog is open, so Cancel always leaves the files as they
 * were. Problems are shown in one line.
 */
public final class LoginScreen {
    private static final double CONTENT_WIDTH = 960;
    private static final double CONTENT_HEIGHT = 520;
    private static final int DOUBLE_CLICK = 2;
    private static final @NotNull String FILE_NAME_SEPARATOR = "-";

    /**
     * Receives what the login step asks of the window.
     */
    public interface Actions {
        /**
         * Called when the user logs in to an account.
         *
         * @param account the account, with its use recorded
         */
        void onLogIn(@NotNull Account account);

        /**
         * Asks the user for the file to import.
         *
         * @return the chosen file, or {@code null} if the user cancelled
         */
        @Nullable Path chooseImportFile();

        /**
         * Asks the user where to save a backup.
         *
         * @param fileName the suggested file name
         * @return the chosen file, or {@code null} if the user cancelled
         */
        @Nullable Path chooseExportFile(@NotNull String fileName);
    }

    private final @NotNull AccountStore store;
    private final @NotNull LauncherTexts texts;
    private final @NotNull Actions actions;
    private final @NotNull SecureRandom random;
    private final @NotNull DateTimeFormatter dateFormat;

    private final @NotNull ListView<Account> list;
    private final @NotNull VBox notices;
    private final @NotNull VBox detailBody;
    private final @NotNull Button exportButton;
    private final @NotNull Button deleteButton;
    private final @NotNull Button logInButton;
    private final @NotNull StackPane root;

    private final @NotNull Set<@NotNull String> inUse;
    private @Nullable ModalDialog dialog;

    /**
     * Creates a {@link LoginScreen} instance and reads the accounts; the most recently used account is selected.
     *
     * @param store   the install's accounts
     * @param texts   the launcher texts of the display language
     * @param actions receives the login and the file questions
     */
    public LoginScreen(@NotNull AccountStore store, @NotNull LauncherTexts texts, @NotNull Actions actions) {
        Button create = ComponentUtils.smallButton(texts.get("accounts.new"), ButtonRole.SECONDARY);
        Button importButton = ComponentUtils.smallButton(texts.get("accounts.import"), ButtonRole.SECONDARY);
        VBox listPanel;
        VBox detailPanel;
        VBox detailColumn;
        HBox content;
        StackPane page;

        this.store      = store;
        this.texts      = texts;
        this.actions    = actions;
        this.random     = new SecureRandom();
        this.dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(texts.getLocale()).withZone(ZoneId.systemDefault());

        this.list         = ComponentUtils.listView(ListItemSize.REGULAR);
        this.notices      = ComponentUtils.column(Spacing.S);
        this.detailBody   = ComponentUtils.panelBody();
        this.exportButton = ComponentUtils.button(texts.get("accounts.export"), ButtonRole.SECONDARY);
        this.deleteButton = ComponentUtils.button(texts.get("accounts.delete"), ButtonRole.DANGER);
        this.logInButton  = ComponentUtils.button(texts.get("accounts.log.in"), ButtonRole.PRIMARY);
        this.root         = new StackPane();

        this.inUse  = new HashSet<>();
        this.dialog = null;

        this.list.setCellFactory(view -> new ListItemCell<>(this::createAccountItem));
        this.list.setPlaceholder(ComponentUtils.paragraph(texts.get("accounts.empty"), "text-caption"));
        this.list.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> this.showDetail(current));
        this.list.setOnMouseClicked(event -> {
            Account selected = this.list.getSelectionModel().getSelectedItem();

            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == DOUBLE_CLICK && selected != null) {
                this.logIn(selected);
            }
        });
        VBox.setVgrow(this.list, Priority.ALWAYS);

        create.setOnAction(event -> this.openNewAccount());
        importButton.setOnAction(event -> this.importAccount());
        this.exportButton.setOnAction(event -> this.withSelection(this::openExport));
        this.deleteButton.setOnAction(event -> this.withSelection(this::openDelete));
        this.logInButton.setOnAction(event -> this.withSelection(this::logIn));

        listPanel = ComponentUtils.panelWithFooter(texts.get("accounts.title"), ComponentUtils.flushPanelBody(this.list),
                                                   ComponentUtils.panelFooter(create, ComponentUtils.growingSpacer(), importButton));
        ComponentUtils.setColumnWidth(listPanel, ColumnWidth.WIDE);

        detailPanel = ComponentUtils.panelWithFooter(texts.get("accounts.detail"), this.detailBody,
                                                     ComponentUtils.panelFooter(this.exportButton, this.deleteButton, ComponentUtils.growingSpacer(),
                                                                                this.logInButton));
        ComponentUtils.fillHeight(detailPanel);
        VBox.setVgrow(detailPanel, Priority.ALWAYS);

        detailColumn = ComponentUtils.column(Spacing.M, this.notices, detailPanel);
        this.notices.setVisible(false);
        this.notices.setManaged(false);
        HBox.setHgrow(detailColumn, Priority.ALWAYS);

        content = ComponentUtils.row(Spacing.L, listPanel, detailColumn);
        content.setAlignment(Pos.TOP_LEFT);
        content.setMaxSize(CONTENT_WIDTH, CONTENT_HEIGHT);
        page = new StackPane(content);
        page.getStyleClass().add(LauncherShell.CONTENT_MARGIN_STYLE_CLASS);
        this.root.getChildren().add(page);

        this.refresh();
    }

    /**
     * @return the screen's root node
     */
    public @NotNull StackPane getRoot() {
        return this.root;
    }

    /**
     * Reads the accounts again, which a running game may hold, keeping the selection; without one the most recently used account is selected.
     */
    public void refresh() {
        Account selected = this.list.getSelectionModel().getSelectedItem();

        this.select(selected == null ? null : selected.getId());
    }

    /**
     * Opens the New account dialog: a name, which cannot be changed later, then a new key and the player stamp of the key and the name, whose
     * progress the dialog shows. The new account is selected.
     */
    public void openNewAccount() {
        ModalDialog shown = new ModalDialog(this.texts.get("accounts.new.title"));
        TextField name = new TextField();
        Button cancel = ComponentUtils.button(this.texts.get("accounts.cancel"), ButtonRole.SECONDARY);
        Button create = ComponentUtils.button(this.texts.get("accounts.create"), ButtonRole.PRIMARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        shown.addContent(ComponentUtils.field(this.texts.get("accounts.name"), name),
                         ComponentUtils.paragraph(this.texts.get("accounts.name.fixed"), "text-caption"));
        shown.setButtons(cancel, create);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.closeDialog(shown);
        });
        create.setOnAction(event -> {
            String checked = this.checkName(shown, name.getText());

            if (checked == null) {
                return;
            }

            IdentityKeyPair keyPair = IdentityKeyPair.generate(this.random);
            ModalDialog.Work<PlayerStamp> search = progress -> PlayerStamp.solve(keyPair.getPublicKey(), checked, PlayerStamp.DEFAULT_DIFFICULTY, share -> {
                progress.accept(share);

                return !cancelled.get();
            });

            name.setDisable(true);
            create.setDisable(true);
            shown.runWork(this.texts.get("accounts.creating"), cancelled, search, stamp -> {
                this.select(this.store.create(keyPair, stamp, PlayerStamp.DEFAULT_DIFFICULTY, System.currentTimeMillis()).getId());
                this.closeDialog(shown);
            }, this::describe, () -> {
                name.setDisable(false);
                create.setDisable(false);
            });
        });

        this.showDialog(shown);
        name.requestFocus();
    }

    private void importAccount() {
        Path file = this.actions.chooseImportFile();
        byte[] contents;

        if (file == null) {
            return;
        }

        try {
            contents = Files.size(file) > AccountBackup.MAXIMUM_LENGTH ? new byte[0] : Files.readAllBytes(file);
        } catch (IOException exception) {
            this.showNotice(Tone.DANGER, this.texts.format("accounts.failed", exception.getMessage()));

            return;
        }

        AccountBackup plain = null;

        if (!AccountBackup.isProtected(contents)) {
            try {
                plain = AccountBackup.read(contents, null);
            } catch (AccountException exception) {
                this.showNotice(Tone.DANGER, this.texts.describeAccountProblem(exception.getProblem()));

                return;
            }
        }

        this.openImport(contents, plain);
    }

    /**
     * Opens the Import dialog: the password of a protected backup, then the import of the account under the name it was made with. The
     * imported account is selected.
     */
    private void openImport(byte @NotNull [] contents, @Nullable AccountBackup plain) {
        ModalDialog shown = new ModalDialog(this.texts.get("accounts.import.title"));
        PasswordField password = new PasswordField();
        Button cancel = ComponentUtils.button(this.texts.get("accounts.cancel"), ButtonRole.SECONDARY);
        Button importButton = ComponentUtils.button(this.texts.get("accounts.import"), ButtonRole.PRIMARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        if (plain == null) {
            shown.addContent(ComponentUtils.field(this.texts.get("accounts.password"), password));
        } else {
            LinkPlayer player = LinkPlayer.of(plain.getKeyPair().getPublicKey().getBytes());

            shown.addContent(ComponentUtils.label(player.formatTaggedName(plain.getName()), "text-subtitle"));
        }

        shown.setButtons(cancel, importButton);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.closeDialog(shown);
        });
        importButton.setOnAction(event -> {
            char[] typed = password.getText().toCharArray();

            password.setDisable(true);
            importButton.setDisable(true);
            shown.runWork(this.texts.get("accounts.importing"), cancelled, progress -> {
                try {
                    return plain != null ? plain : AccountBackup.read(contents, typed);
                } finally {
                    Arrays.fill(typed, '\0');
                }
            }, backup -> {
                if (this.store.find(backup.getId()) != null) {
                    throw new AccountException(AccountProblem.ALREADY_ADDED, "The account is in the list.");
                }

                Account imported = this.store.restore(backup, System.currentTimeMillis());

                this.select(imported.getId());
                this.showNotice(Tone.SUCCESS, this.texts.format("accounts.imported", imported.getTaggedName()));
                this.closeDialog(shown);
            }, this::describe, () -> {
                password.setDisable(false);
                importButton.setDisable(false);
            });
        });

        this.showDialog(shown);

        if (plain == null) {
            password.requestFocus();
        } else {
            importButton.fire();
        }
    }

    private void openExport(@NotNull Account account) {
        ModalDialog shown = new ModalDialog(this.texts.get("accounts.export.title"));
        PasswordField password = new PasswordField();
        PasswordField repeated = new PasswordField();
        Button cancel = ComponentUtils.button(this.texts.get("accounts.cancel"), ButtonRole.SECONDARY);
        Button export = ComponentUtils.button(this.texts.get("accounts.export"), ButtonRole.PRIMARY);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        shown.addContent(ComponentUtils.label(account.getTaggedName(), "text-subtitle"),
                         ComponentUtils.field(this.texts.get("accounts.password.optional"), password),
                         ComponentUtils.field(this.texts.get("accounts.password.repeat"), repeated));
        shown.setButtons(cancel, export);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.closeDialog(shown);
        });
        export.setOnAction(event -> {
            if (!password.getText().equals(repeated.getText())) {
                shown.showMessage(this.texts.get("accounts.password.mismatch"));

                return;
            }

            Path file = this.actions.chooseExportFile(account.getName() + FILE_NAME_SEPARATOR + account.getTag() + AccountBackup.FILE_EXTENSION);

            if (file == null) {
                return;
            }

            char[] typed = password.getText().isEmpty() ? null : password.getText().toCharArray();

            shown.showMessage(null);
            password.setDisable(true);
            repeated.setDisable(true);
            export.setDisable(true);
            shown.runWork(this.texts.get("accounts.exporting"), cancelled, progress -> {
                try {
                    return AccountBackup.create(account, typed, this.random);
                } finally {
                    if (typed != null) {
                        Arrays.fill(typed, '\0');
                    }
                }
            }, backup -> {
                AtomicFileUtils.write(file, backup);
                this.showNotice(Tone.SUCCESS, this.texts.format("accounts.exported", file));
                this.closeDialog(shown);
            }, this::describe, () -> {
                password.setDisable(false);
                repeated.setDisable(false);
                export.setDisable(false);
            });
        });

        this.showDialog(shown);
        password.requestFocus();
    }

    private void openDelete(@NotNull Account account) {
        ModalDialog shown = new ModalDialog(this.texts.get("accounts.delete.title"));
        Button cancel = ComponentUtils.button(this.texts.get("accounts.cancel"), ButtonRole.SECONDARY);
        Button delete = ComponentUtils.button(this.texts.get("accounts.delete"), ButtonRole.DANGER);

        shown.addContent(ComponentUtils.paragraph(this.texts.format("accounts.delete.text", account.getTaggedName()), "text-body"));
        shown.setButtons(cancel, delete);
        cancel.setOnAction(event -> this.closeDialog(shown));
        delete.setOnAction(event -> {
            try {
                this.store.delete(account);
            } catch (IOException exception) {
                shown.showMessage(this.describe(exception));

                return;
            }

            this.closeDialog(shown);
            this.select(null);
        });

        this.showDialog(shown);
        cancel.requestFocus();
    }

    /**
     * Logs in to an account and records its use, so that the next start selects it first. A use that cannot be recorded does not stop the
     * login; the account keeps its place in the list.
     */
    private void logIn(@NotNull Account account) {
        Account used;

        try {
            used = this.store.markUsed(account, System.currentTimeMillis());
        } catch (IOException exception) {
            used = account;
        }

        this.actions.onLogIn(used);
    }

    private void withSelection(@NotNull Consumer<@NotNull Account> action) {
        Account selected = this.list.getSelectionModel().getSelectedItem();

        if (selected != null) {
            action.accept(selected);
        }
    }

    /**
     * Reads the accounts and selects one: the account of an id when it is listed, else the most recently used account.
     */
    private void select(@Nullable String selectedId) {
        List<Account> accounts;

        this.inUse.clear();

        try {
            accounts = this.store.list();

            for (Account account : accounts) {
                if (this.store.isInUse(account)) {
                    this.inUse.add(account.getId());
                }
            }
        } catch (IOException exception) {
            accounts = List.of();
            this.showNotice(Tone.DANGER, this.texts.format("accounts.failed", exception.getMessage()));
        }

        this.list.getSelectionModel().clearSelection();
        this.list.getItems().setAll(accounts);

        Account select = accounts.stream().filter(account -> account.getId().equals(selectedId)).findFirst().orElse(null);

        if (select == null && !accounts.isEmpty()) {
            select = accounts.getFirst();
        }

        if (select != null) {
            this.list.getSelectionModel().select(select);
        } else {
            this.showDetail(null);
        }

        this.list.refresh();
    }

    private void showDetail(@Nullable Account account) {
        if (account == null) {
            this.detailBody.getChildren().setAll(ComponentUtils.paragraph(this.texts.get("accounts.select"), "text-caption"));
            this.exportButton.setDisable(true);
            this.deleteButton.setDisable(true);
            this.logInButton.setDisable(true);

            return;
        }

        boolean inUse = this.inUse.contains(account.getId());
        Label name = ComponentUtils.label(account.getName(), "text-display");
        Label tag = ComponentUtils.label(LinkPlayer.of(account.getKey().getBytes()).getTagText(), "text-title", "text-muted");
        HBox heading = ComponentUtils.row(Spacing.XS, name, tag);

        heading.setAlignment(Pos.BASELINE_LEFT);
        this.detailBody.getChildren().setAll(heading, ComponentUtils.label(this.describeLastUse(account), "text-caption"));

        if (inUse) {
            this.detailBody.getChildren().add(ComponentUtils.notice(Tone.WARNING, this.texts.get("accounts.in.use.notice")));
        }

        this.exportButton.setDisable(false);
        this.deleteButton.setDisable(inUse);
        this.logInButton.setDisable(false);
    }

    /**
     * Creates the list item of an account: a marker, amber with a badge while a running game uses the account, the name and tag, and the last
     * use.
     */
    private @NotNull ListItem createAccountItem(@NotNull Account account) {
        boolean used = this.inUse.contains(account.getId());
        ListItem item = new ListItem(account.getTaggedName()).setMarker(used ? Tone.WARNING : Tone.NEUTRAL)
                                                             .addCaption(this.describeLastUse(account));

        if (used) {
            item.setBadge(ComponentUtils.badge(this.texts.get("accounts.in.use"), Tone.WARNING));
        }

        return item;
    }

    private @NotNull String describeLastUse(@NotNull Account account) {
        long lastUsed = account.getLastUsedMillis();

        return lastUsed <= 0 ? this.texts.get("accounts.never.used")
                             : this.texts.format("accounts.last.used", this.dateFormat.format(Instant.ofEpochMilli(lastUsed)));
    }

    private void showNotice(@NotNull Tone tone, @NotNull String text) {
        this.notices.getChildren().setAll(ComponentUtils.notice(tone, text));
        this.notices.setVisible(true);
        this.notices.setManaged(true);
    }

    private void showDialog(@NotNull ModalDialog shown) {
        ModalDialog previous = this.dialog;

        if (previous != null) {
            this.root.getChildren().remove(previous.getRoot());
        }

        this.dialog = shown;
        this.notices.getChildren().clear();
        this.notices.setVisible(false);
        this.notices.setManaged(false);
        this.root.getChildren().add(shown.getRoot());
    }

    private void closeDialog(@NotNull ModalDialog shown) {
        this.root.getChildren().remove(shown.getRoot());

        if (this.dialog == shown) {
            this.dialog = null;
        }
    }

    private @Nullable String checkName(@NotNull ModalDialog shown, @NotNull String typed) {
        String checked = PlayerNameUtils.normalize(typed);

        shown.showMessage(checked == null ? this.texts.describeAccountProblem(AccountProblem.NAME_INVALID) : null);

        return checked;
    }

    private @NotNull String describe(@NotNull Exception exception) {
        return exception instanceof AccountException problem ? this.texts.describeAccountProblem(problem.getProblem())
                                                             : this.texts.format("accounts.failed", exception.getMessage());
    }
}
