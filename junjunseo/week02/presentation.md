# 실제 백엔드에서 트래픽을 어떻게 다루는가

---

책에서는 트위터 예제를 통해 QPS를 이렇게 추정했다.

- DAU = 3억 × 50% = **1.5억**
- QPS = 1.5억 × 2트윗 / 86,400초 ≈ **3,500**
- 최대 QPS = **7,000**

**백엔드는 이 트래픽을 어떻게 제어하고 방어해야할까?**

---

## 1. Throughput vs Latency

QPS(처리량)를 높이면 무조건 좋을까?

처리량과 응답시간은 **트레이드오프** 관계다. 요청이 몰릴수록 큐 대기시간이 늘어나 응답 지연이 발생한다.

### Little's Law

```
L = λW
```

| 변수 | 의미 |
| --- | --- |
| L | 시스템 내 동시 요청 수 |
| λ (lambda) | 초당 처리 요청 수 (= QPS) |
| W | 평균 응답시간 |

**예시**

- QPS = 1,000, 응답시간 = 100ms
- 동시 요청 수 = 1,000 × 0.1 = **100개**

→ DB 커넥션 풀이 100개 미만이면? **병목 발생**

### 병목이 생기면 어떤 일이 벌어지나?

커넥션 풀이 가득 찬 상황을 예로 들면:

```
요청 폭증
  → 커넥션 풀 고갈
  → 대기 큐 증가
  → 응답시간 급증 (W ↑)
  → 동시 요청 수 폭발 (L ↑)
  → 서버 OOM 또는 타임아웃
```

결국 **처리량을 높이는 것만으로는 부족하다.** 트래픽 자체를 제어하지 않으면 시스템은 버티질 못한다. 이때 필요한 것이 **Rate Limiting**이다.

---

## 2. Rate Limiting — 트래픽을 제어하는 방법

### 왜 필요한가?

- 특정 사용자의 과도한 요청으로 서버 과부하
- DDoS 공격 방어
- 유료 API의 사용량 제한

### 알고리즘 상세 비교

**1. Token Bucket**

```
[토큰 버킷]  ← 일정 속도로 토큰 충전
    ↓
요청 1건 처리 시 토큰 1개 소비
토큰이 없으면 요청 거절
```

- 버킷 크기 = 최대 버스트 허용량
- 충전 속도 = 평균 허용 QPS
- **장점:** 일시적인 트래픽 급증(버스트)을 자연스럽게 허용
- **단점:** 버킷 크기와 충전 속도 두 파라미터를 튜닝해야 함
- **적합한 상황:** API 요청 제한, 일반적인 웹 서비스

**2. Leaky Bucket**

```
요청 → [큐] → 일정한 속도로 처리 (누수처럼)
큐가 가득 차면 요청 거절
```

- **장점:** 출력 속도가 일정해서 다운스트림 서비스 보호에 유리
- **단점:** 버스트를 전혀 허용하지 않음, 큐에 쌓인 오래된 요청이 먼저 처리될 수 있음
- **적합한 상황:** 안정적인 처리율이 중요한 결제, 정산 시스템

**3. Fixed Window Counter**

```
| 00:00 ~ 00:01 | 00:01 ~ 00:02 | ...
|   카운터: 87  |   카운터: 0   |
```

- 시간 윈도우마다 카운터를 초기화
- **장점:** 구현이 가장 단순
- **단점:** 윈도우 경계에서 취약점 존재

```
윈도우 경계: 00:00 ~ 00:01 / 00:01 ~ 00:02, 한도 = 100건

00:00:59에 100건 + 00:01:01에 100건
→ 2초 안에 200건 처리됨 (실질적으로 한도 2배 초과)
```

**4. Sliding Window Log**

```
현재 시각: 00:01:30, 윈도우 크기: 1분

[00:00:31, 00:00:45, 00:01:10, 00:01:25, ...]
 ← 1분 이전 로그 제거  |  유효한 요청 로그 →
```

- 요청마다 타임스탬프를 저장하고, 윈도우 밖의 로그를 제거한 뒤 카운트
- **장점:** Fixed Window의 경계 취약점 없음, 가장 정밀한 제어
- **단점:** 요청마다 로그를 저장하므로 메모리 사용량이 큼
- **적합한 상황:** 정밀한 제어가 필요한 경우 (단, 트래픽이 많으면 부담)

### 한눈에 비교

