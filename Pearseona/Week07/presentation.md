# 벡터 시계(Vector Clock)를 이용한 분산 환경 충돌 해결 전략

**참고 자료:**

> [Amazon Dynamo Whitepaper: "Dynamo: Amazon’s Highly Available Key-value Store"]
(https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf)

> [Basho Technologies: "Why Vector Clocks Are Hard"] 
(https://riak.com/posts/technical/vector-clocks-revisited-part-1/)

> [Cassandra Documentation: "Data Replication & Consistency Levels"] 
(https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html)

## 1. 분산 시스템의 딜레마: 데이터 충동

- **분산 환경의 특징**: 높은 가용성을 위해 데이터를 여러 노드에 복제(Replication)하지만, 네트워크 지연이나 장애로 인해 노드 간 데이터 상태가 달라지는 현상이 발생

- **전동적인 타임스탬프의 한계**: 물리적 시계는 동기화가 완벽하지 않아(Clock Skew), 어떤 업데이트가 먼저 일어났는지 정확히 판단하기 어려움

- **해결책**: 논리적인 인과 관계(Casual Relationship)를 추적하여 충돌을 감지하고 해소하는 벡터 시계 도입

## 2. 실전 구현 (Java & HashMap)

분산 노드들의 ID와 버전 카운터를 매핑하기 위해 `HashMap` 자료구조 활용

**[VectorClock 구조]**

- **데이터 구조**: `Map<String, Integer> versions` (Key: 서버 ID, Value: 버전 카운터)

- **인과 관계 판단**: 두 벡터 시계를 비교하여 한쪽이 다른 쪽의 모든 카운터보다 크거나 같으면 '선후 관계', 그렇지 않으면 '충돌(Concurrent)'로 간주

- **병합(Merge)**: 충돌 발생 시 각 노드의 최대 카운터 값을 취하여 새로운 버전을 생성

**[핵심 로직 분석]**

- **버전 증가**: 특정 서버에서 쓰기 발생 시 자신의 카운터를 +1

- **비교 로직**: `isAncestorOf(other)` 메서드를 통해 데이터의 선후 관계를 O(N) 시간 복잡도로 판단

- **충돌 해소**: 클라이언트가 충돌된 버전들을 읽어 병합한 뒤 서버에서 다시 기록

```
// 선후 관계 비교 구현부
public boolean isAncestorOf(VectorClock other) {
    for (String nodeId : versions.keySet()) {
        if (this.getCounter(nodeId) > other.getCounter(nodeId)) return false;
    }
    return true; // 모든 요소가 작거나 같으면 조상(이전 버전)임
}
```

## 3. 벡터 시계의 동작 프로세스

데이터가 노드 간 이동하며 버전이 업데이트되는 과정을 추적

1. **최초 생성 및 순차 업데이트**: 서버 `Sx`가 연이어 처리하여 버전이 `Sx=1`에서 `Sx=2`로 증가 (인과 관계 명확)

2. **분기(Branch) 발생**: `D2{Sx=2}` 상태에서 서버 `Sy`와 `Sz`가 각각 독립적으로 업데이트를 수행
    - D3: `{Sx=2, Sy=1}`
    - D4: `{Sx=2, Sz=1}` 

3. **충돌 감지**: `D3`와 `D4`는 서로의 변경 사항(`Sy=1` vs `Sz=1`)을 포함하지 않으므로 Concurrent(충돌) 상태로 판단

4. **최종 병합**: 클라이언트가 `max(D3, D4)`로 값을 합친 후, 서버 `Sx`가 기록하며 `{Sx=3, Sy=1, Sz=1}`로 최종 일관성 달성

## 4. 시뮬레이션 결과 

`VectorClock.java` 실행 시 도출되는 로그 데이터 분석

```
=== Vector Clock Conflict Detection Simulation ===

D1 (Sx writes): {Sx=1}
D2 (Sx updates D1): {Sx=2}
D3 (Sy updates D2): {Sx=2, Sy=1}
D4 (Sz updates D2): {Sx=2, Sz=1}

[!] Conflict Detected: D3 and D4 are siblings (Concurrent)!
D5 (Merged & Updated by Sx): {Sx=3, Sy=1, Sz=1}
```

**[결과 해석]**

- **Siblings(형제 버전) 확인**: `D3`와 `D4`가 공통 부모(D2)에서 갈라져 나와 충돌이 발생했음을 시스템이 정확히 인지함

- **데이터 유실 방지**: LWW(Last-Write-Wins) 방식이었다면 `Sy`나 `Sz` 중 하나의 업데이트가 사라졌겠지만, 벡터 시계는 두 서버의 기록을 모두 보존하여 병합함

## 5. 성능 및 한계점 검증

데이터 일관성과 저장 공간 사이의 트레이드오프 분석

| 비교 항목 | Last-Write-Wins (LWW) | 벡터 시계 (Vector Clock) |
|:---|:---|:---|
|**정확도**|낮음(최신 데이터 유실 가능)|높음(모든 변경 이력 추적)|
|**저장 비용**|매우 낮음(타임스탬프 1개)|낮음~중간(노드 수 비례 증가)|
|**복잡도**|단순함|중간(클라이언트 병합 로직 필요)|
|**적용 사례**|Cassandra, Redis|DynamoDB, Riak, Voldemort|

## 6. 결론

- 데이터 무결성 : 동시 수정 상황에서도 업데이트 유실 방지

- 논리적 인과성 : 물리적 시계의 한계를 극복한 정확한 선후 관계 정의

- 유연한 충돌 해소 " 애플리케이션 계층에서 비즈니스 로직에 맞는 병합 전략 수립 가능

<br><br>

#### VectorClock.java (벡터 시계 구현 코드)

```

import java.util.*;

/* 벡터 시계(Vector Clock) 실전 구현 클래스 */

public class VectorClock {

    // 벡터 시계 데이터 저장소: 서버ID -> 버전 카운터
    private final Map<String, Integer> versions = new HashMap<>();

    public VectorClock() {
    }

    // 기존 벡터 시계를 복사하여 생성
    public VectorClock(VectorClock other) {
        this.versions.putAll(other.versions);
    }

    /* 특정 노드의 버전 증가 */
    public void increment(String nodeId) {
        versions.put(nodeId, versions.getOrDefault(nodeId, 0) + 1);
    }

    /* 특정 노드의 카운터 조회 */
    public int getCounter(String nodeId) {
        return versions.getOrDefault(nodeId, 0);
    }

    /* 두 벡터 시계 간의 관계 확인 (조상 관계 여부) */
    public boolean isBefore(VectorClock other) {
        boolean atLeastOneSmaller = false;
        for (String nodeId : this.versions.keySet()) {
            if (this.getCounter(nodeId) > other.getCounter(nodeId))
                return false;
            if (this.getCounter(nodeId) < other.getCounter(nodeId))
                atLeastOneSmaller = true;
        }
        // 모든 항목이 작거나 같고, 최소 하나는 작아야 '엄격한 이전 버전'임
        return atLeastOneSmaller || this.versions.equals(other.versions);
    }

    /* 충돌 발생 시 두 벡터 시계를 병합 */
    public static VectorClock merge(VectorClock v1, VectorClock v2) {
        VectorClock merged = new VectorClock(v1);
        for (Map.Entry<String, Integer> entry : v2.versions.entrySet()) {
            int maxVal = Math.max(merged.getCounter(entry.getKey()), entry.getValue());
            merged.versions.put(entry.getKey(), maxVal);
        }
        return merged;
    }

    @Override
    public String toString() {
        return versions.toString();
    }

    /* 테스트 시나리오 */
    public static void main(String[] args) {
        System.out.println("=== Vector Clock Conflict Detection Simulation ===\n");

        // 1. 초기 버전 생성 (서버 Sx 처리)
        VectorClock d1 = new VectorClock();
        d1.increment("Sx");
        System.out.println("D1 (Sx writes): " + d1);

        // 2. Sx가 다시 업데이트
        VectorClock d2 = new VectorClock(d1);
        d2.increment("Sx");
        System.out.println("D2 (Sx updates D1): " + d2);

        // 3. 병렬 업데이트 발생 (Sy와 Sz가 각각 D2를 기반으로 업데이트)
        VectorClock d3 = new VectorClock(d2);
        d3.increment("Sy"); // Sy 처리

        VectorClock d4 = new VectorClock(d2);
        d4.increment("Sz"); // Sz 처리

        System.out.println("D3 (Sy updates D2): " + d3);
        System.out.println("D4 (Sz updates D2): " + d4);

        // 4. 충돌 감지 로직
        boolean d3BeforeD4 = d3.isBefore(d4);
        boolean d4BeforeD3 = d4.isBefore(d3);

        if (!d3BeforeD4 && !d4BeforeD3) {
            System.out.println("\n[!] Conflict Detected: D3 and D4 are siblings (Concurrent)!");

            // 5. 클라이언트 측에서 충돌 해소 및 병합
            VectorClock d5 = VectorClock.merge(d3, d4);
            d5.increment("Sx"); // 병합 결과를 다시 Sx가 기록
            System.out.println("D5 (Merged & Updated by Sx): " + d5);
        }
    }
}
```