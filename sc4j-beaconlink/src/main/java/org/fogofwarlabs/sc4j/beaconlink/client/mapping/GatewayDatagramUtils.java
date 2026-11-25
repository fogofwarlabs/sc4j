package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Sends the UDP requests of PCP and NAT-PMP: a request to a gateway, resent at a doubling interval from {@value #FIRST_RESEND_MILLIS} ms until
 * the answer time ends, and the first accepted answer from that gateway.
 */
final class GatewayDatagramUtils {
    private static final long FIRST_RESEND_MILLIS = 250L;
    private static final int MAXIMUM_ANSWER_LENGTH = 1100;
    private static final int RESEND_FACTOR = 2;

    /**
     * Sends a request to a gateway and waits for its answer.
     *
     * @param route        the gateway and the local address the request goes out from
     * @param request      the request
     * @param answerMillis the time to wait for an answer, in milliseconds
     * @param accepts      tells whether an answer from the gateway is the one waited for
     * @return the accepted answer, or {@code null} if none arrived in time
     * @throws IOException if the socket cannot be opened
     */
    static @Nullable DatagramPacket exchange(@NotNull GatewayRoute route, byte @NotNull [] request, long answerMillis,
                                             @NotNull Predicate<@NotNull DatagramPacket> accepts) throws IOException {
        InetSocketAddress target = route.getAddress();

        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(route.getLocalAddress(), 0))) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(answerMillis);
            long nextSendNanos = System.nanoTime();
            long intervalMillis = FIRST_RESEND_MILLIS;
            byte[] buffer = new byte[MAXIMUM_ANSWER_LENGTH];

            while (true) {
                long nowNanos = System.nanoTime();

                if (nowNanos >= deadlineNanos) {
                    return null;
                }

                if (nowNanos >= nextSendNanos) {
                    send(socket, request, target);

                    nextSendNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(intervalMillis);
                    intervalMillis *= RESEND_FACTOR;
                }

                long waitMillis = TimeUnit.NANOSECONDS.toMillis(Math.min(nextSendNanos, deadlineNanos) - nowNanos);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                socket.setSoTimeout((int) Math.max(1L, waitMillis));

                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException | PortUnreachableException exception) {
                    // No answer yet, or an earlier datagram reached a closed port: wait for the next one.
                    continue;
                }

                DatagramPacket answer = new DatagramPacket(Arrays.copyOf(buffer, packet.getLength()), packet.getLength(), packet.getSocketAddress());

                if (target.equals(packet.getSocketAddress()) && accepts.test(answer)) {
                    return answer;
                }
            }
        }
    }

    private static void send(@NotNull DatagramSocket socket, byte @NotNull [] request, @NotNull InetSocketAddress target) {
        try {
            socket.send(new DatagramPacket(request, request.length, target));
        } catch (IOException exception) {
            // A gateway that cannot be reached gives no answer, like one that drops the request.
        }
    }

    /**
     * Finds the local address facing a gateway: the route's local address, or the address of a UDP socket connected to the gateway.
     *
     * @param route the gateway and the local address the requests go out from
     * @return the local address, or {@code null} if no route leads to the gateway
     */
    static @Nullable InetAddress findLocalAddress(@NotNull GatewayRoute route) {
        InetAddress routed = route.getLocalAddress();

        if (routed != null) {
            return routed;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(route.getAddress());

            InetAddress local = socket.getLocalAddress();

            return local == null || local.isAnyLocalAddress() ? null : local;
        } catch (IOException exception) {
            return null;
        }
    }

    private GatewayDatagramUtils() {
    }
}
