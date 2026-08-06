# How We Built Prefixy: Autocomplete Service Architecture

> **Prefixy란?**
>
>
> 개발자가 웹 애플리케이션의 검색창에 'Google 스타일'의 실시간 자동완성 기능을 쉽게 추가할 수 있도록 지원하는 호스팅형 프리픽스(Prefix) 검색 서비스. 사용자의 입력을 기반으로 추천 검색어를 실시간으로 업데이트하고 인기도 순으로 정렬해 줌.
>

## 1. 핵심 요구사항 및 디자인 목표

자동완성 시스템의 특성상 다음 두 가지 요소를 만족하기 위해 **'쓰기'보다 '읽기(Read) 속도'를 최우선**으로 설계함.

- **초고속 읽기 속도 (Lightning Fast Reads):** 사용자가 타이핑하는 즉시(약 100ms 이내) 추천 검색어가 나타나야 함.
- **추천어의 연관성 (Relevancy):** 다른 사용자들이 최근에 많이 검색한 데이터를 기반으로 인기도(Score)를 측정해 가장 관련성 높은 추천어를 제공함.

## 2. 데이터 구조 및 알고리즘 최적화 과정

### 시도 1: 일반적인 트라이 (Trie)

- **특징:** 문자열 검색에 자연스러운 구조로, 접두사 매칭 속도가 O(L) (L: 접두사 길이)로 빠름.
- **한계:** 접두사 노드에 도달한 후, 하위 자식 노드를 모두 탐색해 완성된 단어(Completions)들을 찾아야 하므로 O(N) (N: 전체 노드 수)의 병목이 발생함. 데이터가 커질수록 치명적임.

### 시도 2: 사전 계산 (Precomputation) 적용

- **특징:** 각 접두사(Prefix) 노드에 아예 완성된 단어 리스트(Completions)를 함께 저장해 둠.
- **장점:** 자식 노드 전체 탐색(O(N)) 프로세스가 사라져 조회 속도가 획기적으로 빨라짐.
- **단점:** 공간(Space)을 많이 차지하며, 데이터 업데이트 시 더 많은 쓰기 작업이 필요함 (읽기 속도를 위해 트레이드오프 수용).

### 최종 최적화: 데이터 제한 설정 (O(1) 달성)

1. **접두사 길이 제한 (L):** 지나치게 긴 검색어는 드물기 때문에 최대 접두사 길이를 약 20자로 제한.
2. **저장 단어 수 제한 (K):** 실제로 사용자에게 보여줄 추천어는 상위 5~10개이므로, 순위 산정을 위해 접두사당 최대 50개의 단어만 저장하도록 제한.
3. **결과:** L과 K를 상수로 고정함으로써 검색 시간 복잡도를 최종적으로 O(1)로 단축함.

### Prefix Hash Tree (프리픽스 해시 트리)

기존 트라이 대신 해시 맵 구조를 채택함.

- **Key:** 접두사 (Prefix)
- **Value:** 완성된 단어 리스트 (Completions)
- **효과:** 단 한 번의 Step으로 데이터 접근이 가능해지며, NoSQL 데이터베이스 구조와 완벽히 매칭됨.

| **Key (String)** | **Value (JSON 또는 객체 배열 구조)** |
| --- | --- |
| `"a"` | `[{"value": "apple", "score": 100}, {"value": "amazon", "score": 85}]` |
| `"ap"` | `[{"value": "apple", "score": 100}, {"value": "apollo", "score": 30}]` |
| `"app"` | `[{"value": "apple", "score": 100}, {"value": "application", "score": 45}]` |
| `"appl"` | `[{"value": "apple", "score": 100}]` |
| `"apple"` | `[{"value": "apple", "score": 100}]` |

