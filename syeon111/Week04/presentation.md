# Cassandra의 안정 해시 실제 적용

> **참고 자료**
> 
> - [Apache Cassandra 공식 문서 - Dynamo / Token Ring](https://cassandra.apache.org/doc/stable/cassandra/architecture/dynamo.html)
> - [DataStax Docs - Consistent hashing](https://docs.datastax.com/en/cassandra-oss/3.0/cassandra/architecture/archDataDistributeHashing.html)
> - [DataStax Docs - Virtual nodes](https://docs.datastax.com/en/cassandra-oss/3.0/cassandra/architecture/archDataDistributeVnodesUsing.html)

---

## 1. Cassandra가 안정 해시를 도입한 이유

- Cassandra는 페타바이트(PiB+) 규모의 데이터를 전 세계에 분산 저장해야 하는 요구사항에서 출발했다
- 단순한 `hash % N` 방식은 노드가 하나만 추가되거나 삭제돼도 거의 모든 키의 매핑이 바뀌기 때문에, 노드 추가·제거·장애 복구가 잦은 대규모 클러스터 환경에서는 사용하기 어렵다

Cassandra는 이 문제를 해결하기 위해 Amazon Dynamo 논문에서 제안한 **안정 해시(consistent hashing)** 를 핵심 파티셔닝 전략으로 채택했다

---

## 2. Token Ring 기반 파티셔닝

Cassandra는 모든 데이터를 **token ring** 위에 배치한다

- 각 노드는 링 위의 하나 이상의 **token 위치**를 가진다
- 데이터의 partition key를 해시하여 링 위의 위치를 결정한다
- 해당 위치에서 **시계 방향으로 가장 먼저 만나는 노드**가 해당 데이터의 primary replica가 된다

### 동작 예시 (RF=3, 노드 8개 클러스터)

1. partition key를 해시 → 링 위의 token 결정
2. 시계 방향으로 탐색하며 **서로 다른 물리 노드 3개**를 찾을 때까지 이동
3. 해당 3개 노드에 데이터를 복제 저장

이 방식에서 노드가 추가되거나 제거되어도 **인접한 token range의 데이터만 이동**하면 되므로, 전체 재배치가 필요 없다

---

## 3. Virtual Node(vnode) — 균등 분산을 위한 핵심 기법

### 기본 안정 해시의 한계

- 단순하게 물리 노드 하나당 토큰 하나만 배치하면, 노드 수가 적을 때 각 노드의 token range 크기가 불균등해진다
    - 예를 들어 8개 노드 클러스터에 9번째 노드를 추가하려면 기존 token 중간 지점 8곳에 동시에 삽입해야 링이 균형을 유지할 수 있는데, 이는 현실적으로 어렵다

### vnode 도입

Cassandra는 **하나의 물리 노드가 링 위의 여러 token 위치**를 갖도록 하는 vnode 방식을 도입했다

- 4개의 물리 노드가 각각 2개의 token을 가지면, 8개 노드처럼 동작한다
- 새 노드가 추가되면 **링의 여러 지점에서 조금씩** 데이터를 가져오므로 부하가 분산된다
- 노드가 제거될 때도 데이터가 **여러 이웃 노드에 균등하게** 분배된다
- 노드 장애 시 쿼리 부하도 특정 노드에 몰리지 않고 **여러 노드에 분산**된다

### Cassandra 버전별 vnode 처리 방식

| 버전 | 방식 | 기본 token 수 |
| --- | --- | --- |
| 2.x | 랜덤 token 배치 | 256개 (불균형 방지 목적) |
| 3.x 이상 | 결정론적(deterministic) token allocator | 더 적은 수로도 균형 유지 가능 |

Cassandra 3.x부터는 알고리즘이 최적의 token 위치를 자동으로 계산해 적은 수의 vnode로도 균등한 분산을 달성할 수 있게 됐다

### vnode의 트레이드오프

| 장점 | 단점 |
| --- | --- |
| 데이터 균등 분산 | token 수 증가 시 장애 조합의 경우의 수 증가 |
| 노드 추가/제거 시 부하 분산 | 클러스터 전체 maintenance 작업 속도 저하 |
| 장애 시 쿼리 부하 분산 | token range에 걸친 작업 성능 저하 가능 |

---

## 4. 복제 전략 (Replication Strategy)

Cassandra는 안정 해시로 파티션 위치를 결정한 뒤, 복제 전략에 따라 추가 노드에 데이터를 복제한다

- **RF (Replication Factor)**: 데이터를 몇 개 노드에 복제할지 결정. RF=3이면 3개 노드에 저장
- **SimpleStrategy**: 단순히 시계 방향으로 RF개 노드를 선택
- **NetworkTopologyStrategy**: 데이터센터와 랙을 고려해 복제본을 배치
    - 서로 다른 랙의 노드에 복제본을 분산시켜 랙 단위 장애에도 데이터 보호 가능

---

## 5. 장애 감지 — Gossip 프로토콜

Cassandra는 어느 노드가 살아있고 죽었는지를 파악하기 위해 **Gossip 프로토콜**을 사용

- 매 초마다 각 노드는 랜덤하게 다른 노드와 클러스터 상태 정보를 교환
- 각 노드는 **Phi Accrual Failure Detector**를 통해 독립적으로 이웃 노드의 생사를 판단
- 노드가 응답하지 않으면 해당 노드는 `DOWN`으로 판단되고, 쓰기는 **hinted handoff**로 대기
- 노드가 복구되면 대기 중이던 데이터를 전달받아 정상화됨

## 6. 실제 장애 시나리오 — 노드가 죽으면 어떻게 되는가?

### 시나리오: RF=3, 노드 6개 클러스터에서 노드 하나 장애 발생

**1단계 — 장애 감지**

- 각 노드는 매 초마다 Gossip 프로토콜로 서로 상태를 교환한다
- 특정 노드로부터 heartbeat가 일정 시간 이상 오지 않으면, Phi Accrual Failure Detector가 해당 노드를 `DOWN`으로 판단한다

**2단계 — 쓰기 요청 처리 (Hinted Handoff)**

- 장애 노드로 가야 할 쓰기 요청은 다른 살아있는 노드가 **힌트(hint)** 형태로 임시 보관한다
- 장애 노드가 복구되면, 보관하고 있던 힌트 데이터를 전달해 데이터를 동기화한다

**3단계 — 읽기 요청 처리**

- RF=3이므로 같은 데이터가 3개 노드에 복제되어 있다
- 노드 하나가 죽어도 나머지 2개 복제본으로 읽기 요청을 처리한다
- vnode 덕분에 쿼리 부하가 특정 노드에 몰리지 않고 여러 노드에 분산된다

**4단계 — 노드 복구 또는 교체**

- 복구 시: 이전 저장 상태에서 이어서 복구 가능
- 영구 장애 시: 죽은 노드를 제거하고, 해당 노드가 담당하던 token range를 인접 노드들이 나눠 담당한다
- 새 노드로 교체 시: 기존 노드 자리를 그대로 이어받아 데이터를 스트리밍받는다

> **핵심**: 단순 해시였다면 노드 하나 죽는 순간 전체 키 재매핑이 발생했겠지만,
> 
> 
> 안정 해시 + vnode 구조 덕분에 **인접 token range만 재조정되고 서비스는 계속 유지**된다
> 

---

## 7. Redis Cluster와의 비교 — 왜 같은 문제를 다르게 풀었는가?

Cassandra와 Redis Cluster는 둘 다 분산 환경에서 데이터를 나누는 문제를 풀지만, 접근 방식이 다르다

### Redis Cluster의 방식 — Hash Slot

Redis Cluster는 안정 해시를 사용하지 않고, 키 전체를 **16384개의 hash slot**으로 나누는 방식을 사용한다

 특정 키가 어느 슬롯에 속하는지는 `CRC16(key) % 16384`로 결정한다.

- 3개 노드라면 Node A: 슬롯 0~5460, Node B: 5461~10922, Node C: 10923~16383 식으로 범위를 나눈다
- 노드 추가/제거 시 슬롯 범위를 수동으로 재조정해야 한다

### Cassandra vs Redis Cluster 비교

| 구분 | Cassandra | Redis Cluster |
| --- | --- | --- |
| 파티셔닝 방식 | 안정 해시 + vnode | Hash Slot (16384개 고정 슬롯) |
| 노드 추가 시 | 자동으로 token 재분배 | 슬롯을 수동으로 이동해야 함 |
| 운영 복잡도 | vnode 수 조정 필요 | 슬롯 배치를 직접 제어 가능 |
| 데이터 분산 제어 | 알고리즘이 자동 결정 | 운영자가 슬롯 범위를 직접 지정 가능 |
| 주 용도 | 대규모 분산 DB, 높은 가용성 | 캐시 + 데이터 스토어 혼용 |

### 왜 Redis는 안정 해시를 안 썼을까?

- Redis는 캐시뿐 아니라 **데이터 스토어**로도 쓰이기 때문에, 특정 키가 항상 같은 노드에 있어야 한다는 요구사항이 강하다
- Hash Slot 방식은 운영자가 어떤 키를 어떤 노드에 둘지 **직접 제어**할 수 있어서 이 요구사항에 더 유리하다
- 반면 안정 해시는 알고리즘이 자동으로 결정하기 때문에 세밀한 제어가 어렵다

> **결론**: 둘 다 분산 데이터 문제를 풀지만,
> 
> 
> Cassandra는 **자동화된 균등 분산**을 우선시하고,
> 
> Redis Cluster는 **운영자의 직접 제어**를 우선시한다.
> 

---

## 8. 단순 해시 vs Cassandra 방식 비교

| 구분 | 단순 해시 `hash % N` | Cassandra (안정 해시 + vnode) |
| --- | --- | --- |
| 노드 변경 대응 | 거의 모든 매핑이 바뀜 | 인접 token range만 재배치 |
| 데이터 이동량 | 많음 | 적음 |
| 부하 분산 | 불균등 | vnode로 균등하게 분산 |
| 장애 대응 | 전체 영향 | 복제본으로 즉시 대응 |
| 확장성 | 낮음 | 선형적 수평 확장 가능 |