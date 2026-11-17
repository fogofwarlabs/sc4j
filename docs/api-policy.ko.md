# SC4J 엔진 API 정책

[English](api-policy.md) | **한국어**

엔진 API의 범위, 버전이 바뀌는 방식, 호환되는 것을 정합니다. 모드 제작자용 요약: [모드 제작 가이드 16장](mod-guide.ko.md#16-api와-버전).

## 1. API란

아래 모듈의 main 클래스에 있는 모든 `public` 또는 `protected` 클래스, 인터페이스, 생성자, 메서드, 필드(상수 값 포함)입니다. 2절의 내부 요소는 빠집니다.

| 모듈 | 내용 | 목록 |
|---|---|---|
| `sc4j-base` | 예외, 로깅, 바이너리, 파일, JSON, 텍스트 도우미 | `sc4j-base/api/sc4j-base.api` |
| `sc4j-mod-manifest` | 모드 매니페스트 `sc4j-mod.json`, 검증, 설정, 버전, 설치 폴더 구조 | `sc4j-mod-manifest/api/sc4j-mod-manifest.api` |
| `sc4j-engine-core` | JSON, 고정소수점 연산, 맵 격자, 지형, 플레이어, 상태 해시, 콘텐츠 타입, 모드 정체성, 리소스 모델 | `sc4j-engine-core/api/sc4j-engine-core.api` |
| `sc4j-engine-sim` | 게임, 유닛, 기본 시스템, 콘텐츠, 애니메이션 프로그램, 명령, 커맨드, 컴퓨터 플레이어, 트리거, 데이터 팩, 맵, 락스텝, 리플레이, 저장 | `sc4j-engine-sim/api/sc4j-engine-sim.api` |
| `sc4j-engine-net` | 데이터그램 링크, 락스텝 전송, LAN 탐색, 네트워크 로비, Beacon.Link와 커뮤니티 인터페이스, 채널 채팅 | `sc4j-engine-net/api/sc4j-engine-net.api` |
| `sc4j-engine-client` | 호스트, 씬, 입력, 렌더러 추상화, 투영, 텍스트, HUD, 커맨드 카드, 콘솔, 대화 상자, 커서, 스킨, 월드 뷰, 오디오, 동영상, 설정 저장소 | `sc4j-engine-client/api/sc4j-engine-client.api` |
| `sc4j-engine` | 모드 API(`engine.mod`), 클라이언트 애플리케이션(`engine.client.app`), 커뮤니티 화면 부품(`engine.client.community`), 로비 부품(`engine.client.lobby`) | `sc4j-engine/api/sc4j-engine.api` |
| `sc4j-catalog` | 공개된 정보: GitHub 릴리즈, 릴리즈 버전, 새 릴리즈 확인, 모드 목록 | `sc4j-catalog/api/sc4j-catalog.api` |

모드 플러그인은 런타임이 제공하는 모드 API인 이 모듈들을 대상으로 모드를 컴파일합니다([모드 제작 가이드 7.1장](mod-guide.ko.md#71-모드-자체의-빌드)).

배포되는 테스트 픽스처도 API입니다(`testImplementation testFixtures('org.fogofwarlabs.sc4j:<module>:<version>')`, 목록은 `<module>/api/<module>-test-fixtures.api`): `FakeInstallationRecords`와 `TestJarUtils`(`sc4j-mod-manifest`), `RecordingBeaconLink`와 `RecordingCommunitySession`(`sc4j-engine-net`), `ManualProtocolClock`(`sc4j-beaconlink-protocol`), `ClientScenario`와 루프백 NAT 에뮬레이터(`sc4j-beaconlink`). 다른 모듈은 테스트 픽스처를 배포하지 않습니다.

목록은 모듈의 컴파일된 클래스에 대한 `javap -protected -constants` 출력이며, 클래스마다 블록 하나, 클래스와 멤버는 정렬되어 있습니다. 클래스와 그 클래스를 감싸는 모든 클래스가 `public` 또는 `protected`일 때 목록에 들어갑니다. 선언 순서는 목록을 바꾸지 않고, 컴파일러의 브리지 메서드는 목록에 보입니다. 애너테이션은 보이지 않으므로 null 여부 변경은 리뷰에서 잡아야 하는 계약 변경입니다(3.2절).

API 요소의 Javadoc은 계약의 일부입니다. 모든 API 요소에 Javadoc이 있고, `javadoc`은 경고 없이 실행됩니다.

## 2. 내부 요소

엔진 모듈과 런타임을 위해 public이지만 API가 아니며, 어느 버전에서든 바뀌거나 없어질 수 있습니다. 모두 `@ApiStatus.Internal`(JetBrains)이 붙어 있고, 목록에서 빠지며, package-info에 그렇게 적혀 있습니다.

| 요소 | 역할, 모드가 대신 쓰는 것 |
|---|---|
| `org.fogofwarlabs.sc4j.engine.mod.loading` | 런타임의 모드 로더 |
| `org.fogofwarlabs.sc4j.engine.client.render.gl` | 렌더러의 OpenGL 기본값. 모드는 `engine.client.render`를 씁니다(`GraphicsDevice.getCurrent()`가 자기 패스의 `GpuCanvas`와 `ShaderProgram`을 만듦) |
| `org.fogofwarlabs.sc4j.engine.client.audio.openal` | 오디오의 OpenAL 기본값. 모드는 `engine.client.audio`(`AudioDevice.openDefault()`)를 씁니다 |
| `engine.client.host.GlfwClientHost`, `GlfwHostWindow`, `GlfwInput`, `GlfwKeyCodes`, `GlfwCursorSurface` | GLFW 호스트. 모드는 `engine.client.host` 인터페이스(`ClientHost.initialize()`, `HostWindow`, `WindowInput`)와 값 타입을 씁니다 |
| `engine.client.host.WindowsTextInputMethod`, `WindowsSystemPointerSpeed`, `WindowsHostTickClock` | Windows 플랫폼 서비스. 모드는 `HostWindow.getTextInputMethod()`, `SystemPointerSpeed.getDefault()`, `HostTickClock.getDefault()`를 씁니다(이식 가능한 기본값 `NoTextInputMethod`, `FixedSystemPointerSpeed`, `MonotonicHostTickClock`은 API) |

package-info의 애너테이션은 그 패키지(하위 패키지 제외)에, 클래스의 애너테이션은 그 중첩 클래스에 적용됩니다. `apiDump`와 `apiCheck`는 컴파일된 클래스에서 이를 읽으므로, 붙이거나 떼면 목록, 이 표, package-info가 한 커밋에서 바뀝니다. 빌드는 모드가 내부 요소를 쓰는 것을 막지 않습니다.

## 3. 버전

### 3.1 SC4J 버전과 엔진 API 버전

SC4J 버전(`gradle.properties`의 `version`)은 유의적 버전 `MAJOR.MINOR.PATCH`입니다. 변경이 어느 부분을 올리는지는 3.2절이 정합니다.

엔진 API 버전 `EngineVersion.CURRENT`(`sc4j-mod-manifest`)는 빌드에서 가져온 SC4J 버전의 `MAJOR.MINOR`입니다: `0.1.0-SNAPSHOT`이면 `0.1`. 모드 플러그인이 이 값을 모드 `sc4j-mod.json`의 `engine` 필드에 쓰고, 런처와 런타임은 major가 같고 모드의 minor가 더 높지 않을 때만 모드를 실행합니다.

### 3.2 무엇이 어느 번호를 바꾸는가

| 변경 | 올릴 SC4J 버전 부분 |
|---|---|
| 목록 줄이 지워지거나 호환되지 않게 바뀜: 클래스, 멤버, 생성자의 제거나 이름 변경, 인자, 반환, 필드 타입 변경, 접근 범위 축소, 모드 코드가 알 수 있는 `final`, `abstract`, `static` 변경, 모드가 구현하거나 확장하는 타입에 추상 메서드 추가, 검사 예외 추가, 상수 값 변경 | major |
| 목록 변경 없이 문서화된 계약이 바뀜: 반환하거나 받는 값, 예외 조건, 스레드나 수명 규칙, 입력의 null 허용 축소나 출력의 null 허용 확대 | major |
| 같은 입력의 시뮬레이션 결과가 바뀜(4절) | 적어도 minor |
| 추가만: 클래스, 멤버, 생성자, default 메서드, enum 상수(모드가 그 enum을 빠짐없이 switch하지 않는 경우), 호환되는 계약 확대 | minor |
| 사용 중단(3.3절) | minor |
| 목록과 계약 변경 없음(구현, 성능, 내부 요소) | patch |

바뀐 목록 줄은 지운 줄 하나와 추가한 줄 하나로 보입니다. 차이의 모양이 아니라 이 표로 정합니다.

### 3.3 사용 중단

minor 릴리즈에서 `@Deprecated`와 대체 요소를 밝히는 Javadoc `@deprecated` 태그를 붙이고, `forRemoval = true`는 제거할 릴리즈를 표시합니다. 사용 중단된 요소는 적어도 minor 릴리즈 하나 동안 남고, major 릴리즈에서만 제거됩니다.

### 3.4 1.0 선언 전

SC4J 버전은 0.x입니다(엔진 API 버전 `0.MINOR`). 0.x에서는 안정성이 없습니다. 호환되지 않는 변경이 버전 변경 없이 일어날 수 있고, 각각 `.api` 차이로 드러납니다. 3.1절 규칙은 적용되며, 3.2절과 3.3절은 1.0부터 모두 적용됩니다.

## 4. 결정성, 리플레이, 저장 게임

시뮬레이션 결과가 바뀌면 API가 그대로여도 락스텝 게임과 리플레이가 깨지므로 적어도 minor 버전을 올립니다. `ModIdentity`에는 엔진 API 버전이 들어 있어, 엔진 버전이 다른 피어는 로비와 방에서 서로를 거부합니다. 엔진 리플레이(`.sc4r`, 매직 `SC4R`)와 저장 게임(매직 `SC4S`)은 같은 정체성에서만 불러옵니다: 엔진 API 버전, 모드 id, 모드 버전, 내용. 리플레이와 저장 게임은 엔진 버전 사이에 호환되지 않습니다.

## 5. 와이어 형식과 파일 형식

형식마다 자기 버전이 있습니다. 1.0 전에는 모든 번호가 1이고 형식은 번호를 바꾸지 않고 바뀔 수 있으며, 예전 구조는 읽지도 옮기지도 않습니다. 번호가 다른 양 끝은 서로를 거부하고, 읽는 쪽은 번호가 다른 파일을 거부합니다.

| 형식 | 버전 | 위치 |
|---|---|---|
| 데이터그램 위의 락스텝 전송: 턴, 재전송, 이탈, 탈락 합의 | 1 | `DatagramLockstepTransport.PROTOCOL_VERSION`(`sc4j-engine-net`) |
| LAN 방 탐색 | 1 | `LanRoomDiscovery.PROTOCOL_VERSION`(`sc4j-engine-net`) |
| 네트워크 로비 | 1 | `NetworkLobby.PROTOCOL_VERSION`(`sc4j-engine-net`) |
| Beacon.Link 프로토콜: 엔벨로프, 메시지, 레코드, 채널 신호와 데이터그램 | 1 | `Envelope.PROTOCOL_VERSION`(`sc4j-beaconlink-protocol`, [프로토콜 참조](beaconlink/protocol.ko.md)) |
| Beacon.Link 파일: 노드 목록, 학습한 피어, 키 파일, 계정, 친구와 무시한 플레이어 | 1 | `NodeListFile.FORMAT`, `PeerStore.FORMAT`, `IdentityKeyFileUtils.VERSION`, `AccountStore`, `PlayerListStore.FORMAT`([프로토콜 참조, "파일"](beaconlink/protocol.ko.md#파일)) |
| Beacon.Link 계정 백업(`.sc4jaccount`) | 1 | `AccountBackup.VERSION`(`sc4j-beaconlink-peer`) |
| 엔진 리플레이 | 1 | `EngineReplayFormat`(`sc4j-engine-sim`) |
| 엔진 저장 게임 | 1 | `EngineSaveFormat`(`sc4j-engine-sim`) |
| JSON 맵 | 1 | `JsonMapLoader.VERSION`(`sc4j-engine-sim`) |
| 표준 팩 매니페스트(`pack.json`) | 1 | `PackManifest`(`sc4j-engine-sim`, 패키지 `engine.pack`) |
| 표준 팩 콘텐츠(`content.json`) | 1 | `PackContentReader`(`sc4j-engine-sim`, 패키지 `engine.pack`) |
| 모드 정체성 와이어 형식 | 로비, 탐색, 리플레이 버전을 따름 | `ModIdentity`(`sc4j-engine-core`) |

## 6. 목록 갱신

`check`(따라서 `build`)는 1절의 모든 모듈에 `apiCheck`를, 배포되는 테스트 픽스처에 `testFixturesApiCheck`를 실행합니다. 컴파일된 클래스가 `.api` 파일과 다르면 실패하고, 클래스마다 지운 줄(`-`)과 추가한 줄(`+`)을 출력합니다.

1. Javadoc과 함께 변경합니다.
2. `gradlew apiDump testFixturesApiDump` 또는 `gradlew :<module>:apiDump`(`:<module>:testFixturesApiDump`)를 실행합니다.
3. `.api` 차이를 3.2절로 검토하고 버전 영향을 커밋 메시지에 적습니다.
4. `.api` 파일을 변경과 함께 커밋합니다. API 변경 둘을 병합한 뒤에는 병합 결과에서 `apiDump`를 다시 실행합니다.

의도하지 않은 `apiCheck` 실패는 변경이 실수로 API를 건드렸다는 뜻이므로, 그 부분을 되돌립니다.
