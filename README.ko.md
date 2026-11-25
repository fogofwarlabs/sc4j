<h1 align="center">
  <img src="docs/branding/sc4j-logo.svg" alt="SC4J 로고" width="160">
  <br>
  SC4J
</h1>

<p align="center">Java로 만든 실시간 전략 게임 엔진. 모드 런타임, 런처, P2P 온라인 플레이를 함께 제공합니다.</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.fogofwarlabs.sc4j/sc4j-engine"><img src="https://img.shields.io/maven-central/v/org.fogofwarlabs.sc4j/sc4j-engine" alt="Maven Central"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center"><a href="README.md">English</a> | <b>한국어</b></p>

---

SC4J(Simulation Core for Java)는 실시간 전략 게임 엔진입니다. 게임은 **모드**이며, 런타임이 불러오고 런처가 보여 주는 jar 하나입니다. 모든 SC4J는 방, 채팅, 접속 상태를 위한 네트워크 Beacon.Link의 피어이며, 중앙 서버는 없습니다.

이 저장소에는 엔진, 런타임, 런처, Beacon.Link, 데모 모드 [`mods/demo/`](mods/demo/)(id `demo`, "SC4J Demo: Dawnhold and Cindermaw")가 있습니다. SC:R 모드와 SC:R Crossover는 각자의 저장소에 있습니다: [sc4j-scr](https://github.com/fogofwarlabs/sc4j-scr), [sc4j-scr-crossover](https://github.com/fogofwarlabs/sc4j-scr-crossover).

## 기능

- 게임 루프마다 상태 해시를 내는 결정적 락스텝 시뮬레이션. 경로 탐색, 이동, 충돌, 시야, 경제, 채집, 전투, 투사체, 생산, 명령, 트리거, 컴퓨터 플레이어의 기본 시스템이 있고, 모드가 어느 것이든 바꿀 수 있습니다.
- 표준 데이터 팩(JSON, PNG, WAV)과 파일 하나짜리 JSON 맵.
- 클라이언트: 씬, 입력, OpenGL 렌더러, 정사각형과 아이소메트릭 투영, HUD가 있는 기본 게임 화면, 오디오, 동영상.
- 리플레이와 저장 게임. 기록한 모드 정체성에서만 불러옵니다.
- LAN 방과 게임. Beacon.Link 방, 채팅, 귓속말, 친구, 접속 상태.
- 런처: 모드 목록, 모드 설정 입력, 런타임 시작, 이 컴퓨터의 Beacon.Link 노드 실행, GitHub 릴리즈에서 모드 설치와 자체 업데이트.
- 모드 런타임: 세션마다 모드 하나를 자체 클래스 로더에서 실행.
- 모듈별 엔진 API 목록과 빌드 검사([API 정책](docs/api-policy.ko.md)).

## 모듈

| 경로 | 내용 |
|---|---|
| `sc4j-base` | 예외, 로깅, 바이너리, 파일, JSON, 텍스트 도우미 |
| `sc4j-mod-manifest` | 모드 매니페스트 `sc4j-mod.json`, 설정, 버전, 설치 폴더 구조 |
| `sc4j-engine-core` | 고정소수점 연산, 맵 격자, 지형, 플레이어, 상태 해시, 콘텐츠 타입, 모드 정체성 |
| `sc4j-engine-sim` | 게임, 유닛, 기본 시스템, 명령, 커맨드, 데이터 팩, 맵, 리플레이, 저장 |
| `sc4j-engine-net` | 데이터그램 링크, 락스텝 전송, LAN 탐색, 네트워크 로비, Beacon.Link와 커뮤니티 인터페이스, 채널 채팅 |
| `sc4j-engine-client` | 호스트, 씬, 입력, 렌더러, 투영, HUD, 콘솔, 오디오, 동영상 |
| `sc4j-engine` | 모드가 컴파일할 때 쓰는 모드 API와 클라이언트 애플리케이션 |
| `sc4j-runtime` | 모드 jar 하나를 창 모드 또는 헤드리스로 실행 |
| `sc4j-launcher` | 런처(JavaFX) |
| `sc4j-catalog` | 공개된 정보: GitHub 릴리즈, 새 릴리즈 확인, 모드 목록. 런처가 사용하고 런타임이 모드용으로 포함 |
| `sc4j-beaconlink-protocol`, `-peer`, `-node`, `sc4j-beaconlink` | Beacon.Link 와이어 프로토콜, 피어 라이브러리, 헤드리스 노드, 클라이언트 |
| [`mods/`](mods/) | 데모 모드 |
| [`tools/`](tools/) | `api-check`(API 목록), `windows-manifest`(Windows 프로그램의 UTF-8 코드 페이지) |
| [`docs/`](docs/README.ko.md) | 가이드. [`docs/branding/`](docs/branding/README.ko.md): 로고와 데모의 런처 그림 |
| [`licenses/`](licenses/) | 서드파티 라이선스 전문 |

## 빌드와 실행

JDK 25가 필요하며, Gradle은 Gradle 래퍼가 내려받습니다. 명령은 저장소 루트의 PowerShell에서 실행합니다(Linux와 macOS에서는 `./gradlew`).

| 명령 | 결과 |
|---|---|
| `.\gradlew.bat build` | 컴파일, 단위 테스트, 검사 |
| `.\gradlew.bat devInstall` | 개발용 설치본 `build/dev-install/`(런처, 런타임, `mods/demo.jar`) |
| `.\gradlew.bat runDevLauncher` | 개발용 설치본의 런처 열기 |
| `.\gradlew.bat runDevInstall -Psc4j.mod=<id>` | 개발용 설치본의 `mods/<id>.jar` 실행(기본값 `demo`) |
| `.\gradlew.bat distribution` | 빌드한 플랫폼의 사용자 패키지 `build/distributions/SC4J-<version>-<platform>.zip` |
| `.\gradlew.bat :sc4j-beaconlink-node:installDist` | 헤드리스 Beacon.Link 노드를 `sc4j-beaconlink-node/build/install/`에 빌드 |

런처는 `build/dev-install/mods/`의 모든 jar를 보여 줍니다. 다른 저장소의 모드는 그 저장소의 `installMod -Psc4j.install=<이 저장소>\build\dev-install`로 이곳에 설치되며, `devInstall`은 그 jar를 지우지 않습니다.

## 설치

GitHub 릴리즈마다 플랫폼별 사용자 패키지 `SC4J-<version>-<platform>.zip`이 있습니다. `<platform>`은 `windows-x64`, `linux-x64`, `linux-arm64`, `macos-arm64`(Apple Silicon) 중 하나입니다. 패키지에는 런처, 런타임, 데모와 그 플랫폼용 Java 런타임이 있으며, `userdata/`는 처음 시작할 때 만들어집니다. 게임 창에는 OpenGL 3.3이 필요합니다.

- **Windows**: zip을 풀고 `SC4J.exe`(또는 `SC4J.bat`)를 실행합니다.
- **Linux**: 프로그램 권한을 유지하는 `unzip`으로 풀고 `./SC4J`를 실행합니다. 런처에는 GTK 3이 필요합니다.
- **macOS**: zip을 풉니다. 패키지는 개발자 서명이 없으므로 먼저 내려받은 파일의 격리 속성을 지웁니다: `xattr -dr com.apple.quarantine <folder>`. 그다음 Finder에서 `SC4J.command`를 열거나 터미널에서 `./SC4J`를 실행합니다.

런처는 SC4J의 GitHub 릴리즈에서 자기 플랫폼의 패키지로만 스스로 업데이트하고, GitHub 저장소(`owner/name`, 직접 입력하거나 [`fogofwarlabs/sc4j-mods`](https://github.com/fogofwarlabs/sc4j-mods) 목록에서 선택)의 최신 릴리즈에서 모드를 설치합니다. 업데이트는 패키지에 포함된 모드 jar(`launcher/package-mods.txt`에 기록)도 바꾸며, `userdata/`와 `mods/`의 다른 jar는 그대로 남고 플레이어가 지운 포함 jar는 다시 추가하지 않습니다.

## 문서

| 가이드 | 주제 |
|---|---|
| [모드 제작 가이드](docs/mod-guide.ko.md) | 모드 만들기 |
| [모드 템플릿](https://github.com/fogofwarlabs/sc4j-mod-template) | 빌드, 실행, 설치가 되는 시작용 모드 |
| [엔진 API 정책](docs/api-policy.ko.md) | API 범위, 버전, 내부 요소 |
| [Beacon.Link 프로토콜](docs/beaconlink/protocol.ko.md) | 와이어 프로토콜과 파일 |
| [Beacon.Link 운영 가이드](docs/beaconlink/operator.ko.md) | 공개 피어 운영 |
| [브랜딩](docs/branding/README.ko.md) | 로고, 배너, PNG 내보내기 |
| [기여 안내](CONTRIBUTING.ko.md) | 개발 환경, 테스트, 코드 스타일, 커밋 |

## 라이선스

[Apache License 2.0](LICENSE). 저장소와 배포본의 서드파티 자료는 [NOTICE](NOTICE)에, 라이선스 전문은 [`licenses/`](licenses/)에 있습니다.

| 자료 | 라이선스 | 자세히 |
|---|---|---|
| 데모 그림: KayKit 모델과 Poly Haven 텍스처의 렌더 | CC0 1.0 | [`mods/demo/THIRD-PARTY.md`](mods/demo/THIRD-PARTY.md) |
| 배포본의 라이브러리(LWJGL, JavaCPP, FFmpeg, OpenJFX 등) | 각 라이브러리의 라이선스 | [NOTICE](NOTICE), [`licenses/`](licenses/) |