`"apple"`이라는 단어 하나가 등록되더라도 `"a"`, `"ap"`, `"app"`, `"appl"`, `"apple"` 모든 접두사 Key의 Value 내부에 중복 저장됩니다. 덕분에 사용자가 `"ap"`만 쳐도 루프를 돌 필요 없이 `ap`라는 Key로 곧바로 리스트를 꺼내올 수 있습니다 (O(1)).

## 3. 데이터 저장소 선정 및 활용 (Redis & MongoDB)

### 1) 메인 데이터 저장소: Redis (In-Memory)

Prefix Hash Tree 구조를 메모리 상에서 초고속으로 처리하기 위해 Redis를 선택함.

| **비교 항목** | **Option 1: Redis List** | **Option 2: Redis Sorted Set (최종 채택)** |
| --- | --- | --- |
| **개념** | Doubly-linked list 구조 | 값(String)과 점수(Score)를 쌍으로 저장 |
| **조회 (SEARCH)** | O(1) (정렬된 상태 유지 시 Head에서 추출) | O(log K) |
| **업데이트 (INCREMENT)** | O(K) (전체 리스트를 가져와 앱에서 정렬/중복제거 후 다시 써야 함. Concurrency 문제 발생 위험) | **O(log K)** (`ZINCRBY` 명령어 단 한 줄로 Redis가 알아서 정렬 및 중복 제거 처리) |
- **인기도 유지 알고리즘 (K 제한 관리):**
    - 최대 저장 개수 K에 도달했을 때 새로운 단어가 들어오면, 가장 점수가 낮은 단어를 제거함.
    - 새로운 단어의 점수를 `[제거된 단어의 점수 + 1]`로 설정하여, 신규 단어도 상위 노출 기회를 얻을 수 있도록 보장함.

### **Redis Sorted Set**

**정상적인 인기도 증가 시 (`ZINCRBY` 호출)**

- **상태:** 현재 `K` 제한이 4개이고, 4개가 꽉 차 있는 상태
- **동작:** 사용자가 추천어 중 `"apple"`을 클릭하여 점수가 100에서 101로 증가

```
Score: 12   -> Member: "apollo"       (가장 인기 없음)
Score: 45   -> Member: "application"
Score: 70   -> Member: "apart"
Score: 100  -> Member: "apple"        ─── [사용자 클릭! +1] ───＞ Score: 101로 변경되어 서열 유지
```

**K 제한(Max = 4) 도달 상태에서 '새로운 단어' `"api"`가 진입할 때**

- **상태:** 공간이 없으므로 가장 점수가 낮은 `"apollo"`(12점)를 탈락시켜야 함
- **동작:** `"apollo"` 제거 ➔ 새 단어 `"api"`의 점수를 `[탈락 점수(12) + 1 = 13]`으로 설정해 진입

```
[기존 상태]                           [새로운 단어 "api" 진입 후 상태]
Score: 12  -> "apollo" (정리 대상)      Score: 13  -> "api" (탈락 점수+1을 받아 최하위 방어 성공)
Score: 45  -> "application"    ───＞    Score: 45  -> "application"
Score: 70  -> "apart"                  Score: 70  -> "apart"
Score: 101 -> "apple"                  Score: 101 -> "apple"
```

### 2) 영구 저장소: MongoDB (Disk-based)

모든 데이터를 메모리(Redis)에 두면 비용이 많이 들기 때문에 가성비와 영구 보존을 위해 MongoDB를 연동함.

- **Redis를 Cache처럼 사용 (LRU Eviction):** 자주 쓰이는 검색어만 Redis에 남기고, 오래된 데이터는 메모리에서 방출(Eviction)함.
- **Cache Miss 발생 시:** MongoDB에서 데이터를 찾아 Redis에 다시 적재(Reinstate)한 후 사용자에게 반환함.
- **데이터 업데이트:** 점수 증가(Increment) 이벤트 발생 시 항상 Redis에 먼저 쓰고 계산된 랭킹 데이터를 읽어와 MongoDB에 직렬화(Serialize)하여 반영함.

### Cache Miss 발생 시 실제 데이터 흐름 예시

