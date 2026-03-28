# NoSQL이란 무엇인가

> 참고 문헌
> - [NoSQL이란? NoSQL 데이터베이스 이해하기 | MongoDB](https://www.mongodb.com/ko-kr/resources/basics/databases/nosql-explained)
> - [Guide to the Storage Engine in Apache Cassandra | Baeldung](https://www.baeldung.com/cassandra-storage-engine)
> - [A deep dive: What is LSM tree? | Vivek Bansal](https://vivekbansal.substack.com/p/what-is-lsm-tree)
> - [Redis: under the hood | Paul Smith](https://pauladamsmith.com/articles/redis-under-the-hood.html)
> - [Data structures | Redis](https://redis.io/technology/data-structures/)
> - [Index-free Adjacency in Graph Databases | Ken Wagatsuma](https://kenwagatsuma.com/blog/neo4j-index-free-adjacency-in-graph)

---

# 1. NoSQL 데이터베이스란?

'NoSQL 데이터베이스'라는 용어는 일반적으로 모든 **비관계형 데이터베이스**를 지칭할 때 사용된다.
- 'NoSQL'을 'non-SQL'의 약자로 보기도 하고, 'not only SQL'의 약자로 보기도 한다.
- 관계형 테이블과는 **다른 방식으로 데이터를 저장**한다.

우리가 익숙한 MySQL, PostgreSQL 같은 관계형 DB는 데이터를 엑셀 시트처럼 **행과 열**로 이루어진 테이블에 저장한다. NoSQL은 이 방식 대신, 저장하려는 데이터의 특성에 맞게 다양한 형태(문서, 키-값, 그래프, 와이드 컬럼)로 저장하는 DB들을 통칭한다.

---

# 2. NoSQL 데이터베이스의 간략한 역사

**왜 NoSQL이 생겨났는가?**

- **2000년대 이전**: 스토리지(저장 장치) 가격이 매우 비쌌다. 같은 데이터를 여러 곳에 저장하면 비용이 낭비되므로, 데이터를 최대한 쪼개고 중복 없이 저장하는 **정규화**가 중요했다. 관계형 DB가 이에 최적화되어 있었다.

- **2000년대 후반**: 스토리지 비용이 급격히 하락했다. 이제 데이터를 여러 곳에 저장해도 비용 부담이 없어졌고, 그 결과 애플리케이션이 저장·조회해야 하는 **데이터 양이 폭발적으로 증가**했다.

- 이 데이터는 사진, 동영상, 로그, 클릭 기록처럼 **형태가 제각각인 비정형 데이터**였다. 관계형 DB처럼 미리 스키마(테이블 구조)를 정의하고 저장하기에는 너무 다양했다.

- **2000년대 초** Google이 발표한 **BigTable** 논문이 분산 저장 시스템의 가능성을 보여줬다.
- **2009년** NoSQL이라는 용어가 처음 사용됐고, **2010년대** 들어 다양한 유형이 등장하며 확산됐다.

### 확산의 배경

- **애자일 방법론의 인기**: 요구사항이 자주 바뀌는 개발 환경에서 테이블 구조를 매번 바꾸기 어려운 관계형 DB보다, 스키마가 자유로운 NoSQL이 유리해졌다.
- **클라우드 컴퓨팅의 인기**: 서버를 여러 대에 분산 배포하는 방식이 보편화됐는데, 관계형 DB는 분산 환경에서 다루기 복잡했다. NoSQL은 분산을 기본 전제로 설계됐다.
- **데이터 규모 증가**: 페이스북, 유튜브, 넷플릭스처럼 수억 명의 사용자를 다루는 서비스가 등장하면서, 기존 관계형 DB로는 감당하기 어려운 규모의 데이터 처리가 필요해졌다.

---

# 3. NoSQL 데이터베이스의 유형

시간이 흐르면서 4가지 주요 유형으로 자리 잡았다.

---

## 3-1. 문서 지향 데이터베이스 (Document)

JSON 객체와 유사한 **문서 형태**로 데이터를 저장한다.

관계형 DB에서는 "사용자"와 "취미" 정보를 각각 다른 테이블에 나눠 저장하고, 조회할 때 JOIN으로 합쳐야 한다. 문서 지향 DB는 관련 데이터를 **하나의 문서** 안에 통째로 묶어 저장한다. 마치 사람의 이름, 이메일, 취미를 모두 적은 명함 한 장처럼.

- 각 문서는 필드와 값의 쌍을 포함하며, 값은 문자열·숫자·배열·중첩 객체 등 다양한 타입이 가능하다.
- 문서마다 구조가 달라도 된다 (유연한 스키마).
- **대표 DB**: MongoDB, Couchbase

```json
{
  "_id": 1,
  "first_name": "Tom",
  "email": "tom@example.com",
  "hobbies": ["bowling", "biking"]
}
```

→ 사용자와 취미 정보를 **하나의 문서**에 저장. 조회 시 JOIN 불필요, 쿼리 속도 향상.

---

## 3-2. 키-값 데이터베이스 (Key-Value)

각 항목이 **키(Key)와 값(Value)** 으로만 이루어진 가장 단순한 유형이다.

마치 사물함 같다. 사물함 번호(Key)를 알면 안에 든 물건(Value)을 바로 꺼낼 수 있다. 내용이 뭔지는 상관없고, 번호만 알면 된다.

- 각 키는 고유하며 하나의 값과만 연결된다.
- 구조가 단순한 만큼 **조회 속도가 매우 빠르다**.
- 주로 **메모리에 저장**하여 마이크로초(μs) 단위 응답을 제공한다.
- 주로 **캐싱**과 **세션 관리**에 사용된다.
- **대표 DB**: Redis, DynamoDB

```
"user:1001:session" → "eyJhbGciOiJIUzI1NiIsInR5..."
```

---

### Redis 내부 자료구조

> "Redis Strings is one of the most versatile of Redis' building blocks, a **binary-safe** data structure."

Redis는 단순한 Key-Value 저장소가 아니라 **다양한 내부 자료구조**를 Value로 지원한다. 어떤 자료구조를 선택하느냐에 따라 내부 저장 방식과 조회 성능이 달라진다.

| 타입 | 내부 구현 | 주요 활용 |
|---|---|---|
| **Strings** | Binary-safe 문자열 | 캐싱, 카운터, 세션 토큰 |
| **Lists** | **Linked List** | 작업 큐, 최근 활동 피드 |
| **Sets** | Hash Table / IntSet | 태그, 유니크 방문자 추적 |
| **Sorted Sets** | **Skip List** | 리더보드, 우선순위 큐 |
| **Hashes** | String field → String value 매핑 | 객체 저장 (유저 프로필 등) |
| **HyperLogLog** | 확률적 자료구조 | 유니크 방문자 수 추정 |
| **Streams** | 고속 데이터 스트림 | 이벤트 로그, 실시간 데이터 |

**왜 Lists에 Linked List를 쓰는가?**

> "Redis Lists are implemented with linked lists because, for a database system, it is crucial to be able to add elements to a very long list in **constant time**."

Linked List는 배열과 달리 끝에 요소를 추가할 때 기존 데이터를 밀어낼 필요가 없다. 리스트 맨 끝에 새 노드를 연결하기만 하면 되므로, **리스트 길이와 무관하게 항상 O(1)** 시간에 추가된다. 채팅 메시지나 작업 큐처럼 계속 쌓이는 데이터에 적합하다.

**왜 Sorted Sets에 Skip List를 쓰는가?**

Sorted Sets는 요소들을 **점수(score) 기준으로 정렬된 상태**로 유지해야 한다. 새 요소를 삽입할 때마다 올바른 위치를 찾아 끼워 넣어야 한다. Skip List는 여러 층의 "고속 레인"을 만들어 O(log n) 시간에 정렬된 위치를 찾을 수 있다. 리더보드처럼 실시간으로 순위가 바뀌는 데이터에 적합하다.

---

### Redis 아키텍처 — 단일 스레드 이벤트 루프

> "ae.h provides a platform-independent wrapper for setting up **I/O event notification loops**, which uses one of epoll, kqueue, or select."

Redis는 **단일 스레드**로 동작한다. "스레드가 하나뿐인데 어떻게 수만 개의 요청을 동시에 처리하는가?"라는 의문이 생긴다.

핵심은 Redis의 병목이 **CPU 계산**이 아니라 **네트워크 I/O** (요청 받고 응답 보내기)라는 점이다. 실제 연산은 매우 빠르게 끝나므로, 스레드 하나가 I/O 이벤트를 효율적으로 감시하면서 순서대로 처리하는 것이 더 빠르다. 여러 스레드를 쓰면 오히려 스레드 간 Lock 충돌로 오버헤드가 생긴다.

```
aeMain() 이벤트 루프 (무한 반복)
    ↓
aeProcessEvents() — epoll / kqueue / select 로 이벤트 감지
    ↓
클라이언트 I/O 이벤트 발생 감지 (요청 도착)
    ↓
readQueryFromClient() → processCommand() → call() → 응답
    ↓
다시 이벤트 대기
```

- **epoll / kqueue / select**: OS가 제공하는 I/O 감시 메커니즘. 여러 소켓(클라이언트 연결)을 동시에 감시하다가 데이터가 도착하면 알려준다. 하나의 스레드가 수천 개의 연결을 동시에 감시할 수 있다.
- Lock 경쟁이 없어 오버헤드가 최소화된다.

---

### 메모리 최적화 — Shared Objects

> "The greatest impact comes from a large pool of shared integers. It creates an array of the **first 10,000 non-negative integers** as Redis objects."

Redis는 자주 사용되는 값들을 시작할 때 미리 만들어두고 재사용한다.
- `+OK`, `-ERR` 같은 공통 응답 문자열을 공유 객체로 미리 생성
- 숫자 `0`부터 `9999`까지는 요청이 올 때마다 새로 만들지 않고 미리 만들어둔 객체를 그대로 반환

예를 들어 카운터를 1씩 올리는 요청이 수백만 번 오더라도, 결과값이 0~9999 범위라면 매번 새 객체를 할당하지 않아도 되므로 메모리와 CPU를 아낄 수 있다.

---

### 영속성 (Persistence) — Redis는 메모리 DB인데 데이터가 사라지지 않는가?

Redis는 기본적으로 메모리에 데이터를 저장하므로, 서버가 꺼지면 데이터가 사라질 수 있다. 이를 방지하기 위해 두 가지 방식으로 디스크에도 기록한다.

> "Redis registers a function to be called each time it enters the event loop, **beforeSleep()**, which calls **flushAppendOnlyFile()**."

- **AOF (Append-Only File)**: 모든 쓰기 명령을 파일에 순서대로 기록해둔다. 서버 재시작 시 이 파일을 재생(replay)하여 데이터 복구. 데이터 손실이 거의 없지만 파일이 커진다.
- **RDB**: 특정 시점의 전체 데이터 스냅샷을 `dump.rdb` 파일로 저장. 복구 속도가 빠르지만 마지막 스냅샷 이후 데이터는 손실될 수 있다.

---

## 3-3. 와이드 컬럼 저장소 (Wide Column)

테이블, 행, 그리고 **동적으로 구성되는 컬럼**에 데이터를 저장한다.

관계형 DB는 같은 테이블의 모든 행이 동일한 컬럼 구조를 가져야 한다. 와이드 컬럼은 **행마다 컬럼 수와 이름이 달라도 된다**. 어떤 사용자는 이메일만 있고, 다른 사용자는 이메일과 생년월일 둘 다 있어도 하나의 테이블에 저장 가능하다.

- **대표 DB**: Cassandra, HBase (Google BigTable에서 영향받음)

| name | email | dob |
|---|---|---|
| Foo bar | foo@bar.com | *(없음)* |
| Carn Yale | bar@foo.com | 12-05-1972 |

---

### 왜 B-Tree를 쓰지 않는가? — Write Amplification 문제

MySQL, PostgreSQL 같은 관계형 DB는 데이터를 빠르게 찾기 위해 **B-Tree** 자료구조로 인덱스를 만든다. B-Tree는 데이터를 정렬된 트리 형태로 저장하며, 읽기와 쓰기 모두 `O(log n)` 성능을 보장한다.

그런데 쓰기가 많은(write-heavy) 환경에서는 문제가 생긴다.

> "B-trees are optimized for balanced reads and writes. However, as the data grows, the B-trees based databases are slower at writes. Whenever any write happens in a B-tree, it can lead to **updating multiple nodes**."

B-Tree에 데이터를 저장하면 트리의 균형을 유지하기 위해 여러 노드를 함께 수정해야 할 수 있다. 즉, 사용자 입장에서는 데이터 하나를 저장했지만, 실제 디스크에는 여러 번 쓰기가 발생한다. 이것이 **Write Amplification(쓰기 증폭)**이다.

SNS의 로그, IoT 센서 데이터처럼 초당 수만 건이 기록되는 환경에서는 이 오버헤드가 치명적이다.

**→ Cassandra는 쓰기 성능을 극대화하기 위해 LSM Tree를 선택했다.**

---

### LSM Tree (Log-Structured Merge Tree)

> "Apache Cassandra leverages a **two-level Log-Structured Merge-Tree** based data structure for storage."

LSM Tree의 핵심 아이디어는 단순하다: **일단 메모리에 빠르게 쓰고, 나중에 디스크로 옮긴다.**

```
C0 (in-memory)  →  주기적 flush  →  C1 (on-disk)
   MemTable                            SSTable
```

- 메모리 쓰기는 디스크 쓰기보다 수천 배 빠르다.
- 모든 쓰기가 일단 메모리(C0, MemTable)로 향하므로 응답이 빠르다.
- 주기적으로 메모리 데이터를 디스크(C1, SSTable)로 옮기면서 I/O 연산을 묶어서 처리한다.

---

#### MemTable — 메모리 속 정렬된 임시 저장소

> "MemTable is a memory-resident data structure such as a **balanced binary search tree** with self-balancing properties."

MemTable은 메모리에 있는 가변(mutable) 자료구조로, **Balanced BST(균형 이진 탐색 트리)** 로 구현된다.

Balanced BST는 항상 왼쪽-오른쪽 균형을 유지하는 이진 트리다. 이렇게 하면 어떤 키를 찾아도 탐색 깊이가 일정하게 유지되어 삽입·검색이 `O(log n)`으로 보장된다. 그리고 데이터가 **키 순서로 정렬된 상태**로 저장된다는 특성이 나중에 SSTable을 만들 때 중요하다.

- 쓰기 요청이 오면 MemTable에 순차적으로 추가된다 → 랜덤 디스크 I/O 없음 → 빠름
- MemTable이 정해진 크기(임계값)에 도달하면:
  1. 새로운 빈 MemTable로 교체 (새 요청은 여기로)
  2. 기존 MemTable은 **백그라운드에서 비동기로 디스크에 flush** → SSTable이 됨

**문제**: 메모리는 전원이 꺼지면 사라진다. 서버가 갑자기 죽으면 MemTable에 있던 데이터가 모두 사라지는 것 아닌가?

---

#### Commit Log (WAL, Write-Ahead Log) — 장애 대비 안전장치

> "It guarantees durability by ensuring all writes persist onto the disk in an **append-only** file called the Commit Log."

이 문제를 해결하기 위해 Cassandra는 모든 쓰기를 **Commit Log**라는 파일에도 동시에 기록한다.

```
쓰기 요청
    ├── Commit Log (디스크, append-only) 기록 ← 내구성 보장
    └── MemTable 업데이트 (메모리) ← 빠른 응답
```

- **append-only**: 파일 끝에 이어 붙이기만 하므로 디스크의 헤드가 이리저리 움직이는 랜덤 쓰기가 없다. 순차 쓰기는 랜덤 쓰기보다 훨씬 빠르다.
- 서버가 장애로 꺼져도 Commit Log가 남아 있으므로, 재시작 시 이를 재생하여 MemTable을 복원할 수 있다.
- **정상 운영 중에는 읽지 않는다** — 장애 복구 시에만 사용.

---

#### SSTable (Sorted String Table) — 디스크 속 정렬된 불변 파일

> "SSTable is the **disk-resident** component of the LSM tree. It derives its name from a similar data structure, first used by Google's **BigTable** database."

MemTable이 flush되면 SSTable이 된다. SSTable의 특성:

- **디스크에 상주**하는 **불변(immutable)** 파일 — 한번 쓰이면 수정되지 않는다
- MemTable이 Balanced BST였기 때문에, flush 시 **키 기준으로 정렬된 상태**로 저장된다
- 키가 같은 데이터(업데이트/삭제)가 여러 SSTable에 걸쳐 존재할 수 있다 → 최신 SSTable의 값이 최신

```
SSTable 1 (오래된 것): [ant:1, beer:2, cat:3]
SSTable 2 (최신):      [ant:5, dog:4, elk:2]
→ ant의 현재 값은 5 (SSTable 2 기준)
```

---

### 쓰기 / 읽기 경로 전체 그림

**쓰기 경로**
```
요청: SET ant = 5
  ↓
1. Commit Log에 append 기록 (디스크, 순차 쓰기, 빠름)
  ↓
2. MemTable에 ant:5 저장 (메모리, O(log n))
  ↓
3. MemTable 가득 참 → SSTable로 flush (디스크)
```
→ 랜덤 디스크 쓰기가 없으므로 **쓰기 성능 극대화**

**읽기 경로**
```
요청: GET ant
  ↓
1. MemTable에서 먼저 검색 (가장 최신 데이터)
  ↓ (없으면)
2. 가장 최신 SSTable부터 순서대로 탐색
  ↓
찾으면 반환
```

---

### 읽기 최적화 — SSTable이 쌓일수록 읽기가 느려진다

쓰기가 많아지면 SSTable 파일 수가 늘어나고, 읽기 시 더 많은 파일을 뒤져야 한다. Cassandra는 이를 두 가지 방법으로 최적화한다.

**① Sparse Index — 어느 세그먼트를 봐야 하는지 빠르게 찾기**

> "Apache Cassandra maintains a sparse index to limit the number of **segments it needs to scan** while looking for a key."

SSTable은 여러 개의 세그먼트(블록)로 이루어진다. Sparse Index는 **각 세그먼트의 첫 번째 키와 그 파일 위치(offset)** 를 메모리에 B-Tree로 기록해둔 인덱스다.

"beer"라는 키를 찾는다면:
```
Sparse Index (메모리):
ant  → offset 0번 세그먼트
dog  → offset 3번 세그먼트
elk  → offset 5번 세그먼트

beer는 알파벳 순서상 ant < beer < dog
→ offset 0번 세그먼트만 열어보면 됨
→ 나머지 세그먼트는 확인하지 않아도 됨
```

전체 SSTable을 읽는 대신, 해당 범위의 세그먼트 하나만 읽으면 된다.

**② Bloom Filter — 없는 키를 빠르게 걸러내기**

> "Apache Cassandra optimizes the read queries using a probabilistic data structure known as a **bloom filter**."

Sparse Index로 세그먼트를 좁혔어도, 찾는 키가 거기에 없을 수 있다. 이때 파일을 실제로 열어보는 비용이 발생한다.

Bloom Filter는 **"이 키가 이 세그먼트에 없다"는 것을 빠르게 판단**하는 확률적 자료구조다. 각 세그먼트마다 하나씩 붙어있다.

- 결과가 **"No"** → 이 세그먼트에 분명히 없음 → **완전히 건너뜀** (디스크 접근 없음)
- 결과가 **"Maybe"** → 있을 수도 있음 → 세그먼트 실제 탐색

> "We can plan to improve the accuracy of bloom filters by allocating **larger storage space** for them."

- **False Negative(없는데 있다고 하는 것)는 절대 없다** → "No"는 100% 확실
- **False Positive(있는데 없다고 하는 것)는 없지만**, "Maybe"인데 실제로 없는 경우는 있다 (허탕)
- Bloom Filter에 메모리를 더 할당할수록 허탕 비율이 줄어든다

---

### Compaction — 쌓인 SSTable 정리하기

> "Apache Cassandra runs a background process of compaction that **merges smaller sorted segments into larger segments**."

SSTable은 불변이므로, 같은 키에 대한 업데이트/삭제가 여러 SSTable에 흩어져 있다. 시간이 지날수록 SSTable 파일이 많아져 읽기 성능이 계속 저하된다.

**Compaction**은 백그라운드에서 주기적으로 작은 SSTable들을 합쳐 큰 SSTable로 만드는 과정이다.

```
합치기 전:
SSTable 1: [A:1, C:3, E:5]
SSTable 2: [A:2, B:4, C:deleted]

합친 후:
SSTable:   [A:2, B:4, E:5]
           ↑ A는 최신값 2, C는 삭제됨(tombstone 제거), E는 그대로
```

- 같은 키의 값이 여러 SSTable에 있으면 **최신 버전만 유지**
- 삭제된 키는 원래 **tombstone(묘비 표시)**으로만 남아있다가 → compaction 시 실제로 지워진다
  - 왜 tombstone? SSTable은 불변이라 즉시 지울 수 없기 때문
- 파일 수가 줄어드니 읽기 빨라짐 + 실제로 사용하는 스토리지도 줄어든다

> "compaction process gives the **dual benefit of faster reads with lesser storage**."

---

## 3-4. 그래프 데이터베이스 (Graph)

**노드(Node)** 와 **엣지(Edge)** 형태로 데이터를 저장한다.

- 노드: 사람, 장소, 사물 같은 개체 정보 저장
- 엣지: 노드들 사이의 **관계** 저장 (Alice "팔로우" Bob, 서울 "수도" 한국 등)
- **대표 DB**: Neo4j

SNS 친구 관계, 추천 시스템, 사기 탐지처럼 **관계가 핵심인 데이터**에 적합하다.

---

### RDBMS로 관계 데이터를 조회하면 무슨 일이 생기는가?

소셜 네트워크에서 "Alice가 팔로우하는 유저"를 찾는 예시를 보자.

관계형 DB라면 이렇게 모델링한다:
- `User` 테이블: 유저 정보
- `Follower` 테이블: 팔로우 관계 (누가 누구를 팔로우하는지)

"Alice(User_ID=1)가 팔로우하는 사람 목록"을 찾으려면 `Follower` 테이블에서 `User_ID == 1`인 행을 모두 찾아야 한다.

**인덱스 없을 때**: Follower 테이블 전체를 처음부터 끝까지 순서대로 스캔해야 한다. 행이 N개면 최악의 경우 N번 확인 → **O(N)**

**인덱스 있을 때**: `User_ID` 컬럼에 해시 인덱스를 미리 만들어두면 바로 찾을 수 있다 → **O(log N)**

하지만 인덱스에는 비용이 따른다:
- 데이터가 바뀔 때마다 **인덱스도 함께 업데이트**해야 하므로 쓰기 성능 저하
- 인덱스 자체가 **스토리지를 차지**한다

그리고 결정적인 문제가 있다. **"친구의 친구의 친구"를 찾으면?** 즉, 관계를 여러 단계 타고 가는 쿼리를 하면 JOIN이 중첩되고, 데이터가 많을수록 속도가 급격히 느려진다.

---

### Index-free Adjacency — 포인터로 직접 연결

> "Index-free Adjacency literally means **'adjacency without an index.'** In other words, related nodes are stored close together without requiring an index lookup."

Neo4j 같은 그래프 DB는 관계를 **디스크에 직접 포인터로** 저장한다. Alice 노드 안에 "Alice가 팔로우하는 사람들"로 가는 주소(포인터)가 직접 박혀 있는 것이다.

```
RDBMS:
Alice → Follower 인덱스 조회 → 테이블 행 접근 → O(log N)

Neo4j:
Alice 노드 → 포인터 → Bob 노드 → 즉시 접근 → O(1)
```

> "Since nodes reference related nodes via direct pointers, there is no need for an index to look up relationships... **related nodes are always adjacent on disk.**"

인덱스 룩업이 없으니 직접 연결된 노드를 찾는 것이 **항상 O(1)**이다.

**친구의 친구의 친구를 찾으면?**
```
Alice → (O(1)) → Bob → (O(1)) → Carol → (O(1)) → Dave
```
각 단계가 O(1)이므로, 아무리 깊어져도 **단계당 비용은 일정**하다. 전체 데이터 크기와 무관하다.

> "Native graph queries perform at a **constant rate** based on the amount of data they touch, no matter the total size of your data."

RDBMS는 전체 데이터가 커질수록 느려지지만, 그래프 DB는 실제로 탐색하는 데이터 양에만 비례한다.

---

# 4. NoSQL 데이터베이스의 특징

- **분산 데이터베이스**: 여러 서버(노드)에 데이터를 분산 저장한다. 한 서버가 죽어도 다른 서버가 서비스를 이어받는다.
- **수평 확장(Scale-out)**: 처리량이 부족하면 서버를 추가하면 된다. 관계형 DB는 주로 더 좋은 서버 1대로 교체하는 수직 확장에 의존하지만, NoSQL은 서버를 여러 대로 늘리는 수평 확장에 최적화되어 있다.
- **유연한 스키마**: 테이블 구조를 미리 정의하지 않아도 된다. 새로운 필드가 필요하면 그냥 저장하면 된다.
- **고가용성**: 데이터를 여러 노드에 복제해두므로, 서버 장애가 나도 서비스가 중단되지 않는다.
- **샤딩**: 데이터를 여러 노드에 분할 저장하여 대용량을 처리한다.

---

### BASE 원칙 — ACID를 포기하는 대신 무엇을 얻는가?

관계형 DB는 **ACID** 원칙을 따른다. 트랜잭션이 완료되면 데이터는 즉시, 완전하게, 모든 노드에서 일관된 상태를 보장한다.

NoSQL은 분산 환경에서 성능과 가용성을 극대화하기 위해 **BASE** 원칙을 따른다. 완전한 일관성을 조금 포기하는 대신 빠른 응답과 높은 가용성을 얻는다.

| 약어 | 원문 | 의미 |
|---|---|---|
| **BA** | Basically Available | **기본 가용성** — 장애가 나도 일단 응답은 보낸다. 일부 데이터가 최신이 아닐 수 있다. |
| **S** | Soft State | **소프트 상태** — 시스템 상태가 시간에 따라 변할 수 있다. 모든 노드가 동시에 같은 값을 보여주지 않을 수도 있다. |
| **E** | Eventual Consistency | **궁극적 일관성** — 업데이트 후 충분한 시간이 지나면 모든 노드가 결국 같은 값을 갖게 된다. |

실생활 비유: 인스타그램에 글을 올렸을 때 친구 A의 피드에는 바로 보이는데 친구 B의 피드에는 2~3초 뒤에 보이는 경우가 있다. 이것이 Eventual Consistency다. 결국엔 모두에게 같은 내용이 보이지만, 그 과정에서 잠깐 불일치가 허용된다.

---

# 5. RDBMS vs NoSQL 비교

| 항목 | RDBMS | NoSQL |
|---|---|---|
| **데이터 모델** | 행/열 기반 표 형식 (구조화 데이터) | 문서, 키-값, 그래프, 와이드 컬럼 (반/비정형) |
| **스키마** | 사전 정의된 고정 스키마 | 유연한 스키마 |
| **쿼리 언어** | SQL | DB마다 다름 (MongoDB Query, Cypher 등) |
| **확장성** | 주로 수직 확장 | 수직 + 수평 확장 모두 지원 |
| **데이터 관계** | 외래 키 + JOIN | 중첩 구조 또는 직접 포인터 |
| **트랜잭션** | ACID | ACID 또는 BASE |
| **성능** | 읽기 위주 + 트랜잭션 중심 | 실시간 처리, 빅데이터, 분산 환경 |
| **데이터 일관성** | 강한 일관성 | 주로 궁극적 일관성 |
| **분산 컴퓨팅** | 분산 설계가 기본이 아님 | 분산 설계가 기본 전제 |

### 구체적인 예시 — 사용자와 취미 정보 저장

**RDBMS 방식 (두 테이블, JOIN 필요)**
```
Users 테이블:           Hobbies 테이블:
user_id | name          user_id | hobby
1       | Tom    →JOIN→  1      | bowling
                         1      | biking
```

**NoSQL 문서형 방식 (한 문서, JOIN 불필요)**
```json
{ "user_id": 1, "name": "Tom", "hobbies": ["bowling", "biking"] }
```

→ 문서 하나만 읽으면 모든 정보가 담겨 있어 쿼리가 빠르다.

---

# 6. 언제 NoSQL을 선택하는가?

다음 조건 중 하나 이상 해당하면 NoSQL을 고려한다:
- 빠른 속도의 **Agile 개발** 환경 (요구사항이 자주 바뀌어 스키마 변경이 잦을 때)
- **비정형 또는 반정형 데이터** 저장 (사용자마다 저장할 데이터 구조가 다를 때)
- **방대한 데이터 양** 처리 (수 TB, PB 단위)
- **수평 확장** 이 필요할 때 (서버를 계속 추가해야 할 때)
- **마이크로서비스**, 실시간 스트리밍 같은 현대적 아키텍처를 지원할 때

---

# 7. 흔한 오해

### 오해 1: 관계형 데이터는 반드시 관계형 DB에 저장해야 한다
→ 사실이 아니다. NoSQL도 관계형 데이터를 저장할 수 있다. 오히려 문서형 DB는 관련 데이터를 중첩 구조로 한 문서에 모아 표현하는 게 더 직관적이라고 느끼는 개발자들이 많다. 별도 테이블 없이 한 문서에서 모든 정보를 꺼낼 수 있기 때문이다.

### 오해 2: NoSQL은 ACID 트랜잭션을 지원하지 않는다
→ 사실이 아니다. MongoDB 같은 일부 NoSQL은 ACID 트랜잭션을 실제로 지원한다. 또한 NoSQL의 데이터 모델링 방식에 따라 관련 데이터를 하나의 문서에 저장하므로, 애초에 다중 레코드 트랜잭션 자체가 필요 없는 경우도 많다.

---

# 8. 유형별 요약

| 유형 | 대표 DB | 핵심 저장 구조 | 강점 | 주요 사용처 |
|---|---|---|---|---|
| 문서형 | MongoDB, Couchbase | BSON/JSON 문서 | 유연한 스키마, 범용 | 일반 웹 앱, 콘텐츠 관리 |
| 키-값 | Redis, DynamoDB | Hash / Skip List / Linked List | 초저지연, 다양한 자료구조 | 캐싱, 세션, 리더보드 |
| 와이드 컬럼 | Cassandra, HBase | LSM Tree (MemTable → SSTable) | 대규모 쓰기 heavy 워크로드 | 로그, IoT, 시계열 데이터 |
| 그래프 | Neo4j | 노드 + 직접 포인터(엣지) | 복잡한 관계 탐색, O(1) 인접 | SNS, 추천 시스템, 사기 탐지 |
