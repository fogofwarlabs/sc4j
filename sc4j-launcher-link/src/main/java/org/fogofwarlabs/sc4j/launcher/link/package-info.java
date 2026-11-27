/**
 * Links the launcher with the runtimes it starts over a local Unix domain socket: the launcher's
 * {@link org.fogofwarlabs.sc4j.launcher.link.LauncherLinkServer} serves named services, a runtime's
 * {@link org.fogofwarlabs.sc4j.launcher.link.LauncherLinkClient} sends them requests and receives their events. The
 * {@link org.fogofwarlabs.sc4j.launcher.link.LauncherLinkAddress} names the socket and the token a client proves itself with. Payloads are
 * bytes; each service owns its payload codec.
 */
package org.fogofwarlabs.sc4j.launcher.link;
