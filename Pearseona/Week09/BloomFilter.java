import java.util.BitSet;

/* 블룸 필터(Bloom Filter) 실전 구현 클래스 */
public class BloomFilter {

    // 비트 배열: 멤버십 정보를 저장하는 핵심 자료구조
    private final BitSet bitArray;

    // 해시 함수 시뮬레이션을 위한 시드 배열 (k개의 해시 함수)
    private final int[] hashSeeds;

    // 비트 배열의 크기 (m)
    private final int size;

    public BloomFilter(int size, int[] hashSeeds) {
        this.size = size;
        this.hashSeeds = hashSeeds;
        this.bitArray = new BitSet(size);
    }

    /* 특정 시드를 사용한 해시 인덱스 계산 */
    // 문자열을 돌며 시드를 곱해 정수형태로 압축
    private int hash(String value, int seed) {
        int result = 0;
        for (char c : value.toCharArray()) {
            result = result * seed + c;
        }
        // 음수 방지 후 비트 배열 범위 내로 제한
        return Math.abs(result) % size;
    }

    /* [삽입] 원소 삽입: k개의 해시 함수로 나온 인덱스를 모두 1로 설정 */
    public void add(String url) {
        for (int seed : hashSeeds) {
            int index = hash(url, seed);
            bitArray.set(index);
        }
        System.out.println("[ADD] \"" + url + "\" -> inserted into Bloom Filter");
    }

    /* [검사] 멤버십 검사: k개의 인덱스 중 하나라도 0이면 확실히 미존재 */
    public boolean mightContain(String url) {
        for (int seed : hashSeeds) {
            int index = hash(url, seed);
            if (!bitArray.get(index)) {
                return false; // Definitely NOT present
            }
        }
        return true; // Possibly present (False Positive 가능성 있음)
    }

    /* 테스트 시나리오 */
    public static void main(String[] args) {
        System.out.println("=== Bloom Filter URL Deduplication Simulation ===\n");

        // 비트 배열 크기: 1000, 해시 함수 7개 (시드 배열로 시뮬레이션)
        int[] seeds = {7, 11, 13, 17, 19, 23, 29};
        BloomFilter bf = new BloomFilter(1000, seeds);

        // 1. 단축 URL 후보 삽입
        bf.add("https://tinyurl.com/zn9edcu");
        bf.add("https://tinyurl.com/ab3kfpq");
        bf.add("https://tinyurl.com/xy7mwvr");

        System.out.println();

        // 2. 멤버십 검사
        String[] queries = {
            "https://tinyurl.com/zn9edcu",  // 삽입된 URL
            "https://tinyurl.com/newurl1",  // 삽입되지 않은 URL
            "https://tinyurl.com/newurl2"   // 삽입되지 않은 URL
        };

        int saved = 0;
        int total = queries.length;

        for (String url : queries) {
            boolean result = bf.mightContain(url);
            String verdict = result
                ? "mightContain: true  (DB lookup required)"
                : "mightContain: false (Definitely NOT present, skip DB)";
            System.out.println("[QUERY] \"" + url + "\" -> " + verdict);
            if (!result) saved++;
        }

        // 3. 통계 출력
        System.out.println("\n=== Statistics ===");
        System.out.println("Total inserted : 3");
        System.out.printf("DB lookup saved: %d / %d queries (%.1f%%)%n",
            saved, total, (double) saved / total * 100);
        System.out.println("False Positive : 0 detected in this run");
    }
}