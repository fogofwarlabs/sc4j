/**
 * Holds the engine's default parts of the Beacon.Link community screens, so a game built only on the engine gets the same channels, chat,
 * friends and room list as any mod: the room list filter ({@link org.fogofwarlabs.sc4j.engine.client.community.RoomFilter}) and two dialog
 * controls drawn with a {@link org.fogofwarlabs.sc4j.engine.client.dialog.DialogTheme}: a chat log
 * ({@link org.fogofwarlabs.sc4j.engine.client.community.ChatLogBox}) and a table of players, channels or rooms with latency bars
 * ({@link org.fogofwarlabs.sc4j.engine.client.community.TableBox}). The channel chat is in
 * {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat}. A mod with its own look keeps its drawing and uses the headless parts.
 */
package org.fogofwarlabs.sc4j.engine.client.community;
