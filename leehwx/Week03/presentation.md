# **Redis Sorted Set을 이용한 더 나은 처리율 제한**

> 출처 : https://engineering.classdojo.com/blog/2015/02/06/rolling-rate-limiter/
> 

# 개요

ClassDojo에서는 최근 푸시 알림 인프라를 구축하고 있고, 다음 기준을 충족하는 처리율 제한기가 필요했다.

- 분산형
    - 처리율 제한기는 여러 프로세스에서 공유될 수 있다.
    - 이를 위해 외부 키-값 저장소가 필요했는데, 스택의 다른 부분에서도 Redis를 사용하고 있기 때문에 Redis를 선택했다.
- 이동 윈도우(rolling window)
    - 분당 최대 메시지 수를 10개로 설정했을 때, 사용자가 0시 59분에 10개의 메시지를 받고 1시 1분에 또 10개의 메시지를 받는 상황을 방지하고자 했다.
- 메시지 간 최소 간격
    - 전체적인 메시지 전송 빈도와 관계없이, 연속되는 메시지 사이에 최소 간격을 두어 연속적인 알림을 제한하고 싶었다.

# 첫 번째 시도 - 토큰 버킷

이동 윈도우를 사용한 처리율 제한의 표준 알고리즘은 토큰 버킷이다.

## 동작 방식

각 사용자는 토큰 개수를 담고 있는 버킷을 가진다.

1. 사용자가 어떤 작업을 시도할 때, 버킷에 있는 토큰 수를 확인
2. 버킷이 비어 있으면 사용자가 제한 횟수를 초과한 것이므로 작업 차단
    - 비어 있지 않으면 버킷에서 토큰을 1개 제거 후 해당 작업 허용
        - 비용이 많이 드는 작업의 경우 여러 개를 제거
3. 정해진 속도로 모든 버킷을 최대 용량까지 다시 채움

### 장점

- 공간을 거의 차지하지 않음
    - 사용자당 하나의 정수 카운터만 사용

### 문제점

- 버킷을 지속적으로 채워야 하는 프로세스 필요
    - 수백만명의 사용자가 있고, 각 버킷 충전 작업에 쓰기 작업이 필요하다면 Redis에 감당할 수 없는 부하를 발생시킬 것이다.

## 더 정교한 접근 방식 - 지연 계산(Lazy Refill)

요청이 왔을 때만 토큰이 몇 개나 생겼을지 계산하자는 전략이다.

- 각 사용자는 두 가지 키를 가짐
    - 토큰 버킷
    - 버킷이 마지막으로 다시 채워진 시점의 타임스탬프

### 동작 방식

1. 사용자가 어떤 작업을 시도할 때, 저장된 타임스탬프를 가져옴
2. 사용자가 마지막 요청 이후 부여 받았어야 할 토큰 수를 계산
    - `현재 시각 - 마지막 타임스탬프`를 계산 → 마지막 요청 이후 시간이 얼마나 흘렀는지 확인
    - 흘러간 시간만큼 생성되었어야 할 토큰 개수를 계산
        - 예) 1초에 1개씩 생성되는데 5초가 지났다면, 토큰 5개 추가
3. 토큰 수 업데이트
    - 기존 토큰 + 새로 생성된 토큰
4. 토큰이 있으면 하나를 사용하고 요청을 허용, 없으면 거부

### 문제점

분산 환경(서버가 여러 대인 상황)에서 제대로 작동하지 않는다.

- Redis는 여러 작업을 하나의 원자적 작업으로 처리할 수 있지만, 사용자에게 필요한 토큰 수를 계산하려면 Redis에 최소 두 번 접근해야 한다.
    1. 마지막 타임스탬프 조회 (GET)
    2. 새로운 토큰 개수 업데이트 (SET)

따라서 원자성이 보장되지 않으므로 경쟁 상태가 발생할 수 있다.

### **경쟁 상태(race condition) 발생 시나리오**

현재 토큰 수는 1개이다.

- 토큰이 새로 충전될 만큼의 시간은 지나지 않음
1. 클라이언트 1이 Redis에서 현재 토큰 수와 타임스탬프 조회
2. 클라이언트 2가 Redis에서 현재 토큰 수와 타임스탬프 조회
3. 클라이언트 1이 요청 허용 후, Redis에 토큰 개수 0으로 저장
4. 클라이언트 1도 요청 허용 후, Redis에 토큰 개수 0으로 저장

결과

- 원래대로라면 토큰이 1개뿐이라 요청 1개만 통과되어야 하지만, 실제로는 2개의 요청이 모두 통과
- 만약 서버(Worker)가 수십 대라면, 수십 명의 클라이언트가 동시에 토큰 1개임을 확인하게 되어서 사용자가 수십 개의 푸시 알림이 한꺼번에 받을 수 있음
- 읽기(Read) → 계산(Modify) → 쓰기(Write) 과정이 한 덩어리로 묶이지 않아 데이터의 정합성이 깨진 것

