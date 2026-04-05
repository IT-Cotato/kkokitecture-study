# LINE 서비스의 Rate Limiter 구현 사례

> 원문 : [LINE Engineering Blog - 고 처리량 분산 비율 제한기](https://engineering.linecorp.com/ko/blog/high-throughput-distributed-rate-limiter/) / [Gmarket Tech Blog - Redis Lua Script를 이용한 API Rate Limiter 개발](https://dev.gmarket.com/69) / [Gmarket Tech Blog - 지마켓 대기열 시스템 Redcarpet](https://dev.gmarket.com/46)

---

## 1. 배경

### 왜 Rate Limiter인가

국내 대형 서비스들은 수천만 명의 사용자와 수억 건의 API 요청을 처리한다.
이론에서 배운 Rate Limiter가 실제 프로덕션 환경에서 어떻게 구현되는지, LINE·지마켓의 기술 블로그를 통해 확인해본다.

### 단순 구현의 한계

| 문제점 | 단순 구현의 한계 | 실제 해결책 |
| --- | --- | --- |
| **분산 환경 동기화** | 여러 서버가 카운터를 따로 관리 → 한도 초과 | Redis 중앙 집중 카운터 |
| **Race Condition** | 동시 요청 시 카운터가 atomic하지 않음 | Redis Lua Script로 원자 실행 |
| **순간 트래픽 폭증** | 임계치 초과 요청 전부 즉시 거절 | 대기열 시스템으로 순서 보장 |
| **클라이언트 과부하** | 서버 측만 제한 → 내부 서비스 연쇄 장애 | 클라이언트 측 Rate Limiter 도입 |

---

## 2. 사례 ① LINE — 클라이언트 측 분산 Rate Limiter

> 이미 많은 글과 튜토리얼에서 단일 호스트 서버 측 비율 제한기를 다루고 있기 때문에, 이번 글에선 고 처리량(high-throughput) 분산 시스템을 위한 **클라이언트 측 비율 제한기**에 대해 이야기하려고 합니다.
> — LINE Engineering Blog

### 도입 배경

LINE은 마이크로서비스 아키텍처로 운영되며, 컴포넌트 수가 증가할수록 서비스 간 연결도 폭발적으로 늘어났다.
특히 2020 LINE New Year 캠페인처럼 연중 가장 많은 트래픽이 몰리는 **1월 1일 자정**에 내부 서비스가 과부하로 연쇄 장애를 일으키는 문제가 발생했다.

### 핵심 차이: 서버 측 vs 클라이언트 측

| 구분 | 서버 측 Rate Limiter | LINE의 클라이언트 측 Rate Limiter |
| --- | --- | --- |
| **위치** | 요청을 받는 서비스 | 요청을 보내는 서비스 |
| **한도 초과 시** | 요청 즉시 거절 (429) | 대기 후 재시도 / 타임아웃 |
| **적합한 상황** | 외부 API 보호 | 내부 서비스 간 과부하 방지 |
| **장점** | 구현 단순 | 사용자 경험 저하 없이 내부 보호 가능 |

### 구현 구조

```kotlin
interface ApiRateLimiter {
    // 비율 제한기가 허락할 때까지 대기 후 실행
    fun acquire(maximumWaitForMillis: Long): Completable
}

// 한도 초과 시: 다음 초 시작 시점에 재귀적으로 재시도 (RxJava2)
// 최대 대기 시간 초과 시: RateLimiterTimeoutException 반환
```

- `acquire()` 메서드가 허용될 때까지 **비동기로 대기** → 요청 유실 없이 내부 서비스 보호
- 최대 대기 시간(`maximumWaitForMillis`)을 초과하면 타임아웃 처리로 무한 대기 방지
- 한도는 실행 중에도 **동적으로 변경 가능** → 재귀 호출마다 최신 한도 재조회

---

## 3. 사례 ② 지마켓 — Redis Lua Script로 Race Condition 해결

### 도입 배경

지마켓은 외부 API를 호출하는 셀러 서비스에서 API 호출 수를 **분당 100건**으로 제한해야 했다.
처음에는 Redis에 카운터를 저장하는 단순한 방식으로 구현했지만, 16개의 컨슈머가 동시에 실행되는 환경에서 심각한 문제가 발생했다.

### 문제: Race Condition 발생

> 4개의 컨슈머가 동시에 카운터가 99인 상황을 조회하면, 모두 '아직 한도 미달'로 판단하고 API를 호출합니다. 조회 → 판단 → 증가 로직이 atomic하지 않아 한도(100건)를 초과한 호출이 그대로 통과되는 문제가 발생했습니다.
> — Gmarket Tech Blog

```python
# 문제가 있는 기존 로직 (atomic하지 않음)
count = redis.get('api:count:seller_id')   # ① 조회
if count < 100:                             # ② 판단
    redis.incr('api:count:seller_id')       # ③ 증가  ← 동시에 여러 컨슈머가 이 사이를 통과
    call_external_api()
```

### 해결: Redis Lua Script로 Atomic 실행 보장

Redis Document에 따르면, Lua 스크립트가 실행되는 동안 Redis는 blocked 상태가 된다.
즉, Lua 스크립트는 **atomic하게 실행됨이 보장**된다. 지마켓은 이를 활용해 조회·판단·증가를 하나의 원자적 연산으로 묶었다.

```lua
-- Redis Lua Script (atomic 실행 보장)
local current = redis.call('GET', KEYS[1])
if current and tonumber(current) >= tonumber(ARGV[1]) then
    return 0  -- 한도 초과, 거절
else
    redis.call('INCR', KEYS[1])
    redis.call('EXPIRE', KEYS[1], 60)  -- 1분 TTL
    return 1  -- 허용
end
```

- AOP(Aspect-Oriented Programming)를 활용해 Rate Limiter를 **부가기능으로 분리**
- `@Around PointCut`으로 대상 메서드 실행 전·후를 가로채어 한도 체크
- 인터페이스 기반 설계로 **확장성 확보** → 추후 알고리즘 교체 용이

---

## 4. 사례 ③ 지마켓 대기열 시스템 'Redcarpet' — Rate Limiter의 확장

### 도입 배경

Big Smile Day, Big Sale 등 대형 이벤트나 인기 상품에 트래픽이 순간적으로 폭증하면, 단순 Rate Limiter는 초과 요청을 전부 거절(429)한다.
지마켓은 거절 대신 **'순서를 보장하는 대기열'** 로 사용자 경험을 유지하는 방법을 선택했다.

### 핵심 구조: Redis Sorted Set 기반 대기열

```
개별 사용자 요청
    ↓
Redis Sorted Set
├── Score: 요청 Timestamp (FIFO 순서 보장)
└── Member: 사용자 식별 키 (중복 방지)
    ↓
설정된 유입량에 따라 낮은 Score(오래된 요청)부터 순서대로 처리
```

| Redis Sorted Set 필드 | 저장 값 | 역할 |
| --- | --- | --- |
| **Score** | 요청 Timestamp | FIFO 순서 보장 |
| **Member** | 사용자 식별 키 | 중복 방지 및 사용자 추적 |

### 기술 스택 선택 이유

| 기술 | 선택 이유 |
| --- | --- |
| **Redis** | 고성능 인메모리 저장소 + Sorted Set으로 대기열 구현에 최적 |
| **Node.js** | Non-Blocking I/O로 Redis를 충분히 활용, 높은 동시성 처리 |
| **Kubernetes** | 이벤트 규모에 따라 컨테이너 수를 유동적으로 Scale-out |

---

## 5. 이론 vs 실제 — 책과 현실의 차이

| 구분 | 책 (이론) | 실제 기업 구현 |
| --- | --- | --- |
| **LINE** | 서버 측 Rate Limiter 중심 설명 | 클라이언트 측으로 구현해 내부 서비스 보호 |
| **지마켓 ①** | Redis 카운터 단순 소개 | Lua Script로 Race Condition 해결 |
| **지마켓 ②** | 큐 보관으로 나중에 처리 언급 | Sorted Set 대기열로 사용자 경험 유지 |
| **공통** | 단일 알고리즘 선택 설명 | 서비스 특성에 따라 복합 전략 사용 |

---

## 6. 핵심 교훈

1. **알고리즘보다 환경이 먼저다**: 분산 환경에서는 단순 카운터로는 한계가 있다. Race Condition을 반드시 고려해야 한다.
2. **서버 측과 클라이언트 측을 구분하라**: 외부 보호는 서버 측, 내부 서비스 간 과부하 방지는 클라이언트 측이 적합하다.
3. **거절보다 대기가 나을 때도 있다**: 사용자 경험이 중요한 서비스에서는 429 거절 대신 대기열로 순서를 보장하는 방법을 고려한다.
4. **Redis는 Rate Limiter의 표준 도구다**: 인메모리 속도 + Atomic 연산(Lua Script) + TTL 지원으로 Rate Limiter 구현에 최적화되어 있다.
5. **Atomic 연산을 항상 확인하라**: 조회 → 판단 → 갱신 사이에 다른 요청이 끼어들 수 있다. 반드시 원자적 실행을 보장해야 한다.