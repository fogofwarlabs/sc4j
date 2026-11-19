package org.fogofwarlabs.sc4j.engine.net.transfer;

/**
 * Lists the states of a download ({@link TransferDownload}).
 */
public enum TransferState {
    /**
     * The request went to the offering peer, which has not answered yet.
     */
    REQUESTING,
    /**
     * The offering peer accepted the request and sends the payload.
     */
    RECEIVING,
    /**
     * The sink holds the whole payload and its digest matched.
     */
    COMPLETE,
    /**
     * The download failed ({@link TransferDownload#getFailure()}).
     */
    FAILED,
    /**
     * The requesting side canceled the download.
     */
    CANCELED
}
