package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

/**
 * Holds a payload a service offers under a key ({@link TransferService#offer}): every peer that requests the key gets the payload until the
 * offer is withdrawn.
 */
public final class TransferOffer {
    private final @NotNull TransferKey key;
    private final @NotNull TransferPayload payload;
    private final @NotNull TransferSource source;
    private boolean withdrawn;

    TransferOffer(@NotNull TransferKey key, @NotNull TransferPayload payload, @NotNull TransferSource source) {
        this.key     = key;
        this.payload = payload;
        this.source  = source;

        this.withdrawn = false;
    }

    /**
     * @return the key the payload is offered under
     */
    public @NotNull TransferKey getKey() {
        return this.key;
    }

    /**
     * @return the offered payload
     */
    public @NotNull TransferPayload getPayload() {
        return this.payload;
    }

    /**
     * @return the source of the payload's bytes
     */
    @NotNull TransferSource getSource() {
        return this.source;
    }

    /**
     * @return {@code true} if the offer was withdrawn, {@code false} otherwise
     */
    public boolean isWithdrawn() {
        return this.withdrawn;
    }

    /**
     * Withdraws the offer: later requests are refused, and the service's next update cancels the uploads of the payload.
     */
    public void withdraw() {
        this.withdrawn = true;
    }
}
