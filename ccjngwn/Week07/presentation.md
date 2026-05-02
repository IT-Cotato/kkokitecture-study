# Cassandra에서 Dynamo 설계는 어떻게 구현됐을까?

> 참고 자료
>
> - [Apache Cassandra Architecture - Dynamo](https://cassandra.apache.org/doc/stable/cassandra/architecture/dynamo.html)
> - [Apache Cassandra Architecture - Storage Engine](https://cassandra.apache.org/doc/stable/cassandra/architecture/storage-engine.html)

---

## 1. 학습 주제

키-값 저장소 설계에서는 Dynamo 스타일의 분산 저장소를 다뤘다.

- 안정 해시
- 데이터 복제
- 정족수 합의
- 벡터 시계
- 가십 프로토콜
- 머클 트리
- SSTable
- Bloom filter

이번에는 이 개념들이 **Cassandra라는 실제 데이터베이스에서 어떻게 구현되는지**를 살펴본다.

Cassandra 공식 문서에 따르면 Cassandra는 Amazon Dynamo의 여러 기법을 기반으로 한다. 다만 Dynamo를 그대로 복사한 것은 아니고, 분산 클러스터 구조는 Dynamo의 영향을 받고 로컬 저장 엔진은 LSM Tree 기반 구조를 사용한다.

```text
Cassandra
= Dynamo 스타일 분산 클러스터 설계
+ LSM Tree 기반 로컬 저장 엔진
```

여기서 **LSM Tree(Log-Structured Merge Tree)** 는 쓰기 성능을 높이기 위한 저장 구조다. 데이터를 디스크의 기존 위치에 바로 덮어쓰지 않고, 먼저 메모리에 모았다가 정렬된 파일 형태로 디스크에 순차적으로 기록한다. 이후 여러 파일을 백그라운드에서 병합하면서 읽기 비용과 저장 공간을 관리한다.

또한 Cassandra 문서에서 자주 나오는 **mutation**은 데이터 변경 작업을 의미한다. `INSERT`, `UPDATE`, `DELETE`처럼 저장된 데이터를 바꾸는 요청을 통틀어 mutation이라고 볼 수 있다.

---

## 2. Cassandra가 Dynamo에서 가져온 것

Cassandra는 Dynamo 스타일의 다음 특징을 사용한다.

| Dynamo 계열 개념 | Cassandra에서의 형태                 |
| ---------------- | ------------------------------------ |
| 안정 해시        | token ring 기반 consistent hashing   |
| 가상 노드        | vnode                                |
| N개 복제         | replication factor                   |
| R/W quorum       | consistency level                    |
| hinted handoff   | hints                                |
| Merkle tree      | anti-entropy repair                  |
| gossip protocol  | cluster membership/failure detection |

Cassandra는 단일 master 노드를 두지 않는다. 각 노드는 자신이 담당하는 데이터에 대해 독립적으로 mutation, 즉 데이터 변경 요청을 받을 수 있다.

이 구조는 높은 가용성에 유리하다. 특정 master 장애로 전체 쓰기가 막히지 않기 때문이다. 대신 여러 복제본 사이에 데이터 버전 차이가 생길 수 있으므로, 충돌 해결과 복제본 동기화가 중요해진다.

---

## 3. 데이터 파티셔닝: Consistent Hashing

일반적인 해시 방식은 다음처럼 키를 노드에 배정할 수 있다.

```text
node = hash(key) % number_of_nodes
```

하지만 이 방식은 노드 수가 바뀌면 대부분의 키 매핑이 바뀐다. 노드 하나를 추가했을 뿐인데 많은 데이터가 다른 노드로 이동해야 하므로, 대규모 시스템에서는 부담이 크다.

Cassandra는 대신 **해시 링**을 사용한다.

```text
key를 해시한다
→ 해시 링 위의 token 위치를 찾는다
→ 링을 한 방향으로 이동하면서 담당 노드를 찾는다
```

예를 들어 replication factor가 3이라면, 키의 token 위치에서 링을 따라가며 만나는 서로 다른 세 노드가 해당 키의 복제본을 저장한다.

```text
key = "user:123"
hash(key) = token X

token X 이후의 노드:
Node A → Node B → Node C

replication factor = 3
=> user:123은 A, B, C에 저장
```

이 구조 덕분에 노드가 추가되거나 제거되어도 전체 데이터가 아니라 일부 token range만 이동하면 된다.

---

## 4. vnode: 물리 노드 하나를 여러 위치에 배치하기

단순한 consistent hashing만으로는 노드 수가 적을 때 부하가 고르게 나뉘지 않을 수 있다. 어떤 노드는 큰 token range를 맡고, 어떤 노드는 작은 token range만 맡게 될 수 있기 때문이다.

Cassandra는 이 문제를 줄이기 위해 **vnode**를 사용한다.

vnode는 하나의 물리 노드가 해시 링 위에 여러 token을 갖도록 하는 방식이다.

```text
물리 노드 A
→ token A1
→ token A2
→ token A3
→ token A4
```

vnode를 사용하면 노드 하나가 링의 한 지점이 아니라 여러 지점에 분산되어 배치된다.

효과는 다음과 같다.

- 데이터 분산이 더 균등해진다.
- 노드 추가 시 부하 재분배가 쉬워진다.
- 작은 클러스터에서도 균형 잡힌 token 배치가 가능하다.

---

## 5. Dynamo와 Cassandra의 차이: 충돌 해결 방식

Dynamo는 여러 복제본에 동시에 쓰기가 발생할 수 있으므로, 각 데이터 버전을 **vector clock**으로 추적한다. vector clock을 사용하면 두 버전 사이의 선후 관계를 판단하거나 서로 충돌하는 버전인지 감지할 수 있다.

반면 Cassandra는 vector clock을 사용하지 않는다. Cassandra는 더 단순한 방식인 **timestamp 기반 last-write-wins**를 사용한다.

Cassandra의 모든 mutation은 timestamp를 가진다. 이 timestamp는 클라이언트가 제공할 수도 있고, 없으면 coordinator 노드의 시간을 사용할 수도 있다.

동일한 데이터에 충돌하는 mutation이 들어오면 Cassandra는 timestamp가 더 최신인 값을 선택한다.

```text
값 A: timestamp = 100
값 B: timestamp = 120

=> B가 이김
```

정리하면 다음과 같다.

| 항목      | Dynamo                              | Cassandra              |
| --------- | ----------------------------------- | ---------------------- |
| 충돌 추적 | vector clock                        | timestamp              |
| 충돌 해결 | 애플리케이션이 해결 가능            | last-write-wins        |
| 장점      | 의미 기반 병합 가능                 | 단순하고 운영하기 쉬움 |
| 단점      | 클라이언트/애플리케이션 복잡도 증가 | clock 동기화에 의존    |

즉, Cassandra는 충돌 해결을 단순화한 대신 clock 동기화에 민감하다. 공식 문서에서도 Cassandra의 정확성은 clock에 의존하므로 NTP 같은 시간 동기화가 필요하다고 설명한다.

여기서 **NTP(Network Time Protocol)** 는 여러 서버의 시스템 시간을 맞추기 위한 프로토콜이다. 분산 시스템에서는 각 노드가 서로 다른 물리 서버에서 실행되기 때문에 서버마다 시간이 조금씩 어긋날 수 있다. Cassandra는 timestamp를 기준으로 최신 값을 판단하므로, 노드 시간이 크게 어긋나면 실제로는 나중에 발생한 쓰기보다 timestamp가 더 큰 예전 쓰기가 이기는 문제가 생길 수 있다.

따라서 Cassandra처럼 timestamp 기반 충돌 해결을 사용하는 시스템에서는 모든 노드의 시간을 지속적으로 맞추는 것이 중요하다.

---

## 6. Tunable Consistency: N/R/W가 Consistency Level이 된다

Dynamo 설계에서는 `N`, `R`, `W`를 사용해 일관성과 가용성을 조절한다.

| 기호 | 의미                       |
| ---- | -------------------------- |
| `N`  | 복제본 수                  |
| `R`  | 읽기 성공에 필요한 응답 수 |
| `W`  | 쓰기 성공에 필요한 응답 수 |

일반적으로 `R + W > N`이면 읽기와 쓰기 집합이 최소 하나 이상 겹치므로 더 강한 일관성을 기대할 수 있다.

Cassandra도 같은 아이디어를 사용하지만, 사용자가 매번 `R`, `W` 숫자를 직접 지정하지는 않는다. 대신 **consistency level**이라는 선택지를 제공한다.

| Consistency Level | 의미                                                      |
| ----------------- | --------------------------------------------------------- |
| `ONE`             | 복제본 하나만 응답하면 성공                               |
| `QUORUM`          | 복제본 과반수가 응답해야 성공                             |
| `ALL`             | 모든 복제본이 응답해야 성공                               |
| `LOCAL_QUORUM`    | 현재 데이터센터의 복제본 과반수가 응답해야 성공           |
| `LOCAL_ONE`       | 현재 데이터센터의 복제본 하나만 응답하면 성공             |
| `ANY`             | 쓰기 전용. 복제본 응답이 없더라도 hint 저장으로 성공 가능 |

예를 들어 replication factor가 3이고, 읽기와 쓰기 모두 `QUORUM`을 사용한다고 하자.

```text
RF = 3
QUORUM = 2

write: 2개 복제본 응답 필요
read: 2개 복제본 응답 필요

2 + 2 > 3
=> 읽기 집합과 쓰기 집합이 최소 하나 이상 겹침
```

이 경우 성공한 쓰기를 이후 읽기에서 볼 가능성이 높아진다. 반면 `ONE`을 사용하면 지연시간은 줄어들고 가용성은 높아지지만, 최신 값을 보장하기는 어려워진다.

중요한 점은 consistency level이 쓰기를 어디까지 보내느냐를 의미하지 않는다는 것이다. Cassandra의 쓰기 요청은 consistency level과 관계없이 모든 복제본으로 전송된다. consistency level은 coordinator가 클라이언트에게 성공을 반환하기 전에 **몇 개의 응답을 기다릴지**를 정한다.

---

## 7. Replica Synchronization

Cassandra에서는 여러 복제본이 독립적으로 mutation을 받을 수 있으므로, 어떤 복제본은 최신 데이터를 갖고 있고 어떤 복제본은 오래된 데이터를 갖고 있을 수 있다.

Cassandra는 복제본 차이를 줄이기 위해 다음 방식을 사용한다.

| 방식                | 역할                                                             |
| ------------------- | ---------------------------------------------------------------- |
| Read repair         | 읽기 과정에서 오래된 복제본을 최신 값으로 보정                   |
| Hinted handoff      | 장애 노드에 전달하지 못한 mutation을 hint로 저장 후 복구 시 전달 |
| Anti-entropy repair | Merkle tree로 복제본 차이를 찾아 동기화                          |

### Hinted handoff 예시

```text
원래 저장 대상: Node C
Node C 장애

Coordinator가 hint 저장
→ 클라이언트에게 쓰기 성공 반환 가능
→ Node C 복구 후 hint 전달
```

read repair와 hinted handoff는 best-effort 방식이다. 따라서 Cassandra는 최종적으로 복제본 수렴을 보장하기 위해 anti-entropy repair를 사용한다.

anti-entropy repair에서는 복제본이 데이터 범위에 대한 Merkle tree를 만들고, tree의 hash 값을 비교해 차이가 있는 범위만 동기화한다. 전체 데이터를 직접 비교하지 않아도 되기 때문에 복구 비용을 줄일 수 있다.

Cassandra는 Dynamo 방식의 full repair뿐 아니라 sub-range repair와 incremental repair도 지원한다.

| Repair 방식        | 의미                                    |
| ------------------ | --------------------------------------- |
| Full repair        | 전체 데이터셋을 대상으로 비교           |
| Sub-range repair   | 일부 데이터 범위만 더 세밀하게 비교     |
| Incremental repair | 마지막 repair 이후 변경된 부분만 repair |

**Full repair**는 복제본들이 가진 전체 데이터 범위를 대상으로 Merkle tree를 만들고 비교한다. 가장 단순하고 포괄적인 방식이지만, 데이터가 많을수록 비용이 크다.

**Sub-range repair**는 전체 범위를 한 번에 비교하지 않고 특정 token range만 나누어 repair한다. 범위를 더 작게 잡을 수 있기 때문에 차이가 있는 구간을 더 세밀하게 찾을 수 있고, 한 번의 repair가 시스템에 주는 부담도 줄일 수 있다.

**Incremental repair**는 마지막 repair 이후 변경된 데이터만 대상으로 삼는다. 매번 전체 데이터를 다시 비교하지 않아도 되기 때문에 반복적인 운영 환경에서 비용을 줄일 수 있다.

---

## 8. 로컬 저장 엔진: Commit Log, Memtable, SSTable

Dynamo 스타일 설계가 Cassandra의 분산 클러스터 구조에 해당한다면, 실제 노드 내부에서 데이터를 저장하는 방식은 LSM Tree 기반 저장 엔진에 가깝다.

Cassandra는 쓰기를 빠르게 처리하기 위해 기존 디스크 파일을 즉시 수정하지 않는다. 먼저 변경 내용을 안전하게 기록하고, 메모리에 반영한 뒤, 일정 시점에 디스크의 정렬된 파일로 내려보낸다.

| 구성 요소  | 역할                                                 |
| ---------- | ---------------------------------------------------- |
| Commit log | 장애 복구를 위해 쓰기 내용을 먼저 기록               |
| Memtable   | 쓰기를 빠르게 받기 위한 메모리 구조                  |
| SSTable    | memtable이 디스크로 flush된 immutable data file      |
| Compaction | 여러 SSTable을 병합해 읽기 비용과 공간 사용량을 관리 |

**Commit log**는 장애 복구를 위한 로그다. 쓰기 요청이 메모리에만 반영된 상태에서 노드가 죽으면 데이터가 사라질 수 있으므로, Cassandra는 먼저 commit log에 변경 내용을 기록한다.

**Memtable**은 메모리에 있는 쓰기 버퍼다. 쓰기 요청은 commit log에 기록된 뒤 memtable에 반영된다. 메모리에 쓰기 때문에 빠르게 처리할 수 있다.

SSTable은 한 번 생성되면 다시 수정되지 않는다. 같은 key에 대한 변경이 발생하면 기존 SSTable을 고치는 것이 아니라 새로운 SSTable에 변경 내용이 기록된다.

따라서 시간이 지나면 하나의 partition 데이터가 여러 SSTable에 흩어질 수 있다. 이를 정리하는 과정이 compaction이다.

**Compaction**은 여러 SSTable을 병합하는 백그라운드 작업이다. 오래된 값, 삭제 표시, 중복된 데이터를 정리해서 읽기 성능과 저장 공간 사용량을 관리한다.

---

## 9. Bloom Filter가 읽기 경로에서 하는 일

SSTable에는 여러 구성 파일이 있는데, 그중 `Filter.db`는 partition key에 대한 Bloom filter다.

Bloom filter는 “이 SSTable에 해당 key가 없을 가능성”을 빠르게 판단하는 데 사용된다. 따라서 읽기 요청이 들어왔을 때 모든 SSTable을 무작정 뒤지지 않아도 된다.

```text
read 요청
→ memtable 확인
→ Bloom filter로 관련 없는 SSTable 제외
→ 필요한 SSTable만 탐색
```

이 부분은 키-값 저장소 설계에서 배운 읽기 경로와 직접 연결된다.

---

## 10. 최종 정리

Cassandra는 Dynamo 논문의 아이디어를 실제 데이터베이스로 구현한 대표적인 사례다.

핵심은 다음과 같다.

- Cassandra는 consistent hashing과 vnode로 데이터를 여러 노드에 분산한다.
- replication factor와 consistency level로 일관성, 가용성, 지연시간의 균형을 조절한다.
- Dynamo는 vector clock을 사용하지만, Cassandra는 timestamp 기반 last-write-wins를 사용한다.
- read repair, hinted handoff, anti-entropy repair로 복제본 간 불일치를 줄인다.
- 노드 내부에서는 commit log, memtable, SSTable, Bloom filter를 사용해 쓰기와 읽기를 처리한다.

> Cassandra는 Dynamo의 분산 설계 아이디어를 가져와 consistent hashing, replication factor, consistency level, gossip, repair 같은 실제 DB 기능으로 제공한다. 다만 충돌 해결은 vector clock 대신 timestamp 기반 last-write-wins로 단순화했다.
