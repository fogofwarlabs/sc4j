package org.fogofwarlabs.sc4j.beaconlink.peer.store;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.SignOutRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.ObservedAddressStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Holds the client registrations of a node: one per connection of a key, at most the capacity per key and the capacity in total. A key's
 * connection that registers from a new address (a NAT remap) moves there; a connection that registers at the address of another connection of
 * its key replaces it, since one address is one socket. Expired registrations count until they are removed, which happens when a limit is
 * reached and on every {@link #removeExpired}.
 */
public final class RegistrationStore {
    private final int capacity;
    private final int capacityPerKey;

    private final @NotNull HashMap<@NotNull IdentityKey, @NotNull List<@NotNull Registration>> registrations;
    private int size;

    /**
     * Creates a {@link RegistrationStore} instance.
     *
     * @param capacity       the largest number of registrations
     * @param capacityPerKey the largest number of registrations of one key, one per connection
     * @throws IllegalArgumentException if a capacity is not positive
     */
    public RegistrationStore(int capacity, int capacityPerKey) {
        if (capacity <= 0 || capacityPerKey <= 0) {
            throw Exceptions.newIllegalArgumentException("The registration capacity {0} and the capacity per key {1} must be positive.",
                                                         capacity, capacityPerKey);
        }

        this.capacity       = capacity;
        this.capacityPerKey = capacityPerKey;

        this.registrations = new HashMap<>();
        this.size          = 0;
    }

    /**
     * @return the number of registrations, expired ones included until they are removed
     */
    public int getSize() {
        return this.size;
    }


    /**
     * Adds a registration, or replaces the registration of its connection and the registration of another connection of its key at its
     * address. A key that has the most live connections gives {@link ObservedAddressStatus#TOO_MANY_CONNECTIONS}, a store full of live
     * registrations {@link ObservedAddressStatus#FULL}.
     *
     * @param registration the registration
     * @param nowMillis    the current time
     * @return {@link ObservedAddressStatus#REGISTERED} if the registration is stored, otherwise why it is not
     */
    public @NotNull ObservedAddressStatus register(@NotNull Registration registration, long nowMillis) {
        List<Registration> connections = this.registrations.computeIfAbsent(registration.getKey(), key -> new ArrayList<>(1));

        this.removeIf(connections, stored -> stored.getConnection() == registration.getConnection() ||
                                             stored.getAddress().equals(registration.getAddress()));

        if (connections.size() >= this.capacityPerKey) {
            this.removeIf(connections, stored -> stored.isExpired(nowMillis));
        }

        ObservedAddressStatus status = ObservedAddressStatus.REGISTERED;

        if (connections.size() >= this.capacityPerKey) {
            status = ObservedAddressStatus.TOO_MANY_CONNECTIONS;
        } else if (this.size >= this.capacity) {
            this.removeExpired(nowMillis);

            if (this.size >= this.capacity) {
                status = ObservedAddressStatus.FULL;
            }
        }

        if (status == ObservedAddressStatus.REGISTERED) {
            this.registrations.computeIfAbsent(registration.getKey(), key -> new ArrayList<>(1)).add(registration);
            this.size++;
        } else if (connections.isEmpty()) {
            this.registrations.remove(registration.getKey());
        }

        return status;
    }

    /**
     * Removes the registration of a connection.
     *
     * @param key        the client's key
     * @param connection the client's connection number
     */
    public void remove(@NotNull IdentityKey key, long connection) {
        List<Registration> connections = this.registrations.get(key);

        if (connections != null) {
            this.removeIf(connections, stored -> stored.getConnection() == connection);

            if (connections.isEmpty()) {
                this.registrations.remove(key);
            }
        }
    }

    /**
     * Removes the registrations a player's sign-out ends: of every connection of the key but the kept one that started before the sign-out.
     *
     * @param signOut the player's sign-out
     * @return the removed registrations
     */
    public @NotNull List<@NotNull Registration> removeSignedOut(@NotNull SignOutRecord signOut) {
        List<Registration> connections = this.registrations.get(signOut.getAuthor());
        List<Registration> removed = new ArrayList<>();

        if (connections == null) {
            return removed;
        }

        for (Registration registration : connections) {
            if (signOut.isSignedOut(registration.getConnection(), registration.getConnectionStartMillis())) {
                removed.add(registration);
            }
        }

        this.removeIf(connections, removed::contains);

        if (connections.isEmpty()) {
            this.registrations.remove(signOut.getAuthor());
        }

        return removed;
    }

    /**
     * Finds the live registration of a connection.
     *
     * @param key        the client's key
     * @param connection the client's connection number
     * @param nowMillis  the current time
     * @return the registration, or {@code null} if the connection has no live registration
     */
    public @Nullable Registration find(@NotNull IdentityKey key, long connection, long nowMillis) {
        for (Registration registration : this.registrations.getOrDefault(key, List.of())) {
            if (registration.getConnection() == connection && !registration.isExpired(nowMillis)) {
                return registration;
            }
        }

        return null;
    }

    /**
     * Finds the live registration of a key at a source address: the check that a request comes from the address a connection of the client
     * proved.
     *
     * @param key       the client's key
     * @param address   the request's source address
     * @param nowMillis the current time
     * @return the registration, or {@code null} if no connection of the key has a live registration at that address
     */
    public @Nullable Registration findAt(@NotNull IdentityKey key, @NotNull InetSocketAddress address, long nowMillis) {
        for (Registration registration : this.registrations.getOrDefault(key, List.of())) {
            if (registration.getAddress().equals(address) && !registration.isExpired(nowMillis)) {
                return registration;
            }
        }

        return null;
    }

    /**
     * Removes the expired registrations.
     *
     * @param nowMillis the current time
     */
    public void removeExpired(long nowMillis) {
        Iterator<List<Registration>> keys = this.registrations.values().iterator();

        while (keys.hasNext()) {
            List<Registration> connections = keys.next();

            this.removeIf(connections, stored -> stored.isExpired(nowMillis));

            if (connections.isEmpty()) {
                keys.remove();
            }
        }
    }

    private void removeIf(@NotNull List<@NotNull Registration> connections, @NotNull Predicate<@NotNull Registration> filter) {
        int before = connections.size();

        connections.removeIf(filter);
        this.size -= before - connections.size();
    }
}
