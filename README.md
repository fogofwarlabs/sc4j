<h1 align="center">
  <img src="docs/branding/sc4j-logo.svg" alt="SC4J logo" width="160">
  <br>
  SC4J
</h1>

<p align="center">A real-time strategy engine in Java, with a mod runtime, a launcher and peer-to-peer online play.</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.fogofwarlabs.sc4j/sc4j-engine"><img src="https://img.shields.io/maven-central/v/org.fogofwarlabs.sc4j/sc4j-engine" alt="Maven Central"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center"><b>English</b> | <a href="README.ko.md">한국어</a></p>

---

SC4J (Simulation Core for Java) is an engine for real-time strategy games. A game is a **mod**: one jar that the runtime loads and the launcher lists. Every SC4J is a peer of Beacon.Link, the network for rooms, chat and presence; there is no central server.

This repository holds the engine, the runtime, the launcher, Beacon.Link and the demo mod [`mods/demo/`](mods/demo/) (id `demo`, "SC4J Demo: Dawnhold and Cindermaw"). The SC:R mod and SC:R Crossover have their own repositories: [sc4j-scr](https://github.com/fogofwarlabs/sc4j-scr), [sc4j-scr-crossover](https://github.com/fogofwarlabs/sc4j-scr-crossover).

## Features

- Deterministic lockstep simulation with a state hash per game loop; default systems for path finding, movement, collision, visibility, economy, harvest, combat, projectiles, production, orders, triggers and computer players, each replaceable by a mod.
- Standard data pack (JSON, PNG, WAV) and one-file JSON maps.
- Client: scenes, input, OpenGL renderer, square and isometric projections, a default in-game screen with HUD, audio and video.
- Replays and saved games, bound to the mod identity that wrote them.
- LAN rooms and games; Beacon.Link rooms, chat, whispers, friends and presence.
- Launcher: lists mods, asks for their settings, starts the runtime, runs the computer's Beacon.Link node, installs mods from GitHub releases and updates itself.
- Mod runtime: one mod per session in its own class loader.
- Engine API listed per module and checked by the build ([API policy](docs/api-policy.md)).

## Modules

| Path | Contents |
|---|---|
| `sc4j-base` | exceptions, logging, binary, file, JSON and text helpers |
| `sc4j-mod-manifest` | mod manifest `sc4j-mod.json`, settings, versions, install layout |
| `sc4j-engine-core` | fixed-point math, map grid, terrain, players, state hash, content types, mod identity |
| `sc4j-engine-sim` | game, units, default systems, orders, commands, data pack, maps, replays, saves |
| `sc4j-engine-net` | datagram links, lockstep transport, LAN discovery, network lobby, Beacon.Link and community interfaces, channel chat |
| `sc4j-engine-client` | host, scenes, input, renderer, projections, HUD, console, audio, video |
| `sc4j-engine` | the mod API and client application that mods compile against |
| `sc4j-runtime` | runs one mod jar, in a window or headless |
| `sc4j-launcher` | the launcher (JavaFX) |
| `sc4j-catalog` | what is published: GitHub releases, the check for a newer release, the mod catalog; used by the launcher, shipped by the runtime for mods |
| `sc4j-beaconlink-protocol`, `-peer`, `-node`, `sc4j-beaconlink` | Beacon.Link wire protocol, peer library, headless node, client |
| [`mods/`](mods/) | the demo mod |
| [`tools/`](tools/) | `api-check` (API listings), `windows-manifest` (UTF-8 code page of the Windows programs) |
| [`docs/`](docs/README.md) | guides; [`docs/branding/`](docs/branding/README.md): logos and the demo's launcher art |
| [`licenses/`](licenses/) | third-party licence texts |

## Build and run

JDK 25 is required; the Gradle wrapper fetches Gradle. Commands run in PowerShell from the repository root (`./gradlew` on Linux and macOS).

| Command | Result |
|---|---|
| `.\gradlew.bat build` | compiles, runs the unit tests and checks |
| `.\gradlew.bat devInstall` | development install `build/dev-install/` (launcher, runtime, `mods/demo.jar`) |
| `.\gradlew.bat runDevLauncher` | opens the launcher of the development install |
| `.\gradlew.bat runDevInstall -Psc4j.mod=<id>` | runs `mods/<id>.jar` of the development install (default `demo`) |
| `.\gradlew.bat distribution` | user package of the build's platform `build/distributions/SC4J-<version>-<platform>.zip` |
| `.\gradlew.bat :sc4j-beaconlink-node:installDist` | headless Beacon.Link node in `sc4j-beaconlink-node/build/install/` |

The launcher lists every jar in `build/dev-install/mods/`. A mod of another repository installs there with its `installMod -Psc4j.install=<this repository>\build\dev-install`; `devInstall` keeps it.

## Install

Each GitHub release has one user package per platform, `SC4J-<version>-<platform>.zip` with `<platform>` one of `windows-x64`, `linux-x64`, `linux-arm64` and `macos-arm64` (Apple silicon). It holds the launcher, the runtime, the demo and a Java runtime for that platform; `userdata/` is created at the first start. The game window needs OpenGL 3.3.

- **Windows**: unpack the zip and start `SC4J.exe` (or `SC4J.bat`).
- **Linux**: unpack the zip with `unzip`, which keeps the program modes, and start `./SC4J`. The launcher needs GTK 3.
- **macOS**: unpack the zip. The package is not signed by a developer, so remove the quarantine of the download first: `xattr -dr com.apple.quarantine <folder>`. Then open `SC4J.command` in Finder or start `./SC4J` in Terminal.

The launcher updates itself from the GitHub releases of SC4J, with the package of its own platform only, and installs a mod from the latest release of a GitHub repository (`owner/name`, typed or chosen from the catalog of [`fogofwarlabs/sc4j-mods`](https://github.com/fogofwarlabs/sc4j-mods)). An update also replaces the mod jars the package ships (listed in `launcher/package-mods.txt`); `userdata/` and the other jars of `mods/` stay, and a shipped jar the player deleted is not added again.

## Documentation

| Guide | Topic |
|---|---|
| [Mod author guide](docs/mod-guide.md) | making a mod |
| [Mod template](https://github.com/fogofwarlabs/sc4j-mod-template) | a mod that builds, runs and installs, to start from |
| [Engine API policy](docs/api-policy.md) | API scope, versions, internal elements |
| [Beacon.Link protocol](docs/beaconlink/protocol.md) | wire protocol and files |
| [Beacon.Link operator guide](docs/beaconlink/operator.md) | running a public peer |
| [Branding](docs/branding/README.md) | logos, banners, PNG export |
| [Contributing](CONTRIBUTING.md) | setup, tests, code style, commits |

## Licence

[Apache License 2.0](LICENSE). [NOTICE](NOTICE) lists the third-party material of the repository and its distributions; the licence texts are in [`licenses/`](licenses/).

| Material | Licence | Details |
|---|---|---|
| Demo pictures: renders of KayKit models and Poly Haven textures | CC0 1.0 | [`mods/demo/THIRD-PARTY.md`](mods/demo/THIRD-PARTY.md) |
| Libraries of the distributions (LWJGL, JavaCPP, FFmpeg, OpenJFX and others) | their own licences | [NOTICE](NOTICE), [`licenses/`](licenses/) |
