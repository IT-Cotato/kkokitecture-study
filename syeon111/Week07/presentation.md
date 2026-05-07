# DynamoDB와 실제 장애 대응 사례

> 참고 자료
> 
> - [Amazon DynamoDB 공식 논문 (USENIX ATC 2022)](https://assets.amazon.science/33/9d/b77f13fe49a798ece85cf3f9be6d/amazon-dynamodb-a-scalable-predictably-performant-and-fully-managed-nosql-database-service.pdf)
> - [Dynamo 원본 논문 (SOSP 2007)](https://www.cs.cornell.edu/courses/cs5414/2017fa/papers/dynamo.pdf)
> - [Amazon DynamoDB 공식 논문 (USENIX ATC 2022](https://assets.amazon.science/33/9d/b77f13fe49a798ece85cf3f9be6d/amazon-dynamodb-a-scalable-predictably-performant-and-fully-managed-nosql-database-service.pdf)

---

## 1.  DynamoDB 주요 특징

- AWS에서 제공하는 완전 관리형 NoSQL 키-값 저장소
- 2007년 Amazon Dynamo 논문 → 2012년 DynamoDB로 공개 서비스화
- AWS 내부 시스템(EC2, Lambda, ECS 등)도 DynamoDB에 의존할 만큼 핵심 인프라

---

## 2. 데이터 파티셔닝

DynamoDB는 Partition Key를 해시하여 물리적 파티션을 결정한다. 중앙화된 파티션 메타데이터 서비스가 해시된 키를 올바른 스토리지 노드에 매핑하며, 파티션이 용량(10GB)이나 처리량 한계에 도달하면 자동으로 분할하여 재분배한다.

### 실제 문제: Hot Partition

- 특정 Partition Key에 트래픽이 집중되면 해당 파티션만 과부하 발생
- 예: 이커머스에서 특정 인기 상품 ID가 항상 같은 파티션에 몰리는 경우
- 해결 전략: Partition Key에 랜덤 suffix 추가 (`productId#1`, `productId#2`) → 트래픽 분산

---

## 3. 데이터 다중화

DynamoDB는 데이터를 3개의 서로 다른 AZ에 걸쳐 복제한다. 각 파티션은 하나의 Leader와 두 개의 Follower로 구성된 복제 그룹을 형성하며, 쓰기가 인정되려면 최소 2개 노드의 확인이 필요하다.

### 쓰기 경로

쓰기 요청이 들어오면 Leader가 WAL 레코드를 생성하고 다른 복제본에 전송한다. 복제본들의 쿼럼(2/3)이 로그 레코드를 로컬 WAL에 저장하면 완료된 것으로 간주된다.

---

## 4. 일관성

| 옵션 | 동작 방식 | 비용 |
| --- | --- | --- |
| Eventually Consistent Read | 아무 복제본에서 읽음, 낮은 레이턴시 | 기본 |
| Strongly Consistent Read | 반드시 Leader에서 읽음, 최신 데이터 보장 | 읽기 비용 2배 |
| Transactional Read/Write | ACID 보장 | 비용 2배 |

---

## 5. 장애 처리 및 실제 대응 사례

### 장애 감지

복제본이 다운되면 다른 복제본들이 장애를 감지하고 새로운 Leader를 선출하여 가용성 중단을 최소화한다.

### Hinted Handoff

장애 노드로 가야 할 데이터는 다른 임시 노드가 보관하며, 장애 노드가 복구되면 해당 데이터를 다시 전달하여 동기화한다. 단, 장애가 낮은 빈도로 일시적으로 발생할 때만 유효하다.

### Anti-Entropy

- Hinted Handoff만으로 해결 안 될 경우 복제본 간 데이터를 비교하여 최신 상태로 동기화
- 머클 트리로 불일치 구간만 탐지 후 전송량 최소화

### Log Replica — 빠른 장애 복구를 위한 장치

스토리지 노드가 장애를 일으키면 해당 노드에 복제본을 두고 있던 수천 개의 파티션이 동시에 2개의 복제본만 남은 상태가 된다. 이 위험한 상태를 빠르게 해소하기 위해 DynamoDB는 Log Replica를 도입했다. Log Replica는 B-Tree 없이 WAL만 보관하는 복제본으로, 전체 복제본보다 훨씬 빠르게 구성할 수 있어 쿼럼 유지에 즉시 활용된다. 그 사이 백그라운드에서 완전한 복제본을 새로 구성하여 정상 상태로 복구한다.

### 실제 장애 사례 — 2025년 10월 AWS US-EAST-1 대규모 장애

**배경**

2025년 10월 19~20일, AWS의 가장 큰 리전인 US-EAST-1에서 DynamoDB 장애를 시작으로 EC2, Lambda, ECS 등 수많은 서비스가 연쇄적으로 다운되는 대규모 장애가 발생했다.

**발생 원인**

DynamoDB DNS 항목을 관리하는 여러 DNS Enactor 중 하나가 지연되어 오래된 정보를 새 정보 위에 덮어씌웠고, 이를 DNS Planner가 stale 데이터로 판단해 삭제하면서 us-east-1의 모든 DNS 항목이 사라졌다. 코드나 설정 변경 없이 타이밍 문제 하나만으로 전체 장애가 발생한 사례다.

**장애 전파 흐름**

```
DNS Race Condition 발생
→ DynamoDB DNS 레코드 전체 삭제 (약 3시간)
→ EC2 인스턴스 상태 확인 불가 → 신규 생성 실패 (추가 12시간)
→ NLB 헬스체크 실패 → Lambda, ECS 등 연쇄 장애
```

**피해 규모**

Snapchat, Roblox, Reddit, Venmo 등이 영향을 받았으며 1700만 건 이상의 장애 보고가 접수됐고, 보험 손실 추정액은 최대 5억 8100만 달러에 달했다.

**AWS의 실제 대응 과정**

AWS는 먼저 DNS Planner와 DNS Enactor 자동화를 전 세계적으로 비활성화했다. 이후 엔지니어들이 수동으로 DNS 레코드를 복구하여 DynamoDB 서비스를 약 3시간 만에 정상화했다. 그러나 EC2와 NLB의 연쇄 장애는 추가 12시간이 지나서야 완전히 해소됐다.

**장기 개선 계획**

- Race Condition 수정 및 잘못된 DNS 플랜 적용 방지 safeguard 추가
- NLB의 velocity control 도입 — AZ failover 시 한 번에 과도한 용량이 제거되지 않도록 제한
- EC2 데이터 전파 시스템의 throttling 메커니즘 개선

**정리**

- Hinted Handoff, Anti-Entropy 등 데이터 복제 관련 장애 처리는 잘 동작했으나, 복제 경로 바깥의 DNS 자동화 버그 하나로 전체 서비스가 다운된 사례
- 완전 관리형 서비스도 내부 자동화 시스템의 결함에서 자유롭지 않음
- 단일 리전 의존 시 장애 발생 시 서비스 전체가 다운될 수 있음

---

## 6. 쓰기/읽기 경로

### 쓰기 경로

1. 요청이 Request Router를 통해 해당 파티션의 Leader로 전달
2. Leader가 WAL에 기록
3. Follower 복제본에 전파 → 쿼럼(2/3) 확인 후 완료 응답

### 읽기 경로

- **Eventually Consistent**: 아무 복제본에서 즉시 반환
- **Strongly Consistent**: 반드시 Leader에서 읽어 최신 데이터 보장

---

## 7. 다른 DB와의 차이점

| 구분 | DynamoDB | Redis | Cassandra |
| --- | --- | --- | --- |
| 파티셔닝 | 중앙화된 메타데이터 서비스로 관리 | Hash Slot 16384개 고정 | 안정 해시 + vnode |
| 일관성 설정 | Eventually / Strongly 옵션 선택 | 단일 노드 기준 강한 일관성 | W/R/N 세밀하게 조정 |
| 장애 처리 | 자동화, AWS가 완전 관리 | Sentinel/Cluster로 수동 구성 | 직접 구성 필요 |
| 운영 복잡도 | 낮음 (완전 관리형) | 중간 | 높음 |