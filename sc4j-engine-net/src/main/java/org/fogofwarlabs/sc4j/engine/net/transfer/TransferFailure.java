package org.fogofwarlabs.sc4j.engine.net.transfer;

/**
 * Lists why a download failed ({@link TransferState#FAILED}).
 */
public enum TransferFailure {
    /**
     * The peer offers no payload of the key, or serves no more downloads.
     */
    REFUSED,
    /**
     * Nothing of the download arrived from the peer for the idle time.
     */
    TIMED_OUT,
    /**
     * The received bytes do not have the announced digest; the sink's bytes were dropped.
     */
    VERIFICATION_FAILED,
    /**
     * The sink could not be read or written.
     */
    STORAGE_FAILED,
    /**
     * The peer canceled the upload, or withdrew the payload.
     */
    CANCELED_BY_PEER,
    /**
     * The service was closed.
     */
    CLOSED
}