> **상황 가정:** 새벽 시간에 오랜만에 어떤 유저가 검색창에 `"w"`를 입력. 하지만 Redis 메모리 용량 한계(LRU 정책)로 인해 `"w"` 데이터는 이미 메모리에서 방출되었고, 현재 MongoDB 디스크에만 보존되어 있는 상태.
>

#### Step 1: Redis 조회 ➔ 실패 (Cache Miss)

- Prefixy 서버가 Redis에서 `tenant_A:w` 라는 Key를 찾았으나, 메모리에 없으므로 `null`(데이터 없음)이 반환됩니다.

#### Step 2: MongoDB에서 원본 데이터 조회 ➔ 성공

- 서버는 곧바로 MongoDB의 전용 컬렉션으로 넘어가 아래 데이터를 찾아냅니다.

    ```json
    {
      "prefix": "w",
      "completions": [
        { "value": "weather", "score": 250 },
        { "value": "wikipedia", "score": 180 },
        { "value": "walmart", "score": 95 }
      ]
    }
    ```


#### Step 3: Redis 메모리에 다시 적재 (Reinstate)

- MongoDB에서 가져온 데이터를 기반으로, 백엔드 서버가 Redis 명령어를 연속 실행하여 메모리에 방을 다시 파줍니다.
    - `ZADD tenant_A:w 250 "weather"`
    - `ZADD tenant_A:w 180 "wikipedia"`
    - `ZADD tenant_A:w 95 "walmart"`

#### Step 4: 결과 반환 및 캐싱 효과

- 이제 Redis 메모리에 데이터가 정상적으로 올라왔으므로, 사용자에게 즉시 추천어 리스트를 반환합니다.
- **이후 효과:** 바로 다음 분에 다른 유저가 똑같이 `"w"`를 검색할 때는 MongoDB까지 내려갈 필요 없이 **Redis 메모리에서 1ms 만에 초고속으로** 데이터를 읽어오게 됩니다.

## 4. 멀티테넌시 (Multi-Tenancy) 아키텍처

여러 서비스(고객사)가 Prefixy 하나를 동시에 안전하게 이용할 수 있도록 격리 환경을 구축함.

- **Token Generator:** 개발자가 가입하면 고유 Tenant ID를 생성하고, 이를 JWT(JSON Web Token)로 암호화하여 프론트엔드 스크립트에 임베딩함.
- **Redis 데이터 격리:** Key값 앞에 테넌트 ID를 붙여 네임스페이스를 분리함 (`<tenantId>:<prefix>`).
- **MongoDB 데이터 격리:** 각 테넌트 ID마다 독립된 컬렉션(Collection)을 할당하여 격리함.

하나의 커다란 통합 테이블에 모든 고객사의 데이터를 모아두고 `WHERE tenant_id = 'xxx'`와 같이 필터링하는 방식이 **아닙**니다. 아예 **고객사 고유 ID로 명명된 전용 테이블을 독립적으로 개설**하여 그 안에서만 조회가 일어나도록 격리함으로써 **보안성과 인덱싱 성능을 동시에 챙긴 구조**.

## 5. 최종 시스템 아키텍처 및 데이터 흐름

```
[Client.js (사용자 브라우저)]
       │ (타이핑 이벤트 발생 시 GET 요청 + JWT 전달)
       ▼
[Prefixy Server (애플리케이션 로직)]
       │
       ├───＞ [Redis (In-Memory Cache & 실시간 랭킹 정렬)] ── (Cache Miss 시) ──┐
       │                                                                      ▼
       └─────────────────────────────────────────────────────────────＞ [MongoDB (영구 저장소)]
```

- **Client.js:** 사용자 앱의 검색창 이벤트를 감지해 Prefixy 서버로 `GET` 요청을 보낸 뒤, 반환된 JSON 데이터를 기반으로 UI에 드롭다운 추천 리스트를 렌더링함.