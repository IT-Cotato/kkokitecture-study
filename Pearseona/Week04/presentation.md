# 안정 해시(Consistent Hasing) 설계와 가상 노드를 통한 최적화

## 1. 왜 안정 해시인가?

- **전통적인 해시 방식의 한계** : `serverIndex = hash(key) % N` 방식은 서버 개수(N)가 변할 때 대부분의 키가 재배치되는 대규모 캐시 미스를 유발
- **해결책** : 서버의 추가/삭제 시에도 오직 k/n개의 키만 재배치되는 **안정 해시** 도입

## 2. 실전 구현 (Java & TreeMap)
> 실제 코드로 구현하기 위해 `TreeMap` 자료구조를 활용

**[TreeMap]**
- **이진 탐색 트리(Red-Black Tree)** 기반의 Map 구현체
- 가장 큰 특징은 키(Key)를 기준으로 데이터가 항상 오름차순으로 정렬
- 안정 해시의 링 위에서 특정 지점보다 "시계 방향으로 가까운" 노드를 찾기에 최적화

**[핵심 코드 로직 분석]**
- **데이터 구조** : `TreeMap<Long, String> ring`을 사용하여 해시값을 정렬된 상태로 보관
- **시계 방향 탐색** : `ring.tailMap(hash)` 메서드를 통해 특정 해시값보다 크거나 같은 위치에 있는 서버를 O(log N) 시간 복잡도로 조회
- **링 연결** : `tailMap`이 비어있을 경우 `ring.firstKey()` 를 호출하여 원형 구조를 완성

```
// 시계 방향 탐색 구현부
SortedMap<Long, String> tailMap = ring.tailMap(hash);
long targetHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
return ring.get(targetHash);
```

## 3. 가상 노드(Virtual Node)의 필요성
> 기본적인 안정 해시는 서버의 위치가 불균등할 경우 특정 서버에 부하가 쏠리는 Hotspot 문제가 발생

**[가상 노드(Virtual Node)의 필요성]**
- 실제 서버 하나를 링 위의 여러 지점(가상 노드)에 복제하여 배치

- 서버 1대당 가상 노드 수를 늘릴수록 해시 링의 파티션이 잘게 쪼개져 데이터가 더욱 균등하게 분배


## 4. 실험 결과 및 데이터 검증
> 작성한 코드를 통해 가상 노드 수에 따른 데이터 분산율을 측정 (데이터 10만 개 기준)

|가상 노드 수|Server-A 할당량|Server-B 할당량|Server-C 할당량|표준 편차/분포|
|:---|:---|:---|:---|:---|
|1개|24.5%|61.2%|14.3%|**매우 불균형**|
|100개|30.82%|34.04%|35.14%|**균형**|
|500개|33.1%|33.5%|33.4%|**매우 균등(이론값 수렴)**|

=> 가상 노드 수가 증가할 수록 데이터의 표준 편차가 작아지며, 수평적 규모 확장(Scalability)에 최적화된 상태가 됨을 확인

## 결론
- **유연한 확장성** : 서버 추가/삭제 시 데이터 이동 최소화
- **부하 분산**: 가상 노드를 통해 데이터 치우침 현상 방지
- **Hotspot 완화**: 특정 서버에 대한 과부하 위험 감소


### ConsistentHasher.java (안정 해시 구현 코드)

```
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 안정 해시(Consistent Hashing) 실전 구현 클래스
 */

public class ConsistentHasher {

    // 해시 링(Hash Ring): 해시값(Long)을 기준으로 서버 이름(String)을 정렬하여 저장
    // TreeMap은 내부적으로 Red-Black Tree를 사용하여 정렬된 상태를 유지하며 탐색(O(log N))에 최적화됨
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodeCount; // 서버당 생성할 가상 노드 개수

    public ConsistentHasher(int virtualNodeCount, List<String> servers) {
        this.virtualNodeCount = virtualNodeCount; 
        for(String server: servers) {
            addServer(server);
        }
    }

    
    // SHA-256 알고리즘을 사용하여 문자열을 64비트 Long 해시값으로 변환
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes());

            // 해시 바이트 배열 중 앞 8바이트를 추출하여 long 정수로 변환 (Big-endian)
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h <<= 8;
                h |= (digest[i] & 0xFF);
            }
            return h;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("해시 알고리즘을 찾을 수 없습니다.", e);
        }
    }

    /* 서버를 해시 링에 추가 */
    // 한 대의 실제 서버를 여러 개의 가상 노드로 복제하여 링 위에 골고루 배치
    public void addServer(String server) {
        for (int i =0; i < virtualNodeCount; i++) {
            // 서버 이름 뒤에 인덱스를 붙여 서로 다른 해시 위치를 갖게 함 (예: Server-A#VN0, Server-A#VN1...)
            long hashValue = hash(server + "#VN" + i);
            ring.put(hash(server + "-" + i), server);
        }
    }
    
    /* 서버를 해시 링에서 제거 */
    // 해당 서버가 가졌던 모든 가상 노드를 삭제
    public void removeServer(String server) {
        for (int i = 0; i < virtualNodeCount; i++) {
            long hashValue = hash(server + "#VN" + i);
            ring.remove(hashValue);
        }
    }

    /* 특정 키(데이터)가 저장될 서버를 조회 */
    // 시계 방향 탐색 알고리즘 적용
    public String getServer(String key) {
        if (ring.isEmpty()) return null;

        // 1. 키의 해시값을 계산
        long hash = hash(key);

        // 2. tailMap: 링에서 'hash'값보다 크거나 같은 값들만 모은 부분 집합(시계 방향)을 가져옴
        SortedMap<Long, String> tailMap = ring.tailMap(hash);

        // 3. 시계 방향으로 가장 가까운 첫 번째 서버 노드를 선택
        // 만약 tailMap이 비어있다면 링의 마지막을 지난 것이므로 다시 링의 시작(firstKey)으로 돌아감 (Circular 구조 구현)
        long targetHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();

        return ring.get(targetHash);
    }

    /* 실행 및 시각적 통계 확인을 위한 메인 함수 */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        // 테스트용 서버 리스트
        List<String> servers = Arrays.asList("Server-A", "Server-B", "Server-C");

        while(true) {
            System.out.println("\n==============================================");
            System.out.println("테스트할 가상 노드 개수를 입력하세요 (종료: 0): ");
            int virtualNodes = scanner.nextInt();

            if (virtualNodes == 0) {
                System.out.println("프로그램을 종료합니다.");
                break;
            }
        
            // 안정 해시 인스턴스 생성
            ConsistentHasher hasher = new ConsistentHasher(virtualNodes, servers);
            Map<String, Integer> distribution = new HashMap<>();
            int totalKeys = 100000; // 테스트용 데이터(키) 개수

            // 10만 개의 테이터를 서버에 분배 시뮬레이션
            for (int i = 0; i < totalKeys; i++) {
                String server = hasher.getServer("Key-" + i);
                distribution.put(server, distribution.getOrDefault(server, 0) + 1);
            }

            // 결과 출력: 가상 노드 수에 따른 분산 결과 확인
            System.out.println("\n>>> 결과 (서버: " + servers.size() + "대, 가상노드: " + virtualNodes + "개)");
            for (String server : servers) {
                int count = distribution.getOrDefault(server, 0);
                double percent = (count / (double) totalKeys) * 100;
                System.out.printf("[%s] 할당량: %d개 (%.2f%%)\n", server, count, percent);
            }
        }
        scanner.close();
    }
}
```