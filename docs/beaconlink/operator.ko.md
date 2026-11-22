# Beacon.Link 피어 운영 가이드: 헤드리스 피어와 런처 공개 모드

[English](operator.md) | **한국어**

다른 플레이어를 위한 전체 피어를 실행하는 방법입니다. 공개 모드의 런처(계속 켜 두는 데스크톱) 또는 헤드리스 노드 `sc4j-beaconlink-node`(서버)로 실행합니다. 모든 SC4J는 Beacon.Link 피어이며 중앙 서버는 없습니다. 와이어 세부 사항: [프로토콜 참조](protocol.ko.md).

## 전체 피어가 하는 일

전체 피어는 모르는 상대가 닿을 수 있는 피어입니다. 조회를 라우팅하고(Kademlia), 다이얼백 자체 시험을 실행하고 돕고, 자기 ID에 가장 가까운 토픽을 저장하며 그 레코드(방, 접속 상태, 채팅 줄, 로그아웃)를 가십하고, NAT 뒤 플레이어의 홈 피어가 됩니다. 홈 피어로서 플레이어의 연결을 등록하고(플레이어 키당 최대 8개), 관찰된 주소를 알려 주고(STUN 서비스 불필요), 레코드를 게시하고 보내 주고, 홀 펀칭 신호와 봉인된 귓속말을 중계하며, 다른 곳에서 로그아웃된 연결을 거부합니다(`SIGNED_OUT`). 핑에 답합니다.

자신이 참여하지 않은 게임의 트래픽을 나르지 않고, 채널 데이터그램을 중계하지 않으며, 서명된 레코드나 신호를 위조할 수 없습니다. 버리거나 늦출 수만 있으며, 플레이어는 여러 피어를 씁니다.

## 런처 공개 모드

열려 있는 모든 런처는 로그인 여부와 관계없이 이 컴퓨터의 노드를 실행합니다. 설치본에서 처음 연 런처가 노드를 가지며(`node.lock`), 같은 설치본의 다른 런처는 첫 런처가 닫힌 뒤 5분 안에 넘겨받습니다. 노드는 키 하나(`peer.key`)와 포트 하나를 유지합니다.

**역할**(`node.json`): 자동(기본값, "다른 플레이어 연결 돕기" 켬)은 모든 IPv4 인터페이스의 UDP 포트(기본값 7455. 사용 중이면 로그에 남긴 빈 포트)에서 수신하고, 자체 시험을 통과하면 전체 피어, 아니면 리프입니다. 끔: 빈 포트의 리프 전용이며, 네트워크에 참가하고 `peers.json`을 최신으로 유지합니다.

**포트 매핑**(`node.json`의 `portMapping`, "공유기 포트 자동 열기(UPnP)", 기본값 켬): 런처는 공유기에 노드 포트(자동 역할일 때만)와 각 게임 세션의 포트를 열어 달라고 PCP, NAT-PMP, UPnP-IGD로 동시에 요청합니다. 임대는 1시간이고 절반이 지나면 갱신하며 닫을 때 해제합니다. 공유기 목록에서 매핑 이름은 `SC4J Beacon.Link`입니다. 매핑된 주소가 사설 주소이거나 100.64.0.0/10 안에 있거나 피어가 관찰한 주소와 다르면 바깥에 NAT가 또 있으므로 쓰지 않습니다. 설정을 끄거나 공유기가 이 프로토콜을 지원하지 않으면 직접 포트 포워딩해야 전체 피어가 됩니다. 헤드리스 노드는 매핑하지 않습니다.

**다른 플레이어를 위해 서비스하기**:

1. 런처, 설정, Beacon.Link: "다른 플레이어 연결 돕기" 켬, UDP 포트(기본값 7455), "적용".
2. 공유기: 포트 매핑이 켜져 있고 공유기가 포트를 열면(로그 줄 `port mapping by ...`) 할 일이 없습니다. 아니면 UDP(TCP 아님) 7455를 이 PC로 포워딩하고, PC에 고정 LAN 주소를 줍니다(DHCP 예약).
3. Windows Defender 방화벽: 런처의 Java 런타임에 들어오는 UDP 7455를 허용합니다(처음 수신할 때 Windows가 묻습니다).
4. 런처를 켜 둡니다. 게임은 열려 있지 않아도 됩니다.

**패널**은 네트워크 상태("연결됨", "응답하는 피어 없음", "피어 없음"), "외부 접속 가능", "외부 접속 확인 안 됨" 또는 "외부 접속 안 됨 (UDP 7455)", 로컬과 외부 IP 주소, 피어 수를 보여 줍니다. "외부 접속 가능"에는 통과한 자체 시험이나 모르는 상대의 hello가 필요하며, 맨 처음 시드는 다른 피어가 연결할 때까지 "확인 안 됨"입니다. "다른 런처에서 실행 중"은 설치본의 다른 런처가 노드를 가지고 있다는 뜻입니다.

