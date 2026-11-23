package org.fogofwarlabs.sc4j.beaconlink.protocol.identity;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoublePredicate;

/**
 * Holds the stamp of a player: a nonce whose SHA-256 hash with a domain text, the player key and the player's name starts with a number of
 * zero bits (the difficulty, {@value #DEFAULT_DIFFICULTY} on the network). Finding it costs about two to the power of the difficulty hashes,
 * once per account, when the account is made; every registration, presence and chat line carries the name and the nonce, and every peer
 * checks them, so that throwaway player identities for spam cost work and a key shows only the name its stamp was found for: another name
 * fails the check.
 * <p>
 * The hashed subject is the 32 key bytes, the UTF-8 length of the name (one byte) and the name's UTF-8 bytes; the name must be a valid player
 * name ({@link PlayerNameUtils}).
 */
public final class PlayerStamp {
    /**
     * The stamp difficulty of the network: the number of leading zero bits of a valid stamp hash.
     */
    public static final int DEFAULT_DIFFICULTY = 24;

    private static final byte @NotNull [] DOMAIN = "BLNK player stamp 1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Creates a {@link PlayerStamp} instance with the smallest valid nonce of a key and a name: about two to the power of the difficulty hashes.
     * The result is the same for the same key, name and difficulty.
     *
     * @param key        the player key
     * @param name       the player's name, valid and normalized ({@link PlayerNameUtils#isValid})
     * @param difficulty the number of leading zero bits the stamp hash needs, at most 256
     * @return the created {@link PlayerStamp} instance
     * @throws IllegalArgumentException if the name is not valid or the difficulty is outside {@code 0..256}
     */
    public static @NotNull PlayerStamp solve(@NotNull IdentityKey key, @NotNull String name, int difficulty) {
        return new PlayerStamp(key, name, ProofOfWorkUtils.solve(DOMAIN, createSubject(key, name), difficulty));
    }

    /**
     * Creates a {@link PlayerStamp} instance with the smallest valid nonce of a key and a name, as {@link #solve(IdentityKey, String, int)},
     * reporting the progress on the calling thread so that a user interface can show it and stop the search.
     *
     * @param key        the player key
     * @param name       the player's name, valid and normalized ({@link PlayerNameUtils#isValid})
     * @param difficulty the number of leading zero bits the stamp hash needs, at most 256
     * @param progress   receives the share of the expected work done so far (below {@code 1}) and returns {@code false} to stop the search
     * @return the created {@link PlayerStamp} instance, or {@code null} if the progress callback stopped the search
     * @throws IllegalArgumentException if the name is not valid or the difficulty is outside {@code 0..256}
     */
    public static @Nullable PlayerStamp solve(@NotNull IdentityKey key, @NotNull String name, int difficulty, @NotNull DoublePredicate progress) {
        long nonce = ProofOfWorkUtils.solve(DOMAIN, createSubject(key, name), difficulty, progress);

        return nonce == ProofOfWorkUtils.CANCELLED ? null : new PlayerStamp(key, name, nonce);
    }

    private static byte @NotNull [] createSubject(@NotNull IdentityKey key, @NotNull String name) {
        if (!PlayerNameUtils.isValid(name)) {
            throw Exceptions.newIllegalArgumentException("The player name {0} is not valid.", name);
        }

        byte[] keyBytes = key.getBytes();
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] subject = Arrays.copyOf(keyBytes, keyBytes.length + 1 + nameBytes.length);

        subject[keyBytes.length] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, subject, keyBytes.length + 1, nameBytes.length);

        return subject;
    }


    private final @NotNull IdentityKey key;
    private final @NotNull String name;
    private final long nonce;

    /**
     * Creates a {@link PlayerStamp} instance; the stamp is not checked.
     *
     * @param key   the player key
     * @param name  the player's name
     * @param nonce the stamp nonce
     */
    public PlayerStamp(@NotNull IdentityKey key, @NotNull String name, long nonce) {
        this.key   = key;
        this.name  = name;
        this.nonce = nonce;
    }

    /**
     * @return the player key
     */
    public @NotNull IdentityKey getKey() {
        return this.key;
    }

    /**
     * @return the player's name the stamp binds to the key
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the stamp nonce
     */
    public long getNonce() {
        return this.nonce;
    }

    /**
     * Checks the stamp: the name must be a valid player name, and the stamp hash of the key and the name must start with at least
     * {@code difficulty} zero bits.
     *
     * @param difficulty the number of leading zero bits the stamp hash needs
     * @return {@code true} if the stamp is valid, {@code false} otherwise
     */
    public boolean isValid(int difficulty) {
        return PlayerNameUtils.isValid(this.name) &&
               ProofOfWorkUtils.isValid(DOMAIN, createSubject(this.key, this.name), this.nonce, difficulty);
    }


    /**
     * @param other the other object
     * @return {@code true} if the other object is a stamp of the same key, name and nonce, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof PlayerStamp stamp && this.key.equals(stamp.key) && this.name.equals(stamp.name) && this.nonce == stamp.nonce;
    }

    /**
     * @return the hash code of the key, the name and the nonce
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.key, this.name, this.nonce);
    }

    /**
     * @return the name, the key and the nonce, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.name + " " + this.key + "/" + this.nonce;
    }
}
