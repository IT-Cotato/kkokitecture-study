# 정확히 한 번 전송(Exactly-Once Delivery)은 왜 불가능한가?

> 알림 시스템 설계 심화 발표 | 시스템 디자인 스터디

---

## 목차

1. 배경 — 알림 시스템에서 왜 이 문제가 생기나
2. 전송 보장 모델 3가지
3. Exactly-Once가 불가능한 이유
4. 실제로는 어떻게 해결하나 — 멱등성(Idempotency)
5. 실무 패턴 정리
6. 마무리 요약

---

## 1. 배경

### 알림 시스템의 기본 흐름

```
서비스 N  →  알림 서버  →  메시지 큐  →  작업 서버  →  APNS / FCM / SMS
```

- 작업 서버가 큐에서 이벤트를 꺼내 제3자 서비스로 전송
- 전송 실패 시 **재시도(retry)** 발생
- 재시도는 곧 **중복 전송 가능성**을 의미

### 책에서 언급한 수준

> "같은 알림이 여러 번 반복되는 것을 완전히 막는 것은 가능하지 않다."
> → 참고문헌 [5]로 넘김

**왜** 불가능한지, **그럼 어떻게** 하는지에 대해 조사해봤습니다.

---

## 2. 전송 보장 모델 3가지

| 모델 | 설명 | 누락 | 중복 |
|------|------|----|----|
| **At-Most-Once** | 최대 한 번. 실패해도 재시도 없음 | 발생 가능 | 없음 |
| **At-Least-Once** | 최소 한 번. 성공 확인될 때까지 재시도 | 없음 | 발생 가능 |
| **Exactly-Once** | 정확히 한 번. 누락도 중복도 없음 | 없음 | 없음 |

### 알림 시스템에서 선택은?

- **At-Most-Once** → 알림 유실 = 사용자 경험 최악. 탈락.
- **At-Least-Once** → 중복은 있지만 유실은 없음. **현실적 선택.**
- **Exactly-Once** → 이상적이지만… 분산 시스템에서 구현 불가능에 가깝다.

---

## 3. Exactly-Once가 불가능한 이유

### 핵심 문제: 전송 성공 여부를 알 수 없는 구간이 존재한다

```
작업 서버                       APNS
    │──────── 알림 전송 ────────▶│
    │                            │ (처리 완료)
    │◀──── 응답이 와야 하는데 ───│
    │                            │
    │     네트워크 끊김           │
    │  응답을 못 받음             │
    │                            │
    [성공한 건지 실패한 건지 모름]
```

**서버 입장에서 선택지:**
- 재시도 → 중복 전송 가능 (At-Least-Once)
- 재시도 안 함 → 유실 가능 (At-Most-Once)

**어느 쪽을 선택해도 Exactly-Once는 보장 불가.**

---

### 이론적 근거: Two Generals Problem

```
장군 A  ──── 전령 ────▶  장군 B
       ◀── 확인 전령 ───
       ──── 재확인 ────▶
       ...
```

- 두 장군이 동시에 공격하려면 서로의 합의가 필요
- 하지만 전령이 중간에 잡힐 수 있음 (메시지 유실)
- **유한한 횟수의 통신으로는 100% 합의 보장 불가**
- 분산 시스템의 네트워크 통신도 동일한 문제

> 📌 이것이 분산 환경에서 Exactly-Once가 이론적으로 불가능한 이유다.

---

### 실제 장애 시나리오

**시나리오 A: 작업 서버가 전송 직후 죽는 경우**

```
작업 서버: 큐에서 이벤트 꺼냄
작업 서버: APNS로 전송 성공
작업 서버: 💀 (크래시 — ACK를 큐에 못 보냄)
큐: 타임아웃 → 다른 작업 서버에 같은 이벤트 재전달
결과: 사용자에게 알림 2번 도착
```

**시나리오 B: 네트워크 지연**

```
작업 서버: APNS로 전송
APNS: 처리 완료했지만 응답이 지연됨
작업 서버: 타임아웃으로 판단 → 재시도
APNS: 두 번째 요청도 처리
결과: 중복 전송
```

---

## 4. 실제로는 어떻게 해결하나 — 멱등성(Idempotency)

### 멱등성이란?

> **같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 동일한 성질**

```
f(f(x)) = f(x)
```

예시:
- `DELETE /users/123` → 두 번 호출해도 결과는 "삭제됨"으로 동일 
- `POST /notifications` → 두 번 호출하면 알림 2개 생성 (멱등하지 않음)

---

### 알림 시스템에서 멱등성 구현

**핵심 아이디어: 이벤트 ID로 중복 판별**

