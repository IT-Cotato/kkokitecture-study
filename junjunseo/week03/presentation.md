추가 학습 내용

# 처리율 제한(Rate Limiting) 알고리즘 — 실제 사례 정리

> 실제 서비스에서는 어떤 처리율 제한 알고리즘을 쓸까?
>

---

## 알고리즘 요약 복습

| 알고리즘 | 핵심 원리 | 버스트 허용 | 트래픽 평탄화 |
| --- | --- | --- | --- |
| **Fixed Window** | 고정된 시간 창마다 카운터 초기화 | △ (창 경계 취약) | ✗ |
| **Sliding Window** | 현재 시점 기준 이전 N초 내 요청 수 추적 | ○ | ○ |
| **Token Bucket** | 버킷에 토큰을 일정 속도로 채움, 요청 시 소비 | ✓ (저장된 토큰) | ✗ |
| **Leaky Bucket** | 요청을 큐에 쌓고 일정 속도로 처리 | ✗ | ✓ (일정 출력) |

---

## 1. 수강신청 · 티켓팅 — 예측 가능한 순간 폭증

### 핵심 문제

- 오픈 시각이 정해져 있어 수만~수십만 명이 **동시에 몰림**
- 공정성(누가 먼저 들어왔는가)이 중요
- 오토스케일링만으로는 트래픽 급증 속도를 따라가기 어려움
- 봇이 진짜 사용자보다 훨씬 빠르게 접근

### 사용 알고리즘: **Leaky Bucket + Virtual Waiting Room (가상 대기실)**

티켓팅 플랫폼 SeatGeek은 이 문제를 내부적으로 "Room"이라 부르는 가상 대기실 시스템으로 해결한다.

- 트래픽이 몰리면 사용자를 바로 목적 페이지로 보내지 않고 **대기실로 redirect**한다.
- 대기실은 Fastly(CDN), AWS Lambda + API Gateway, DynamoDB로 구성된다.
- 대기 순서는 **Redis Sorted Set**에 타임스탬프 기준으로 저장해 FIFO를 구현한다.
- 실제 페이지로 들어오는 사용자 수는 **Leaky Bucket**으로 제어한다.

