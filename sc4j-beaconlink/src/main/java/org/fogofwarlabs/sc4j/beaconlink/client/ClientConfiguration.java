package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the timings of a Beacon.Link client: how often and how long a request is sent, when a node that failed is tried again, the requested
 * registration lifetime, the lifetime and refresh of a published room, the community values (the player stamp difficulty, the lifetime and
 * refresh of a presence and the chat rate) and the port mapping of the session socket on the router, off unless set.
 * {@link #createDefault()} gives the values of a normal run without port mapping.
 */
public final class ClientConfiguration {
    private static final long DEFAULT_RESEND_MILLIS = 1_000L;
    private static final int DEFAULT_REQUEST_ATTEMPTS = 4;
    private static final long DEFAULT_RETRY_MILLIS = 30_000L;
    private static final int DEFAULT_REGISTRATION_SECONDS = 120;
    private static final long DEFAULT_ROOM_LIFETIME_MILLIS = 120_000L;
    private static final long DEFAULT_ROOM_REFRESH_MILLIS = 40_000L;
    private static final int DEFAULT_STAMP_DIFFICULTY = PlayerStamp.DEFAULT_DIFFICULTY;
    private static final long DEFAULT_PRESENCE_LIFETIME_MILLIS = 120_000L;
    private static final long DEFAULT_PRESENCE_REFRESH_MILLIS = 50_000L;
    private static final int DEFAULT_CHAT_BURST = 5;
    private static final int DEFAULT_CHAT_RATE = 1;
    private static final int MAXIMUM_REGISTRATION_SECONDS = 0xffff;
    private static final int MAXIMUM_STAMP_DIFFICULTY = 256;

    /**
     * Builds {@link ClientConfiguration} instances, starting from the default values.
     */
    public static final class Builder {
        private long resendMillis;
        private int requestAttempts;
        private long retryMillis;
        private int registrationSeconds;

        private long roomLifetimeMillis;
        private long roomRefreshMillis;

        private int stampDifficulty;
        private long presenceLifetimeMillis;
        private long presenceRefreshMillis;
        private int chatBurst;
        private int chatRate;

        private @Nullable PortMappingConfiguration portMapping;

        /**
         * Creates a {@link Builder} instance with the default values.
         */
        public Builder() {
            this.resendMillis        = DEFAULT_RESEND_MILLIS;
            this.requestAttempts     = DEFAULT_REQUEST_ATTEMPTS;
            this.retryMillis         = DEFAULT_RETRY_MILLIS;
            this.registrationSeconds = DEFAULT_REGISTRATION_SECONDS;

            this.roomLifetimeMillis = DEFAULT_ROOM_LIFETIME_MILLIS;
            this.roomRefreshMillis  = DEFAULT_ROOM_REFRESH_MILLIS;

            this.stampDifficulty        = DEFAULT_STAMP_DIFFICULTY;
            this.presenceLifetimeMillis = DEFAULT_PRESENCE_LIFETIME_MILLIS;
            this.presenceRefreshMillis  = DEFAULT_PRESENCE_REFRESH_MILLIS;
            this.chatBurst              = DEFAULT_CHAT_BURST;
            this.chatRate               = DEFAULT_CHAT_RATE;

            this.portMapping = null;
        }

        /**
         * Sets the time between two sends of an unanswered request.
         *
         * @param resendMillis the time in milliseconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setResendMillis(long resendMillis) {
            this.resendMillis = resendMillis;

            return this;
        }

        /**
         * Sets how often a request is sent before the node counts as not answering.
         *
         * @param requestAttempts the number of sends, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setRequestAttempts(int requestAttempts) {
            this.requestAttempts = requestAttempts;

            return this;
        }

        /**
         * Sets the time after which a node that did not answer or refused is tried again.
         *
         * @param retryMillis the time in milliseconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setRetryMillis(long retryMillis) {
            this.retryMillis = retryMillis;

            return this;
        }

        /**
         * Sets the registration lifetime the client asks for; the client refreshes a registration after half the granted lifetime.
         *
         * @param registrationSeconds the lifetime in seconds, {@code 1} to {@code 65535}
         * @return this instance for chaining
         */
        public @NotNull Builder setRegistrationSeconds(int registrationSeconds) {
            this.registrationSeconds = registrationSeconds;

            return this;
        }

        /**
         * Sets the lifetime of a room advertisement.
         *
         * @param roomLifetimeMillis the lifetime in milliseconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setRoomLifetimeMillis(long roomLifetimeMillis) {
            this.roomLifetimeMillis = roomLifetimeMillis;

            return this;
        }

        /**
         * Sets the time after which a published room is signed again with a new sequence and expiry.
         *
         * @param roomRefreshMillis the time in milliseconds, positive and shorter than the room lifetime
         * @return this instance for chaining
         */
        public @NotNull Builder setRoomRefreshMillis(long roomRefreshMillis) {
            this.roomRefreshMillis = roomRefreshMillis;

            return this;
        }

        /**
         * Sets the number of leading zero bits of the install's player stamp: the network's difficulty, which the peers check.
         *
         * @param stampDifficulty the difficulty, {@code 0} to {@code 256}
         * @return this instance for chaining
         */
        public @NotNull Builder setStampDifficulty(int stampDifficulty) {
            this.stampDifficulty = stampDifficulty;

            return this;
        }

        /**
         * Sets the lifetime of a presence.
         *
         * @param presenceLifetimeMillis the time in milliseconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setPresenceLifetimeMillis(long presenceLifetimeMillis) {
            this.presenceLifetimeMillis = presenceLifetimeMillis;

            return this;
        }

        /**
         * Sets the time after which a presence is signed again with a new sequence and expiry.
         *
         * @param presenceRefreshMillis the time in milliseconds, positive and shorter than the presence lifetime
         * @return this instance for chaining
         */
        public @NotNull Builder setPresenceRefreshMillis(long presenceRefreshMillis) {
            this.presenceRefreshMillis = presenceRefreshMillis;

            return this;
        }

        /**
         * Sets the number of chat lines the client sends at once before the chat rate holds them back: the network's chat burst.
         *
         * @param chatBurst the number, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setChatBurst(int chatBurst) {
            this.chatBurst = chatBurst;

            return this;
        }

        /**
         * Sets the number of chat lines per second the client sends after a burst: the network's chat rate.
         *
         * @param chatRate the number, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setChatRate(int chatRate) {
            this.chatRate = chatRate;

            return this;
        }

        /**
         * Sets how the session socket is mapped on the router; the mapped address becomes the first address candidate of the session's channels.
         *
         * @param portMapping the router and lease settings, or {@code null} for no port mapping
         * @return this instance for chaining
         */
        public @NotNull Builder setPortMapping(@Nullable PortMappingConfiguration portMapping) {
            this.portMapping = portMapping;

            return this;
        }

        /**
         * Builds a {@link ClientConfiguration} instance from the values set.
         *
         * @return the built {@link ClientConfiguration} instance
         * @throws IllegalArgumentException if a value is out of its range
         */
        public @NotNull ClientConfiguration build() {
            return new ClientConfiguration(this);
        }
    }

    /**
     * Creates a {@link ClientConfiguration} instance with the default values.
     *
     * @return the created {@link ClientConfiguration} instance
     */
    public static @NotNull ClientConfiguration createDefault() {
        return new Builder().build();
    }


    private final long resendMillis;
    private final int requestAttempts;
    private final long retryMillis;
    private final int registrationSeconds;

    private final long roomLifetimeMillis;
    private final long roomRefreshMillis;

    private final int stampDifficulty;
    private final long presenceLifetimeMillis;
    private final long presenceRefreshMillis;
    private final int chatBurst;
    private final int chatRate;

    private final @Nullable PortMappingConfiguration portMapping;

    private ClientConfiguration(@NotNull Builder builder) {
        if (builder.resendMillis <= 0L || builder.requestAttempts <= 0 || builder.retryMillis <= 0L) {
            throw Exceptions.newIllegalArgumentException("The resend time {0}, the attempts {1} and the retry time {2} must be positive.",
                                                         builder.resendMillis, builder.requestAttempts, builder.retryMillis);
        }

        if (builder.registrationSeconds <= 0 || builder.registrationSeconds > MAXIMUM_REGISTRATION_SECONDS) {
            throw Exceptions.newIllegalArgumentException("The registration lifetime {0} is outside 1..{1}.",
                                                         builder.registrationSeconds, MAXIMUM_REGISTRATION_SECONDS);
        }

        if (builder.roomRefreshMillis <= 0L || builder.roomRefreshMillis >= builder.roomLifetimeMillis) {
            throw Exceptions.newIllegalArgumentException("The room refresh time {0} must be positive and shorter than the room lifetime {1}.",
                                                         builder.roomRefreshMillis, builder.roomLifetimeMillis);
        }

        this.resendMillis        = builder.resendMillis;
        this.requestAttempts     = builder.requestAttempts;
        this.retryMillis         = builder.retryMillis;
        this.registrationSeconds = builder.registrationSeconds;

        if (builder.stampDifficulty < 0 || builder.stampDifficulty > MAXIMUM_STAMP_DIFFICULTY || builder.chatBurst <= 0 || builder.chatRate <= 0 ||
            builder.presenceRefreshMillis <= 0L || builder.presenceRefreshMillis >= builder.presenceLifetimeMillis) {
            throw Exceptions.newIllegalArgumentException("The stamp difficulty {0}, the chat burst {1}, the chat rate {2}, or the presence refresh " +
                                                         "time {3} and lifetime {4} are out of range.",
                                                         builder.stampDifficulty, builder.chatBurst, builder.chatRate,
                                                         builder.presenceRefreshMillis, builder.presenceLifetimeMillis);
        }

        this.roomLifetimeMillis = builder.roomLifetimeMillis;
        this.roomRefreshMillis  = builder.roomRefreshMillis;

        this.stampDifficulty        = builder.stampDifficulty;
        this.presenceLifetimeMillis = builder.presenceLifetimeMillis;
        this.presenceRefreshMillis  = builder.presenceRefreshMillis;
        this.chatBurst              = builder.chatBurst;
        this.chatRate               = builder.chatRate;

        this.portMapping = builder.portMapping;
    }

    /**
     * @return the time between two sends of an unanswered request, in milliseconds
     */
    public long getResendMillis() {
        return this.resendMillis;
    }

    /**
     * @return how often a request is sent before the node counts as not answering
     */
    public int getRequestAttempts() {
        return this.requestAttempts;
    }

    /**
     * @return the time after which a node that did not answer or refused is tried again, in milliseconds
     */
    public long getRetryMillis() {
        return this.retryMillis;
    }

    /**
     * @return the registration lifetime the client asks for, in seconds
     */
    public int getRegistrationSeconds() {
        return this.registrationSeconds;
    }

    /**
     * @return the lifetime of a room advertisement, in milliseconds
     */
    public long getRoomLifetimeMillis() {
        return this.roomLifetimeMillis;
    }

    /**
     * @return the time after which a published room is signed again, in milliseconds
     */
    public long getRoomRefreshMillis() {
        return this.roomRefreshMillis;
    }

    /**
     * @return the number of leading zero bits of the install's player stamp
     */
    public int getStampDifficulty() {
        return this.stampDifficulty;
    }

    /**
     * @return the lifetime of a presence, in milliseconds
     */
    public long getPresenceLifetimeMillis() {
        return this.presenceLifetimeMillis;
    }

    /**
     * @return the time after which a presence is signed again, in milliseconds
     */
    public long getPresenceRefreshMillis() {
        return this.presenceRefreshMillis;
    }

    /**
     * @return the number of chat lines the client sends at once before the chat rate holds them back
     */
    public int getChatBurst() {
        return this.chatBurst;
    }

    /**
     * @return the number of chat lines per second the client sends after a burst
     */
    public int getChatRate() {
        return this.chatRate;
    }

    /**
     * @return how the session socket is mapped on the router, or {@code null} for no port mapping
     */
    public @Nullable PortMappingConfiguration getPortMapping() {
        return this.portMapping;
    }
}
