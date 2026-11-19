# SC4J engine API policy

**English** | [한국어](api-policy.ko.md)

What the engine API is, how its version changes and what stays compatible. Summary for mod authors: [mod author guide, chapter 16](mod-guide.md#16-api-and-versions).

## 1. What the API is

Every `public` or `protected` class, interface, constructor, method and field (constant values included) of the main classes of these modules, except the internal elements of section 2:

| Module | Contents | Listing |
|---|---|---|
| `sc4j-base` | exceptions, logging, binary, file, JSON and text helpers | `sc4j-base/api/sc4j-base.api` |
| `sc4j-mod-manifest` | mod manifest `sc4j-mod.json`, validation, settings, versions, install layout | `sc4j-mod-manifest/api/sc4j-mod-manifest.api` |
| `sc4j-engine-core` | JSON, fixed-point math, map grid, terrain, players, state hash, content types, mod identity, resource models | `sc4j-engine-core/api/sc4j-engine-core.api` |
| `sc4j-engine-sim` | game, units, default systems, content, animation programs, orders, commands, computer players, triggers, data pack, maps, lockstep, replays, saves | `sc4j-engine-sim/api/sc4j-engine-sim.api` |
| `sc4j-engine-net` | datagram links, lockstep transport, payload transfer, LAN discovery, network lobby, Beacon.Link and community interfaces, channel chat | `sc4j-engine-net/api/sc4j-engine-net.api` |
| `sc4j-engine-client` | host, scenes, input, renderer abstraction, projections, text, HUD, command card, console, dialogs, cursors, skins, world view, audio, video, settings store | `sc4j-engine-client/api/sc4j-engine-client.api` |
| `sc4j-engine` | mod API (`engine.mod`), client application (`engine.client.app`), community screen parts (`engine.client.community`), lobby parts (`engine.client.lobby`) | `sc4j-engine/api/sc4j-engine.api` |
| `sc4j-catalog` | what is published: GitHub releases, release versions, the check for a newer release, the mod catalog | `sc4j-catalog/api/sc4j-catalog.api` |

The mod plugin compiles a mod against these modules, the mod API that the runtime provides ([mod author guide, chapter 7.1](mod-guide.md#71-the-mods-own-build)).

Published test fixtures are API too (`testImplementation testFixtures('org.fogofwarlabs.sc4j:<module>:<version>')`, listed in `<module>/api/<module>-test-fixtures.api`): `FakeInstallationRecords` and `TestJarUtils` (`sc4j-mod-manifest`), `RecordingBeaconLink` and `RecordingCommunitySession` (`sc4j-engine-net`), `ManualProtocolClock` (`sc4j-beaconlink-protocol`), `ClientScenario` and the loopback NAT emulator (`sc4j-beaconlink`). Other modules publish no test fixtures.

A listing is the `javap -protected -constants` output of the module's compiled classes, one block per class, classes and members sorted. A class is listed when it and every enclosing class are `public` or `protected`. Declaration order does not change it; compiler bridge methods appear in it. Annotations do not, so a nullability change is a contract change that review must catch (section 3.2).

The Javadoc of an API element is part of its contract. Every API element has Javadoc; `javadoc` runs without warnings.

## 2. Internal elements

Public for the engine's modules and the runtime, but not API: they may change or disappear in any version. Each carries `@ApiStatus.Internal` (JetBrains), the listings leave them out, and their package-info says so.

| Element | Role; what mods use instead |
|---|---|
| `org.fogofwarlabs.sc4j.engine.mod.loading` | the runtime's mod loader |
| `org.fogofwarlabs.sc4j.engine.client.render.gl` | OpenGL renderer default; mods use `engine.client.render` (`GraphicsDevice.getCurrent()` creates the `GpuCanvas` and `ShaderProgram`s of own passes) |
| `org.fogofwarlabs.sc4j.engine.client.audio.openal` | OpenAL audio default; mods use `engine.client.audio` (`AudioDevice.openDefault()`) |
| `engine.client.host.GlfwClientHost`, `GlfwHostWindow`, `GlfwInput`, `GlfwKeyCodes`, `GlfwCursorSurface` | GLFW host; mods use the `engine.client.host` interfaces (`ClientHost.initialize()`, `HostWindow`, `WindowInput`) and value types |
| `engine.client.host.WindowsTextInputMethod`, `WindowsSystemPointerSpeed`, `WindowsHostTickClock` | Windows platform services; mods use `HostWindow.getTextInputMethod()`, `SystemPointerSpeed.getDefault()`, `HostTickClock.getDefault()` (portable defaults `NoTextInputMethod`, `FixedSystemPointerSpeed`, `MonotonicHostTickClock` are API) |

The annotation on a package-info covers that package (not subpackages); on a class, its nested classes. `apiDump` and `apiCheck` read it from the compiled classes, so adding or removing it changes the listing, this table and the package-info in one commit. The build does not stop a mod from using an internal element.

## 3. Versions

### 3.1 The SC4J version and the engine API version

The SC4J version (`version` in `gradle.properties`) is a semantic version `MAJOR.MINOR.PATCH`; section 3.2 says which part a change raises.

The engine API version `EngineVersion.CURRENT` (`sc4j-mod-manifest`) is `MAJOR.MINOR` of the SC4J version, taken from the build: `0.1.0-SNAPSHOT` gives `0.1`. The mod plugin writes it into the `engine` field of a mod's `sc4j-mod.json`; the launcher and the runtime run a mod only when the majors are equal and the mod's minor is not higher.

### 3.2 What changes which number

| Change | Part of the SC4J version to raise |
|---|---|
| A listing line removed or changed incompatibly: a class, member or constructor removed or renamed; a parameter, return or field type changed; access narrowed; `final`, `abstract` or `static` changed where mod code can tell; an abstract method added to a type mods implement or extend; a checked exception added; a constant value changed | major |
| A documented contract changed without a listing change: return or accepted values, throw conditions, threading or lifecycle rules, nullability narrowed for input or widened for output | major |
| A simulation result changed for the same inputs (section 4) | at least minor |
| Additions only: class, member, constructor, default method, enum constant (where mods do not switch over it exhaustively), a compatible contract widening | minor |
| A deprecation (section 3.3) | minor |
| No listing or contract change (implementation, performance, internal elements) | patch |

A changed listing line shows as one line removed and one added; decide by this table, not by the diff shape.

### 3.3 Deprecation

A minor release adds `@Deprecated` and a Javadoc `@deprecated` tag naming the replacement; `forRemoval = true` marks the removing release. A deprecated element stays at least one minor release and is removed only in a major release.

### 3.4 Before 1.0 is declared

The SC4J version is 0.x (engine API version `0.MINOR`). At 0.x nothing is stable: incompatible changes may happen without a version change, each visible as an `.api` diff. The rule of section 3.1 applies; sections 3.2 and 3.3 apply in full from 1.0.

## 4. Determinism, replays and saves

A change of simulation results breaks lockstep games and replays even without an API change, so it raises at least the minor version. `ModIdentity` carries the engine API version, so peers of different engine versions refuse each other in a lobby and a room. Engine replays (`.sc4r`, magic `SC4R`) and saves (magic `SC4S`) load only with an equal identity: engine API version, mod id, mod version and content. Replays and saves are not compatible across engine versions.

## 5. Wire and file formats

Each format has its own version. Before 1.0 every number is 1 and a format may change in place; older layouts are neither read nor migrated. Ends with different numbers refuse each other; a reader refuses a file of another number.

| Format | Version | Where |
|---|---|---|
| Lockstep transport over datagrams: turns, resends, departures, drop agreement | 1 | `DatagramLockstepTransport.PROTOCOL_VERSION` (`sc4j-engine-net`) |
| LAN room discovery | 1 | `LanRoomDiscovery.PROTOCOL_VERSION` (`sc4j-engine-net`) |
| Network lobby | 1 | `NetworkLobby.PROTOCOL_VERSION` (`sc4j-engine-net`) |
| Beacon.Link protocol: envelope, messages, records, channel signals and datagrams | 1 | `Envelope.PROTOCOL_VERSION` (`sc4j-beaconlink-protocol`, [protocol reference](beaconlink/protocol.md)) |
| Beacon.Link files: node list, learned peers, key file, account, friends and ignored players | 1 | `NodeListFile.FORMAT`, `PeerStore.FORMAT`, `IdentityKeyFileUtils.VERSION`, `AccountStore`, `PlayerListStore.FORMAT` ([protocol reference, "Files"](beaconlink/protocol.md#files)) |
| Beacon.Link account backup (`.sc4jaccount`) | 1 | `AccountBackup.VERSION` (`sc4j-beaconlink-peer`) |
| Engine replay | 1 | `EngineReplayFormat` (`sc4j-engine-sim`) |
| Engine save | 1 | `EngineSaveFormat` (`sc4j-engine-sim`) |
| JSON map | 1 | `JsonMapLoader.VERSION` (`sc4j-engine-sim`) |
| Standard pack manifest (`pack.json`) | 1 | `PackManifest` (`sc4j-engine-sim`, package `engine.pack`) |
| Standard pack content (`content.json`) | 1 | `PackContentReader` (`sc4j-engine-sim`, package `engine.pack`) |
| Mod identity wire form | with the lobby, discovery and replay versions | `ModIdentity` (`sc4j-engine-core`) |

## 6. Updating the listings

`check` (and so `build`) runs `apiCheck` on every module of section 1 and `testFixturesApiCheck` on the published test fixtures; they fail when the compiled classes differ from the `.api` file and print the removed (`-`) and added (`+`) lines per class.

1. Make the change with its Javadoc.
2. Run `gradlew apiDump testFixturesApiDump`, or `gradlew :<module>:apiDump` (`:<module>:testFixturesApiDump`).
3. Review the `.api` diff against section 3.2; note the version effect in the commit message.
4. Commit the `.api` files with the change. After merging two API changes, run `apiDump` again on the merged state.

An unintended `apiCheck` failure means the change touched the API by accident: undo that part.
