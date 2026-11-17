# SC4J mod author guide

**English** | [한국어](mod-guide.ko.md)

How to make a game (a mod) for the SC4J engine. Each chapter names the demo classes ([`mods/demo/`](../mods/demo/)) that implement its part.

## 1. What a mod is

A mod is **one jar** with the manifest `sc4j-mod.json` at its root (id, name, version, engine API version, entry class). The runtime of an SC4J install provides the engine and its libraries.

- One mod runs per runtime session, in its own class loader.
- The **mod id** names the mod's settings file `userdata/settings/<id>.json`, storage folder `userdata/mods/<id>/` (`ModStorage`: replays, saves, maps, options) and log folder `userdata/logs/<id>/`.
- The **mod identity** (`ModIdentity`) is the engine API version, the mod id and version, the digest of the jar's content and the digest of the identity data the mod adds. Machines play together, and replays play back, only with equal identities.
- The runtime creates the entry class (public no-argument constructor), calls `Mod.initialize(ModContext)`, `Mod.register(ModRegistration)`, runs what was registered, and calls `Mod.close()`.

You need JDK 25 and this repository (its Gradle wrapper and development install).

## 2. Your first mod

The [mod template](https://github.com/fogofwarlabs/sc4j-mod-template) holds the files of this chapter, ready to build: use it on GitHub with "Use this template".

To build a mod from another mod's sources, see the [SC:R Crossover clone guide](https://github.com/fogofwarlabs/sc4j-scr-crossover/blob/main/docs/scr-crossover.md).

### 2.1 An SC4J install

Mods go into `mods/` of an install. `.\gradlew.bat devInstall` makes the development install `build/dev-install/`; any install holds `launcher/`, `runtime/`, `mods/` and, once used, `userdata/`.

### 2.2 The mod's files

A mod is its own Gradle project, here `my-mod` next to the repository folder:

| File | Content |
|---|---|
| `settings.gradle` | repositories and project name (section 7.1) |
| `build.gradle` | mod plugin (section 7.1) |
| `gradle.properties` | `version=0.1.0` (mod version) and `sc4j.install=<install>` (Windows path with forward slashes) |
| `src/main/resources/sc4j-mod.json` | manifest (chapter 3) |
| `src/main/java/com/example/mymod/` | `MyMod` (entry) and `FirstScene` (screen) |

A mod id is lower-case letters and digits in groups joined by single hyphens (`my-mod`), unique. The manifest:

```json
{
  "format": 1,
  "id": "my-mod",
  "name": {"en": "My Mod"},
  "entry": "com.example.mymod.MyMod"
}
```

The mod plugin adds `version` and `engine` (chapter 3).

The entry registers a headless runner that logs one line and the first screen of the window run:

```java
package com.example.mymod;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.mod.Mod;
import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.fogofwarlabs.sc4j.engine.mod.ModRegistration;

public final class MyMod implements Mod {
    private Logger logger;

    @Override
    public void initialize(ModContext context) {
        this.logger = context.getLogger();
    }

    @Override
    public void register(ModRegistration registration) {
        Logger log = this.logger;

        registration.registerHeadlessRunner(launch -> log.info("MOD: headless run of {0}", launch.getIdentity()));
        registration.registerFirstScene(FirstScene::new);
    }

    @Override
    public void close() {
        this.logger = null;
    }
}
```

The screen fills the window with one colour (`0xAARRGGBB`) and ends the run on Escape:

```java
package com.example.mymod;

import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.app.SceneClient;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;

final class FirstScene implements ClientScene {
    private static final int BACKGROUND = 0xFF204060;

    private final SceneClient client;

    FirstScene(SceneClient client) {
        this.client = client;
    }

    @Override
    public void open(SceneClient context) {
    }

    @Override
    public void update(long time) {
    }

    @Override
    public void draw(Canvas surface) {
        surface.fillRectangle(0, 0, surface.getWidth(), surface.getHeight(), BACKGROUND);
    }

    @Override
    public boolean handleInput(InputEvent event) {
        if (event.getType() == InputEvent.Type.KEY_PRESSED && event.getKey() == Key.ESCAPE) {
            this.client.requestQuit();

            return true;
        }

        return false;
    }

    @Override
    public void close() {
    }
}
```

### 2.3 Build and install

```powershell
.\gradlew.bat -p ..\my-mod installMod
```

builds `..\my-mod\build\libs\my-mod.jar` and copies it into `<install>/mods/`; `jar` only builds it.

### 2.4 Run headless

```text
<install>\runtime\bin\sc4j-runtime.bat --mod <install>\mods\my-mod.jar --headless
```

ends with exit code 0; the last log lines are:

```text
... INFO  [main] RuntimeSession - IDENTITY: my-mod 0.1.0 (engine 0.1, content ..., data ...)
... INFO  [main] MyMod - MOD: headless run of my-mod 0.1.0 (engine 0.1, content ..., data ...)
... INFO  [main] RuntimeSession - RUNTIME END: exit code 0 (SUCCESS)
```

The start scripts use the JDK of `JAVA_HOME` (or `java` on the `PATH`), JDK 25. Mod arguments follow `--` (`ModContext.getArguments()`).

### 2.5 Run from the launcher

`<install>\launcher\bin\sc4j-launcher.bat --list` prints the mods and their status:

```text
Mods in <install>\mods
READY   my-mod               my-mod.jar
```

`<install>\launcher\bin\sc4j-launcher.bat` opens the launcher; starting "My Mod" shows `FirstScene`.

Players install a released mod from the launcher: the latest GitHub release of the mod's repository carries `<id>.jar` and `<id>.jar.sha256`. The catalog [`fogofwarlabs/sc4j-mods`](https://github.com/fogofwarlabs/sc4j-mods) lists mods in its `mods.json` (`"format": 1`): each entry has `name`, `repository` (`owner/name`) and an optional `description`, with `name` and `description` in the manifest's localized form (`{"en": "My Mod", "ko": "내 모드"}`, chapter 3). The launcher shows them in the player's language.

### 2.6 Make it a game

1. **Content** (chapter 5): a standard data pack in `src/main/resources/<id>/pack/` (`pack.json`, `content.json`). `initialize` opens it with `StandardPack.openResources(MyMod.class.getClassLoader(), "my-mod/pack")` and `readContent`; `register` calls `PackContent.register(registration.getContent())` and registers the pack as resource provider.
2. **Rules** (chapter 6): engine default systems and frame phases in `registration.getSimulation()`.
3. **Map** (chapter 8): a map in `src/main/resources/<id>/seed/maps/`, copied into the storage at the first run, with `"victory": "annihilation"` (no units loses) or `"elimination"` (no buildings loses).
4. **Units** in `content.json`: a building has `"traits": ["building"]`, `placementWidth`, `placementHeight` and a `collision` box; a faction's `townHall` is placed at each of its players' start locations, its `startUnits` beside it. Fighters get a `groundWeapon` (a `weapons` entry), `targetAcquisitionRange`, `buildTime` (game loops) and `"idleOrders": {"human": "guard", "computer": "guard"}`. A `cost` needs `resourceKinds` and a start amount (`game.getEconomy().setAmount` after `Game.create`); `supplyRequired` needs a unit with `supplyProvided`. `requirements` such as `"spearman": [{"actor": "keep"}]` let a building train a unit. Create `StandardSpatialIndex` with the width and height of the largest unit; it finds no larger unit.
5. **Orders**: the order types that idle orders and commands name (`guard`, `attack`, `attack-move`) go to `readContent`; `GuardBehavior`, `AttackBehavior` and `AttackMoveBehavior` over `(StandardCombatSystem) game.getCombat()` go to the order system.
6. **Training**: register a `TrainUnitCommandType` under the next id (registry size = highest id + 1); send `train.create(game, building, type)` through a `CommandSink`. `StandardProductionSystem` runs it (`new StandardProductionSystem.Builder(capacity)`, `StandardProductionSystem.addColumns(builder, capacity)`).
7. **Winning**: `StandardTriggerSystem` over `StandardMapData.getTriggers()` as the last frame phase; `((StandardTriggerSystem) game.getTriggers()).isGameOver()` and `getOutcome(player)` (`VICTORY`, `DEFEAT`) end the loops.
8. **Screen and headless runner** (chapters 9, 10).
9. **Check** (chapter 12): two headless runs and the replay give the same state hash after every game loop.

## 3. Manifest and identity

`sc4j-mod.json` at the jar root ([demo](../mods/demo/src/main/resources/sc4j-mod.json)), read without loading a class (`ModManifestReader`); a problem makes the mod `ERROR` in the launcher.

| Field | Required | Value |
|---|---|---|
| `format` | yes | `1` |
| `id` | yes | mod id |
| `name` | yes | per language: `{"en": "My Mod", "ko": "..."}` |
| `version` | written by the mod plugin | mod version: the Gradle project's `version`; part of the identity |
| `engine` | written by the mod plugin | engine API version `MAJOR.MINOR` of the plugin's SC4J version; runs when the majors are equal and the mod's minor is not higher ([API policy 3.1](api-policy.md#31-the-sc4j-version-and-the-engine-api-version)) |
| `entry` | yes | fully qualified entry class |
| `description`, `authors`, `website`, `license` | no | launcher mod details; `license` holds the notation your asset licences ask for |
| `notices` | no | short legal notices per language like `name`: `[{"en": "Example Game is a trademark of Example Company.", "ko": "..."}]`; the About box lists them under the mod name, in the display language or English |
| `images` | no | `icon`, `banner`, `screenshots`: PNG paths in the jar |
| `settings` | no | pre-run settings (below) |

The mod plugin writes `version` and `engine` into the manifest of the jar. A source manifest with either field, or without a `format` that the plugin's engine reads, fails the build.

**Pre-run settings**: entries with `key`, `type` (`directory`, `file`, `choice`, `integer`, `string`, `boolean`), `label`, `description`, `required`, `default`, `message` and type fields (`SettingsSchema`). The launcher validates them and saves `userdata/settings/<id>.json`; the mod reads `ModContext.getSettings()`. A locale `choice` with `"systemDefault": true` defaults to the operating system's locale option (`koKR` for Korean in Korea), else `default`. Missing required settings make the mod `WARNING`.

**Directory settings** have a folder picker and three optional fields:

- `check.contains`: relative paths the folder must hold; else `message` is shown.
- `detect`: uninstall entry names (`uninstallEntries`) and folder names below the program folders (`programFolders`); while the setting is empty, the launcher and the runtime look there read-only and save the first folder that passes `check`.
- `shared`: a name under which mods share one value, saved in `userdata/shared-settings.json`.

The mod reads it with `ModContext.getSettings().getDirectory(key)`.

**Identity data**: a mod that reads external data adds its version or digest with `ModRegistration.addIdentityData`.

## 4. The entry: initialize, register, close

[`DemoMod`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/DemoMod.java).

- `initialize(ModContext)`: open and check what the mod needs; `ModException` refuses the run. `ModContext` gives the manifest, settings, storage, install folder, mod arguments and logger. Read resources through the mod's class loader.
- `register(ModRegistration)`: the registration closes when it returns.
- `close()`: release in reverse order.

| Registration | What | Demo |
|---|---|---|
| `getSimulation()` | game definition (chapter 6) | `DemoRules.define` |
| `getContent()` | content registry (chapter 5) | `DemoContent.register` |
| `registerResourceProvider` | client resources | the pack |
| `registerReplayFormat`, `registerSaveFormat` | chapter 13 | engine formats |
| `registerHeadlessRunner` | `--headless` run (chapter 9) | `DemoHeadlessRunner` |
| `registerFirstScene` | first screen (chapter 10) | `MainMenuScene` |
| `registerApplication` | own frame loop (chapter 19) | - |
| `addIdentityData` | identity data | - |

## 5. Content: the standard data pack

JSON definitions, PNG pictures and WAV sounds under one jar folder, opened with `StandardPack.openResources(loader, root)`. The format (`pack.json`, sprite sheets, animations, tilesets, fonts, dialogs, pictures) is the Javadoc of package `org.fogofwarlabs.sc4j.engine.pack` ([package-info](../sc4j-engine-sim/src/main/java/org/fogofwarlabs/sc4j/engine/pack/package-info.java)).

`content.json` holds `resourceKinds`, `motionProfiles` (top speed, acceleration, stopping distance, turn rate), `weapons`, `factions` (worker, town hall, supply provider, resource kinds, start units), `units` (faction, motion profile, hit points, armor, sight, weapons, cost, build time, supply, collision box, placement size, traits, idle orders) and `requirements`. `StandardPack.readContent` reads it into a `PackContent`; `PackContent.register` adds its types.

Demo: [pack](../mods/demo/src/main/resources/demo/pack/), [`DemoContent`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/content/DemoContent.java).

## 6. Rules: the game definition, systems, orders and commands

**Game definition**: `ModRegistration.getSimulation()` is a `GameDefinition.Builder`: entity limits (`EngineLimits`), one system per slot with `useSystem(KEY, factory)` (defaults are the `Standard*` classes: spatial index, path finding, movement, collision, visibility, economy, harvest, requirements, combat, projectiles, production, orders, triggers, computer players; any can be replaced by a class of the same interface), extension columns (`StandardCombatSystem.addColumns`, ...), hooks (`addHook`) and frame phases in order (`addFramePhase`). A factory runs per game.

The defaults are neutral; the mod sets its values: `new MotionModel.Builder(directionCount)` for movement and projectiles (directions per turn, instant stop speed, braking before a turn), `new StandardProductionSystem.Builder(capacity)` (without shares: full refunds, buildings start at full hit points), `StandardCombatSystem.Builder` (without splash shares and minimum damage: every unit a splash reaches takes full damage, and a hit may deal nothing).

Units that are placed, see, find paths and move need `TableUnitBounds`, spatial index, path finding, movement, collision and visibility, with the phases orders, movement, collision, visibility:

```java
registration.getSimulation()
        .useSystem(MovementSystem.KEY, context -> new StandardMovementSystem(new MotionModel.Builder(64).build()))
        .addFramePhase(MovementSystem.KEY, system -> (StandardMovementSystem) system);
```

Demo: [`DemoRules`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/rules/DemoRules.java).

**New games**: `GameSetup` (map `MapReference`, `PlayerSlot`s with slot, controller kind, team, colour, faction, seed); `Game.create(definition, setup)`; `MapPlacementUtils.placeUnits`. Demo: [`DemoGames`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/game/DemoGames.java).

**Orders**: an `OrderType` is content; its `OrderBehavior` (`MoveBehavior`, `AttackBehavior`, `GatherBehavior`, `BuildBehavior`, ...) is registered per game: `new StandardOrderSystem((game, behaviors) -> behaviors.register(move, new MoveBehavior(16)))` (arrival distance 16 world pixels). Demo: [`DemoOrders`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/order/DemoOrders.java).

**Commands**: every player action is a command of a `CommandRegistry` of `CommandType`s with fixed ids (`IssueOrderCommandType`, `TrainUnitCommandType`, `ConstructBuildingCommandType`, own types), sent through a `CommandSink` and applied by `ControllerCommandSource` before the frame phases. Replays and network turns carry them: change ids or encoding only with a new mod version.

**Mechanics of your own** plug in here:

- values changed by upgrades or effects: a `Stat` registered after the engine stats (`ContentRegistry.getStats().register(new Stat("..."))`) with `StatModifier`s (`ContentRegistry.addModifier`);
- per-type values: `ContentRegistry.addColumn`; per-unit values: a unit pool extension column or a field of an own `Unit` subclass;
- damage layers before hit points (shields, armour plates): a `DamageLayer` added per unit type with `GameContent.addDamageLayer` in a system's `attach`; the standard combat system asks layers in chain order, the HUD shows a bar per layer;
- movement: own code over `MotionModel`, an own algorithm over the `MovingEntity` fields that may move the prospective `MotionStep`, or an own movement system.

## 7. The build and the jar

### 7.1 The mod's own build

The mod plugin `org.fogofwarlabs.sc4j.mod` is on Maven Central with the engine, same version. `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = 'my-mod'
```

`build.gradle`:

```groovy
plugins {
    id 'org.fogofwarlabs.sc4j.mod' version '<sc4j version>'
}
```

The mod id is the `id` of `src/main/resources/sc4j-mod.json`; the mod version is the project version (`version` in `gradle.properties` or `build.gradle`). A missing manifest or `id` fails the build.

| Task or setting | Effect |
|---|---|
| compile | every source set of the mod, in whichever project of the build owns it, against the SC4J mod API of the plugin's version: the engine modules the runtime provides; not packed |
| `processResources` | writes `version` and `engine` into `sc4j-mod.json` (chapter 3) |
| `jar` | `build/libs/<id>.jar`, reproducible (no timestamps, fixed order, duplicates fail), `LICENSE` and `NOTICE` in `META-INF/`; fails when `sc4j-mod.json` names another id, other versions or a missing entry class |
| `run` | runs the jar in the plugin's engine runtime with the install's user data (or `build/sc4j-run`); `--args=--headless`, `--debug-jvm` |
| `installMod` | copies the jar into `<install>/mods/` (Gradle property `sc4j.install`) |
| `sc4jMod` | `id` (optional, must equal the manifest `id`; needed when a build task writes the manifest), `sourceSets` (more source sets in the jar), `install`, `licenses` (files of `META-INF/`) |

The catalog reader `sc4j-catalog` reads what is published: SC4J releases (`ReleaseCheck`, `ReleaseVersion`, `catalog.github`) and the mod catalog (`catalog.mods`). `ReleaseCheck.read(new ReleaseClient(ReleaseClient.GITHUB_API), ReleaseCheck.DEFAULT_REPOSITORY, ReleaseVersion.CURRENT)` reads the latest SC4J release over the network; call it off the frame thread.

The tasks form the group `sc4j`; `tasks.named('jar') { from(...) }` adds files. A run-time library goes into the jar unless the runtime's `lib/` has it (LWJGL, FFmpeg, ...); check its licence (chapter 17).

### 7.2 A mod inside this repository

The demo uses the same plugin ([`mods/demo/build.gradle`](../mods/demo/build.gradle)); inside the repository the engine dependency is the engine projects and the install is `build/dev-install/`. Its checks: `checkDemoNames` (names no other game), `determinismCheck` (chapter 12); its tests run the jar (`DemoModTest`) and the client offscreen (`DemoClientScenario`).

## 8. Maps and first-run content

**Map format**: one JSON file (`"format": "sc4j-map"`, version 1): name, tileset, tile and walk cell size, size in tiles, terrain rows of one-character codes with a legend (walkable, buildable, elevation), neutral slot, start locations, units, resource containers, locations, victory rule, triggers. Fields: Javadoc of [`JsonMapLoader`](../sc4j-engine-sim/src/main/java/org/fogofwarlabs/sc4j/engine/map/JsonMapLoader.java). Read maps with `new JsonMapLoader(unitTypes, resourceKinds)` into a `MapCatalog` (gives each map its `MapReference`: name and digest). The victory rule and triggers need `StandardTriggerSystem` as the last frame phase: `new StandardTriggerSystem.Builder().addTriggers(data.getTriggers()).build()` with the map's `StandardMapData`, victory rule first.

**Grids and projection**: the simulation works in world pixels on a square grid of walk cells (`TerrainGrid`); the client projects it with `SquareProjection` or `IsometricProjection` (demo: [`DemoWorldUtils`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/DemoWorldUtils.java)).

**First-run content**: a new install's storage is empty. A mod that keeps maps in the storage ships them under `<id>/seed/` and copies each listed file in `initialize` **only when the storage has no file of that path**:

```java
for (String file : List.of("maps/first-field.json")) {
    Path target = context.getStorage().resolve(file);

    if (Files.exists(target)) {
        continue;
    }

    try (InputStream input = MyMod.class.getClassLoader().getResourceAsStream("my-mod/seed/" + file)) {
        if (input == null) {
            throw new IOException("The mod jar has no seed file " + file);
        }

        Files.createDirectories(target.getParent());
        Files.copy(input, target);
    }
}
```

Changed maps are never overwritten; deleted ones come back. Then read every `*.json` of `maps/` in file name order, skipping invalid maps with a warning. A new map gets a unique `name`, goes under `src/main/resources/<id>/seed/maps/` and into the list (`"maps/second-field.json"`). A changed seed does not replace an existing copy: delete it or run with another `--storage`. The demo reads its maps from the jar ([`DemoMaps`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/map/DemoMaps.java)) and keeps received maps beside them.

## 9. The headless runner

`HeadlessRunner.run(ModLaunch)` runs `--headless` (no window, audio or input). `ModLaunch` gives the requested replay (`getReplay()`, `getReplayFormat()`) or game to join (`getJoin()`), the identity, game definition, resource provider, formats and Beacon.Link.

Demo: [`DemoHeadlessRunner`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/DemoHeadlessRunner.java): `--map` plays two computer players and records the replay; `--replay` plays back and fails on a differing checkpoint; `--frame-hashes` writes one hash per game loop; `--host` with the runtime's `--join`, and `--beaconlink-host`, `--beaconlink-join` with `--seed <host:port>`, play network games.

## 10. Client screens

The runtime opens window, audio and input, creates the first scene with the `FirstSceneFactory` and runs frames on a `SceneClient` (launch request, storage, `Canvas`, `AudioMixer`, cursor surface). A screen is a `ClientScene` (`open`, `update`, `draw`, `handleInput`, `close`); switch with `client.getScenes().replace(...)`, open popups with `pushModal`, end with `client.requestQuit()`.

**Showing a game**:

- `GameSessionFactory.createLocal`, `createReplay`, `createNetwork`; draw from the session's `GameView`, send commands to its `CommandSink`.
- `CameraScroll` scrolls the `Camera` (`ContinuousScrollRate`, `SteppedScrollRate`).
- `WorldView` of `WorldLayer`s: `TerrainLayer` and `UnitLayer` without art; `TilesetLayer` and `UnitSpriteLayer` with the pack's art (detail versions `<id>-2x` to `<id>-4x` on scaled displays, `DetailUtils`, `spriteSheetDetails`).
- `GameScreen`: default in-game screen (selection, command card, minimap, resource bar, selection panel, fog) in a HUD skin; needs a pack font.
- Slash commands and key bindings: `ConsoleCommandRegistry`, `KeyBindingTable`.

Demo: [client package](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/) (`MainMenuScene`, `GameSetupScene`, `DemoGameScene`, `DemoGameScreen`, `ReplayViewerScene` over `GameFileListScene`, `NetworkScene`, `BeaconLinkScene`, ...).

**Offscreen tests**: a `SceneClient` over an `ImageCanvas` with a silent audio device; send input with `client.handleInput`, run `client.runFrame` (demo: `DemoClientScenario`). Outside this repository: a source set whose class path holds the mod and every jar of `<install>/runtime/lib`, a `ModStorage` of your own over a scratch folder (`getRoot`, `resolve`; the runtime's implementation is not API), a `ModLaunch` with `ModIdentity.create(EngineVersion.CURRENT, manifest, digest, List.of())` and the mod's game definition; `ImageIO.write(canvas.getImage(), "png", file)` saves a picture.

## 11. Computer players

A `PlayerController` whose commands depend only on the game. Default: `StandardComputerPlayerSystem` with `StandardComputerController` (`ComputerSettings`). `ControllerKind.COMPUTER` slots run on every machine of a network game.

## 12. Determinism

The same commands must give the same simulation results on every machine. `checkSimulationDeterminism` checks the engine's simulation packages; mod simulation code (systems, behaviors, controllers) follows the same rules:

- no `Math.random`, clock (`System.currentTimeMillis`, `System.nanoTime`, `java.time`) or `java.util` random generator (`Random`, `SecureRandom`, `SplittableRandom`, `ThreadLocalRandom`); use the game's seeded random generator;
- no hash-ordered collection (`HashMap`, `HashSet`, `Hashtable`, `IdentityHashMap`, `WeakHashMap`, `ConcurrentHashMap`) whose order the simulation sees, no identity hash code; use lists, arrays, `LinkedHashMap` or the entity pools;
- no `float` or `double`: integers or engine fixed-point values;
- the game changes only through commands and frame phases, never from scenes, input handlers or other threads;
- fixed order: systems and phases by registration, controllers by list, commands by submission.

The client may use anything. Check like `determinismCheck`: two runs and the replay give the same `Game.computeStateHash` after every game loop (`--frame-hashes`).

## 13. Replays and saved games

`EngineReplayFormat` (`.sc4r`, magic `SC4R`) records the setup and every command with a state hash checkpoint every n game loops (`createRecorder`, set on the `ControllerCommandSource`); `read` and `createController` play it back and count mismatches. `EngineSaveFormat` (magic `SC4S`) stores a game's state. Both load only with an equal identity. Own formats implement `ReplayFormat` and `SaveFormat`. Keep files in the storage (`replays/`, `saves/`); `GameFileListScene` lists them.

## 14. Network play

`sc4j-engine-net` runs lockstep games: every machine runs the simulation and exchanges commands in turns.

- `NetworkLobby`: members, slots, factions, ready flags, map transfer (`LobbyMaps`; default `FolderLobbyMaps` over `ModStorage.getMapFolders()` finds maps by digest in `maps/` and saves received ones in `maps/downloads/`), start (`NetworkGameStart`); refuses other identities.
- `DatagramLockstepTransport`, `LockstepSession`: the turns; `GameSessionFactory.createNetwork` shows the game.
- `LanRoomDiscovery`: LAN rooms.
- Beacon.Link (`ModLaunch.getBeaconLink()`): Internet rooms and community.

Demo: [`DemoLobby`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoLobby.java), [`DemoNetworkGame`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoNetworkGame.java), [`DemoNetworkSettings`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoNetworkSettings.java). Test network code on 127.0.0.1 only.

### 14.1 Beacon.Link rooms

`ModLaunch.getBeaconLink()` is `null` unless the command line names a seed source (`--seeds <file>`, `--seed <host:port>`, `--peers <file>`); `--account <dir>` names the account, else the run is the guest `Guest` (`BeaconLink.GUEST_NAME`). The launcher passes all of them. `BeaconLink.open()` returns a `BeaconLinkSession`, one connection of the account (`getParticipant()`: `LinkParticipant`, player and connection number). Calls only start work; the session's thread calls `poll()` in its loop and `close()` at the end. `getFailure()` (`LinkFailure`) says why no node is reachable; `getNodes()` gives each node's state.

- **Host**: `publishRoom(settings, password)` (empty password: public) returns a `HostedRoom`: `getState()` (`PUBLISHING`, `PUBLISHED`, `WITHDRAWN`, `FAILED`), `update(settings)`, `acceptChannel()` per checked joiner, `withdraw()`. `RoomSettings`: room and map name (at most 48 UTF-8 bytes each), players, capacity, started flag, up to 64 bytes of mod data (demo: turn rate).
- **Search**: `searchRooms()` returns a `RoomSearch`: `getRooms()` (own identity), `getOtherRooms()` (other versions, with `FoundRoom.getJoinRefusal()`), `isComplete()`. A `FoundRoom` gives host, settings, `isJoinable()`, `isPrivate()`, mod id and version, `getLatencyEstimate()` (`LatencyClass`: `GOOD` up to 100 ms, `FAIR` up to 200 ms, `POOR`, `UNKNOWN`).
- **Join**: `joinRoom(room, password)` opens a `ParticipantChannel`: `CONNECTING`, then `OPEN`, or `CLOSED` with a `ChannelFailure`; `JOIN_REFUSED` carries the `JoinRefusal`.
- **Play**: `getOverlay()` gives the `LinkOverlay` (datagrams by participant, at most 1023 bytes, unreliable). Port 1 (`LOBBY_PORT`) is `NetworkLobby`, port 2 (`GAME_PORT`) `LinkLockstepTransport`, ports 16 to 255 (`FIRST_MOD_PORT`) are the mod's. `NetworkLobby.host(room, overlay, ...)` and `NetworkLobby.join(channel, overlay, ...)` give the LAN lobby and game.

Demo: `DemoLobby`, [`BeaconLinkScene`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/BeaconLinkScene.java), `DemoHeadlessRunner`.

### 14.2 Beacon.Link community

`BeaconLinkSession.getCommunity()` returns the `CommunitySession`:

- **Presence**: `getSelf()`, `setStatus(PlayerStatus)` (`AVAILABLE`, `AWAY`, `IN_ROOM`, `IN_GAME`), `setAwayText` (at most 32 UTF-8 bytes), `setAppearOffline`; `getPresence(player)` gives a `PlayerPresence` merged over the player's connections: name and tag, status, mod id and version, `canPlayTogether()`, channel, away text, latency estimate, device count.
- **Chat**: `joinChannel(name)` (at most 4 at once, shared by all mods) returns a `ChatChannel`: `say`, `emote` (at most 255 UTF-8 bytes; one line per second after a burst of five), `getLines()`, `getMembers()`, `leave()`; `listChannels()`.
- **Whispers**: `whisper(player, text)`; the outcome (`DELIVERED`, `NOT_ONLINE`) goes to the listeners.
- **Friends and ignored players**: `addFriend`, `removeFriend` (at most 16 friends), `ignore`, `unignore`, `isIgnored`; the account owns the list; ignored players' lines and whispers are dropped, the mod hides their rooms.
- **Listeners**: `addListener(CommunityListener)`: `onChatLine`, `onMembersChanged`, `onWhisper`, `onWhisperResult`, called inside `poll()`.

`BeaconLinkSession.signOutOtherDevices()` ends the account's other connections. `ChannelChat` (`engine.net.beaconlink.community.chat`, `sc4j-engine-net`) runs the channel chat; `engine.client.community` (`sc4j-engine`) holds community screen parts: `RoomFilter`, `ChatLogBox`, `TableBox`; `engine.client.lobby` holds `RecentMaps`. Wire: [protocol reference](beaconlink/protocol.md); public peers: [operator guide](beaconlink/operator.md).

## 15. Building, installing and running

- **Install**: put the jar into `<install>/mods/`. The launcher shows `READY`, `WARNING` (settings to fix) or `ERROR` (broken jar or manifest, unsupported engine version, missing image, duplicate id).
- **Runtime**: `sc4j-runtime --mod <jar> [--install <dir>] [--settings <json>] [--storage <dir>] [--log <dir>] [--account <dir>] [--seeds <file>] [--seed <host:port>]... [--peers <file>] [--headless] [--replay <file>] [--join <address>] [-- <mod arguments>]` (`--help`). Exit codes: 0 normal, 1 run failure, 2 command line or settings error, 3 mod load or validation failure. Log: `<install>/userdata/logs/<id>/sc4j.log`.
- **Launcher**: starts the runtime with the mod's settings, storage and log folders.

## 16. API and versions

Details: [engine API policy](api-policy.md).

- The API is the public and protected classes and members of `sc4j-base`, `sc4j-mod-manifest`, `sc4j-engine-core`, `-sim`, `-net`, `-client`, `sc4j-engine` and `sc4j-catalog`, listed in `api/*.api`. The Javadoc of each class is its contract.
- Internal, not API (`@ApiStatus.Internal`): `engine.mod.loading`, `engine.client.render.gl`, `engine.client.audio.openal`, `GlfwClientHost`, `GlfwHostWindow`, `GlfwInput`, `GlfwKeyCodes`, `GlfwCursorSurface`, `WindowsTextInputMethod`, `WindowsSystemPointerSpeed`, `WindowsHostTickClock`. Use `engine.client.render` (`GraphicsDevice.getCurrent()`), `engine.client.audio` (`AudioDevice.openDefault()`) and the `engine.client.host` interfaces (`ClientHost.initialize()`; `HostWindow`: `createInput`, `isKeyDown`, `setPointerMode`, `createCursorSurface`, focus, clipboard, dropped files, gamma ramp, `getTextInputMethod`; `SystemPointerSpeed`, `HostTickClock`).
- The SC4J version is a semantic version; its `MAJOR.MINOR` is the engine API version (`EngineVersion.CURRENT`), which the mod plugin writes into the manifest's `engine` field. A major may break mods, a minor only adds; at 0.x anything may change, visible as `.api` diffs.
- Changed simulation results raise at least the minor version; replays and saves do not cross engine versions.

## 17. Licences for assets

- Use own work or material licensed for redistribution in a mod (CC0, or a licence whose terms you meet). Put the licence files and a notice into the jar and the required notation into the manifest's `license` and `description`. Demo: [`mods/demo/THIRD-PARTY.md`](../mods/demo/THIRD-PARTY.md), packed in `demo.jar`.
- Never put files of another game's installation into a jar or repository; read them at run time through a directory setting (chapter 3).

## 18. Testing your mod

- Outside this repository: build the jar, install it into a scratch install, run the runtime headless twice and once on the replay, compare the frame hashes.
- Published test fixtures ([API policy](api-policy.md#1-what-the-api-is)): `RecordingCommunitySession`, `RecordingBeaconLink` (`sc4j-engine-net`), `ClientScenario` with emulated NATs on 127.0.0.1 (`sc4j-beaconlink`), `FakeInstallationRecords` (`sc4j-mod-manifest`).
- Inside this repository: `RuntimeSession.run` in the test JVM (`DemoModTest`), the client offscreen (`DemoClientScenario`), two runtime JVMs (`determinismCheck`).

## 19. Two ways a mod runs

A mod registers one of these in `register`; both throw an `IllegalStateException`.

| | Engine scenes | Own application |
|---|---|---|
| Registration | `registerFirstScene(FirstSceneFactory)`, `registerHeadlessRunner(HeadlessRunner)` | `registerApplication(ModApplication)` |
| Window run | the runtime opens window, canvas, audio, cursor and input and runs `WindowClient` (a `HostApplication`: polls events, feeds the scenes, runs the `SceneClient` frame, presents with vertical sync) on `ClientHost` | the runtime calls `ModApplication.run(ModLaunch)`; the mod opens window, canvas, audio and cursor and runs its own `HostApplication` (`runFrame`, `getFrameRateLimit`) with `ClientHost.initialize()`, `createWindow`, `ClientHost.run` |
| `--headless` | the `HeadlessRunner` | the same `run` if `isHeadlessCapable()` |
| Used by | the first mod, the demo | mods that own their frame loop |

Use engine scenes unless the mod must own the frame loop.