```
작업 서버가 이벤트를 처리하기 전:
  1. 이벤트 ID를 Redis에 조회
  2. 이미 존재하면 → 중복 → 버림
  3. 없으면 → Redis에 저장 → 전송 진행
```

**Redis 기반 구현 예시 (pseudo code)**

```python
def send_notification(event):
    event_id = event["id"]

    # 중복 체크 (SET NX = Not eXists일 때만 저장)
    is_new = redis.set(f"notif:{event_id}", "1", nx=True, ex=86400)

    if not is_new:
        logger.info(f"중복 이벤트 무시: {event_id}")
        return

    # 실제 전송
    result = apns.send(event["payload"])

    if result.success:
        ack_to_queue(event_id)
    else:
        redis.delete(f"notif:{event_id}")  # 실패 시 키 제거하여 재시도 허용
        raise RetryException()
```

---

### 멱등성 키 설계 전략

| 전략 | 예시 | 주의사항 |
|------|------|----------|
| UUID v4 | `550e8400-e29b-41d4-a716` | 충돌 가능성 극히 낮음 |
| 이벤트 내용 해시 | `SHA256(user_id + content + timestamp)` | timestamp 정밀도 주의 |
| 복합 키 | `{user_id}:{notification_type}:{date}` | 동일 사용자 일일 1회 제한 등에 활용 |

---

## 5. 실무 패턴 정리

### 패턴 1: Outbox Pattern

**문제:** DB 저장 + 큐 전송을 원자적으로 처리하고 싶다

```
[알림 서버]
  ① DB에 알림 저장 (outbox 테이블에 함께 기록)
  ② 트랜잭션 커밋

[별도 폴러(poller)]
  ③ outbox 테이블 주기적으로 읽기
  ④ 큐에 발행
  ⑤ outbox 레코드 "전송 완료" 처리
```

- DB 트랜잭션을 통해 저장과 발행의 원자성 확보
- 폴러가 실패해도 outbox에 남아있으므로 재시도 가능

---

### 패턴 2: Consumer-Side Deduplication

수신 측(작업 서버)에서 중복을 걸러내는 방식

```
큐 → 작업 서버 → [중복 필터] → APNS
                      ↕
                   Redis SET
                 (event_id, TTL 24h)
```

- 생산자는 그냥 보내고, 소비자가 알아서 중복 제거
- 가장 일반적인 실무 패턴

---

### 패턴 3: 제3자 서비스의 멱등성 키 활용

APNS, FCM 등 제3자 서비스도 자체 중복 방지 메커니즘을 제공한다.

**FCM의 경우:**
```json
{
  "message": {
    "token": "device_token_here",
    "notification": { "title": "재고 알림" },
    "fcm_options": {
      "analytics_label": "restock_event_20240620_user123"
    }
  }
}
```

**APNS의 경우:**
- `apns-collapse-id` 헤더: 동일 ID의 이전 알림을 새 알림으로 교체
- 중복 전송 대신 덮어쓰기로 처리

---

## 6. 마무리 요약

### 핵심 3줄 요약

1. **Exactly-Once는 이론적으로 불가능하다** — 네트워크는 항상 불안정하고, 응답 유실 구간에서 성공/실패를 알 수 없다.
2. **현실적 선택은 At-Least-Once + 멱등성** — 중복 전송을 허용하되, 수신 측에서 중복을 걸러낸다.
3. **이벤트 ID 기반 Redis 체크가 가장 일반적인 구현** — TTL 설정으로 스토리지 부담도 관리 가능하다.

### 트레이드오프 정리

| | At-Most-Once | At-Least-Once + 멱등성 | Exactly-Once   |
|---|--------------|---------------------|----------------|
| 구현 난이도 | 낮음           | 중간                  | 매우 높음 (사실상 불가) |
| 알림 유실 | 있음           | 없음                  | 없음             |
| 중복 전송 | 없음           | 거의 없음               | 없음             |
| 실무 사용 | X            | O                   | X              |

---

> **결론:** "정확히 한 번"을 목표로 하되, 실제 구현은 "최소 한 번 + 멱등성"으로 근사한다.
> 분산 시스템에서 완벽한 보장보다 **올바른 트레이드오프 선택**이 더 중요하다.

---

## 참고자료

- [5] You Cannot Have Exactly-Once Delivery — https://bravenewgeek.com/you-cannot-have-exactly-once-delivery/
- Two Generals Problem — https://en.wikipedia.org/wiki/Two_generals_problem
- Idempotent Consumer Pattern — https://microservices.io/patterns/communication-style/idempotent-consumer.html
- Transactional Outbox Pattern — https://microservices.io/patterns/data/transactional-outbox.html