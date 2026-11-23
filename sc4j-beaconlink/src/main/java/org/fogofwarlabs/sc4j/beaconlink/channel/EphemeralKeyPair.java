package org.fogofwarlabs.sc4j.beaconlink.channel;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Holds the X25519 key pair of one side of one channel: made for the channel and dropped with it, so that a later disclosure of the
 * install's identity key does not open recorded channels. Its public key ({@value #KEY_LENGTH} bytes) travels in the signed offer or answer.
 */
public final class EphemeralKeyPair {
    /**
     * The byte length of an X25519 public key and of the agreed secret.
     */
    public static final int KEY_LENGTH = 32;

    private static final @NotNull String ALGORITHM = "X25519";
    private static final byte @NotNull [] X509_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100");

    /**
     * Creates an {@link EphemeralKeyPair} instance with a new random key pair.
     *
     * @param random the source of the private key
     * @return the created {@link EphemeralKeyPair} instance
     */
    public static @NotNull EphemeralKeyPair generate(@NotNull SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);

            generator.initialize(NamedParameterSpec.X25519, random);

            KeyPair pair = generator.generateKeyPair();
            byte[] encoded = pair.getPublic().getEncoded();

            return new EphemeralKeyPair(pair.getPrivate(), Arrays.copyOfRange(encoded, encoded.length - KEY_LENGTH, encoded.length));
        } catch (GeneralSecurityException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK cannot create an {0} key pair.", ALGORITHM);
        }
    }


    private final @NotNull PrivateKey privateKey;
    private final byte @NotNull [] publicKey;

    private EphemeralKeyPair(@NotNull PrivateKey privateKey, byte @NotNull [] publicKey) {
        this.privateKey = privateKey;
        this.publicKey  = publicKey;
    }

    /**
     * @return a copy of the {@value #KEY_LENGTH} public key bytes
     */
    public byte @NotNull [] getPublicKey() {
        return this.publicKey.clone();
    }


    /**
     * Agrees the shared secret with the other side's public key.
     *
     * @param peerPublicKey the other side's public key bytes
     * @return the {@value #KEY_LENGTH} secret bytes, or {@code null} if the key has the wrong length, is not a valid point or gives the all-zero secret of a small-order point
     */
    public byte @Nullable [] agree(byte @NotNull [] peerPublicKey) {
        if (peerPublicKey.length != KEY_LENGTH) {
            return null;
        }

        byte[] encoded = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + KEY_LENGTH);

        System.arraycopy(peerPublicKey, 0, encoded, X509_PREFIX.length, KEY_LENGTH);

        byte[] secret;

        try {
            PublicKey peer = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
            KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);

            agreement.init(this.privateKey);
            agreement.doPhase(peer, true);
            secret = agreement.generateSecret();
        } catch (GeneralSecurityException exception) {
            return null;
        }

        int bits = 0;

        for (byte value : secret) {
            bits |= value;
        }

        return bits == 0 ? null : secret;
    }

    /**
     * @return the public key in hexadecimal, for logs; the private key is never shown
     */
    @Override
    public @NotNull String toString() {
        return HexFormat.of().formatHex(this.publicKey);
    }
}
