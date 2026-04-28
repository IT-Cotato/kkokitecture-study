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