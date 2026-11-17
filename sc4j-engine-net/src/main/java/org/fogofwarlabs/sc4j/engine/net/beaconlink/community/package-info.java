/**
 * Defines the community part of Beacon.Link that a mod (or the engine's own screens) sees: presences of players with the mod each runs
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence}), chat channels shared by every mod
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel}, {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLine},
 * {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChannelSummary}), whispers sealed end to end
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.Whisper}), friends and a local ignore list, all from
 * {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession}, which a session gives
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession#getCommunity()}). Events come to
 * {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunityListener}s on the session's thread. Players are objects
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer}, the account's key, whichever device it uses), shown as {@code Name#tag}.
 * <p>
 * The channel chat over a community session is in {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat}.
 */
package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;
