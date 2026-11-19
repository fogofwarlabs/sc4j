/**
 * Defines the engine's transfer of payloads between peers ({@link org.fogofwarlabs.sc4j.engine.net.transfer.TransferService}): maps,
 * replays, mod data or any other bytes a peer offers under a key ({@link org.fogofwarlabs.sc4j.engine.net.transfer.TransferKey}) and another
 * peer requests into a sink ({@link org.fogofwarlabs.sc4j.engine.net.transfer.TransferSink}), with the payload's size and digest
 * ({@link org.fogofwarlabs.sc4j.engine.net.transfer.TransferPayload}), flow and congestion control, resume, verification, progress and
 * cancel ({@link org.fogofwarlabs.sc4j.engine.net.transfer.TransferDownload}). The service runs over the datagram link of any network; the
 * Beacon.Link one is {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkTransferService}.
 */
package org.fogofwarlabs.sc4j.engine.net.transfer;
