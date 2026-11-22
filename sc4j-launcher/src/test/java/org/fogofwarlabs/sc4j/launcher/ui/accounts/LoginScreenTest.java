package org.fogofwarlabs.sc4j.launcher.ui.accounts;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherShell;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the login step: the list with the last used account preselected and an account a running game uses (it can be logged in to but not
 * deleted), no way to rename an account, Log in, the New account dialog that says the name stays, with its progress and Cancel, the Export
 * dialog that checks the repeated password and saves a protected backup, the Import dialog that refuses a wrong password and adds the account
 * under its own name, and the Delete warning. Without accounts a full New account run creates and selects the account with the stamp of its
 * name, and Log in logs in to it.
 */
final class LoginScreenTest {
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull String PASSWORD = "quiet harbor";
    private static final long ALICE_USE_MILLIS = Instant.parse("2026-11-10T18:30:00Z").toEpochMilli();
    private static final long RAYNOR_USE_MILLIS = Instant.parse("2026-11-08T20:00:00Z").toEpochMilli();
    /**
     * The share of the stamp's work to wait for before Cancel, so that the bar shows progress.
     */
    private static final double SHOWN_PROGRESS = 0.05;

    private static @NotNull IdentityKeyPair aliceKey;
    private static @NotNull PlayerStamp aliceStamp;
    private static @NotNull IdentityKeyPair raynorKey;
    private static @NotNull PlayerStamp raynorStamp;
    private static @NotNull IdentityKeyPair kerriganKey;
    private static @NotNull PlayerStamp kerriganStamp;

    /**
     * Records the window's side of the screen: the accounts logged in to and the files the user picks.
     */
    private static final class RecordingActions implements LoginScreen.Actions {
        private final @NotNull List<@NotNull Account> loggedIn;
        private @Nullable Path importFile;
        private @Nullable Path exportFile;

        private RecordingActions() {
            this.loggedIn   = new ArrayList<>();
            this.importFile = null;
            this.exportFile = null;
        }

        @Override
        public void onLogIn(@NotNull Account account) {
            this.loggedIn.add(account);
        }

        @Override
        public @Nullable Path chooseImportFile() {
            return this.importFile;
        }

        @Override
        public @Nullable Path chooseExportFile(@NotNull String fileName) {
            return this.exportFile;
        }
    }

    @BeforeAll
    static void startToolkitAndSolveStamps() {
        LauncherFxScenario.startToolkit();

        aliceKey      = IdentityKeyPair.generate(new SecureRandom());
        aliceStamp    = PlayerStamp.solve(aliceKey.getPublicKey(), "Alice", PlayerStamp.DEFAULT_DIFFICULTY);
        raynorKey     = IdentityKeyPair.generate(new SecureRandom());
        raynorStamp   = PlayerStamp.solve(raynorKey.getPublicKey(), "Raynor", PlayerStamp.DEFAULT_DIFFICULTY);
        kerriganKey   = IdentityKeyPair.generate(new SecureRandom());
        kerriganStamp = PlayerStamp.solve(kerriganKey.getPublicKey(), "Kerrigan", PlayerStamp.DEFAULT_DIFFICULTY);
    }

    private static @NotNull StackPane getTopDialog(@NotNull LoginScreen screen) {
        List<Node> layers = screen.getRoot().getChildren();

        Assertions.assertTrue(layers.size() > 1, "a dialog is shown");

        return (StackPane) layers.getLast();
    }

    private static boolean hasDialog(@NotNull LoginScreen screen) {
        return screen.getRoot().getChildren().size() > 1;
    }

    private static <T extends Node> @NotNull List<T> find(@NotNull Parent root, @NotNull Class<T> type) {
        List<T> found = new ArrayList<>();

        for (Node node : root.lookupAll("*")) {
            if (type.isInstance(node)) {
                found.add(type.cast(node));
            }
        }

        return found;
    }

    private static boolean showsText(@NotNull Parent root, @NotNull String text) {
        return find(root, Label.class).stream().anyMatch(label -> label.isVisible() && label.getText().contains(text));
    }

    @SuppressWarnings("unchecked")
    private static @NotNull ListView<Account> getList(@NotNull LoginScreen screen) {
        return (ListView<Account>) screen.getRoot().lookup(".list-view");
    }

    private static void lay(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
    }

