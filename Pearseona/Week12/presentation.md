# 메시지 큐 실전 비교: Kafka vs RabbitMQ vs AWS SQS

**참고 자료:**

> [Kafka vs. RabbitMQ vs. SQS – Key Differences & Best Use Cases - GetSdeReady, 2025](https://getsdeready.com/kafka-vs-rabbitmq-vs-sqs-which-message-queue-is-best-for-you/)

> [RabbitMQ vs Kafka vs Amazon SQS: Choosing the Right Message Broker (2026) - DanubeData](https://danubedata.ro/blog/rabbitmq-vs-kafka-vs-sqs-comparison-2026)

> [Kafka vs RabbitMQ vs NATS vs SQS: Choosing the Right Message Broker - BackendBytes, 2026](https://backendbytes.com/articles/message-queue-comparison/)

> [Kafka vs. JMS, RabbitMQ, SQS, and Modern Messaging - 2025 Edition - Cloudurable](https://cloudurable.com/blog/kafka-vs-jms-2025/)

---

## 1. 왜 메시지 큐인가: 동기 통신의 한계

현대 분산 시스템에서 서비스 간 통신을 HTTP 직접 호출(동기)로만 처리하면 세 가지 문제가 생깁니다.

```
[동기 방식 - 강결합]
Order Service → HTTP → Payment Service → HTTP → Inventory Service → HTTP → Notification Service
(결제 서비스가 1초 느려지면 전체 흐름이 1초 지연, 한 서비스 장애 = 연쇄 장애)

[비동기 방식 - 느슨한 결합]
Order Service → [Message Queue] → Payment Service
                               → Inventory Service
                               → Notification Service
(각 서비스가 독립적으로 동작, 장애가 전파되지 않음)
```

메시지 큐는 이 문제를 해결하는 핵심 컴포넌트지만, 어떤 큐를 선택하느냐에 따라 시스템이 제공할 수 있는 보장(ordering, replay, latency)이 근본적으로 달라집니다.

---

## 2. 세 가지 도구의 철학적 차이

세 도구는 태생부터 목적이 다릅니다. 벤치마크 수치보다 이 철학을 이해하는 것이 선택에 더 결정적입니다.

| | Apache Kafka | RabbitMQ | AWS SQS |
|:---|:---|:---|:---|
| **탄생 배경** | LinkedIn의 대용량 로그 수집 | 금융권 메시지 표준(AMQP) 구현 | AWS 생태계 내 단순 큐잉 |
| **핵심 추상화** | 분산 커밋 로그(Distributed Log) | 메시지 브로커(Exchange → Queue) | 완전 관리형 큐 서비스 |
| **소비 후 메시지** | 보존 (재소비 가능) | 삭제 | 삭제 |
| **한 줄 요약** | "이벤트 스트리밍 플랫폼" | "유연한 라우팅 브로커" | "운영 없는 AWS 큐" |

---

## 3. Apache Kafka: 분산 커밋 로그

### 3.1 핵심 아키텍처

Kafka의 핵심은 **"모든 메시지를 불변의 로그(append-only log)로 저장한다"** 는 것입니다.

```
[Producer] → Topic (분산 저장소)
               ├── Partition 0: [msg1] [msg4] [msg7] ...
               ├── Partition 1: [msg2] [msg5] [msg8] ...
               └── Partition 2: [msg3] [msg6] [msg9] ...
                        ↓
              [Consumer Group A] — offset 7까지 읽음
              [Consumer Group B] — offset 3까지 읽음 (독립적으로 소비)
```

- **파티션**: 토픽을 수평으로 분할한 단위. 파티션 내에서는 순서가 보장됩니다.
- **오프셋(Offset)**: 컨슈머가 "어디까지 읽었는지" 직접 추적. 메시지는 삭제되지 않습니다.
- **컨슈머 그룹**: 서로 다른 그룹이 같은 데이터를 독립적으로 소비 가능합니다.
- **KRaft**: Kafka 4.0(2025년 3월)부터 ZooKeeper 의존성이 완전히 제거되고 KRaft 모드만 지원됩니다.

### 3.2 Kafka가 유리한 상황

- **이벤트 재처리(Replay)**: 장애 복구 후 특정 시점부터 재소비하거나, 새 서비스가 과거 이벤트 전체를 읽어야 할 때
- **여러 컨슈머가 같은 데이터를 독립적으로 소비**: 결제 서비스와 통계 서비스가 같은 주문 이벤트를 각자 처리하는 패턴
- **초고처리량**: 초당 수백만 건 이상의 이벤트 스트리밍

### 3.3 Kafka의 트레이드오프

- **운영 복잡도가 높습니다**: 파티션 수, 리플리케이션 팩터, 컨슈머 그룹 리밸런싱 등 튜닝 포인트가 많습니다.
- **파티션 내에서만 순서 보장**: 전체 토픽 범위의 글로벌 순서는 보장되지 않습니다. 같은 엔티티(예: 같은 주문 ID)의 이벤트가 반드시 같은 파티션으로 가도록 파티션 키를 설계해야 합니다.
- **단순 작업 큐에는 오버스펙**: 소비 후 삭제되는 단순 태스크 큐 용도에 Kafka를 도입하면 불필요한 복잡성을 떠안게 됩니다.

---

## 4. RabbitMQ: 유연한 라우팅 브로커

### 4.1 핵심 아키텍처

RabbitMQ는 **Exchange → Binding → Queue** 의 3단계 라우팅 구조가 핵심입니다.

```
[Producer]
    ↓
[Exchange] — 라우팅 규칙 적용
    ├── Direct Exchange  : routing_key가 정확히 일치하는 큐로 전달
    ├── Topic Exchange   : 패턴(user.*, order.#) 기반 라우팅
    ├── Fanout Exchange  : 바인딩된 모든 큐에 브로드캐스트
    └── Headers Exchange : 메시지 헤더 값 기반 라우팅
         ↓
      [Queue A] → [Consumer A]
      [Queue B] → [Consumer B]
```

- **Push 방식**: 브로커가 컨슈머에게 메시지를 능동적으로 전달합니다 (Kafka의 Pull과 반대).
- **메시지 ACK**: 컨슈머가 처리 완료를 브로커에게 명시적으로 통보해야 메시지가 삭제됩니다.
- **메시지 소비 후 삭제**: Kafka와 달리 컨슈머가 가져간 메시지는 큐에서 제거됩니다.

### 4.2 RabbitMQ가 유리한 상황

- **복잡한 라우팅 로직**: "결제 실패 이벤트 중 금액이 100만 원 이상인 것만 특정 큐로"처럼 세밀한 라우팅이 필요할 때
- **Request-Reply 패턴**: RPC 스타일의 요청-응답 구현이 자연스럽습니다.
- **낮은 레이턴시**: Push 방식으로 메시지 도달 지연이 짧습니다.
- **다양한 프로토콜 지원**: AMQP, MQTT, STOMP를 모두 지원해 이기종 시스템 통합에 유리합니다.

### 4.3 RabbitMQ의 트레이드오프

- **재소비(Replay) 불가**: 메시지가 소비되면 사라집니다. 감사 로그나 이벤트 소싱에는 적합하지 않습니다.
- **대용량 스트리밍에서는 Kafka에 밀림**: 단순 처리량 경쟁에서는 Kafka가 우위입니다.
- **클러스터 운영**: 고가용성을 위한 클러스터 구성과 유지보수가 필요합니다.

---

## 5. AWS SQS: 운영 없는 완전 관리형 큐

### 5.1 핵심 아키텍처

SQS는 **인프라 관리를 AWS에 완전히 위임**하는 것이 핵심 가치입니다.

```
[Producer] → [SQS Queue] → [Consumer (Poll 방식)]
                                 ↓
                          처리 완료 시 메시지 삭제

[두 가지 큐 타입]
Standard Queue : 최대 처리량, Best-effort 순서, At-least-once 전달
FIFO Queue     : 엄격한 순서 보장, Exactly-once 처리, 초당 3,000건 한도
```

- **Pull(Polling) 방식**: 컨슈머가 주기적으로 큐에 요청을 보내 메시지를 가져옵니다.
- **Visibility Timeout**: 한 컨슈머가 메시지를 가져가면 일정 시간 다른 컨슈머에게 보이지 않게 됩니다. 처리 실패 시 타임아웃 후 재처리됩니다.
- **Dead Letter Queue(DLQ)**: 처리 실패가 반복되는 메시지를 별도 큐로 격리합니다.

### 5.2 SQS가 유리한 상황

- **AWS 네이티브 환경**: Lambda, SNS, Step Functions와 별도 설정 없이 통합됩니다.
- **운영 인력이 부족한 소규모 팀**: 인프라를 전혀 관리하지 않아도 됩니다.
- **트래픽 변동이 심한 워크로드**: 유휴 시 비용이 0에 수렴하고, 급증 시 자동으로 확장됩니다.

### 5.3 SQS의 트레이드오프

- **재소비(Replay) 불가**: 소비된 메시지는 삭제됩니다. 최대 보존 기간도 14일입니다.
- **레이턴시**: 폴링 오버헤드로 인해 10~100ms 수준으로 세 도구 중 가장 높습니다.
- **복잡한 라우팅 불가**: SQS 자체에는 Exchange 개념이 없습니다. 팬아웃이 필요하면 SNS와 조합해야 합니다.
- **벤더 종속(Vendor Lock-in)**: AWS 외 환경으로의 이전 비용이 큽니다.

---

## 6. 성능 수치 비교

| 항목 | Kafka | RabbitMQ | AWS SQS |
|:---|:---:|:---:|:---:|
| **처리량** | 10M+ msg/sec | ~1M msg/sec | ~300K msg/sec |
| **레이턴시** | 2~5ms | 1~20ms | 10~100ms |
| **메시지 보존** | 설정 기간 (기본 7일) | 소비 후 삭제 | 최대 14일 |
| **순서 보장** | 파티션 내 보장 | 큐 내 보장 | FIFO 큐만 보장 |
| **재소비(Replay)** | O | X | X |
| **운영 복잡도** | 높음 | 중간 | 없음 (완전 관리형) |
| **라우팅 유연성** | 낮음 (앱 레벨 처리) | 높음 (Exchange) | 낮음 (SNS 조합 필요) |

---

## 7. 핵심 설계 결정과 선택 기준

```
메시지를 재소비(Replay)해야 하는가?
├── YES → Kafka
└── NO
     ├── AWS 환경이고 운영 부담을 줄이고 싶은가?
     │    ├── YES → SQS
     │    └── NO
     │         └── 복잡한 라우팅/낮은 레이턴시가 필요한가?
     │              ├── YES → RabbitMQ
     │              └── NO  → SQS 또는 RabbitMQ (팀 역량에 따라)
     └── (대규모 이벤트 스트리밍, 멀티 컨슈머 그룹이 필요하다면 Kafka)
```

| 설계 결정 | 채택한 이유 |
|:---|:---|
| 이벤트 소싱 / 감사 로그 / CDC 파이프라인 → **Kafka** | 메시지 재소비와 시점 복원이 구조적으로 지원됨 |
| 복잡한 워크플로우 / RPC / 이기종 프로토콜 통합 → **RabbitMQ** | Exchange 기반 라우팅이 애플리케이션 레벨 복잡성을 브로커로 흡수함 |
| AWS 네이티브 / 소규모 팀 / 서버리스 아키텍처 → **SQS** | 운영 비용 0, Lambda 등 AWS 서비스와 즉시 통합 |
| 트래픽 급등이 예측되는 단순 큐잉 → **SQS** | 자동 스케일링으로 용량 계획 불필요 |

---

## 8. 실제 기업 채택 사례

- **Netflix**: 팬아웃 서비스의 백엔드 메시지 라우팅 계층에 Kafka 기반 처리 큐를 구성하여 대량의 메시지를 안정적으로 비동기 처리
- **LinkedIn (Kafka 창시)**: 사용자 활동 로그, 메트릭 수집 등 하루 수조 건의 이벤트를 Kafka로 처리
- **AWS 내 마이크로서비스**: Lambda 트리거, 주문 처리 비동기화 등 단순 디커플링에 SQS 폭넓게 활용
- **금융권 / 이기종 시스템 통합**: AMQP 표준 준수가 필요한 환경에서 RabbitMQ 선택

---

## 9. 요약 및 결론

**"어떤 메시지 큐가 가장 좋은가"라는 질문에는 정답이 없습니다. 중요한 것은 메시지 큐의 선택이 시스템이 제공할 수 있는 보장(Guarantee)을 결정한다는 점입니다.**

- **Kafka**: 메시지를 로그로 보존하고 재소비할 수 있어야 할 때. 높은 운영 비용을 감수할 만한 처리량과 요구사항이 있을 때
- **RabbitMQ**: 복잡한 라우팅이 필요하거나 낮은 레이턴시의 전통적인 작업 큐 패턴이 필요할 때
- **SQS**: AWS 환경에서 운영 부담 없이 빠르게 도입해야 할 때. 팀 역량이 메시지 큐 운영보다 서비스 개발에 집중되어야 할 때

대부분의 대형 시스템은 단일 솔루션이 아니라 세 가지를 **용도에 따라 조합**하여 사용합니다.