# RabbitMQ 모니터링과 핵심 지표

책의 알림 시스템 설계에서는 알림 서버와 작업 서버 사이에 메시지 큐를 두어 시스템을 비동기적으로 처리하도록 설계한다.

RabbitMQ는 이러한 메시지 큐를 구현하는 대표적인 메시지 브로커이다.

책에 나온 알림 구조
```text
서비스
   ↓
알림 서버
   ↓
종류별 메시지 큐
   ↓
작업 서버
   ↓
제3자 서비스
   ↓
사용자
```

RabbitMQ 관점 시스템 구조는 다음과 같이 구성할 수 있다.

```text
서비스
   ↓
Producer (알림 서버)
   ↓
Exchange
   ↓
 Queue
   ↓
Consumer (작업 서버)
   ↓
제3자 서비스
   ↓
사용자
```

알림 서버는 메시지를 직접 전송하지 않고 RabbitMQ에 적재한다.

작업 서버는 큐에 저장된 메시지를 읽어 외부 알림 서비스에 전달한다.

이 구조는 다음과 같은 장점을 가진다.

- 비동기 처리
- 트래픽 버퍼링
- 서비스 간 결합도 감소
- 장애 격리
- 수평 확장 지원

---

# RabbitMQ와 Datadog의 역할

RabbitMQ와 Datadog은 서로 다른 역할을 담당한다.

### RabbitMQ

메시지를 저장하고 전달하는 메시지 브로커이다.

- 메시지 저장
- 메시지 전달
- 재시도 처리
- 큐 관리

### Datadog

RabbitMQ를 포함한 시스템 상태를 관찰하는 모니터링 플랫폼이다.

- 메트릭 수집
- 대시보드 제공
- 장애 탐지
- 알림 전송

즉 RabbitMQ가 실제 작업을 수행한다면 Datadog은 RabbitMQ가 정상적으로 동작하는지 감시하는 역할을 수행한다.

---

# RabbitMQ 메시지 흐름

RabbitMQ 내부에서 메시지는 다음 경로를 통해 이동한다.

```text
Producer
   ↓
Exchange
   ↓
Queue
   ↓
Consumer
```


### Producer

메시지를 생성하는 주체

예)

- 알림 서버
- 주문 서버
- 결제 서버

---

### Exchange

메시지를 어떤 Queue로 보낼지 결정하는 라우터 역할

예)

```text
Notification Exchange
      │
 ┌────┼────┐
 ↓    ↓    ↓
Push SMS Email
```

---

### Queue

메시지를 임시 저장하는 공간

예)

- push_queue
- sms_queue
- email_queue

---

### Consumer

메시지를 읽어 실제 작업을 수행하는 주체

예)

- Push Worker
- SMS Worker
- Email Worker

---

# Queue Depth

Queue Depth는 현재 Queue에 저장된 전체 메시지 수를 의미한다.

이 지표는 시스템 적체 여부를 판단하는 가장 기본적인 지표이다.

### 의미

```text
Queue Depth = 생산 속도 - 처리 속도
```

### 정상 사례

```text
push_queue = 10
```

작업 서버가 충분히 처리하고 있음

### 비정상 사례

```text
push_queue = 500000
```

메시지가 지속적으로 누적되고 있음

### 가능한 원인

- Worker 부족
- Consumer 장애
- 외부 Provider 지연
- 트래픽 급증

---

# Messages Ready

Messages Ready는 아직 Consumer가 가져가지 않은 메시지 수이다.

즉 현재 대기 중인 작업의 수를 의미한다.

### Messages Ready 증가

다음 상황을 의심할 수 있다.

- Worker 수 부족
- Worker 처리 속도 저하
- 트래픽 급증

### 해석

```text
Messages Ready ↑
=
대기 작업 증가
=
처리 속도보다 생성 속도가 빠름
```

---

# Messages Unacknowledged

RabbitMQ는 Consumer가 메시지를 가져갔다고 즉시 삭제하지 않는다.

Consumer는 작업 완료 후 ACK(Acknowledgement)를 보내야 한다.

Messages Unacknowledged는 전달은 되었지만 아직 ACK를 받지 못한 메시지 수를 의미한다.

### 증가 시 의미

```text
Worker
 ↓
메시지 수신
 ↓
FCM 호출
 ↓
장애 발생
 ↓
ACK 미전송
```

결과적으로 Messages Unacknowledged가 증가한다.

### 가능한 원인

- Worker 장애
- 처리 지연
- 외부 API 장애
- 네트워크 오류

실무에서 가장 중요한 장애 탐지 지표 중 하나이다.

---

# Consumer Utilization

Consumer Utilization은 Consumer가 실제로 메시지를 받을 수 있었던 시간 비율이다.

### 1.0

Consumer가 항상 처리 가능한 상태

### 0.5

절반의 시간 동안만 처리 가능

### 0

Consumer 장애 또는 중단

### 활용

Consumer Utilization이 낮다면 다음을 확인해야 한다.

- Worker 개수 부족
- Prefetch 설정 문제
- Consumer 장애
- 처리 성능 저하

---

# Node 모니터링

RabbitMQ 자체가 사용하는 시스템 자원도 함께 모니터링해야 한다.

## Memory Used

RabbitMQ가 사용하는 메모리

임계치를 초과하면 메시지 발행이 제한될 수 있다.

---

## Disk Space Used

RabbitMQ가 사용하는 디스크

디스크 부족 시 메시지 저장이 불가능해진다.

클러스터 환경에서는 하나의 노드만 임계치를 초과해도 전체 시스템에 영향을 줄 수 있다.

---

## File Descriptors

RabbitMQ가 사용하는 TCP 연결 수

제한에 도달하면 새로운 연결을 수락할 수 없다.

---

# Data Rate

Connection을 통해 송수신되는 데이터 양이다.

메시지 수는 동일하지만 Data Rate가 증가했다면 메시지 크기(Payload)가 커졌을 가능성이 있다.

### 예시

```text
메시지 수 = 동일
Data Rate ↑
```

가능한 원인

- 이미지 포함
- 긴 텍스트 포함
- 대용량 Payload 사용

---

# 정리

RabbitMQ는 알림 시스템에서 메시지 큐 역할을 수행하며, 알림 서버와 작업 서버 사이의 비동기 처리를 담당한다.

RabbitMQ 운영 시 가장 중요하게 관찰해야 하는 지표는 다음과 같다.

| 영역 | 핵심 지표 |
|--------|--------|
| Queue 상태 | Queue Depth, Messages Ready, Messages Unacknowledged |
| Consumer 상태 | Consumer Utilization |
| 시스템 자원 | Memory, Disk, File Descriptors |
| 네트워크 | Data Rate |

특히 Queue Depth, Messages Ready, Messages Unacknowledged는 알림 시스템의 적체와 장애를 가장 빠르게 탐지할 수 있는 핵심 지표이며, Datadog과 같은 모니터링 도구를 통해 지속적으로 관찰해야 한다.