**포트 매핑 로그 줄**(런처와 게임 로그): `port mapping by <protocol> at <router>: <external address> -> :<port>/UDP, lease <time>`, `port mapping not available (PCP: ..., NAT-PMP: ..., UPnP: ...)`, `port mapping skipped: ...`(공유기 바깥에 NAT가 또 있음), `port mapping renewed`, `released` 또는 `lost`.

**외부 접속 안 됨**: 포트 매핑 로그 줄, 포워딩, 방화벽 규칙을 확인합니다. 외부 IP가 100.64.0.0/10 안에 있거나 공유기의 WAN 주소와 다르면 통신사 NAT 뒤이므로 이 PC는 전체 피어가 될 수 없습니다. 유동 IP라면 동적 DNS 이름을 시드 주소로 씁니다.

**파일**(`<install>/userdata/beaconlink/`, [형식](protocol.ko.md#파일)):

| 파일 | 내용 |
|---|---|
| `node.json` | 역할 `automatic` 또는 `off`, 포트, 포트 매핑 |
| `peer.key` | 노드의 키 |
| `node.lock` | 노드를 실행하는 런처가 잡고 있음 |
| `peers.json` | 학습한 피어 |
| `nodes.json` | 사용자의 노드 목록(패널의 "피어" 버튼). 저장하기 전까지 `<install>/launcher/seeds.json`이 대신함 |

## 헤드리스 피어

`gradlew :sc4j-beaconlink-node:installDist`는 `sc4j-beaconlink-node/build/install/sc4j-beaconlink-node/`를 만듭니다: `bin/sc4j-beaconlink-node`(Windows는 `.bat`), `lib/`(노드, 피어 라이브러리, 프로토콜, 기반. 엔진, 모드, 그래픽 없음), `docs/`(`operator.md`, `protocol.md`, `operator.ko.md`, `protocol.ko.md`), `LICENSE`, `NOTICE`, `licenses/`. Java 25가 필요합니다(헤드리스로 충분).

```text
sc4j-beaconlink-node --state <dir> --bind <ip>:<port> [--peer <host>:<port>]...
```

| 옵션 | 의미 |
|---|---|
| `--state <dir>` | 필수. 노드의 폴더(키, 학습한 피어, 로그). 없으면 만듦 |
| `--bind <ip>:<port>` | 필수. IP 리터럴과 UDP 포트, 기본값 없음. 예: `203.0.113.7:7455`. IPv6는 대괄호(`[2001:db8::1]:7455`)이지만 피어 계층은 IPv4만 라우팅 |
| `--peer <host>:<port>` | 시드, 반복 가능. 이름은 시작할 때 한 번 조회. 첫 시드는 없이 실행 |
| `--help`, `-h` | 사용법 |

종료 코드: `0` 정상 종료(`--help` 포함), `1` 실행 실패(바인딩할 수 없는 주소 등), `2` 명령줄 오류. 노드는 런처 노드와 같은 피어를 기본 한계로, 멈출 때까지(Ctrl+C, SIGTERM, 서비스 중지) 실행합니다. 멈출 때 학습한 피어를 `peers.json`에 병합하고 카운트를 로그에 남깁니다. NAT 뒤에서는 UDP 포워딩의 내부 주소에 바인딩합니다.

| 환경 변수 | 의미 |
|---|---|
| `JAVA_OPTS`, `SC4J_BEACONLINK_NODE_OPTS` | JVM 옵션. 예: `-Xmx256m` |
| `SC4J_BEACONLINK_NODE_CACHE` | JVM 임시 파일 폴더(`<it>/tmp`, 시작할 때마다 만듦). 기본값 `<install>/.cache`. 설치본이 읽기 전용이면 지정합니다 |

### 상태 폴더

| 파일 | 내용 |
|---|---|
| `identity.key` | 노드의 피어 키(`BLKF` 버전 1). 첫 시작 때 만듦. 비공개로 둡니다. 새 키는 새 피어입니다 |
| `peers.json` | 학습한 전체 피어, 최대 256개. 5분마다와 멈출 때 병합. 답 없는 시작 3번이나 성공 없는 30일 뒤 버림. 다음 시작 때 인사하므로 재시작에는 시드가 필요 없음 |
| `logs/` | 로그 파일 |

노드는 이 폴더와 캐시 폴더 밖에는 쓰지 않습니다.

### 로그 줄

- 시작할 때마다 `Node <key> listens at /<ip>:<port>.`와 `Peer key: <64 hexadecimal digits>`. 키는 시드의 고정 키 항목에만 씁니다.
- `Joining the network through <n> seeds and learned peers.` 다음에 피어가 답하면 `Network state: CONNECTED`, 30초 안에 아무도 답하지 않으면 `Network state: NO_PEER_REACHABLE`. 첫 시드(`0 seeds`)는 첫 피어가 오면 `CONNECTED`를 남깁니다.
- `Reachable: an unrequested hello arrived from ...` 또는 `... probe ...`: 전체 피어. `Not reachable: the probe of a dial-back to ... did not arrive.`: 포워딩, 방화벽, 바인딩 주소를 확인합니다.
- 한 시간마다 `Peer counts: handled ..., dropped ...`, 멈출 때 `Peer <key> stops: ...`([카운터](#카운터)).
- `Graylisted the peer <key> at <address> for <time> ms.`([한계](#한계)).

## Linux 서비스로 실행

예: Rocky Linux 9, vCPU 1개, 1 GB, 인터페이스에 공인 IP.

```sh
dnf install java-25-openjdk-headless tar
useradd --system --no-create-home --shell /sbin/nologin sc4j
tar -xf sc4j-beaconlink-node.tar -C /opt           # the installDist folder, as /opt/sc4j-beaconlink-node
```

`/etc/systemd/system/sc4j-node.service`:

```ini
[Unit]
Description=SC4J Beacon.Link node
After=network-online.target
Wants=network-online.target

[Service]
User=sc4j
Group=sc4j
Environment=JAVA_OPTS=-Xmx256m
Environment=SC4J_BEACONLINK_NODE_CACHE=/var/cache/sc4j-node
ExecStart=/opt/sc4j-beaconlink-node/bin/sc4j-beaconlink-node --state /var/lib/sc4j-node --bind 203.0.113.7:7455
StateDirectory=sc4j-node
CacheDirectory=sc4j-node
Restart=always
RestartSec=10
MemoryMax=512M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```sh
systemctl daemon-reload
systemctl enable --now sc4j-node
journalctl -u sc4j-node -f                         # or /var/lib/sc4j-node/logs/
```

- `StateDirectory=`와 `CacheDirectory=`가 쓰기 가능한 `/var/lib/sc4j-node`와 `/var/cache/sc4j-node`를 만듭니다. `ProtectSystem=strict`에서 나머지는 읽기 전용이므로 `SC4J_BEACONLINK_NODE_CACHE`는 `/opt` 밖을 가리켜야 합니다.
- `MemoryMax=`는 프로세스 전체의 상한이므로 힙보다 넉넉히 크게 둡니다.
- 공인 IP가 있는 인터페이스의 주소에 바인딩합니다(`ip -4 addr`).
- 방화벽: 들어오는 UDP만. 예: `firewall-cmd --permanent --add-port=7455/udp && firewall-cmd --reload` 또는 `ufw allow 7455/udp`.
- 다른 컴퓨터에서 런처의 피어 확인(설정, Beacon.Link, 피어, Ping)으로 확인합니다: 왕복 시간, 버전(`sc4j-beaconlink-peer 0.11`), 관찰된 외부 IP, 또는 `No answer`.

**업데이트**:

1. `gradlew :sc4j-beaconlink-node:installDist` 다음 `tar -cf sc4j-beaconlink-node.tar -C sc4j-beaconlink-node/build/install sc4j-beaconlink-node`. 서버(`/tmp`)로 복사합니다.
2. `systemctl stop sc4j-node`.
3. `rm -rf /opt/sc4j-beaconlink-node && tar -xf /tmp/sc4j-beaconlink-node.tar -C /opt`(상태 폴더는 그대로).
4. 유닛이 바뀌었으면 `systemctl daemon-reload`, 그다음 `systemctl start sc4j-node`.
5. 로그에 같은 `Peer key`, `listens at`, 피어가 답하면 `Network state: CONNECTED`가 나옵니다.

**다른 컴퓨터로 옮기기**: 상태 폴더를 복사합니다(키가 정체성을 유지). 피어들은 답 없는 시작 세 번 뒤 옛 주소를 버립니다.

**키 교체**: 멈추고, `identity.key`를 치우고, 시작합니다. 다른 피어에게는 새 피어이며, 옛 키를 고정한 시드는 새 키를 고정할 때까지 잘못된 키로 보고합니다.

## 한계

모든 테이블에 한계가 있으며, 명령줄로 바꿀 수 없습니다.

| 한계 | 기본값 |
|---|---|
| 데이터그램 길이 | 1200바이트 |
| 출발지 IP 주소당 속도(서명 처리 전) | 순간 256, 그 뒤 초당 128 |
| 보낸 쪽 키당 속도(서명과 nonce 확인 뒤) | 순간 32, 그 뒤 초당 16 |
| 보관하는 속도 버킷 | 주소 8192개, 키 8192개. 가득 차면 다시 가득 찬 버킷만 내보냄 |
| 재전송 창 | 노드 시계 기준 30초 |
| 기억하는 nonce | 65536개, 출발지 IP 주소당 최대 2048개. 가득 차면 새 메시지를 버림 |
| 라우팅 테이블 | k-버킷당 8개, 캐시 4개. 버킷당 같은 /24 2개, 테이블당 같은 /24 8개, 같은 /16 16개. 테이블 밖 리프 256개 |
| 등록 | 4096개, 최대 300초 |
| 토픽 | 256개. 토픽당 방 512개(철회 포함), 호스트 키당 8개, 호스트 연결당 4개, 최대 600초 뒤. 토픽당 접속 상태 256개, 플레이어당 연결 8개. 플레이어당 로그아웃 하나. 채팅 기록 32줄 |
| 작성자 하나의 레코드 | 방 순간 8, 그 뒤 초당 1. 채팅 줄 순간 5, 그 뒤 초당 1(5초보다 오래된 기록 줄: 32, 그 뒤 초당 1). 접속 상태와 로그아웃 순간 16, 그 뒤 5초당 1 |
| 구독한 클라이언트 | 토픽당 256개, 각 90초 |
| 저장된 알림 | 토픽 2048개, 각 멤버 64개, 10분 |
| 토픽당 메시 | 이웃 6개(4~12), 적어도 2개는 노드가 직접 고름 |
| 자체 시험 프로브 | 동시에 4개, 그 뒤 IP 주소당 초당 1 |
| 벌점 | 40점이면 그 주소의 키를 10분 동안 그레이리스트에 올림. 주소가 증명되었으면 키 단독과 주소 단독도([점수](protocol.ko.md#수신)) |
| 증명되지 않은 주소에 대한 응답 | 요청보다 길지 않음 |

증명된 주소는 IP 주소 전체(모든 포트)로 그레이리스트에 오르므로, 통신사 NAT 하나 뒤의 플레이어들은 그 속도와 그레이리스트 항목을 함께 씁니다.

## 카운터

버린 데이터그램에는 답하지 않습니다. 노드는 한 시간마다와 멈출 때 버린 이유별 카운트를 남깁니다: `OVERSIZE`, `ADDRESS_RATE`, `MALFORMED`, `UNEXPECTED_REPLY`, `OWN_KEY`, `OUTSIDE_WINDOW`, `FORGED`, `KEY_RATE`, `REPLAYED`, `REPLAY_SOURCE_FULL`, `REPLAY_CACHE_FULL`, `UNPADDED`, `NOT_A_PEER`, `INVALID_PROOF`, `INVALID_ENTRY`, `UNREQUESTED_PROBE`, `NOT_IN_TOPIC`, `INVALID_RECORD`, `COORDINATE_OUTLIER`, `GRAYLISTED`, `DEFECT`.

| 카운터 | 의미 |
|---|---|
| `DEFECT` | 노드의 버그(데이터그램은 잃고 노드는 계속 실행). `Failed to handle a datagram` 주변 로그와 함께 보고합니다 |
| 많은 `OUTSIDE_WINDOW` | 서버 시계(NTP)를 확인합니다 |
| `COORDINATE_OUTLIER` | 주장한 좌표와 맞지 않는 왕복 시간. 몇 개는 정상 |
| `ADDRESS_RATE`, `KEY_RATE`, `GRAYLISTED` | 한계를 넘은 보낸 쪽. 많으면 폭주이며 한계가 막습니다 |
| `UNREQUESTED_PROBE` | 기다리는 시험이 없는 프로브. 무시합니다 |

다이얼백 프로브는 정상입니다. 노드는 다른 피어의 자체 시험을 위해 작은 `PROBE`를 보내고(위의 속도 제한), 자기 시험을 위한 프로브를 받습니다. 위조한 요청으로 노드와 제3 피어가 요청하지 않은 주소에 각각 작은 데이터그램 하나를 보내게 할 수 있습니다.

## 개인 정보

전체 피어의 IP 주소는 공개됩니다(라우팅 테이블, 토픽 멤버 목록). 노드는 등록한 플레이어의 주소, 표시 이름, 키, 모드 지문, 자기 토픽의 서명된 공개 레코드, 중계하는 귓속말의 보낸 사람, 대상, 크기를 보며 내용은 보지 못합니다. 플레이어 사이의 신호는 서명되지만 암호화되지 않으며 채널 제안(임시 키, 주소 후보)을 담습니다. 게임 트래픽은 노드를 거치지 않으며, 함께 플레이하는 플레이어는 서로의 주소를 봅니다. 런처는 이를 "다른 플레이어 연결 돕기"의 도움말과 정보 상자의 Beacon.Link 개인정보 안내로 플레이어에게 알립니다.
