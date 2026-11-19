/**
 * Defines what a mod sees of Beacon.Link, the network of nodes that anyone runs: a session that registers at the nodes of the run's command
 * line and learns the observed address ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession}), rooms the mod publishes
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.HostedRoom}) and room searches in the topic of the run's mod, which tell the joinable
 * rooms of the run's mod identity from the rooms of the mod's other versions ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.RoomSearch})
 * with the estimated round trip time to each host ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate}), and channels to
 * other participants ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.ParticipantChannel}): to a room's host when joining a room (the host
 * checks the engine version and the mod identity), or to another participant, directly or through a third participant, and the overlay over
 * those channels ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkOverlay}) that the engine's lobby, lockstep transport
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkLockstepTransport}) and transfer of payloads
 * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkTransferService}) run on. A session's community part (presence, chat channels
 * shared by every mod, whispers, friends) is in the subpackage {@code community}. The runtime gives a mod the implementation of
 * {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink} in the launch request of the mod API ({@code ModLaunch#getBeaconLink()});
 * the engine holds no network implementation of it.
 * <p>
 * A session is driven by its owner's thread: {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession#poll()} receives the
 * waiting datagrams, resends and times out requests, refreshes registrations and rooms, and carries out the channels' work. Nodes carry no
 * game traffic: they relay the signed signals that open a channel, and the channel's datagrams go between the participants.
 */
package org.fogofwarlabs.sc4j.engine.net.beaconlink;