> *"The leaky bucket stores access tokens of users that enter the protected zone. The leaky bucket size equals the protected zone capacity."*
— [Virtual Waiting Room Architecture at SeatGeek](https://newsletter.systemdesign.one/p/virtual-waiting-room)
>

### 전체 흐름

```
[사용자 대거 접속]
       ↓
[CDN / 엣지에서 가상 대기실로 redirect]
       ↓
[Redis Sorted Set에 타임스탬프 기록 → FIFO 순서 보장]
       ↓
[Leaky Bucket: 초당 N명씩 실제 페이지로 release]
       ↓
[WebSocket으로 현재 순서 실시간 알림]
```

### Leaky Bucket을 선택한 이유

**백엔드가 처리할 수 있는 TPS가 명확히 정해진 경우** Leaky Bucket이 이상적이다. 유입량이 아무리 많아도 처리율이 일정하게 유지되므로 서버가 버티는 한 계속 동작한다. 운영자가 대시보드에서 실시간으로 release 속도를 조절할 수도 있다.

BookMyShow·DISTRICT·Ticketmaster의 가상 대기열이 내부적으로 사용하는 스택:

| 컴포넌트 | 기술 |
| --- | --- |
| 큐 저장소 | Redis Sorted Set (O(log N) 삽입/삭제) |
| 실시간 알림 | WebSocket |
| 백엔드 | Go, Node.js |
| 인프라 | Kubernetes (auto-scaling) |

Ticketmaster는 Queue-it와 협력해 17,000개 이벤트에서 **130억 개 이상의 봇 요청을 차단**했으며, 일부 공연 presale에서 전체 요청 3,300만 건 중 진짜 팬은 겨우 4%에 불과했다.
([Queue-it 공식 블로그](https://queue-it.com/blog/online-ticket-queue/))

---

## 2. Netflix — 대규모 스트리밍 트래픽 제어 (유튜브·넷플릭스류)

Netflix는 단일 알고리즘이 아니라 **레이어별로 다른 방식**을 조합한다.

### 2-1. 클라이언트 ↔ CDN: Adaptive Bitrate Streaming (ABR)

처리율 제한의 목적이 다르다. 유저를 차단하는 게 아니라 **네트워크 상황에 맞게 화질을 자동 조절**해 버퍼링 없이 시청 유지.

| ABR 알고리즘 유형 | 설명 |
| --- | --- |
| **Throughput-based** | 최근 다운로드 속도 기반으로 다음 세그먼트 화질 결정 |
| **Buffer-based (BOLA)** | 클라이언트 버퍼 잔량만 보고 결정 |
| **Hybrid (DYNAMIC)** | 두 가지 정보를 결합 |

Netflix는 머신러닝을 추가로 활용해 네트워크 throughput을 예측하고 화질을 선제적으로 조정한다.
([Netflix TechBlog: Using ML to Improve Streaming Quality](https://netflixtechblog.com/using-machine-learning-to-improve-streaming-quality-at-netflix-9651263ef09f))

### 2-2. 서비스 간: Adaptive Concurrency Limiting

Netflix는 마이크로서비스끼리의 트래픽을 제어하기 위해 **TCP 혼잡 제어 알고리즘에서 영감을 받은** Adaptive Concurrency Limit을 개발해 오픈소스로 공개했다.

> *"We turned to tried and true TCP congestion control algorithms that seek to determine how many packets may be transmitted concurrently without incurring timeouts or increased latency."*
— [Netflix TechBlog: Performance Under Load](https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581)
>

작동 방식:

1. 요청 수를 낮게 시작하면서 동시 처리 가능 수 탐색
2. 레이턴시가 올라가면 혼잡으로 판단 → 동시 처리 수 감소
3. 수렴 후 안정적인 처리량 유지 (saw-tooth 패턴)
4. 기본 허용 큐 크기 = `sqrt(현재 한계)` (성장 속도와 안정성 균형)

오픈소스: [github.com/Netflix/concurrency-limits](https://github.com/Netflix/concurrency-limits)

### 2-3. API 게이트웨이(Zuul): 우선순위 기반 Load Shedding

CPU 사용률, 동시 요청 수, 커넥션 수가 임계치를 넘으면 **낮은 우선순위 트래픽부터 순차적으로 차단**한다. 단순 on/off 차단기 대신 점진적 제어를 사용한다.
([Netflix TechBlog: Keeping Netflix Reliable Using Prioritized Load Shedding](https://netflixtechblog.com/keeping-netflix-reliable-using-prioritized-load-shedding-6cc827b02f94))

---

## 3. GitHub API — 개발자 API

### 사용 알고리즘: **Fixed Window**

[GitHub REST API 공식 문서](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)에서 명시:

| 접근 방식 | 제한 |
| --- | --- |
| 미인증 (IP 기준) | 60 req/hour |
| Personal Access Token | 5,000 req/hour |
| GitHub Apps (Enterprise Cloud) | 15,000 req/hour |

응답 헤더에 잔여 요청 수와 리셋 시각이 포함된다:

```
X-RateLimit-Limit: 5000
X-RateLimit-Remaining: 4999
X-RateLimit-Reset: 1372700873
```

Fixed Window를 선택한 이유: 구현이 단순하고 개발자가 예측하기 쉽다. 경계 버스트 문제가 있지만 API 특성상 허용 범위 내로 판단.

---

## 정리: 상황별 알고리즘 선택 가이드

| 상황 | 추천 알고리즘 | 이유 |
| --- | --- | --- |
| 수강신청 / 티켓팅 (순간 폭증) | **Leaky Bucket + 가상 대기실** | 백엔드 처리율 보호, FIFO 공정성 |
| 일반 API (개발자 대상) | **Fixed Window** | 단순, 예측 가능 |
| 대규모 스트리밍 (Netflix 등) | **Adaptive Concurrency Limit + ABR** | 동적 부하 대응, 레이턴시 기반 제어 |
| 범용 API (단기 스파이크 허용) | **Token Bucket** | 저장된 토큰으로 버스트 처리, 유연성 |

---

## 참고 출처

- [Netflix TechBlog: Performance Under Load (Adaptive Concurrency)](https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581)
- [Netflix TechBlog: Prioritized Load Shedding](https://netflixtechblog.com/keeping-netflix-reliable-using-prioritized-load-shedding-6cc827b02f94)
- [Netflix TechBlog: Using ML to Improve Streaming Quality](https://netflixtechblog.com/using-machine-learning-to-improve-streaming-quality-at-netflix-9651263ef09f)
- [SeatGeek Virtual Waiting Room Architecture (InfoQ)](https://www.infoq.com/presentations/ticketing-system-virtual-waiting-room/)
- [Queue-it: Online Ticket Queue](https://queue-it.com/blog/online-ticket-queue/)
- [systemdesign.one: Virtual Waiting Room Architecture](https://newsletter.systemdesign.one/p/virtual-waiting-room)
- [smudge.ai: Visualizing Rate Limiting Algorithms](https://smudge.ai/blog/ratelimit-algorithms)
- [Rate Limit Algorithm Comparison (codenote.net)](https://codenote.net/en/posts/rate-limit-algorithm-comparison/)