# 더 나은 접근 방식 - `Sorted Set`

 Redis에는 이러한 경쟁 조건을 방지하는 데 사용할 수 있는 sorted set이 있다.

## 고안한 알고리즘 동작 원리

각 사용자는 하나의 `Sorted Set`을 가진다.

- 키, 값은 모두 요청 시각(타임스탬프)
1. 현재 시점을 기준으로 일정 시간 간격 전에 발생했던 요소 삭제
    - Redis의 `ZREMRANGEBYSCORE` 명령어 사용
    - 예) 최근 1분 제한이라면 1분보다 더 오래된 기록 삭제
2. 남아 있는 집합의 모든 요소를 가져옴
    - `ZRANGE(0, -1)` 사용
3. 지금 들어온 요청의 타임스탬프를 집합에 추가
    - `ZADD` 사용
4. TTL 설정
    - 메모리 절약을 위해 집합 자체에 유효 기간 설정
    - 처리율 제한 간격과 동일하게 설정
5. 조회된 요소 개수와 허용 수치 비교
    1. 허용 수치를 초과하면 해당 작업 허용 X
6. 조회된 요소 중 가장 큰 요소와 현재의 타임스탬프 비교
    - 두 값이 너무 가깝다면 해당 작업 허용 X

## 장점

- `MULTI` 명령어를 사용해 **모든 Redis 작업을 원자적 작업으로 수행할 수 있다.**
    - 위에서 언급한 모든 Redis 작업(삭제, 조회, 추가, TTL 설정)을 하나의 트랜잭션(`MULTI`)으로 묶어서 실행한다.
        
        ⇒ Race Condition 해결 : 여러 서버가 동시에 접근해도 데이터가 꼬이지 않고 항상 최신 상태를 유지하게 된다.
        
- 하나의 Sorted Set만 있어도, 여러 가지 복잡한 제한 규칙을 동시에 적용할 수 있다.
    - 예) 분당 10개 메시지 이하, 3초당 2개 메시지 이하
    - 타임스탬프를 저장하므로 제한 규칙마다 별도의 카운터나 버킷을 만들지 않아도 됨

## 주의사항

- 이 알고리즘에서는 모든 Redis 작업이 완료된 후에 요청 거부 여부가 결정된다.
    - 거부된 작업도 여전히 작업으로 간주하여 기록됨
- 사용자가 제한 속도를 초과해서 계속 요청을 보내면, 그 거부된 요청들도 기록되어 윈도우 안에 쌓인다.
    - 제한 시간이 지나도 기록이 계속 갱신되어 영영 서비스 이용이 불가능해질 수 있음

# 모듈 - rolling-rate-limiter

만든 처리율 제한기를 `rolling-rate-limiter`라는 이름의 npm 모듈로 오픈소스 공개했다.

이 모듈은 Redis 백엔드를 사용할 수도 있고, 만약 여러 프로세스에 걸쳐 제한기를 실행할 필요가 없다면 메모리 내에서 직접 작동하게 할 수도 있다.

- Redis 모드 : 서버가 여러 대(분산 환경)일 때 데이터를 공유하기 위해 사용 (Sorted Set 구조 활용)
- 인메모리 모드 : 서버가 딱 한 대일 때 별도 DB 없이 서버 메모리를 사용 (단순 Array 구조 활용)

```jsx
 /*  Setup:  */

  var RateLimiter = require("rolling-rate-limiter");
  var Redis = require("redis");
  var client = Redis.createClient(config);

  var limiter = RateLimiter({
    redis: client,
    namespace: "UserLoginLimiter"
    interval: 1000 // 기준 시간 (1000ms = 1초)
    maxInInterval: 10 // 1초 동안 최대 10번까지만 허용
    minDifference: 100  // 요청과 요청 사이의 최소 간격 (100ms)
  });

  /*  Action:  */

  function attemptAction(userId, cb) {
    limiter(userId, function(err, timeLeft) {
      if (err) {

        // redis failed or similar.

      } else if (timeLeft > 0) {

        // limit was exceeded, action should not be allowed
        // timeLeft is the number of ms until the next action will be allowed
        // note that this can be treated as a boolean, since 0 is falsy

      } else {

        // limit was not exceeded, action should be allowed

      }
    });
  }
```

Express.js의 미들웨어로 사용

```jsx
 var limiter = RateLimiter({
    redis: redisClient,
    namespace: "requestRateLimiter",
    interval: 60000,
    maxInInterval: 100,
    minDifference: 100
  });

  app.use(function(req, res, next) {

    // "req.ipAddress" could be replaced with any unique user identifier
    limiter(req.ipAddress, function(err, timeLeft) {
      if (err) {
        return res.status(500).send();
      } else if (timeLeft) { // 제한 초과된 경우
        return res.status(429).send("You must wait " + timeLeft + " ms before you can make requests.");
      } else {
        return next();
      }
    });

  });
```