    @Test
    void login_screen_shows_its_list_and_dialogs(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        Account alice = store.create(aliceKey, aliceStamp, PlayerStamp.DEFAULT_DIFFICULTY, ALICE_USE_MILLIS);
        Account raynor = store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        Account kerrigan = AccountStore.of(directory.resolve("other")).create(kerriganKey, kerriganStamp, PlayerStamp.DEFAULT_DIFFICULTY,
                                                                              RAYNOR_USE_MILLIS);
        Path kerriganBackup = directory.resolve("Kerrigan" + AccountBackup.FILE_EXTENSION);
        Path aliceBackup = directory.resolve("Alice" + AccountBackup.FILE_EXTENSION);
        RecordingActions actions = new RecordingActions();

        Files.write(kerriganBackup, AccountBackup.create(kerrigan, PASSWORD.toCharArray(), new SecureRandom()));
        actions.importFile = kerriganBackup;
        actions.exportFile = aliceBackup;

        try (AccountLock running = AccountLock.acquire(raynor)) {
            Assertions.assertEquals(raynor.getId(), running.getAccount().getId());

            LauncherShell shell = LauncherFxScenario.run(() -> new LauncherShell(texts, install, 1, section -> {
            }, () -> {
            }, () -> {
            }));
            LoginScreen screen = LauncherFxScenario.run(() -> {
                LoginScreen created = new LoginScreen(store, texts, actions);
                Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);

                ThemeUtils.apply(scene, texts.getLanguage());
                shell.showLogin(created.getRoot());
                lay(shell);

                return created;
            });

            // The list: the last used account first and selected, the account a running game uses marked; it can be logged in to but not deleted.
            LauncherFxScenario.run(() -> {
                ListView<Account> list = getList(screen);

                Assertions.assertEquals(List.of(alice.getId(), raynor.getId()), list.getItems().stream().map(Account::getId).toList());
                Assertions.assertEquals(alice.getId(), list.getSelectionModel().getSelectedItem().getId(), "the last used account is preselected");
                Assertions.assertEquals(List.of(texts.get("accounts.new"), texts.get("accounts.import"), texts.get("accounts.export"),
                                                texts.get("accounts.delete"), texts.get("accounts.log.in")),
                                        find(screen.getRoot(), Button.class).stream().map(Button::getText).toList(), "no account can be renamed");

                list.getSelectionModel().select(1);
                lay(shell);

                Assertions.assertFalse(LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.log.in")).isDisabled(),
                                       "a running game does not keep another game from the account");
                Assertions.assertTrue(LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.delete")).isDisabled());
                Assertions.assertTrue(showsText(screen.getRoot(), texts.get("accounts.in.use.notice")));

                list.getSelectionModel().select(0);
                LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.log.in")).fire();

                return null;
            });

            Assertions.assertEquals(List.of(alice.getId()), actions.loggedIn.stream().map(Account::getId).toList(), "Log in logs in to the selection");

            // New account: the name, then the progress of the new key's stamp, which Cancel stops without an account.
            LauncherFxScenario.run(() -> {
                screen.openNewAccount();
                lay(shell);
                find(getTopDialog(screen), TextField.class).getFirst().setText("Zeratul");

                Assertions.assertTrue(showsText(getTopDialog(screen), texts.get("accounts.name.fixed")), "the dialog says the name stays");
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.create")).fire();

                return null;
            });
            // The stamp's search ends at a random point: it may end before its progress shows, and then the account is made.
            LauncherFxScenario.await(() -> !hasDialog(screen) ||
                                           find(getTopDialog(screen), ProgressBar.class).getFirst().getProgress() > SHOWN_PROGRESS,
                                     "the stamp's progress shows");

            boolean finishedEarly = LauncherFxScenario.run(() -> {
                if (!hasDialog(screen)) {
                    return true;
                }

                lay(shell);
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.cancel")).fire();

                Assertions.assertFalse(hasDialog(screen));

                return false;
            });

            if (finishedEarly) {
                Account made = store.list().stream().filter(account -> account.getName().equals("Zeratul")).findFirst().orElseThrow();

                store.delete(made);
                LauncherFxScenario.run(() -> {
                    screen.refresh();

                    return null;
                });
            }

            Assertions.assertEquals(2, store.list().size(), "a cancelled account is not created");

            // Export: the repeated password must match; the backup opens with the password.
            LauncherFxScenario.run(() -> {
                getList(screen).getSelectionModel().select(0);
                LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.export")).fire();

                List<PasswordField> fields = find(getTopDialog(screen), PasswordField.class);

                fields.get(0).setText(PASSWORD);
                fields.get(1).setText(PASSWORD + "!");
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.export")).fire();
                lay(shell);

                Assertions.assertTrue(showsText(getTopDialog(screen), texts.get("accounts.password.mismatch")));

                fields.get(1).setText(PASSWORD);
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.export")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> !hasDialog(screen), "the backup is saved");

            Assertions.assertEquals(alice.getId(), AccountBackup.read(Files.readAllBytes(aliceBackup), PASSWORD.toCharArray()).getId());

            AccountException refused = Assertions.assertThrows(AccountException.class,
                                                               () -> AccountBackup.read(Files.readAllBytes(aliceBackup), "wrong".toCharArray()));

            Assertions.assertEquals(AccountProblem.WRONG_PASSWORD, refused.getProblem());

            // Import: a protected backup asks its password; a wrong one is refused in one line, the right one adds and selects the account.
            LauncherFxScenario.run(() -> {
                LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.import")).fire();
                find(getTopDialog(screen), PasswordField.class).getFirst().setText("wrong");
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.import")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> showsText(getTopDialog(screen), texts.describeAccountProblem(AccountProblem.WRONG_PASSWORD)),
                                     "a wrong password is refused");
            LauncherFxScenario.run(() -> {
                lay(shell);
                find(getTopDialog(screen), PasswordField.class).getFirst().setText(PASSWORD);
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.import")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> !hasDialog(screen), "the account is imported");

            Account imported = store.find(kerrigan.getId());

            Assertions.assertNotNull(imported);
            Assertions.assertEquals("Kerrigan", imported.getName(), "the import keeps the name");
            Assertions.assertEquals(kerrigan.getId(), LauncherFxScenario.run(() -> getList(screen).getSelectionModel().getSelectedItem().getId()),
                                    "the imported account is selected");
            Assertions.assertEquals(1, actions.loggedIn.size(), "an import does not log in");

            // Delete: a short warning names the account; the list keeps the other accounts and selects one of them.
            LauncherFxScenario.run(() -> {
                lay(shell);
                LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.delete")).fire();
                lay(shell);

                Assertions.assertTrue(showsText(getTopDialog(screen), kerrigan.getTaggedName()));
                LauncherFxScenario.findButton(getTopDialog(screen), texts.get("accounts.delete")).fire();

                Assertions.assertFalse(hasDialog(screen));
                Assertions.assertNotNull(getList(screen).getSelectionModel().getSelectedItem(), "another account is selected");

                return null;
            });

            Assertions.assertEquals(List.of(alice.getId(), raynor.getId()), store.list().stream().map(Account::getId).toList());
        }
    }

    @Test
    void new_account_creates_and_selects_the_account_and_log_in_logs_in_to_it(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        AccountStore store = AccountStore.of(directory);
        RecordingActions actions = new RecordingActions();
        LoginScreen screen = LauncherFxScenario.run(() -> {
            LoginScreen created = new LoginScreen(store, texts, actions);

            Assertions.assertTrue(LauncherFxScenario.findButton(created.getRoot(), texts.get("accounts.log.in")).isDisabled(), "no account to log in");

            created.openNewAccount();
            find(getTopDialog(created), TextField.class).getFirst().setText("with#hash");
            LauncherFxScenario.findButton(getTopDialog(created), texts.get("accounts.create")).fire();

            Assertions.assertTrue(showsText(getTopDialog(created), texts.describeAccountProblem(AccountProblem.NAME_INVALID)));

            find(getTopDialog(created), TextField.class).getFirst().setText("  Zeratul ");
            LauncherFxScenario.findButton(getTopDialog(created), texts.get("accounts.create")).fire();

            return created;
        });

        LauncherFxScenario.await(() -> !hasDialog(screen), "the account is created");

        List<Account> accounts = store.list();

        Assertions.assertEquals(1, accounts.size());
        Assertions.assertEquals("Zeratul", accounts.getFirst().getName());
        Assertions.assertEquals("Zeratul", accounts.getFirst().getStamp().getName(), "the new account's stamp binds its name");
        Assertions.assertTrue(accounts.getFirst().getStamp().isValid(PlayerStamp.DEFAULT_DIFFICULTY), "the new account has its player stamp");
        Assertions.assertTrue(actions.loggedIn.isEmpty(), "a new account does not log in by itself");

        LauncherFxScenario.run(() -> {
            Assertions.assertEquals(accounts.getFirst().getId(), getList(screen).getSelectionModel().getSelectedItem().getId());
            LauncherFxScenario.findButton(screen.getRoot(), texts.get("accounts.log.in")).fire();

            return null;
        });

        Assertions.assertEquals(List.of(accounts.getFirst().getId()), actions.loggedIn.stream().map(Account::getId).toList());
    }
}
