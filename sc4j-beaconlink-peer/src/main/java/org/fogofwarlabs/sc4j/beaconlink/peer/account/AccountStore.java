package org.fogofwarlabs.sc4j.beaconlink.peer.account;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyFileUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerNameUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Holds the player accounts of an install in {@code <install>/userdata/beaconlink/accounts/}: one folder per account, named by its id
 * ({@link Account}). Nothing is created on its own: an account exists only after {@link #create} or {@link #restore}. Creating the store
 * object reads and writes nothing.
 * <p>
 * The account file ({@value Account#ACCOUNT_FILE}) is {@code {"format": 1, "name": "<name>", "difficulty": <bits>, "stamp": <nonce>,
 * "lastUsed": <epoch ms>}}: the name, the player stamp that binds it to the key ({@link PlayerStamp}) with its difficulty, and the last use. A
 * folder whose stamp does not bind its name to its key holds no account. The key file and the friends keep the Beacon.Link formats
 * ({@link IdentityKeyFileUtils}). An account's name never changes.
 */
public final class AccountStore {
    /**
     * The name of the accounts folder in the install's Beacon.Link folder.
     */
    public static final @NotNull String FOLDER = "accounts";

    private static final @NotNull Logger LOGGER = Logger.of(AccountStore.class);
    private static final long FORMAT = 1L;
    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String DIFFICULTY_FIELD = "difficulty";
    private static final @NotNull String STAMP_FIELD = "stamp";
    private static final @NotNull String LAST_USED_FIELD = "lastUsed";
    private static final long MAXIMUM_ACCOUNT_FILE_LENGTH = 4096L;
    private static final long MAXIMUM_DIFFICULTY = 256L;
    /**
     * Orders a listing: the most recently used account first, then by name.
     */
    private static final @NotNull Comparator<@NotNull Account> LISTING_ORDER = Comparator.comparingLong(Account::getLastUsedMillis).reversed()
                                                                                         .thenComparing(Account::getName, String.CASE_INSENSITIVE_ORDER)
                                                                                         .thenComparing(Account::getId);

    /**
     * Creates a {@link AccountStore} instance for an install's Beacon.Link folder; nothing is read or created.
     *
     * @param beaconLinkDirectory the install's Beacon.Link folder, {@code <install>/userdata/beaconlink}
     * @return the created {@link AccountStore} instance
     */
    public static @NotNull AccountStore of(@NotNull Path beaconLinkDirectory) {
        return new AccountStore(beaconLinkDirectory.toAbsolutePath().normalize().resolve(FOLDER));
    }

    /**
     * Reads the account of an account folder, wherever the folder lies: a game started with an account folder reads it so. A folder holds no
     * readable account if its key or account file is missing or broken, it is not named by the id of its key, or its stamp does not bind the
     * name to the key.
     *
     * @param folder the account folder, named by the account's id
     * @return the account
     * @throws IOException if the folder holds no readable account
     */
    public static @NotNull Account read(@NotNull Path folder) throws IOException {
        Path directory = folder.toAbsolutePath().normalize();
        IdentityKeyPair keyPair = IdentityKeyFileUtils.read(directory.resolve(Account.KEY_FILE));
        Path accountFile = directory.resolve(Account.ACCOUNT_FILE);
        Path name = directory.getFileName();

        if (name == null || !Account.createId(keyPair.getPublicKey()).equals(name.toString())) {
            throw Exceptions.newIOException("The folder name is not the id of its key {0}.", keyPair.getPublicKey());
        }

        if (Files.size(accountFile) > MAXIMUM_ACCOUNT_FILE_LENGTH ||
            !(JsonReader.parse(Files.readAllBytes(accountFile)) instanceof Map<?, ?> object) ||
            !(object.get(FORMAT_FIELD) instanceof Long format) || format != FORMAT ||
            !(object.get(NAME_FIELD) instanceof String accountName) || !(object.get(DIFFICULTY_FIELD) instanceof Long difficulty) ||
            !(object.get(STAMP_FIELD) instanceof Long nonce) || !(object.get(LAST_USED_FIELD) instanceof Long lastUsed)) {
            throw Exceptions.newIOException("The account file {0} is not of format {1} with a name, a stamp and the last use.", accountFile, FORMAT);
        }

        PlayerStamp stamp = new PlayerStamp(keyPair.getPublicKey(), accountName, nonce);

        if (difficulty < 0L || difficulty > MAXIMUM_DIFFICULTY || !stamp.isValid(difficulty.intValue())) {
            throw Exceptions.newIOException("The stamp of the account file {0} does not bind the name {1} to the key.", accountFile, accountName);
        }

        return new Account(directory, stamp, difficulty.intValue(), lastUsed);
    }


    private final @NotNull Path directory;

    private AccountStore(@NotNull Path directory) {
        this.directory = directory;
    }

    /**
     * @return the absolute accounts folder
     */
    public @NotNull Path getDirectory() {
        return this.directory;
    }


    /**
     * Lists the accounts, the most recently used first. A folder that holds no readable account is left out with a warning in the log.
     *
     * @return the accounts, empty if the folder does not exist
     * @throws IOException if the accounts folder cannot be listed
     */
    public @NotNull List<@NotNull Account> list() throws IOException {
        List<Account> accounts = new ArrayList<>();

        if (!Files.isDirectory(this.directory)) {
            return accounts;
        }

        try (DirectoryStream<Path> folders = Files.newDirectoryStream(this.directory, Files::isDirectory)) {
            for (Path folder : folders) {
                try {
                    accounts.add(read(folder));
                } catch (IOException exception) {
                    LOGGER.warning("ACCOUNT: the folder {0} holds no account: {1}", folder, exception.getMessage());
                }
            }
        }

        accounts.sort(LISTING_ORDER);

        return accounts;
    }

    /**
     * Finds an account by its id.
     *
     * @param id the account id
     * @return the account, or {@code null} if no folder of that id exists
     * @throws IOException if the folder exists but holds no readable account
     */
    public @Nullable Account find(@NotNull String id) throws IOException {
        Path folder = this.directory.resolve(id).normalize();

        if (!folder.getParent().equals(this.directory) || !Files.isDirectory(folder)) {
            return null;
        }

        return read(folder);
    }

    /**
     * Creates an account: its folder with the key file and the account file. The account's name is the stamp's, fixed from now on. A name that
     * is not a valid player name gives {@link AccountProblem#NAME_INVALID}, a key that has an account {@link AccountProblem#ALREADY_ADDED}.
     *
     * @param keyPair    the player key pair
     * @param stamp      the player stamp of the key and the name ({@link PlayerStamp#solve})
     * @param difficulty the difficulty the stamp meets
     * @param nowMillis  the current time, the account's last use
     * @return the created account
     * @throws AccountException         if the stamp's name is not a valid player name or an account of the key exists
     * @throws IOException              if a file cannot be written
     * @throws IllegalArgumentException if the stamp is of another key or does not meet the difficulty
     */
    public @NotNull Account create(@NotNull IdentityKeyPair keyPair, @NotNull PlayerStamp stamp, int difficulty, long nowMillis) throws IOException {
        return this.create(keyPair, stamp, difficulty, Map.of(), nowMillis);
    }

    private @NotNull Account create(@NotNull IdentityKeyPair keyPair, @NotNull PlayerStamp stamp, int difficulty,
                                    @NotNull Map<@NotNull String, byte @NotNull []> files, long nowMillis) throws IOException {
        String id = Account.createId(keyPair.getPublicKey());
        Path folder = this.directory.resolve(id);

        if (!PlayerNameUtils.isValid(stamp.getName())) {
            throw new AccountException(AccountProblem.NAME_INVALID, Exceptions.format("The account name {0} is not valid.", stamp.getName()));
        }

        if (!stamp.getKey().equals(keyPair.getPublicKey()) || !stamp.isValid(difficulty)) {
            throw Exceptions.newIllegalArgumentException("The stamp {0} is not of the key {1} or does not meet the difficulty {2}.",
                                                         stamp, keyPair.getPublicKey(), difficulty);
        }

        if (Files.exists(folder)) {
            throw new AccountException(AccountProblem.ALREADY_ADDED, Exceptions.format("The account {0} exists.", id));
        }

        Account account = new Account(folder, stamp, difficulty, nowMillis);

        Files.createDirectories(folder);
        IdentityKeyFileUtils.write(folder.resolve(Account.KEY_FILE), keyPair);

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            AtomicFileUtils.write(folder.resolve(file.getKey()), file.getValue());
        }

        this.writeAccountFile(account);

        return account;
    }

    /**
     * Creates an account from a backup: as {@link #create}, with the backup's name and stamp, and the backup's friends and blocked players
     * are written as well.
     *
     * @param backup    the read backup
     * @param nowMillis the current time, the account's last use
     * @return the created account
     * @throws AccountException if an account of the key exists ({@link AccountProblem#ALREADY_ADDED})
     * @throws IOException      if a file cannot be written
     */
    public @NotNull Account restore(@NotNull AccountBackup backup, long nowMillis) throws IOException {
        return this.create(backup.getKeyPair(), backup.getStamp(), backup.getStampDifficulty(), backup.getFiles(), nowMillis);
    }

    /**
     * Records the use of an account, so that a listing shows it first.
     *
     * @param account   the account
     * @param nowMillis the current time
     * @return the account with its new last use
     * @throws IOException if the account file cannot be written
     */
    public @NotNull Account markUsed(@NotNull Account account, long nowMillis) throws IOException {
        Account used = new Account(account.getDirectory(), account.getStamp(), account.getStampDifficulty(), nowMillis);

        this.writeAccountFile(used);

        return used;
    }

    private void writeAccountFile(@NotNull Account account) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();

        object.put(FORMAT_FIELD, FORMAT);
        object.put(NAME_FIELD, account.getName());
        object.put(DIFFICULTY_FIELD, (long) account.getStampDifficulty());
        object.put(STAMP_FIELD, account.getStamp().getNonce());
        object.put(LAST_USED_FIELD, account.getLastUsedMillis());

        AtomicFileUtils.write(account.getDirectory().resolve(Account.ACCOUNT_FILE), JsonWriterUtils.write(object).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks whether a process uses an account ({@link AccountLock}).
     *
     * @param account the account
     * @return {@code true} if a game or launcher of this or another process uses the account, {@code false} otherwise
     * @throws IOException if the lock file cannot be opened
     */
    public boolean isInUse(@NotNull Account account) throws IOException {
        try (FileChannel lock = AccountLock.lockExclusively(account)) {
            return lock == null;
        }
    }

    /**
     * Deletes an account with all its files. Its tag and friends are gone unless a backup of it is kept. The account's lock file stays locked
     * exclusively until its other files are deleted, so that no process starts using the account meanwhile.
     *
     * @param account the account
     * @throws AccountException if a process uses the account ({@link AccountProblem#IN_USE})
     * @throws IOException      if a file cannot be deleted
     */
    public void delete(@NotNull Account account) throws IOException {
        if (!Files.isDirectory(account.getDirectory())) {
            return;
        }

        Path lockFile = account.getDirectory().resolve(Account.LOCK_FILE);
        List<Path> paths;

        try (FileChannel lock = AccountLock.lockExclusively(account)) {
            if (lock == null) {
                throw new AccountException(AccountProblem.IN_USE, Exceptions.format("The account {0} is in use.", account.getId()));
            }

            try (Stream<Path> walk = Files.walk(account.getDirectory())) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }

            for (Path path : paths) {
                if (!path.equals(lockFile) && !Files.isDirectory(path)) {
                    Files.deleteIfExists(path);
                }
            }
        }

        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
