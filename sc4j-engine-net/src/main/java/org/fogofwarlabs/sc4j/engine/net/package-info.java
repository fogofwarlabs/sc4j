/**
 * Defines the engine default network: datagram links and endpoints ({@link org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint}, UDP by
 * default), the lockstep transport over them ({@link org.fogofwarlabs.sc4j.engine.net.DatagramLockstepTransport}, on UDP
 * {@link org.fogofwarlabs.sc4j.engine.net.UdpLockstepTransport}) with acknowledgement, resend, reassembly and departures, and the clock of its
 * timeouts ({@link org.fogofwarlabs.sc4j.engine.net.NetworkClock}). The transfer of payloads over the same links is in the subpackage
 * {@code transfer}.
 * <p>
 * The network is not a simulation package: it reads a clock and the operating system's sockets. What it delivers to the lockstep core does not
 * depend on either.
 */
package org.fogofwarlabs.sc4j.engine.net;