| 알고리즘 | 버스트 허용 | 정밀도 | 메모리 사용 | 구현 난이도 |
| --- | --- | --- | --- | --- |
| Token Bucket | O | 중간 | 낮음 | 중간 |
| Leaky Bucket | X | 중간 | 낮음 | 중간 |
| Fixed Window | O (경계 취약) | 낮음 | 낮음 | 쉬움 |
| Sliding Window Log | O | 높음 | 높음 | 어려움 |

---

## 3. 실제 적용 예시

### Spring Boot + Redis (Token Bucket 방식)

Bucket4j는 Java에서 Token Bucket을 쉽게 구현할 수 있는 라이브러리다. Redis와 연동하면 여러 서버 인스턴스 간에 카운터를 공유할 수 있어 분산 환경에서도 정확한 제한이 가능하다.

```java
Bucket bucket = Bucket.builder()
    .addLimit(
        Bandwidth.classic(
            100,                          // 버킷 최대 크기 = 버스트 허용량 (최대 100건)
            Refill.greedy(                // greedy: 토큰을 가능한 한 빠르게 충전
                100,                      // 충전량: 1분마다 100개 충전
                Duration.ofMinutes(1)     // 충전 주기
            )
        )
    )
    .build();

if (bucket.tryConsume(1)) {   // 토큰 1개 소비 시도
    // 성공: 요청 처리
} else {
    // 실패: 429 Too Many Requests 반환
    // 클라이언트에게 Retry-After 헤더를 함께 내려주는 것이 좋다
}
```

**Refill.greedy vs Refill.intervally**

- `greedy`: 충전 주기 내에서 토큰을 최대한 빠르게 채움 → 버스트에 유리
- `intervally`: 주기가 끝날 때 한꺼번에 충전 → 더 균일한 처리

### Nginx 설정 (Fixed Window에 가까운 방식)

Nginx의 `limit_req` 모듈은 내부적으로 Leaky Bucket을 기반으로 동작한다. 설정이 간단해서 API Gateway 역할을 Nginx가 담당할 때 많이 쓰인다.

```
# 클라이언트 IP($binary_remote_addr)별로 카운터를 관리하는 zone 선언
# zone=api:10m → 'api'라는 이름의 zone, 메모리 10MB 할당 (약 16만 개 IP 저장 가능)
# rate=10r/s → IP당 초당 최대 10건 허용
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

server {
    location /api/ {
        # burst=20: 순간적으로 최대 20건까지 큐에 허용 (Token Bucket의 버스트와 유사)
        # nodelay: 버스트 요청을 큐에서 기다리지 않고 즉시 처리 (없으면 지연 발생)
        limit_req zone=api burst=20 nodelay;
    }
}
```

**nodelay를 빼면?**

burst 범위 내 요청이 rate에 맞춰 지연 처리된다. 예를 들어 rate=10r/s이면 버스트 요청도 100ms 간격으로 처리된다. 응답 속도보다 처리 균일성이 중요한 경우에 사용한다.

### 어느 계층에서 제한할까?

```
Client
  ↓
[API Gateway] ← 1차 차단: IP/토큰 기반 글로벌 제한 (가장 효율적)
  ↓
[App Server] ← 2차 차단: 사용자/플랜별 세밀한 비즈니스 로직 기반 제한
  ↓
[DB]
```

두 계층을 함께 쓰는 이유는 역할이 다르기 때문이다.

- **API Gateway**: 악성 트래픽이나 DDoS를 앱 서버에 도달하기 전에 차단. IP 단위로 빠르게 판단.
- **App Server**: "무료 플랜은 하루 1,000건, 유료 플랜은 무제한" 같은 비즈니스 규칙은 앱 서버에서만 처리 가능.

실무에서는 두 계층을 함께 적용하는 것이 일반적이다.

---

## 정리

```
QPS 추정 → 병목 예측 (Little's Law) → Rate Limiting으로 방어
```

- QPS 추정은 시작일 뿐, **병목이 어디서 생기는지** 파악하는 것이 핵심
- Rate Limiting은 **알고리즘 선택**과 **적용 계층**이 모두 중요하다
- 실무에서는 API Gateway + 앱 서버 두 계층에서 함께 적용하는 경우가 많다

---

## 참고 자료

- [Bucket4j GitHub](https://github.com/bucket4j/bucket4j)
- [Nginx rate limiting 공식 문서](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)
- [Cloudflare Blog — How we built rate limiting capable of scaling to millions of domains](https://blog.cloudflare.com/counting-things-a-lot-of-different-things/)