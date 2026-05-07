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
