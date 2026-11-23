package org.fogofwarlabs.sc4j.beaconlink.peer.account;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyFileUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds an account as a backup carries it to another install: the name with the player stamp that binds it to the key, the key pair and the
 * account's other files (friends, blocked players). A backup file is one file; it is protected by a password the user picks, or not
 * protected. The name is the account's own: a backup restores the account under the name it was made with.
 * <p>
 * File layout (big-endian): the magic {@code BLKA}, the version {@value #VERSION}, the flags (bit 0: protected by a password). A protected
 * backup follows with the salt (16 bytes), the PBKDF2-HMAC-SHA256 iteration count (4) and the AES-GCM nonce (12); the rest is the body
 * encrypted with AES-256-GCM under the key PBKDF2 derives from the password, its 16-byte tag at the end and the header bytes as associated
 * data, so that a wrong password or a changed byte is detected. The body: the name (length byte, UTF-8), the stamp difficulty (one byte),
 * the stamp nonce (eight bytes), the key file (two-byte length, the bytes of {@link IdentityKeyFileUtils}), the number of other files (one
 * byte) and per file its name (length byte, ASCII) and its bytes (four-byte length, at most 16 KiB). A backup whose stamp does not bind its
 * name to its key is no account backup.
 */
public final class AccountBackup {
    /**
     * The backup file version.
     */
    public static final int VERSION = 1;
    /**
     * The file name extension of backup files.
     */
    public static final @NotNull String FILE_EXTENSION = ".sc4jaccount";
    /**
     * The largest length of a backup file.
     */
    public static final int MAXIMUM_LENGTH = 65_536;

    private static final byte @NotNull [] MAGIC = {'B', 'L', 'K', 'A'};
    /**
     * The length of the magic, the version and the flags.
     */
    private static final int HEADER_LENGTH = MAGIC.length + 2;
    private static final int PASSWORD_FLAG = 0x01;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 600_000;
    private static final int MINIMUM_ITERATIONS = 100_000;
    private static final int MAXIMUM_ITERATIONS = 10_000_000;
    private static final int MAXIMUM_FILE_LENGTH = 16_384;
    private static final @NotNull String KEY_DERIVATION = "PBKDF2WithHmacSHA256";
    private static final @NotNull String CIPHER = "AES/GCM/NoPadding";
    private static final @NotNull String CIPHER_KEY = "AES";
    /**
     * The account files a backup carries besides the key file, the only names it accepts.
     */
    private static final @NotNull List<@NotNull String> CARRIED_FILES = List.of(Account.FRIENDS_FILE, Account.IGNORED_FILE);

    /**
     * Creates the backup of an account.
     *
     * @param account  the account
     * @param password the password that protects the backup, or {@code null} for an unprotected backup
     * @param random   the source of the salt and the nonce
     * @return the backup file bytes
     * @throws IOException if a file of the account cannot be read
     */
    public static byte @NotNull [] create(@NotNull Account account, char @Nullable [] password, @NotNull SecureRandom random) throws IOException {
        byte[] keyFile = Files.readAllBytes(account.getDirectory().resolve(Account.KEY_FILE));
        Map<String, byte[]> files = new LinkedHashMap<>();

        for (String name : CARRIED_FILES) {
            Path file = account.getDirectory().resolve(name);

            if (Files.isRegularFile(file) && Files.size(file) <= MAXIMUM_FILE_LENGTH) {
                files.put(name, Files.readAllBytes(file));
            }
        }

        byte[] body = writeBody(account.getStamp(), account.getStampDifficulty(), keyFile, files);

        Arrays.fill(keyFile, (byte) 0);

        try {
            return password == null ? writeHeader(0, null, 0, null, body) : encrypt(body, password, random);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    /**
     * Checks whether a file needs a password: a backup protected by one.
     *
     * @param contents the file bytes
     * @return {@code true} if the bytes are a backup protected by a password, {@code false} otherwise
     */
    public static boolean isProtected(byte @NotNull [] contents) {
        return contents.length >= HEADER_LENGTH && Arrays.equals(contents, 0, MAGIC.length, MAGIC, 0, MAGIC.length) &&
               (contents[MAGIC.length + 1] & PASSWORD_FLAG) != 0;
    }

    /**
     * Reads a backup file. Bytes that are no backup of this version, or whose stamp does not bind its name to its key, give
     * {@link AccountProblem#NOT_AN_ACCOUNT_FILE}; a protected backup without a password gives {@link AccountProblem#PASSWORD_REQUIRED}, and a
     * password that does not open it {@link AccountProblem#WRONG_PASSWORD}.
     *
     * @param contents the file bytes
     * @param password the password of a protected backup, or {@code null}
     * @return the read backup
     * @throws AccountException if the bytes are no valid backup, or the password is missing or wrong
     */
    public static @NotNull AccountBackup read(byte @NotNull [] contents, char @Nullable [] password) throws AccountException {
        if (contents.length > MAXIMUM_LENGTH || contents.length < HEADER_LENGTH ||
            !Arrays.equals(contents, 0, MAGIC.length, MAGIC, 0, MAGIC.length) || contents[MAGIC.length] != VERSION) {
            throw notAnAccountFile("The file is no account backup of version " + VERSION + ".");
        }

        int flags = Byte.toUnsignedInt(contents[MAGIC.length + 1]);

        if ((flags & ~PASSWORD_FLAG) != 0) {
            throw notAnAccountFile("The backup has unknown flags.");
        }

        if ((flags & PASSWORD_FLAG) == 0) {
            return readBody(Arrays.copyOfRange(contents, HEADER_LENGTH, contents.length));
        }

        if (password == null) {
            throw new AccountException(AccountProblem.PASSWORD_REQUIRED, "The backup is protected by a password.");
        }

        byte[] body = decrypt(contents, password);

        try {
            return readBody(body);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static @NotNull AccountException notAnAccountFile(@Nullable String reason) {
        return new AccountException(AccountProblem.NOT_AN_ACCOUNT_FILE, Exceptions.format("The file is no account backup: {0}", reason));
    }

    private static byte @NotNull [] writeBody(@NotNull PlayerStamp stamp, int difficulty, byte @NotNull [] keyFile,
                                              @NotNull Map<@NotNull String, byte @NotNull []> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        byte[] nameBytes = stamp.getName().getBytes(StandardCharsets.UTF_8);

        try {
            output.writeByte(nameBytes.length);
            output.write(nameBytes);
            output.writeByte(difficulty);
            output.writeLong(stamp.getNonce());
            output.writeShort(keyFile.length);
            output.write(keyFile);
            output.writeByte(files.size());

            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                byte[] fileName = file.getKey().getBytes(StandardCharsets.US_ASCII);

                output.writeByte(fileName.length);
                output.write(fileName);
                output.writeInt(file.getValue().length);
                output.write(file.getValue());
            }
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "A byte array stream failed: {0}", exception.getMessage());
        }

        return bytes.toByteArray();
    }

    private static @NotNull AccountBackup readBody(byte @NotNull [] body) throws AccountException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] keyFile = null;

        try {
            String name = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
            int difficulty = input.readUnsignedByte();
            long nonce = input.readLong();

            keyFile = input.readNBytes(input.readUnsignedShort());

            IdentityKeyPair keyPair = IdentityKeyFileUtils.decode(keyFile, Account.KEY_FILE);
            PlayerStamp stamp = new PlayerStamp(keyPair.getPublicKey(), name, nonce);
            int count = input.readUnsignedByte();

            for (int index = 0; index < count; index++) {
                String fileName = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII);
                int length = input.readInt();

                if (!CARRIED_FILES.contains(fileName) || files.containsKey(fileName) || length < 0 || length > MAXIMUM_FILE_LENGTH) {
                    throw notAnAccountFile("The backup holds an unexpected file " + fileName + ".");
                }

                byte[] contents = input.readNBytes(length);

                if (contents.length != length) {
                    throw new EOFException();
                }

                files.put(fileName, contents);
            }

            if (input.available() > 0) {
                throw notAnAccountFile("The backup has bytes after its last file.");
            }

            if (!stamp.isValid(difficulty)) {
                throw notAnAccountFile("The stamp of the backup does not bind the name " + name + " to its key.");
            }

            return new AccountBackup(stamp, difficulty, keyPair, files);
        } catch (AccountException exception) {
            throw exception;
        } catch (IOException exception) {
            throw notAnAccountFile(exception.getMessage());
        } finally {
            if (keyFile != null) {
                Arrays.fill(keyFile, (byte) 0);
            }
        }
    }

    private static byte @NotNull [] encrypt(byte @NotNull [] body, char @NotNull [] password, @NotNull SecureRandom random) {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];

        random.nextBytes(salt);
        random.nextBytes(nonce);

        byte[] header = writeHeader(PASSWORD_FLAG, salt, ITERATIONS, nonce, new byte[0]);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER);

            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);

            byte[] encrypted = cipher.doFinal(body);
            byte[] file = Arrays.copyOf(header, header.length + encrypted.length);

            System.arraycopy(encrypted, 0, file, header.length, encrypted.length);

            return file;
        } catch (GeneralSecurityException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK cannot encrypt a backup: {0}", exception.getMessage());
        }
    }

    private static byte @NotNull [] decrypt(byte @NotNull [] contents, char @NotNull [] password) throws AccountException {
        int saltOffset = HEADER_LENGTH;
        int iterationsOffset = saltOffset + SALT_LENGTH;
        int nonceOffset = iterationsOffset + Integer.BYTES;
        int bodyOffset = nonceOffset + NONCE_LENGTH;

        if (contents.length < bodyOffset + TAG_BITS / Byte.SIZE) {
            throw notAnAccountFile("The protected backup is too short.");
        }

        byte[] salt = Arrays.copyOfRange(contents, saltOffset, iterationsOffset);
        int iterations = ByteBuffer.wrap(contents).getInt(iterationsOffset);
        byte[] nonce = Arrays.copyOfRange(contents, nonceOffset, bodyOffset);

        if (iterations < MINIMUM_ITERATIONS || iterations > MAXIMUM_ITERATIONS) {
            throw notAnAccountFile("The protected backup has an iteration count out of range.");
        }

        try {
            Cipher cipher = Cipher.getInstance(CIPHER);

            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(contents, 0, bodyOffset);

            return cipher.doFinal(contents, bodyOffset, contents.length - bodyOffset);
        } catch (AEADBadTagException exception) {
            throw new AccountException(AccountProblem.WRONG_PASSWORD, "The password does not open the backup.");
        } catch (GeneralSecurityException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK cannot decrypt a backup: {0}", exception.getMessage());
        }
    }

    private static @NotNull SecretKey deriveKey(char @NotNull [] password, byte @NotNull [] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, KEY_BITS);

        try {
            byte[] key = SecretKeyFactory.getInstance(KEY_DERIVATION).generateSecret(specification).getEncoded();

            try {
                return new SecretKeySpec(key, CIPHER_KEY);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            specification.clearPassword();
        }
    }

    private static byte @NotNull [] writeHeader(int flags, byte @Nullable [] salt, int iterations, byte @Nullable [] nonce, byte @NotNull [] body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        try {
            output.write(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(flags);

            if (salt != null && nonce != null) {
                output.write(salt);
                output.writeInt(iterations);
                output.write(nonce);
            }

            output.write(body);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "A byte array stream failed: {0}", exception.getMessage());
        }

        return bytes.toByteArray();
    }


    private final @NotNull PlayerStamp stamp;
    private final int stampDifficulty;
    private final @NotNull IdentityKeyPair keyPair;
    private final @NotNull Map<@NotNull String, byte @NotNull []> files;

    private AccountBackup(@NotNull PlayerStamp stamp, int stampDifficulty, @NotNull IdentityKeyPair keyPair,
                          @NotNull Map<@NotNull String, byte @NotNull []> files) {
        this.stamp           = stamp;
        this.stampDifficulty = stampDifficulty;
        this.keyPair         = keyPair;
        this.files           = files;
    }

    /**
     * @return the account's name, the name of its stamp
     */
    public @NotNull String getName() {
        return this.stamp.getName();
    }

    /**
     * @return the player stamp that binds the account's name to its key
     */
    public @NotNull PlayerStamp getStamp() {
        return this.stamp;
    }

    /**
     * @return the difficulty the stamp meets
     */
    public int getStampDifficulty() {
        return this.stampDifficulty;
    }

    /**
     * @return the account's key pair
     */
    public @NotNull IdentityKeyPair getKeyPair() {
        return this.keyPair;
    }

    /**
     * @return the id of the account the backup holds ({@link Account#createId})
     */
    public @NotNull String getId() {
        return Account.createId(this.keyPair.getPublicKey());
    }

    /**
     * @return the account's other files by name: friends and blocked players, each only if the backup carries it
     */
    @NotNull Map<@NotNull String, byte @NotNull []> getFiles() {
        return this.files;
    }
}
