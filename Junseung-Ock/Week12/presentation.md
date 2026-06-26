# 그래프 DB(Neo4j) vs 관계형 DB(SQL) — "친구의 친구 추천" 예제로 이해하기
## 참고 자료

- [Friend of Friend Recommendations with Neo4j](https://web.archive.org/web/20210116003626/http://geekswithblogs.net/brendonpage/archive/2015/10/26/friend-of-friend-recommendations-with-neo4j.aspx)
---
> **N단계 관계 탐색(친구의 친구의…)** 과 **관계 방향을 유연하게 다루는 작업**은 그래프 DB가 압도적으로 유리하다.
>



## 0. 사전 지식: 그래프 DB

- **그래프 DB**는 데이터를 **노드**와 **관계**로 저장하는 DB다. NoSQL 계열.
- 관계형 DB(RDB)와의 결정적 차이:
    - **RDB**: 관계에 참여하는 객체 각각의 **PK를 FK로 가지는 별도 테이블 생성해야 함 →** 관계가 1급 시민이 아니다.
    - **그래프 DB**: 관계 자체가 디스크에 실제로 저장된 1급 시민이다.
- **Neo4j** = 대표적인 그래프 DB. 쿼리 언어로 **Cypher**를 쓴다.

> 비유: RDB에서 "친구"는 *주민등록 명부 두 개를 대조해서 추론하는 관계*고,
그래프 DB에서 "친구"는 *두 사람 손을 실제로 묶어둔 끈*이다. 끈을 따라가기만 하면 된다.
>

---

## 1. 예제로 쓸 소셜 네트워크 (구체화)

등장인물: **Brendon, Bob, Charles, Alice, Louise** 5명.

친구 관계에는 **방향**이 있다고 하자. 화살표 `A → B`의 의미는 **"A가 B를 친구로 추가했다"**.

```
        Brendon
         │   │
         ▼   ▼
        Bob  Charles
        │ │    │
        ▼ ▼    ▼
     Alice Louise ◄── (Bob, Charles 양쪽에서 Louise를 가리킴)
```

방향을 가진 친구 관계(엣지) 목록:

| 누가(From) | 누구를(To) |
| --- | --- |
| Brendon | Bob |
| Brendon | Charles |
| Bob | Louise |
| Charles | Louise |
| Bob | Alice |

이 그림에서 직관적으로 보이는 것:

- **Brendon**은 화살표가 **바깥으로 뻗어나가는** 맨 위 사람.
- **Louise**는 화살표가 **안으로 들어오는** 맨 아래 사람.

---

## 2. 풀고 싶은 문제: "친구 추천"

추천 규칙:

1. **내 친구의 친구**를 추천한다. (= 나와 2단계 떨어진 사람)
2. **이미 내 친구**인 사람은 제외한다.
3. **공통 친구가 많을수록** 우선순위가 높다.

### Brendon에게 추천한다면?

- Brendon의 친구: **Bob, Charles**
- 그 친구들의 친구(= 친구의 친구):
    - Bob의 친구 → Louise, Alice
    - Charles의 친구 → Louise
- 집계:

| 추천 후보 | 공통 친구 | 설명 |
| --- | --- | --- |
| **Louise** | **2** | Bob을 통해 1번 + Charles를 통해 1번 |
| **Alice** | **1** | Bob을 통해서만 |
- 이미 친구인 Bob, Charles는 제외.
-  **결과: Louise(2명 공통) > Alice(1명 공통)** ← 우리가 원하던 그대로!

---

## 3. 1차 시도 — Brendon은 둘 다 잘 됨

### 3-1. Cypher (Neo4j)

```
MATCH
    (me:Person)-[:FRIEND]->(myFriend:Person)-[:FRIEND]->(friendOfFriend:Person)
WHERE NOT
    (me)-[:FRIEND]->(friendOfFriend:Person)
    AND me.name = 'Brendon'
RETURN
    count(friendOfFriend) AS friendsInCommon,
    friendOfFriend.name   AS suggestedFriend
ORDER BY
    friendsInCommon DESC;
```

**읽는 법** — 위 한 줄이 그림 그 자체다:

```
(me) ──FRIEND──▶ (myFriend) ──FRIEND──▶ (friendOfFriend)
 나                내 친구                  친구의 친구
```

- `WHERE NOT (me)-[:FRIEND]->(fof)` → 이미 내 친구면 제외 (규칙 2)
- `count(...)` → 공통 친구 수 집계 (규칙 3)

**실행 결과**

```
suggestedFriend | friendsInCommon
----------------|----------------
Louise          | 2
Alice           | 1
```

### 3-2. SQL (관계형 DB)

데이터는 두 테이블에 들어있다.

`People` 테이블:

| Id | Name |
| --- | --- |
| 1 | Brendon |
| 2 | Bob |
| 3 | Charles |
| 4 | Alice |
| 5 | Louise |

`FriendMaps` 테이블 (관계를 표현한 테이블):

| MeId | FriendId |
| --- | --- |
| 1 | 2 |
| 1 | 3 |
| 2 | 5 |
| 3 | 5 |
| 2 | 4 |

쿼리 — 같은 테이블을 **3번 셀프 조인**해야 한다:

```sql
SELECT
    Me.Id                   AS MeId,
    FriendOfFriend.FriendId AS SuggestedFriendId,
    COUNT(*)                AS FriendsInCommon
FROM People AS Me
INNER JOIN FriendMaps AS MyFriends
    ON MyFriends.MeId = Me.Id                       -- 나 → 내 친구
INNER JOIN FriendMaps AS FriendOfFriend
    ON MyFriends.FriendId = FriendOfFriend.MeId     -- 내 친구 → 친구의 친구
LEFT JOIN FriendMaps AS FriendsWithMe
    ON  Me.Id = FriendsWithMe.MeId
    AND FriendOfFriend.FriendId = FriendsWithMe.FriendId
WHERE
    FriendsWithMe.MeId IS NULL                       -- 이미 친구면 제외
    AND Me.Name = 'Brendon'
GROUP BY Me.Id, FriendOfFriend.FriendId
ORDER BY FriendsInCommon DESC;
```

**실행 결과**

```
MeId | SuggestedFriendId | FriendsInCommon
-----|-------------------|----------------
1    | 5                 | 2     ← Louise
1    | 4                 | 1     ← Alice
```

### 3-3. 여기서 보이는 작은 차이 2가지

1. **SQL은 ID(5, 4)만 나온다.** 이름(Louise, Alice)을 보려면 `People`과 **조인을 또** 해야 함 → 오버헤드.
   Cypher는 `friendOfFriend.name`으로 이름·ID 둘 다 바로 접근 가능.
2. **Cypher가 훨씬 짧고 읽기 쉽다.** SQL은 같은 테이블을 별칭(`MyFriends`, `FriendOfFriend`, `FriendsWithMe`)으로 3번 조인해서 머리가 아프다.

> 아직은 "Neo4j가 좀 더 낫네" 정도. **결정타는 다음 단계에 나온다.**
>

---

## 4. 도로 차단 — Louise에게 돌리면 결과가 0개

같은 Cypher 쿼리를 이름만 `'Louise'`로 바꿔 돌려보자.

```
MATCH (me:Person)-[:FRIEND]->(myFriend:Person)-[:FRIEND]->(friendOfFriend:Person)
WHERE NOT (me)-[:FRIEND]->(friendOfFriend:Person)
    AND me.name = 'Louise'
RETURN count(friendOfFriend) AS friendsInCommon, friendOfFriend.name AS suggestedFriend
ORDER BY friendsInCommon DESC;
```

**실행 결과: (아무것도 안 나옴)**

쿼리는 `(me)-[:FRIEND]->(...)`, 즉 **Louise에서 바깥으로 나가는 화살표**를 따라가려 한다.
그런데 우리 데이터에서 Louise는:

```
Bob ─────▶ Louise
Charles ──▶ Louise      ← Louise로 들어오는 화살표만 있음!
```

**Louise에서 나가는 화살표가 하나도 없다.** 그래서 첫 발짝부터 못 떼고 결과 0개.

- Brendon은 맨 위라 화살표가 다 바깥으로 향함 → 쿼리 잘 됨.
- Louise는 맨 아래라 화살표가 다 안으로 향함 → 쿼리 실패.

**진짜 문제는 "방향(direction)"이다.**

---

## 5. 해결 — Cypher는 글자 하나만 바꾸면 끝

방향을 무시하려면 화살표 머리 `>` 만 빼면 된다.

```diff
- (me)-[:FRIEND]->(myFriend)-[:FRIEND]->(friendOfFriend)   // 방향 있음
+ (me)-[:FRIEND]-(myFriend)-[:FRIEND]-(friendOfFriend)     // 방향 무시
```

- `>` →  로 바꾸면 "들어오든 나가든 상관없이 친구면 따라간다"는 뜻이 된다.

**Louise 대상 실행 결과 (방향 무시 후)**

| 추천 후보 | 공통 친구 | 설명 |
| --- | --- | --- |
| **Brendon** | **2** | Bob, Charles 양쪽으로 연결 |
| **Alice** | **1** | Bob을 통해서만 |

### SQL의 경우 방향 무시가 어려움

- `FriendMaps`의 한 행 `(MeId, FriendId)`는 본질적으로 "MeId → FriendId" 라는 **단방향**이다.
- 방향을 무시하려면 모든 조인마다 "`MeId=X OR FriendId=X`" 식의 양쪽 매칭을 끼워 넣어야 하고, 셀프 조인이 3중으로 얽히면서 조건이 폭발적으로 복잡해진다.

---

## 6. "그냥 데이터에 양방향을 넣으면 되잖아?"에 대한 반박

흔한 반박: *"Bob→Louise 만 있지 말고 Louise→Bob 도 같이 넣었으면 원래(방향 있는) 쿼리도 잘 됐을 텐데?"*

→ **틀렸다. 방향에 "의미"가 있을 때는 함부로 넣으면 안 된다.**

예를 들어 방향에 이런 의미를 부여했다고 하자:

| 상태 | 의미 |
| --- | --- |
| `A → B` (한쪽만) | A가 B에게 **친구 요청을 보냄** (대기 중, pending) |
| `A → B` **AND** `B → A` (양쪽) | B가 **수락함** (정식 친구, accepted) |

이 상태에서 쿼리 돌리겠다고 무턱대고 반대 방향 화살표를 채워 넣으면?
→ **"요청 대기 중"과 "수락 완료"를 구분할 수 없게 된다.** 데이터 모델 자체가 망가진다.

> **핵심**: "쿼리 편하게 하려고 데이터를 더 넣자"는 진짜 해결이 아니다.
그래프 DB는 데이터를 안 건드리고 **쿼리에서** 방향 무시를 즉석 선택할 수 있다는 게 강점이다.
저자는 이런 작업을 **"임시 관계 쿼리(ad-hoc relationship queries)"** 라고 부른다.
>

---

## 7. 성능 — 데이터가 커질수록 격차가 벌어진다

### 직관

- **그래프 DB**: 한 노드에서 시작해 **연결된 관계(엣지)만** 따라간다.
  전체 데이터가 얼마나 크든, 내가 따라가는 양은 **내 주변 연결 수**에만 비례.
- **관계형 DB**: 친구의 친구를 찾을 때마다 `FriendMaps` **테이블 전체를 조인**한다.
  연결이 많아질수록 **조인 중간 결과의 크기가 곱셈으로 폭발**한다.

### 숫자로 느껴보기

가정: 사용자 **N명**, 각자 평균 친구 **F명**.

|  | 그래프 DB (Neo4j) | 관계형 DB (SQL) |
| --- | --- | --- |
| 친구의 친구 탐색 비용 | 약 **F × F** (내 친구 F명 × 각자의 친구 F명) | `FriendMaps`(N×F 행) 셀프 조인 → **중간 결과가 N과 함께 증가** |
| N이 10배 커지면? | **거의 그대로** (내 주변은 안 변함) | **눈에 띄게 느려짐** (조인 대상 테이블이 10배) |

예: N = 100만 명, F = 50명일 때

- **그래프**: Louise 한 명 추천 → 50 × 50 = 약 2,500개의 경로만 확인. 100만이라는 전체 크기와 **무관**.
- **SQL**: 5,000만 행짜리 `FriendMaps`를 3번 조인 → 중간 결과 폭발.

> 저자 표현: *"연결된 사람이 많을수록 조인 결과의 크기도 커진다."*
그래프는 규모가 커져도 **선형적으로** 증가하지만, SQL은 복잡성이 커질수록 **심각하게** 느려진다.
>

---

## 8. 최종 정리

| 구분 | 그래프 DB (Neo4j) | 관계형 DB (SQL) |
| --- | --- | --- |
| 관계의 지위 | **1급 객체** (직접 저장) | 테이블로 흉내 (간접) |
| 다단계 관계 쿼리 | `-[:FRIEND]->` 경로로 직관적 | 셀프 조인 N중첩, 복잡 |
| 방향 무시 | 화살표 한 글자(`->`→`-`)만 제거 | 매우 어려움 (저자 30분 시도 후 포기) |
| 이름+ID 반환 | 바로 접근 | 추가 조인 필요 |
| 가독성 | 짧고 그림 같음 | 별칭 셀프 조인으로 난해 |
| 성능 (규모 ↑) | 선형 증가 (주변 연결에만 비례) | 조인 결과 폭발 (전체 크기에 영향) |

### 그래프 DB가 유용한 경우

- 소셜 네트워크 (친구 추천, 인맥 N촌 탐색)
- 추천 엔진 ("이 상품을 산 사람이 함께 산 상품")
- 사기 탐지 (계좌·거래의 연결 패턴 추적)
- 지식 그래프, 네트워크/인프라 토폴로지

### RDB가 유용한 경우

- 관계 탐색보다 **정형화된 행 단위 트랜잭션**이 중심일 때 (정산, 재고, 주문 등)
- 강한 스키마/제약·집계 통계가 주 업무일 때

---

### 한 줄 결론

> 친구 추천처럼 **"관계를 따라 깊게 들어가고, 방향을 유연하게 다뤄야 하는"** 문제에서는
관계를 끈으로 묶어 직접 저장하는 **그래프 DB가 코드도 짧고 성능도 안정적**이다.
>