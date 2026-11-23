# SC4J 모드 제작 가이드

[English](mod-guide.md) | **한국어**

SC4J 엔진용 게임(모드)을 만드는 방법입니다. 각 장은 그 부분을 구현한 데모([`mods/demo/`](../mods/demo/)) 클래스를 알려 줍니다.

## 1. 모드란

모드는 루트에 매니페스트 `sc4j-mod.json`(id, 이름, 버전, 엔진 API 버전, 진입 클래스)이 있는 **jar 하나**입니다. 엔진과 그 라이브러리는 SC4J 설치본의 런타임이 제공합니다.

- 런타임 세션 하나에서 모드 하나가 자체 클래스 로더에서 실행됩니다.
- **모드 id**는 설정 파일 `userdata/settings/<id>.json`, 저장 폴더 `userdata/mods/<id>/`(`ModStorage`: 리플레이, 저장 게임, 맵, 옵션), 로그 폴더 `userdata/logs/<id>/`의 이름입니다.
- **모드 정체성**(`ModIdentity`)은 엔진 API 버전, 모드 id와 버전, jar 내용의 다이제스트, 모드가 추가한 정체성 데이터의 다이제스트입니다. 정체성이 같아야 함께 플레이하고 리플레이를 재생할 수 있습니다.
- 런타임은 진입 클래스를 만들고(인자 없는 public 생성자), `Mod.initialize(ModContext)`, `Mod.register(ModRegistration)`을 호출하고, 등록된 것을 실행한 뒤 `Mod.close()`를 호출합니다.

JDK 25와 이 저장소(Gradle 래퍼와 개발용 설치본)가 필요합니다.

## 2. 첫 모드

