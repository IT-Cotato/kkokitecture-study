# 분산 환경의 수문장: Redis와 Lua Script로 구현하는 원자적 처리율 제한

**참고 자료:**

> [Redis 공식 문서: "Incr Command & Rate Limiting Patterns"] (https://redis.io/docs/latest/commands/incr/)

> [Stripe Engineering: "Scaling your API with rate limiters"] (https://stripe.com/blog/rate-limiters)

> [Class Calc: "Redis Lua Scripting for Rate Limiting"] (https://redis.io/tutorials/rate-limiting-in-java-spring-with-redis/)


> **주제 선정 이유:** 우리는 실제 백엔드 환경에서 여러 대의 서버를 운영하게 되는 순간, 예상치 못한 **경쟁 조건(Race Condition)**과 데이터 부정합 문제에 직면하게 됩니다. 단순히 '제한하는 법'을 아는 것을 넘어, 수만 개의 요청이 동시에 몰릴 때 어떻게 **데이터의 원자성(Atomicity)**을 보장하며 시스템을 방어할 것인지 그 실전 해벌인 **Redis와 Lua Script**의 조합을 공부해보고자 이 주제를 선택하였습니다.

---

# 1. 배경: 분산 환경이 만드는 '숫자의 함정'

## 1.1 단일 서버 vs 분산 서버

- 단일 서버에서는 메모리 변수 하나로 카운트를 세면 그만
- 서버가 여러 대(Server A, B, C)인 분산 환경에서는 공용 저장소인 **Redis**가 필요

## 1.2 경쟁 조건(Race Condition)의 발생

> 여러 서버가 동시에 Redis의 카운터를 업데이트하려고 할 때 데이터가 꼬이는 현상

**상황:** 현재 카운터 `5`, 임계치 `10`
1. **Server A:** "지금 카운터 몇이지?" -> Redis: `5` (GET)
2. **Server B:** "지금 카운터 몇이지?" -> Redis: `5` (GET)
3. **Server A:** "오케이, 5니까 1 더해서 저장할게!" -> Redis: `6` (SET)
4. **Server B:** "나도 5인 거 확인했어, 1 더해서 저장할게!" -> Redis: `6` (SET)

=> 요청은 2개가 들어왔는데, 카운터는 '6',

    1개가 누락되어 **처리율 제한 장치가 무력화**

---

# 2. 해결책: 왜 Lua Script인가?

## 2.1 원자성(Atomicity)의 보장

> Redis는 Lua Script를 실행할 때 **전체 스크립트를 하나의 명령어처럼 처리**

- 스크립트가 실행되는 동안 다른 명령어가 끼어들 수 없음
- "읽고(GET) -> 판단하고(Logic) -> 쓰는(SET)" 과정이 중간에 중단 X

## 2.2 네트워크 오버헤드 감소

- 기존: GET(요청/응답) + SET(요청/응답) = **2번의 왕복(Round-trip)**
- Lua Script: 스크립트 전송(요청/응답) = **1번의 왕복**
=> 지연 시간(Latency)이 생명인 처리율 제한 장치에서 매우 큰 이점

|비교 항목|일반적인 방식 (Read-Modify-Write)|Redis Lua Script|
|:---|:---|:---|
|원자성(Atomicity)|보장 안 됨 (Race Condition 발생)|완벽 보장 (Single Unit 실행)
|네트워크 비용|최소 2회 이상의 왕복 (RTT 발생)|1회 왕복으로 통신 종료
|데이터 정합성|분산 서버 환경에서 숫자가 누락|어떤 상황에서도 정확한 카운팅 가능
|로직 실행 위치|애플리케이션 서버 (Java/Python 등)|데이터 저장소 내부 (Redis)

---

# 3. 실전 구현: 토큰 버킷 Lua Script 로직

> 실제 현업에서 사용하는 로직을 간단히 추상화한 코드

```lua
-- KEYS[1]: 유저 식별 키 (예: "user:123:vlimit")
-- ARGV[1]: 버킷 용량 (최대 토큰 수)
-- ARGV[2]: 토큰 보충률 (초당 보충량)
-- ARGV[3]: 현재 타임스탬프

local tokens = tonumber(redis.call('get', KEYS[1]) or ARGV[1])
local last_refilled = tonumber(redis.call('get', KEYS[1] .. ':time') or 0)

-- 1. 경과 시간에 따른 토큰 리필 계산
local now = tonumber(ARGV[3])
local delta = math.max(0, now - last_refilled)
local refilled_tokens = math.min(tonumber(ARGV[1]), tokens + (delta * ARGV[2]))

-- 2. 토큰이 1개 이상 있다면 승인, 없다면 거부
if refilled_tokens >= 1 then
    redis.call('set', KEYS[1], refilled_tokens - 1)
    redis.call('set', KEYS[1] .. ':time', now)
    return 1 -- [승인]
else
    return 0 -- [거부]
end
```

---

# 4. 엔지니어링 Trade-off (주의사항)

## 4.1 Redis는 싱글 스레드다
- Lua Script가 너무 길거나 무거운 연산을 포함하면 Redis 전체가 멈춤
- **해결:** 스크립트는 최대한 짧고 간결하게 작성

## 4.2 고가용성 전략
- Redis 자체가 단일 장애 지점(SPOF)이 될 수 있음
- **해결:** Redis Sentinel이나 Cluster 구성을 통해 인프라 수준의 안정성을 확보

---

# 5. 결론

## 5.1 핵심 요약
- **이론과 실제:** 토큰 버킷 알고리즘을 아는 것보다, 그것이 **분산 환경에서 깨질 수 있음**을 인지하는 것이 더 중요
- **원자적 사고:** 분산 시스템 설계 시 "중간에 데이터가 바뀔 수 있는가?"를 항상 고려
- **도구의 활용:** Redis와 Lua Script의 조합은 성능과 데이터 정합성을 동시에 잡는 강력한 조합

## 5.2 한줄 정리
- 완벽한 알고리즘보다 중요한 것은, 그 알고리즘이 동작할 '환경'에 대한 깊은 이해
