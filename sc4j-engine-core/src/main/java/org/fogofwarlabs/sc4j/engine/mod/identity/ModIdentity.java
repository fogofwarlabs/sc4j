package org.fogofwarlabs.sc4j.engine.mod.identity;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Identifies exactly what runs in a session, for lockstep games, replays and Beacon.Link matching: the engine API
 * version, the mod id and version, the content digest of the mod jar ({@link ModContentDigestUtils}) and the digest of
 * the identity data the mod added ({@code ModRegistration.addIdentityData}). Two runs are compatible only when their
 * identities are equal.
 * <p>
 * The engine is responsible for the jar only; a mod that uses external data decides itself whether to add that data's
 * version or digest.
 * <p>
 * The identity's wire form ({@link #write}, {@link #read}), used by the network lobby, the room discovery and the engine replay: the engine
 * version, the mod id and the mod version as modified UTF-8 strings ({@link DataOutput#writeUTF}), then the content digest and the identity
 * data digest, each as its length (one byte) and its bytes.
 */
public final class ModIdentity {
    private static final @NotNull HexFormat HEX_FORMAT = HexFormat.of();
    private static final int SHORT_DIGEST_LENGTH = 8;
    private static final int MAXIMUM_DIGEST_LENGTH = 0xFF;

    /**
     * Creates a {@link ModIdentity} instance for a run.
     *
     * @param engine        the engine API version of the runtime
     * @param manifest      the manifest of the mod
     * @param contentDigest the content digest of the mod jar
     * @param identityData  the identity data the mod added, in the order it added them
     * @return the created {@link ModIdentity} instance
     */
    public static @NotNull ModIdentity create(@NotNull EngineVersion engine, @NotNull ModManifest manifest, byte @NotNull [] contentDigest,
                                              @NotNull List<byte @NotNull []> identityData) {
        MessageDigest dataDigest = ModContentDigestUtils.createDigest();
        ByteBuffer size = ByteBuffer.allocate(Integer.BYTES);

        for (byte[] data : identityData) {
            dataDigest.update(size.clear().putInt(data.length).array());
            dataDigest.update(data);
        }

        return new ModIdentity(engine, manifest.getId(), manifest.getVersion(), contentDigest.clone(), dataDigest.digest());
    }

    /**
     * Creates a {@link ModIdentity} instance for a program that takes part in Beacon.Link without a mod jar, such as the launcher: its content
     * digest and its identity data digest are the digests of no data.
     *
     * @param engine     the engine API version of the program
     * @param modId      the id the program shows in its presence
     * @param modVersion the program's version
     * @return the created {@link ModIdentity} instance
     */
    public static @NotNull ModIdentity createWithoutJar(@NotNull EngineVersion engine, @NotNull String modId, @NotNull ModVersion modVersion) {
        byte[] emptyDigest = ModContentDigestUtils.createDigest().digest();

        return new ModIdentity(engine, modId, modVersion, emptyDigest, emptyDigest.clone());
    }

    /**
     * Reads an identity in its wire form.
     *
     * @param input the input
     * @return the created {@link ModIdentity} instance
     * @throws IOException if the input ends early or holds no identity of the wire form
     */
    public static @NotNull ModIdentity read(@NotNull DataInput input) throws IOException {
        String engine = input.readUTF();
        String modId = input.readUTF();
        String modVersion = input.readUTF();
        byte[] contentDigest = readDigest(input);
        byte[] dataDigest = readDigest(input);

        try {
            return new ModIdentity(EngineVersion.parse(engine), modId, ModVersion.parse(modVersion), contentDigest, dataDigest);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid mod identity: {0} {1} (engine {2})", modId, modVersion, engine);
        }
    }

    private static byte @NotNull [] readDigest(@NotNull DataInput input) throws IOException {
        int length = input.readUnsignedByte();

        if (length < SHORT_DIGEST_LENGTH) {
            throw Exceptions.newIOException("Invalid mod identity digest length: {0}", length);
        }

        byte[] digest = new byte[length];

        input.readFully(digest);

        return digest;
    }

    private static void writeDigest(@NotNull DataOutput output, byte @NotNull [] digest) throws IOException {
        if (digest.length < SHORT_DIGEST_LENGTH || digest.length > MAXIMUM_DIGEST_LENGTH) {
            throw Exceptions.newIOException("A mod identity digest of {0} bytes has no wire form.", digest.length);
        }

        output.writeByte(digest.length);
        output.write(digest);
    }


    private final @NotNull EngineVersion engine;
    private final @NotNull String modId;
    private final @NotNull ModVersion modVersion;
    private final byte @NotNull [] contentDigest;
    private final byte @NotNull [] dataDigest;

    /**
     * Creates a {@link ModIdentity} instance.
     *
     * @param engine        the engine API version
     * @param modId         the mod id
     * @param modVersion    the mod version
     * @param contentDigest the content digest of the mod jar
     * @param dataDigest    the digest of the identity data
     */
    private ModIdentity(@NotNull EngineVersion engine, @NotNull String modId, @NotNull ModVersion modVersion,
                        byte @NotNull [] contentDigest, byte @NotNull [] dataDigest) {
        this.engine        = engine;
        this.modId         = modId;
        this.modVersion    = modVersion;
        this.contentDigest = contentDigest;
        this.dataDigest    = dataDigest;
    }

    /**
     * @return the engine API version of the runtime
     */
    public @NotNull EngineVersion getEngine() {
        return this.engine;
    }

    /**
     * @return the mod id
     */
    public @NotNull String getModId() {
        return this.modId;
    }

    /**
     * @return the mod version
     */
    public @NotNull ModVersion getModVersion() {
        return this.modVersion;
    }

    /**
     * @return a copy of the content digest of the mod jar
     */
    public byte @NotNull [] getContentDigest() {
        return this.contentDigest.clone();
    }

    /**
     * @return a copy of the digest of the identity data the mod added
     */
    public byte @NotNull [] getDataDigest() {
        return this.dataDigest.clone();
    }

    /**
     * Finds the first part in which another identity differs from this one: the engine version, the mod id, the mod version, the content
     * digest, the identity data digest.
     *
     * @param other the other identity
     * @return the first differing part, or {@code null} if the identities are equal
     */
    public @Nullable ModIdentityMismatch findMismatch(@NotNull ModIdentity other) {
        if (!this.engine.equals(other.engine)) {
            return ModIdentityMismatch.ENGINE_VERSION;
        }

        if (!this.modId.equals(other.modId)) {
            return ModIdentityMismatch.MOD_ID;
        }

        if (!this.modVersion.equals(other.modVersion)) {
            return ModIdentityMismatch.MOD_VERSION;
        }

        if (!Arrays.equals(this.contentDigest, other.contentDigest)) {
            return ModIdentityMismatch.MOD_CONTENT;
        }

        if (!Arrays.equals(this.dataDigest, other.dataDigest)) {
            return ModIdentityMismatch.IDENTITY_DATA;
        }

        return null;
    }

    /**
     * Writes the identity in its wire form.
     *
     * @param output the output
     * @throws IOException if the output cannot be written or a digest is longer than {@code 255} bytes
     */
    public void write(@NotNull DataOutput output) throws IOException {
        output.writeUTF(this.engine.toString());
        output.writeUTF(this.modId);
        output.writeUTF(this.modVersion.toString());
        writeDigest(output, this.contentDigest);
        writeDigest(output, this.dataDigest);
    }

    /**
     * Describes the identity on one line for logs and messages.
     *
     * @return the line, such as {@code my-mod 1.0.0 (engine 0.1, content 1a2b3c4d5e6f7a8b, data 0f1e2d3c4b5a6978)}
     */
    public @NotNull String describe() {
        return this.modId + " " + this.modVersion + " (engine " + this.engine +
               ", content " + HEX_FORMAT.formatHex(this.contentDigest, 0, SHORT_DIGEST_LENGTH) +
               ", data " + HEX_FORMAT.formatHex(this.dataDigest, 0, SHORT_DIGEST_LENGTH) + ")";
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * <p>
     * The {@code equals} method implements an equivalence relation on non-null object references.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     * @implSpec Two identities are equal when the engine versions, mod ids, mod versions, content digests and identity data
     *         digests are equal.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ModIdentity identity && this.engine.equals(identity.engine) && this.modId.equals(identity.modId) &&
               this.modVersion.equals(identity.modVersion) && Arrays.equals(this.contentDigest, identity.contentDigest) &&
               Arrays.equals(this.dataDigest, identity.dataDigest);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     * @implSpec Combines the hash codes of the mod id and the content digest, consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return this.modId.hashCode() ^ Arrays.hashCode(this.contentDigest);
    }

    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns {@link #describe()}.
     */
    @Override
    public @NotNull String toString() {
        return this.describe();
    }
}