[모드 템플릿](https://github.com/fogofwarlabs/sc4j-mod-template)에 이 장의 파일이 바로 빌드할 수 있게 들어 있습니다. GitHub에서 "Use this template"으로 쓰면 됩니다.

다른 모드의 소스로 모드를 만들려면 [SC:R Crossover 복제 가이드](https://github.com/fogofwarlabs/sc4j-scr-crossover/blob/main/docs/scr-crossover.md)를 보세요.

### 2.1 SC4J 설치본

모드는 설치본의 `mods/`에 들어갑니다. `.\gradlew.bat devInstall`은 개발용 설치본 `build/dev-install/`을 만듭니다. 설치본에는 `launcher/`, `runtime/`, `mods/`가 있고, 한 번 쓰면 `userdata/`가 생깁니다.

### 2.2 모드의 파일

모드는 독립된 Gradle 프로젝트이며, 여기서는 저장소 폴더 옆의 `my-mod`입니다.

| 파일 | 내용 |
|---|---|
| `settings.gradle` | 저장소와 프로젝트 이름(7.1절) |
| `build.gradle` | 모드 플러그인(7.1절) |
| `gradle.properties` | `version=0.1.0`(모드 버전)과 `sc4j.install=<install>`(Windows 경로는 슬래시로) |
| `src/main/resources/sc4j-mod.json` | 매니페스트(3장) |
| `src/main/java/com/example/mymod/` | `MyMod`(진입 클래스)와 `FirstScene`(화면) |

모드 id는 소문자와 숫자 묶음을 하이픈 하나로 이은 고유한 이름(`my-mod`)입니다. 매니페스트:

```json
{
  "format": 1,
  "id": "my-mod",
  "name": {"en": "My Mod"},
  "entry": "com.example.mymod.MyMod"
}
```

`version`과 `engine`은 모드 플러그인이 넣습니다(3장).

진입 클래스는 한 줄을 로그에 쓰는 헤드리스 러너와 창 실행의 첫 화면을 등록합니다.

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

화면은 창을 한 가지 색(`0xAARRGGBB`)으로 채우고 Escape로 실행을 끝냅니다.

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

### 2.3 빌드와 설치

```powershell
.\gradlew.bat -p ..\my-mod installMod
```

`..\my-mod\build\libs\my-mod.jar`를 빌드해 `<install>/mods/`에 복사합니다. `jar`는 빌드만 합니다.

### 2.4 헤드리스로 실행

```text
<install>\runtime\bin\sc4j-runtime.bat --mod <install>\mods\my-mod.jar --headless
```

종료 코드 0으로 끝나며, 마지막 로그 줄은 다음과 같습니다.

```text
... INFO  [main] RuntimeSession - IDENTITY: my-mod 0.1.0 (engine 0.1, content ..., data ...)
... INFO  [main] MyMod - MOD: headless run of my-mod 0.1.0 (engine 0.1, content ..., data ...)
... INFO  [main] RuntimeSession - RUNTIME END: exit code 0 (SUCCESS)
```

시작 스크립트는 설치본의 Java 런타임 `<install>/java`가 있으면 그것을, 없으면 `JAVA_HOME`의 JDK(없으면 `PATH`의 `java`), JDK 25를 씁니다. 모드 인자는 `--` 뒤에 씁니다(`ModContext.getArguments()`).

### 2.5 런처에서 실행

`<install>\launcher\bin\sc4j-launcher.bat --list`는 모드와 상태를 출력합니다.

```text
Mods in <install>\mods
READY   my-mod               my-mod.jar
```

`<install>\launcher\bin\sc4j-launcher.bat`은 런처를 엽니다. "My Mod"를 시작하면 `FirstScene`이 보입니다.

플레이어는 릴리즈된 모드를 런처에서 설치합니다. 모드 저장소의 최신 GitHub 릴리즈에 `<id>.jar`와 `<id>.jar.sha256`이 있어야 합니다. [`fogofwarlabs/sc4j-mods`](https://github.com/fogofwarlabs/sc4j-mods) 목록은 `mods.json`(`"format": 1`)에 모드를 나열합니다. 각 항목에는 `name`, `repository`(`owner/name`), 선택 항목 `description`이 있으며, `name`과 `description`은 매니페스트의 다국어 형식(`{"en": "My Mod", "ko": "내 모드"}`, 3장)입니다. 런처는 플레이어의 언어로 보여 줍니다.

### 2.6 게임으로 만들기

1. **콘텐츠**(5장): `src/main/resources/<id>/pack/`의 표준 데이터 팩(`pack.json`, `content.json`). `initialize`가 `StandardPack.openResources(MyMod.class.getClassLoader(), "my-mod/pack")`과 `readContent`로 읽고, `register`가 `PackContent.register(registration.getContent())`를 호출하고 팩을 리소스 제공자로 등록합니다.
2. **규칙**(6장): `registration.getSimulation()`의 엔진 기본 시스템과 프레임 단계.
3. **맵**(8장): `src/main/resources/<id>/seed/maps/`의 맵. 첫 실행 때 저장 폴더로 복사되며, `"victory": "annihilation"`(유닛이 없으면 패배) 또는 `"elimination"`(건물이 없으면 패배).
4. **유닛**(`content.json`): 건물에는 `"traits": ["building"]`, `placementWidth`, `placementHeight`, `collision` 상자가 있습니다. 진영의 `townHall`은 그 진영 플레이어마다 시작 위치에, `startUnits`는 그 옆에 놓입니다. 전투 유닛에는 `groundWeapon`(`weapons` 항목), `targetAcquisitionRange`, `buildTime`(게임 루프), `"idleOrders": {"human": "guard", "computer": "guard"}`를 줍니다. `cost`에는 `resourceKinds`와 시작 자원량(`Game.create` 뒤 `game.getEconomy().setAmount`)이, `supplyRequired`에는 `supplyProvided`가 있는 유닛이 필요합니다. `"spearman": [{"actor": "keep"}]` 같은 `requirements`로 건물이 유닛을 훈련합니다. `StandardSpatialIndex`는 가장 큰 유닛의 너비와 높이로 만들며, 그보다 큰 유닛은 찾지 못합니다.
5. **명령**: 대기 명령과 커맨드가 쓰는 명령 타입(`guard`, `attack`, `attack-move`)은 `readContent`에, `(StandardCombatSystem) game.getCombat()` 위의 `GuardBehavior`, `AttackBehavior`, `AttackMoveBehavior`는 명령 시스템에 넘깁니다.
6. **훈련**: `TrainUnitCommandType`을 다음 id로 등록하고(레지스트리 크기 = 가장 큰 id + 1), `train.create(game, building, type)`을 `CommandSink`로 보냅니다. `StandardProductionSystem`이 실행합니다(`new StandardProductionSystem.Builder(capacity)`, `StandardProductionSystem.addColumns(builder, capacity)`).
7. **승리**: 마지막 프레임 단계로 `StandardMapData.getTriggers()` 위의 `StandardTriggerSystem`. `((StandardTriggerSystem) game.getTriggers()).isGameOver()`와 `getOutcome(player)`(`VICTORY`, `DEFEAT`)로 루프를 끝냅니다.
8. **화면과 헤드리스 러너**(9장, 10장).
9. **확인**(12장): 헤드리스 실행 두 번과 리플레이가 게임 루프마다 같은 상태 해시를 냅니다.

## 3. 매니페스트와 정체성

jar 루트의 `sc4j-mod.json`([데모](../mods/demo/src/main/resources/sc4j-mod.json))은 클래스를 불러오지 않고 읽습니다(`ModManifestReader`). 문제가 있으면 런처에서 모드가 `ERROR`가 됩니다.

| 필드 | 필수 | 값 |
|---|---|---|
| `format` | 예 | `1` |
| `id` | 예 | 모드 id |
| `name` | 예 | 언어별: `{"en": "My Mod", "ko": "...", "zh": "..."}`. 런처는 `en`, `ko`, `zh`(간체 중국어) 중 표시 언어로 보여 주고, 없으면 영어로 보여 줍니다 |
| `version` | 모드 플러그인이 씀 | 모드 버전: Gradle 프로젝트의 `version`. 정체성의 일부 |
| `engine` | 모드 플러그인이 씀 | 플러그인 SC4J 버전의 엔진 API 버전 `MAJOR.MINOR`. major가 같고 모드의 minor가 더 높지 않으면 실행([API 정책 3.1](api-policy.ko.md#31-sc4j-버전과-엔진-api-버전)) |
| `entry` | 예 | 진입 클래스의 전체 이름 |
| `description`, `authors`, `website`, `license` | 아니요 | 런처의 모드 상세 정보. `license`에는 에셋 라이선스가 요구하는 표기를 씁니다 |
| `notices` | 아니요 | `name`처럼 언어별인 짧은 법적 고지: `[{"en": "Example Game is a trademark of Example Company.", "ko": "..."}]`. 정보 상자가 모드 이름 아래에 표시 언어나 영어로 보여 줍니다 |
| `images` | 아니요 | `icon`, `banner`, `screenshots`: jar 안의 PNG 경로 |
| `settings` | 아니요 | 실행 전 설정(아래) |
| `tools` | 아니요 | 명령줄 도구: `[{"id": "check", "main": "com.example.mymod.CheckTool"}]`. `id`는 모드 id와 같은 형식이고 모드 안에서 겹치지 않으며, `main`은 `main` 메서드가 있는 클래스의 전체 이름입니다(15장) |

모드 플러그인이 jar의 매니페스트에 `version`과 `engine`을 씁니다. 소스 매니페스트에 둘 중 하나가 있거나, 플러그인 엔진이 읽는 `format`이 없으면 빌드가 실패합니다.

**실행 전 설정**: `key`, `type`(`directory`, `file`, `choice`, `integer`, `string`, `boolean`), `label`, `description`, `required`, `default`, `message`와 타입별 필드를 가진 항목입니다(`SettingsSchema`). 런처가 검사해 `userdata/settings/<id>.json`에 저장하고, 모드는 `ModContext.getSettings()`로 읽습니다. `"systemDefault": true`인 로캘 `choice`의 기본값은 운영 체제 로캘의 선택지(한국의 한국어면 `koKR`)이고, 없으면 `default`입니다. 필수 설정이 비어 있으면 모드는 `WARNING`입니다.

**폴더 설정**에는 폴더 선택기와 선택 필드 세 개가 있습니다.

- `check.contains`: 폴더에 있어야 하는 상대 경로. 없으면 `message`를 보여 줍니다.
- `detect`: 제거 항목 이름(`uninstallEntries`)과 프로그램 폴더 아래 폴더 이름(`programFolders`). 설정이 비어 있는 동안 런처와 런타임이 그곳을 읽기만 하며 찾아보고, `check`를 통과한 첫 폴더를 저장합니다.
- `shared`: 여러 모드가 값 하나를 함께 쓰는 이름. `userdata/shared-settings.json`에 저장됩니다.

모드는 `ModContext.getSettings().getDirectory(key)`로 읽습니다.

**정체성 데이터**: 외부 데이터를 읽는 모드는 그 버전이나 다이제스트를 `ModRegistration.addIdentityData`로 추가합니다.

## 4. 진입 클래스: initialize, register, close

[`DemoMod`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/DemoMod.java).

- `initialize(ModContext)`: 필요한 것을 열고 확인합니다. `ModException`은 실행을 거부합니다. `ModContext`는 매니페스트, 설정, 저장 폴더, 설치 폴더, 모드 인자, 로거를 줍니다. 리소스는 모드의 클래스 로더로 읽습니다.
- `register(ModRegistration)`: 반환하면 등록이 닫힙니다.
- `close()`: 역순으로 해제합니다.

| 등록 | 내용 | 데모 |
|---|---|---|
| `getSimulation()` | 게임 정의(6장) | `DemoRules.define` |
| `getContent()` | 콘텐츠 레지스트리(5장) | `DemoContent.register` |
| `registerResourceProvider` | 클라이언트 리소스 | 팩 |
| `registerReplayFormat`, `registerSaveFormat` | 13장 | 엔진 형식 |
| `registerHeadlessRunner` | `--headless` 실행(9장) | `DemoHeadlessRunner` |
| `registerFirstScene` | 첫 화면(10장) | `MainMenuScene` |
| `registerApplication` | 자체 프레임 루프(19장) | - |
| `addIdentityData` | 정체성 데이터 | - |

## 5. 콘텐츠: 표준 데이터 팩

jar 폴더 하나 아래의 JSON 정의, PNG 그림, WAV 소리이며, `StandardPack.openResources(loader, root)`로 엽니다. 형식(`pack.json`, 스프라이트 시트, 애니메이션, 타일셋, 글꼴, 대화 상자, 그림)은 `org.fogofwarlabs.sc4j.engine.pack` 패키지의 Javadoc입니다([package-info](../sc4j-engine-sim/src/main/java/org/fogofwarlabs/sc4j/engine/pack/package-info.java)).

`content.json`에는 `resourceKinds`, `motionProfiles`(최고 속도, 가속도, 정지 거리, 회전 속도), `weapons`, `factions`(일꾼, 본진 건물, 보급 건물, 자원 종류, 시작 유닛), `units`(진영, 이동 프로필, 체력, 방어력, 시야, 무기, 비용, 생산 시간, 보급, 충돌 상자, 배치 크기, 특성, 대기 명령), `requirements`가 있습니다. `StandardPack.readContent`가 `PackContent`로 읽고, `PackContent.register`가 타입을 추가합니다.

데모: [팩](../mods/demo/src/main/resources/demo/pack/), [`DemoContent`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/content/DemoContent.java).

## 6. 규칙: 게임 정의, 시스템, 명령, 커맨드

**게임 정의**: `ModRegistration.getSimulation()`은 `GameDefinition.Builder`입니다. 개체 한계(`EngineLimits`), `useSystem(KEY, factory)`로 슬롯마다 시스템 하나(기본값은 `Standard*` 클래스: 공간 인덱스, 경로 탐색, 이동, 충돌, 시야, 경제, 채집, 요구 조건, 전투, 투사체, 생산, 명령, 트리거, 컴퓨터 플레이어. 같은 인터페이스의 클래스로 바꿀 수 있음), 확장 열(`StandardCombatSystem.addColumns` 등), 훅(`addHook`), 순서대로 프레임 단계(`addFramePhase`). 팩토리는 게임마다 실행됩니다.

기본값은 중립적이므로 모드가 값을 정합니다: 이동과 투사체의 `new MotionModel.Builder(directionCount)`(한 바퀴의 방향 수, 즉시 정지 속도, 회전 전 감속), `new StandardProductionSystem.Builder(capacity)`(비율이 없으면 비용 전액 환불, 건물은 체력이 가득 찬 채 시작), `StandardCombatSystem.Builder`(스플래시 비율과 최소 피해가 없으면 스플래시가 닿은 모든 유닛이 피해를 전부 받고, 공격이 피해를 주지 못할 수 있음).

배치되고, 보고, 경로를 찾고, 움직이는 유닛에는 `TableUnitBounds`, 공간 인덱스, 경로 탐색, 이동, 충돌, 시야와 프레임 단계 명령, 이동, 충돌, 시야가 필요합니다.

```java
registration.getSimulation()
        .useSystem(MovementSystem.KEY, context -> new StandardMovementSystem(new MotionModel.Builder(64).build()))
        .addFramePhase(MovementSystem.KEY, system -> (StandardMovementSystem) system);
```

데모: [`DemoRules`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/rules/DemoRules.java).

**새 게임**: `GameSetup`(맵 `MapReference`, 슬롯, 컨트롤러 종류, 팀, 색, 진영을 가진 `PlayerSlot`, 시드), `Game.create(definition, setup)`, `MapPlacementUtils.placeUnits`. 데모: [`DemoGames`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/game/DemoGames.java).

**명령**: `OrderType`은 콘텐츠이고, 그 `OrderBehavior`(`MoveBehavior`, `AttackBehavior`, `GatherBehavior`, `BuildBehavior` 등)는 게임마다 등록합니다: `new StandardOrderSystem((game, behaviors) -> behaviors.register(move, new MoveBehavior(16)))`(도착 거리 16 월드 픽셀). 데모: [`DemoOrders`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/order/DemoOrders.java).

**커맨드**: 플레이어의 모든 행동은 고정 id를 가진 `CommandType`들의 `CommandRegistry`에 속한 커맨드입니다(`IssueOrderCommandType`, `TrainUnitCommandType`, `ConstructBuildingCommandType`, 자기 타입). `CommandSink`로 보내고, `ControllerCommandSource`가 프레임 단계 전에 적용합니다. 리플레이와 네트워크 턴이 이를 담으므로 id나 인코딩은 새 모드 버전에서만 바꿉니다.

**자기만의 메커니즘**의 연결 지점:

- 업그레이드나 효과로 바뀌는 값: 엔진 스탯 다음에 등록한 `Stat`(`ContentRegistry.getStats().register(new Stat("..."))`)과 `StatModifier`(`ContentRegistry.addModifier`).
- 타입별 값: `ContentRegistry.addColumn`. 유닛별 값: 유닛 풀 확장 열, 또는 자기 `Unit` 하위 클래스의 필드.
- 체력보다 먼저 피해를 받는 층(보호막, 장갑판): 시스템의 `attach`에서 `GameContent.addDamageLayer`로 유닛 타입마다 추가하는 `DamageLayer`. 표준 전투 시스템은 체인 순서대로 층에 묻고, HUD는 층마다 막대를 보여 줍니다.
- 이동: `MotionModel` 위의 자기 코드, 예정된 `MotionStep`을 옮길 수 있는 `MovingEntity` 필드 위의 자기 알고리즘, 또는 자기 이동 시스템.

## 7. 빌드와 jar

### 7.1 모드 자체의 빌드

모드 플러그인 `org.fogofwarlabs.sc4j.mod`는 엔진과 같은 버전으로 Maven Central에 있습니다. `settings.gradle`:

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

모드 id는 `src/main/resources/sc4j-mod.json`의 `id`이고, 모드 버전은 프로젝트 버전(`gradle.properties`나 `build.gradle`의 `version`)입니다. 매니페스트나 `id`가 없으면 빌드가 실패합니다.

| 태스크나 설정 | 효과 |
|---|---|
| 컴파일 | 모드의 모든 소스 세트(빌드의 어느 프로젝트에 있든)를 플러그인 버전의 SC4J 모드 API, 곧 런타임이 제공하는 엔진 모듈 대상으로 컴파일. jar에는 넣지 않음 |
| `processResources` | `sc4j-mod.json`에 `version`과 `engine`을 씀(3장) |
| `jar` | `build/libs/<id>.jar`, 재현 가능(시간 기록 없음, 고정 순서, 중복이면 실패), `META-INF/`에 `LICENSE`와 `NOTICE`. `sc4j-mod.json`이 다른 id, 다른 버전, 없는 진입 클래스나 도구 클래스를 가리키면 실패 |
| `run` | 플러그인 버전의 엔진 런타임에서 설치본의 사용자 데이터(없으면 `build/sc4j-run`)로 실행. `--args=--headless`, `--debug-jvm` |
| `installMod` | jar를 `<install>/mods/`에 복사(Gradle 속성 `sc4j.install`) |
| `sc4jMod` | `id`(선택, 매니페스트 `id`와 같아야 함. 빌드 태스크가 매니페스트를 쓰면 필요), `sourceSets`(jar에 더 넣을 소스 세트), `install`, `licenses`(`META-INF/`의 파일) |

목록 리더 `sc4j-catalog`는 공개된 정보를 읽습니다. SC4J 릴리즈(`ReleaseCheck`, `ReleaseVersion`, `catalog.github`)와 모드 목록(`catalog.mods`)입니다. `ReleaseCheck.read(new ReleaseClient(ReleaseClient.GITHUB_API), ReleaseCheck.DEFAULT_REPOSITORY, ReleaseVersion.CURRENT)`은 네트워크로 최신 SC4J 릴리즈를 읽으므로 프레임 스레드 밖에서 호출합니다.

태스크는 그룹 `sc4j`에 있고, `tasks.named('jar') { from(...) }`로 파일을 더 넣습니다. 실행에 필요한 라이브러리는 런타임의 `lib/`에 없으면(LWJGL, FFmpeg 등은 있음) jar에 넣으며, 먼저 라이선스를 확인합니다(17장).

### 7.2 이 저장소 안의 모드

데모는 같은 플러그인을 씁니다([`mods/demo/build.gradle`](../mods/demo/build.gradle)). 저장소 안에서는 엔진 의존성이 엔진 프로젝트이고 설치본은 `build/dev-install/`입니다. 검사: `checkDemoNames`(다른 게임 이름 없음), `determinismCheck`(12장). 테스트는 jar를 실행하고(`DemoModTest`) 클라이언트를 화면 밖에서 실행합니다(`DemoClientScenario`).

## 8. 맵과 첫 실행 콘텐츠

**맵 형식**: JSON 파일 하나(`"format": "sc4j-map"`, 버전 1): 이름, 타일셋, 타일과 보행 셀 크기, 타일 단위 크기, 범례(보행 가능, 건설 가능, 고도)가 있는 한 글자 코드 행의 지형, 중립 슬롯, 시작 위치, 유닛, 자원 컨테이너, 로케이션, 승리 규칙, 트리거. 필드: [`JsonMapLoader`](../sc4j-engine-sim/src/main/java/org/fogofwarlabs/sc4j/engine/map/JsonMapLoader.java)의 Javadoc. `new JsonMapLoader(unitTypes, resourceKinds)`로 읽어 `MapCatalog`에 둡니다(맵마다 `MapReference`: 이름과 다이제스트). 승리 규칙과 트리거에는 마지막 프레임 단계의 `StandardTriggerSystem`이 필요합니다: 맵의 `StandardMapData`로 `new StandardTriggerSystem.Builder().addTriggers(data.getTriggers()).build()`, 승리 규칙이 처음.

**격자와 투영**: 시뮬레이션은 보행 셀의 정사각형 격자(`TerrainGrid`) 위에서 월드 픽셀로 동작하고, 클라이언트는 `SquareProjection`이나 `IsometricProjection`으로 투영합니다(데모: [`DemoWorldUtils`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/DemoWorldUtils.java)).

**첫 실행 콘텐츠**: 새 설치본의 저장 폴더는 비어 있습니다. 맵을 저장 폴더에 두는 모드는 `<id>/seed/` 아래에 맵을 담고, `initialize`에서 목록의 파일을 **같은 경로의 파일이 없을 때만** 복사합니다.

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

바꾼 맵은 덮어쓰지 않고, 지운 맵은 다시 생깁니다. 그 뒤 `maps/`의 모든 `*.json`을 파일 이름 순서로 읽고, 올바르지 않은 맵은 경고와 함께 뺍니다. 새 맵은 고유한 `name`으로 `src/main/resources/<id>/seed/maps/`에 두고 목록에 넣습니다(`"maps/second-field.json"`). 시드를 바꿔도 이미 있는 복사본은 바뀌지 않으므로, 지우거나 다른 `--storage`로 실행합니다. 데모는 맵을 jar에서 읽고([`DemoMaps`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/map/DemoMaps.java)) 받은 맵을 그 옆에 둡니다.

## 9. 헤드리스 러너

`HeadlessRunner.run(ModLaunch)`이 `--headless` 실행입니다(창, 오디오, 입력 없음). `ModLaunch`는 요청된 리플레이(`getReplay()`, `getReplayFormat()`)나 참가할 게임(`getJoin()`), 정체성, 게임 정의, 리소스 제공자, 형식, Beacon.Link를 줍니다.

데모: [`DemoHeadlessRunner`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/DemoHeadlessRunner.java). `--map`은 컴퓨터 플레이어 둘의 게임을 진행하고 리플레이를 기록하며, `--replay`는 재생하고 체크포인트가 다르면 실패하고, `--frame-hashes`는 게임 루프마다 해시를 씁니다. `--host`와 런타임의 `--join`, 그리고 `--seed <host:port>`와 함께 `--beaconlink-host`, `--beaconlink-join`은 네트워크 게임을 진행합니다.

## 10. 클라이언트 화면

런타임은 창, 오디오, 입력을 열고, `FirstSceneFactory`로 첫 씬을 만들어 `SceneClient`(실행 요청, 저장 폴더, `Canvas`, `AudioMixer`, 커서 표면) 위에서 프레임을 실행합니다. 화면은 `ClientScene`(`open`, `update`, `draw`, `handleInput`, `close`)이며, `client.getScenes().replace(...)`로 바꾸고 `pushModal`로 팝업을 열고 `client.requestQuit()`로 끝냅니다.

**게임 보여 주기**:

- `GameSessionFactory.createLocal`, `createReplay`, `createNetwork`. 세션의 `GameView`에서 그리고 `CommandSink`로 커맨드를 보냅니다.
- `CameraScroll`이 `Camera`를 스크롤합니다(`ContinuousScrollRate`, `SteppedScrollRate`).
- `WorldLayer`들의 `WorldView`: 그림 없이 `TerrainLayer`와 `UnitLayer`, 팩의 그림으로 `TilesetLayer`와 `UnitSpriteLayer`(배율 있는 화면에서는 고해상도 판 `<id>-2x`부터 `<id>-4x`까지, `DetailUtils`, `spriteSheetDetails`).
- `GameScreen`: HUD 스킨 안의 기본 게임 화면(선택, 커맨드 카드, 미니맵, 자원 표시줄, 선택 패널, 안개). 팩 글꼴이 필요합니다.
- 슬래시 명령과 키 바인딩: `ConsoleCommandRegistry`, `KeyBindingTable`.

데모: [클라이언트 패키지](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/)(`MainMenuScene`, `GameSetupScene`, `DemoGameScene`, `DemoGameScreen`, `GameFileListScene` 위의 `ReplayViewerScene`, `NetworkScene`, `BeaconLinkScene` 등).

**화면 밖 테스트**: 무음 오디오 장치와 `ImageCanvas` 위의 `SceneClient`에 `client.handleInput`으로 입력을 보내고 `client.runFrame`을 실행합니다(데모: `DemoClientScenario`). 이 저장소 밖에서는 모드와 `<install>/runtime/lib`의 모든 jar가 클래스 경로에 있는 소스 세트, 임시 폴더 위의 자기 `ModStorage`(`getRoot`, `resolve`. 런타임의 구현은 API가 아님), `ModIdentity.create(EngineVersion.CURRENT, manifest, digest, List.of())`와 모드의 게임 정의를 가진 `ModLaunch`를 씁니다. `ImageIO.write(canvas.getImage(), "png", file)`로 그림을 저장합니다.

## 11. 컴퓨터 플레이어

게임에만 의존해 커맨드를 내는 `PlayerController`입니다. 기본값: `StandardComputerController`를 쓰는 `StandardComputerPlayerSystem`(`ComputerSettings`). `ControllerKind.COMPUTER` 슬롯은 네트워크 게임의 모든 컴퓨터에서 실행됩니다.

## 12. 결정성

같은 커맨드는 모든 컴퓨터에서 같은 시뮬레이션 결과를 내야 합니다. `checkSimulationDeterminism`이 엔진의 시뮬레이션 패키지를 검사하며, 모드의 시뮬레이션 코드(시스템, 동작, 컨트롤러)도 같은 규칙을 지킵니다.

- `Math.random`, 시계(`System.currentTimeMillis`, `System.nanoTime`, `java.time`), `java.util` 난수 생성기(`Random`, `SecureRandom`, `SplittableRandom`, `ThreadLocalRandom`) 없음. 시드가 정해진 게임의 난수 생성기를 씁니다.
- 시뮬레이션이 순서를 보는 해시 순서 컬렉션(`HashMap`, `HashSet`, `Hashtable`, `IdentityHashMap`, `WeakHashMap`, `ConcurrentHashMap`)과 identity hash code 없음. 리스트, 배열, `LinkedHashMap`, 개체 풀을 씁니다.
- `float`, `double` 없음. 정수나 엔진 고정소수점 값을 씁니다.
- 게임은 커맨드와 프레임 단계로만 바뀌며, 씬, 입력 처리기, 다른 스레드에서 바꾸지 않습니다.
- 순서 고정: 시스템과 단계는 등록 순서, 컨트롤러는 목록 순서, 커맨드는 제출 순서.

클라이언트는 무엇이든 쓸 수 있습니다. `determinismCheck`처럼 두 번의 실행과 리플레이가 게임 루프마다 같은 `Game.computeStateHash`를 내는지 확인합니다(`--frame-hashes`).

## 13. 리플레이와 저장 게임

`EngineReplayFormat`(`.sc4r`, 매직 `SC4R`)은 설정과 모든 커맨드를 기록하고 n 게임 루프마다 상태 해시 체크포인트를 남깁니다(`createRecorder`, `ControllerCommandSource`에 설정). `read`와 `createController`가 재생하며 불일치를 셉니다. `EngineSaveFormat`(매직 `SC4S`)은 게임 상태를 저장합니다. 둘 다 같은 정체성에서만 불러옵니다. 자기 형식은 `ReplayFormat`과 `SaveFormat`을 구현합니다. 파일은 저장 폴더(`replays/`, `saves/`)에 두며, `GameFileListScene`이 목록을 보여 줍니다.

## 14. 네트워크 플레이

`sc4j-engine-net`은 락스텝 게임을 실행합니다. 모든 컴퓨터가 시뮬레이션을 실행하고 턴마다 커맨드를 주고받습니다.

- `NetworkLobby`: 멤버, 슬롯, 진영, 준비 표시, 맵 전송(`LobbyMaps`. 기본 `FolderLobbyMaps`는 `ModStorage.getMapFolders()` 위에서 `maps/`의 맵을 다이제스트로 찾고 받은 맵을 `maps/downloads/`에 저장), 시작(`NetworkGameStart`). 다른 정체성은 거부합니다.
- `DatagramLockstepTransport`, `LockstepSession`: 턴. `GameSessionFactory.createNetwork`가 게임을 보여 줍니다.
- `LanRoomDiscovery`: LAN 방.
- Beacon.Link(`ModLaunch.getBeaconLink()`): 인터넷 방과 커뮤니티.

데모: [`DemoLobby`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoLobby.java), [`DemoNetworkGame`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoNetworkGame.java), [`DemoNetworkSettings`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/net/DemoNetworkSettings.java). 네트워크 코드는 127.0.0.1에서만 테스트합니다.

### 14.1 Beacon.Link 방

명령줄에 시드 출처(`--seeds <file>`, `--seed <host:port>`, `--peers <file>`)가 없으면 `ModLaunch.getBeaconLink()`는 `null`입니다. `--account <dir>`는 계정을 정하며, 없으면 게스트 `Guest`(`BeaconLink.GUEST_NAME`)입니다. 런처는 이 모두를 넘깁니다. `BeaconLink.open()`은 계정의 연결 하나인 `BeaconLinkSession`을 반환합니다(`getParticipant()`: `LinkParticipant`, 플레이어와 연결 번호). 호출은 작업을 시작하기만 하며, 세션의 스레드가 루프에서 `poll()`을, 끝에 `close()`를 호출합니다. `getFailure()`(`LinkFailure`)는 노드에 닿지 못한 이유를, `getNodes()`는 노드마다의 상태를 줍니다. `getConnectivity()`(`LinkConnectivity`)는 다른 플레이어와의 직접 연결이 될 만한지 알려 주며, 노드가 세션을 서로 다른 포트로 관찰하면 `RESTRICTED`입니다([프로토콜](beaconlink/protocol.ko.md#등록과-관찰된-주소)).

- **호스트**: `publishRoom(settings, password)`(빈 비밀번호는 공개 방)는 `HostedRoom`을 반환합니다: `getState()`(`PUBLISHING`, `PUBLISHED`, `WITHDRAWN`, `FAILED`), `update(settings)`, 확인을 통과한 참가자마다 `acceptChannel()`, `withdraw()`. `RoomSettings`: 방 이름과 맵 이름(각각 UTF-8 최대 48바이트), 플레이어 수, 정원, 시작 여부, 최대 64바이트의 모드 데이터(데모: 턴 속도).
- **검색**: `searchRooms()`는 `RoomSearch`를 반환합니다: `getRooms()`(자기 정체성), `getOtherRooms()`(다른 버전, `FoundRoom.getJoinRefusal()`과 함께), `isComplete()`. `FoundRoom`은 호스트, 설정, `isJoinable()`, `isPrivate()`, 모드 id와 버전, `getLatencyEstimate()`(`LatencyEstimate.getLatencyClass()`: 100 ms까지 `GOOD`, 200 ms까지 `FAIR`, `POOR`, `UNKNOWN`)를 줍니다.
- **참가**: `joinRoom(room, password)`는 `ParticipantChannel`을 엽니다: `CONNECTING` 다음 `OPEN`, 또는 `ChannelFailure`와 함께 `CLOSED`. `JOIN_REFUSED`에는 `JoinRefusal`이 따릅니다.
- **플레이**: `getOverlay()`는 `LinkOverlay`(참가자 단위 데이터그램, 최대 1023바이트, 신뢰성 없음)를 줍니다. 포트 1(`LOBBY_PORT`)은 `NetworkLobby`, 포트 2(`GAME_PORT`)는 `LinkLockstepTransport`, 포트 16부터 255(`FIRST_MOD_PORT`)는 모드의 것입니다. `NetworkLobby.host(room, overlay, ...)`와 `NetworkLobby.join(channel, overlay, ...)`는 LAN과 같은 로비와 게임을 줍니다.

데모: `DemoLobby`, [`BeaconLinkScene`](../mods/demo/src/main/java/org/fogofwarlabs/sc4j/demo/client/BeaconLinkScene.java), `DemoHeadlessRunner`.

### 14.2 Beacon.Link 커뮤니티

`BeaconLinkSession.getCommunity()`는 `CommunitySession`을 반환합니다.

- **접속 상태**: `getSelf()`, `setStatus(PlayerStatus)`(`AVAILABLE`, `AWAY`, `IN_ROOM`, `IN_GAME`), `setAwayText`(UTF-8 최대 32바이트), `setAppearOffline`. `getPresence(player)`는 플레이어의 연결들을 합친 `PlayerPresence`를 줍니다: 이름과 태그, 상태, 모드 id와 버전, `canPlayTogether()`, 채널, 자리 비움 문구, 지연 추정, 장치 수.
- **채팅**: `joinChannel(name)`(동시에 최대 4개, 모든 모드가 공유)은 `ChatChannel`을 반환합니다: `say`, `emote`(UTF-8 최대 255바이트. 다섯 줄 연속 뒤에는 초당 한 줄), `getLines()`, `getMembers()`, `leave()`. `listChannels()`.
- **귓속말**: `whisper(player, text)`. 결과(`DELIVERED`, `NOT_ONLINE`)는 리스너로 옵니다.
- **친구와 무시한 플레이어**: `addFriend`, `removeFriend`(친구 최대 16명), `ignore`, `unignore`, `isIgnored`. 목록은 계정에 속하며, 무시한 플레이어의 줄과 귓속말은 버리고 그 방은 모드가 숨깁니다.
- **리스너**: `addListener(CommunityListener)`: `onChatLine`, `onMembersChanged`, `onWhisper`, `onWhisperResult`. `poll()` 안에서 호출됩니다.

`BeaconLinkSession.signOutOtherDevices()`는 계정의 다른 연결을 끝냅니다. `ChannelChat`(`engine.net.beaconlink.community.chat`, `sc4j-engine-net`)이 채널 채팅을 실행합니다. `engine.client.community`(`sc4j-engine`)에는 커뮤니티 화면 부품 `RoomFilter`, `ChatLogBox`, `TableBox`가 있고, `engine.client.lobby`에는 `RecentMaps`가 있습니다. 와이어: [프로토콜 참조](beaconlink/protocol.ko.md). 공개 피어: [운영 가이드](beaconlink/operator.ko.md).

## 15. 빌드, 설치, 실행

- **설치**: jar를 `<install>/mods/`에 넣습니다. 런처는 `READY`, `WARNING`(고칠 설정), `ERROR`(망가진 jar나 매니페스트, 지원하지 않는 엔진 버전, 없는 그림, 중복 id)로 보여 줍니다.
- **런타임**: `sc4j-runtime --mod <jar> [--install <dir>] [--settings <json>] [--storage <dir>] [--log <dir>] [--account <dir>] [--seeds <file>] [--seed <host:port>]... [--peers <file>] [--no-port-mapping] [--headless] [--replay <file>] [--join <address>] [-- <mod arguments>]`(`--help`). 종료 코드: 0 정상, 1 실행 실패, 2 명령줄이나 설정 오류, 3 모드 로드나 검증 실패. 로그: `<install>/userdata/logs/<id>/sc4j.log`.
- **런처**: 모드의 설정, 저장 폴더, 로그 폴더로 런타임을 시작합니다.
- **모드 도구**: `SC4J.bat tool <mod id> <tool id> [arguments]`(Linux는 `SC4J`, Windows 콘솔은 `SC4J-cli.exe`)는 설치본의 Java 런타임과 런타임이 모드에 주는 클래스 경로(런타임 라이브러리와 모드 jar)로 도구의 `main`을 실행합니다. 작업 폴더는 호출한 곳의 현재 폴더이고, 런타임의 JVM 옵션과 함께 설치 폴더를 시스템 속성 `sc4j.install`(`InstallLayout.INSTALL_PROPERTY`)로 넘기며, 인수를 그대로 넘기고 도구의 종료 코드를 돌려줍니다. `tool`은 설치된 모드의 도구를, `tool <mod id>`는 한 모드의 도구를 보여 줍니다.

## 16. API와 버전

자세히: [엔진 API 정책](api-policy.ko.md).

- API는 `sc4j-base`, `sc4j-mod-manifest`, `sc4j-engine-core`, `-sim`, `-net`, `-client`, `sc4j-engine`, `sc4j-catalog`의 public, protected 클래스와 멤버이며 `api/*.api`에 나열됩니다. 각 클래스의 Javadoc이 계약입니다.
- API가 아닌 내부 요소(`@ApiStatus.Internal`): `engine.mod.loading`, `engine.client.render.gl`, `engine.client.audio.openal`, `GlfwClientHost`, `GlfwHostWindow`, `GlfwInput`, `GlfwKeyCodes`, `GlfwCursorSurface`, `WindowsTextInputMethod`, `WindowsSystemPointerSpeed`, `WindowsHostTickClock`. 대신 `engine.client.render`(`GraphicsDevice.getCurrent()`), `engine.client.audio`(`AudioDevice.openDefault()`), `engine.client.host` 인터페이스(`ClientHost.initialize()`. `HostWindow`: `createInput`, `isKeyDown`, `setPointerMode`, `createCursorSurface`, 포커스, 클립보드, 끌어다 놓은 파일, 감마 램프, `getTextInputMethod`. `SystemPointerSpeed`, `HostTickClock`)를 씁니다.
- SC4J 버전은 유의적 버전이며, 그 `MAJOR.MINOR`가 엔진 API 버전(`EngineVersion.CURRENT`)입니다. 모드 플러그인이 이 값을 매니페스트의 `engine` 필드에 씁니다. major는 모드를 깨뜨릴 수 있고 minor는 추가만 합니다. 0.x에서는 무엇이든 바뀔 수 있으며 `.api` 차이로 드러납니다.
- 시뮬레이션 결과가 바뀌면 적어도 minor 버전을 올립니다. 리플레이와 저장 게임은 엔진 버전 사이에 호환되지 않습니다.

## 17. 에셋의 라이선스

- 자기 작업물이나 모드에 담아 재배포할 수 있는 라이선스의 자료(CC0, 또는 조건을 지킬 수 있는 라이선스)를 씁니다. 라이선스 파일과 고지문을 jar에 넣고, 요구되는 표기를 매니페스트의 `license`와 `description`에 넣습니다. 데모: [`mods/demo/THIRD-PARTY.md`](../mods/demo/THIRD-PARTY.md), `demo.jar`에 들어 있음.
- 다른 게임 설치본의 파일은 jar나 저장소에 넣지 않습니다. 실행할 때 폴더 설정(3장)으로 읽습니다.

## 18. 모드 테스트

- 이 저장소 밖: jar를 빌드해 임시 설치본에 설치하고, 런타임을 헤드리스로 두 번, 리플레이로 한 번 실행해 프레임 해시를 비교합니다.
- 배포되는 테스트 픽스처([API 정책](api-policy.ko.md#1-api란)): `RecordingCommunitySession`, `RecordingBeaconLink`(`sc4j-engine-net`), 127.0.0.1에서 NAT를 에뮬레이션하는 `ClientScenario`(`sc4j-beaconlink`), `FakeInstallationRecords`(`sc4j-mod-manifest`).
- 이 저장소 안: 테스트 JVM의 `RuntimeSession.run`(`DemoModTest`), 화면 밖 클라이언트(`DemoClientScenario`), 런타임 JVM 둘(`determinismCheck`).

## 19. 모드가 실행되는 두 방식

모드는 `register`에서 둘 중 하나를 등록합니다. 둘 다 등록하면 `IllegalStateException`이 발생합니다.

| | 엔진 씬 | 자체 애플리케이션 |
|---|---|---|
| 등록 | `registerFirstScene(FirstSceneFactory)`, `registerHeadlessRunner(HeadlessRunner)` | `registerApplication(ModApplication)` |
| 창 실행 | 런타임이 창, 캔버스, 오디오, 커서, 입력을 열고 `ClientHost` 위에서 `WindowClient`(`HostApplication`: 이벤트 폴링, 씬에 입력 전달, `SceneClient` 프레임 실행, 수직 동기화로 표시)를 실행 | 런타임이 `ModApplication.run(ModLaunch)`을 호출하고, 모드가 창, 캔버스, 오디오, 커서를 열고 `ClientHost.initialize()`, `createWindow`, `ClientHost.run`으로 자기 `HostApplication`(`runFrame`, `getFrameRateLimit`)을 실행 |
| `--headless` | `HeadlessRunner` | `isHeadlessCapable()`이면 같은 `run` |
| 사용 | 첫 모드, 데모 | 자체 프레임 루프를 가진 모드 |

모드가 프레임 루프를 직접 가져야 하지 않으면 엔진 씬을 씁니다.
