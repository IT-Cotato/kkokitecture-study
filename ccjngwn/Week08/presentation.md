# 최신 분산 ID 생성기 비교 분석

## 왜 최신 서비스들은 UUIDv4를 버리기 시작했는가?

---

# 출처

- Twitter Engineering Blog - Announcing Snowflake  
  https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake

- Baeldung - Guide to Snowflake ID Generator  
  https://www.baeldung.com/java-snowflake

- RFC 9562 - UUIDv7  
  https://www.rfc-editor.org/rfc/rfc9562

- ULID 공식 문서  
  https://github.com/ulid/spec

- Segment KSUID 공식 저장소  
  https://github.com/segmentio/ksuid

- Sonyflake 공식 저장소  
  https://github.com/sony/sonyflake

---

# 1. UUIDv4는 왜 문제가 되었는가?

분산 시스템에서는 여러 서버가 동시에 데이터를 생성한다. 그래서 충돌 없이 유일한 ID를 만드는 것이 중요하다.

가장 널리 사용되던 방식 중 하나가 UUIDv4다. UUIDv4는 128비트 크기의 랜덤 기반 ID를 생성한다.

예시는 다음과 같다.

```text
550e8400-e29b-41d4-a716-446655440000
```

UUIDv4의 가장 큰 장점은 중앙 서버 없이도 각 서버가 독립적으로 ID를 만들 수 있다는 점이다. 하지만 서비스 규모가 커질수록 이 방식에서도 문제가 드러난다.

---

## 랜덤 기반 ID의 문제점

UUIDv4는 완전히 랜덤한 값이다. 그래서 먼저 생성된 ID가 나중에 생성된 ID보다 클 수도 있고, 반대로 작을 수도 있다.

이 특성은 데이터베이스 인덱스 성능 문제로 이어진다.

---

## B+Tree Page Split 문제

대부분의 관계형 데이터베이스는 기본 키 인덱스로 B+Tree를 사용한다.

Snowflake처럼 증가하는 ID는 다음과 같이 순서대로 insert가 발생한다.

```text
1 → 2 → 3 → 4
```

이 경우 데이터는 주로 마지막 페이지에 추가된다. 덕분에 디스크 locality가 좋아지고 캐시 효율도 높아진다.

반면 UUIDv4는 랜덤 값이기 때문에 중간 페이지에 insert가 발생할 가능성이 높다.

이렇게 되면 page split과 인덱스 재정렬이 발생하고, 디스크 I/O가 늘어나며 캐시 효율도 떨어진다. 서비스 규모가 커질수록 이 차이는 꽤 크게 벌어진다.

---

# 2. 정렬 가능한 ID의 필요성

실제 서비스에서는 단순히 유일한 ID를 만드는 것만으로는 부족하다. 최신 데이터 조회, 생성 순서 보장, 시간 기반 정렬, 인덱스 locality 유지 같은 요구사항도 함께 따라온다.

예를 들어 SNS 피드를 조회한다고 가정해보자.

```sql
SELECT *
FROM post
ORDER BY id DESC
LIMIT 20;
```

ID 자체가 시간 순서를 반영한다면 이런 조회를 훨씬 효율적으로 처리할 수 있다. 이 때문에 최근에는 정렬 가능한(sortable) ID 생성기가 중요하게 다뤄진다.

---

# 3. Snowflake

Twitter는 대규모 분산 환경에서 사용할 수 있는 ID 생성기인 Snowflake를 공개하였다.

Snowflake는 64비트 정수 기반 ID이며, 구조는 다음과 같다.

```text
timestamp | datacenterId | workerId | sequence
```

`timestamp`는 시간 정보를 나타내고, `datacenterId`는 데이터센터를 구분한다. `workerId`는 서버를 구분하는 값이며, `sequence`는 같은 millisecond 안에서 여러 ID가 생성될 때 증가하는 값이다.

조금 더 쪼개보면 보통 1비트 sign, 41비트 timestamp, 5비트 datacenterId, 5비트 workerId, 12비트 sequence로 구성한다.

```text
sign | timestamp(41) | datacenterId(5) | workerId(5) | sequence(12)
```

timestamp가 앞쪽에 있기 때문에 시간이 지날수록 ID 값도 대체로 커진다. 같은 millisecond 안에서 여러 ID를 만들어야 할 때는 sequence를 1씩 증가시켜 충돌을 피한다. 그래서 Snowflake는 중앙 DB의 auto_increment 없이도 여러 서버에서 숫자 ID를 만들 수 있다.

---

## Snowflake의 장점

Snowflake는 64비트 숫자 기반 ID라 저장과 비교가 빠르다. 또한 시간순 정렬이 가능하고 분산 환경에서도 사용할 수 있으며, 데이터베이스 인덱스 locality 측면에서도 유리하다.

---

## Snowflake의 한계

### Clock Rollback 문제

Snowflake는 현재 시간을 기반으로 ID를 생성한다. 그런데 서버 시간은 항상 정확하게 앞으로만 흐르지 않는다.

예를 들어 NTP 시간 보정, VM migration, container restart 같은 상황에서는 시간이 뒤로 밀릴 수 있다.

```text
12:00:05.500
→ 12:00:05.300
```

이 경우 이전보다 작은 ID가 생성될 수 있고, sequence 충돌이나 ID 중복 위험도 생긴다.

---

### Worker ID 관리 문제

Snowflake는 서버마다 고유한 workerId가 필요하다.

예시는 다음과 같다.

| 서버     | workerId |
| -------- | -------- |
| server-1 | 1        |
| server-2 | 2        |

하지만 최근 클라우드 환경에서는 Auto Scaling, Kubernetes, Container orchestration 등으로 서버가 동적으로 생성되고 삭제된다. 이때 같은 workerId가 중복 할당되면 ID 충돌이 발생할 수 있다.

그래서 실무에서는 ZooKeeper, Redis, Etcd 등을 이용해 workerId를 중앙에서 관리하기도 한다.

---

# 4. UUIDv7

최근 주목받는 ID 생성 방식 중 하나가 UUIDv7이다.

UUIDv7은 기존 UUID의 장점을 유지하면서 시간 기반 정렬 기능을 추가한 방식이다. UUIDv4가 랜덤 기반이라 정렬이 어렵다면, UUIDv7은 timestamp를 기반으로 하기 때문에 생성 시간 순서대로 정렬할 수 있다.

예시는 다음과 같다.

```text
01890f6e-2c9f-7cc3-98c4-dc0c0c07398f
```

UUID 형태는 기존 UUID와 똑같이 8-4-4-4-12 자리의 hexadecimal 문자열이다. 중간의 `7`이 version 값을 나타내기 때문에 UUIDv7이라는 것을 알 수 있다.

조금 더 구체적으로 보면 UUIDv7은 앞쪽 48비트에 Unix timestamp를 millisecond 단위로 넣는다. 그리고 나머지 영역에는 version, variant 정보와 함께 랜덤 값이 들어간다.

```text
timestamp(ms) | version | random/counter | variant | random
```

즉 UUIDv7은 전체가 완전히 랜덤하게 만들어지는 것이 아니라, 시간 정보를 앞에 두고 뒤쪽의 랜덤 값으로 충돌 가능성을 낮추는 방식이다. 그래서 서로 다른 millisecond에 생성된 UUID는 앞부분 timestamp 값이 달라지고, 이 덕분에 UUID 전체를 정렬했을 때 생성 시간 순서와 거의 맞아떨어진다.

다만 같은 millisecond 안에서 여러 개를 생성하는 경우에는 timestamp가 같기 때문에 뒤쪽 값으로 순서가 결정된다. 단순 구현에서는 이 부분이 랜덤이라 같은 millisecond 내부의 생성 순서까지 완전히 보장되지는 않는다. 그래서 일부 구현체는 랜덤 영역 일부를 counter나 sub-millisecond timestamp로 사용해 같은 millisecond 안에서도 단조 증가하도록 만든다.

---

## UUIDv7의 특징

UUIDv7은 UUID 표준 형식을 유지하면서도 시간순 정렬이 가능하다. 서버 간 조율 없이 분산 생성할 수 있고, DB index locality도 UUIDv4보다 개선된다.

즉 다음 두 가지 장점을 결합한 형태라고 볼 수 있다.

```text
UUID의 범용성 +
Snowflake의 정렬 가능성
```

최근 여러 서비스와 라이브러리들이 UUIDv7 지원을 추가하고 있다.

---

# 5. ULID

ULID(Universally Unique Lexicographically Sortable Identifier)는 UUID의 대안으로 등장한 ID 생성 방식이다.

예시는 다음과 같다.

```text
01ARZ3NDEKTSV4RRFFQ69G5FAV
```

ULID는 시간순 정렬이 가능하고 문자열 기반이며, URL-safe한 형태로 사용할 수 있다. UUID보다 사람이 읽기에도 상대적으로 편하다.

ULID는 128비트 ID이고, 앞쪽 48비트는 millisecond 단위 timestamp, 뒤쪽 80비트는 randomness로 구성된다.

```text
timestamp(48) | randomness(80)
```

문자열로 표현하면 총 26자이며, 앞의 10자가 timestamp를 담고 뒤의 16자가 random 값을 담는다. 그래서 문자열을 사전순으로 정렬해도 시간순 정렬과 거의 맞아떨어진다.

---

## ULID의 장점

ULID는 UUID보다 상대적으로 읽기 쉽다. timestamp 기반이기 때문에 생성 순서대로 정렬할 수 있고, Base32 기반 문자열을 사용하므로 웹 환경에서도 다루기 편하다.

---

## ULID의 단점

ULID는 문자열 기반이므로 숫자 기반 PK보다 크기가 크고, 저장 공간이 더 필요할 수 있다. 또한 같은 millisecond 안에서 대량으로 ID를 생성해야 한다면 monotonic 처리를 별도로 구현해야 한다.

---

# 6. KSUID

KSUID(K-Sortable Unique Identifier)는 Segment에서 개발한 ID 생성 방식이다.

KSUID는 timestamp를 포함하므로 정렬이 가능하고, 글로벌 분산 환경에서 사용할 수 있도록 설계되었다.

예시는 다음과 같다.

```text
0ujsswThIGTUYm2K8FjOOfXtY1K
```

KSUID는 20바이트, 즉 160비트 크기의 ID다. 앞쪽 32비트에는 timestamp가 들어가고, 뒤쪽 128비트에는 랜덤 payload가 들어간다.

```text
timestamp(32) | random payload(128)
```

ULID나 UUIDv7이 millisecond 단위 timestamp를 쓰는 것과 달리, KSUID는 second 단위 timestamp를 사용한다. 대신 random payload가 128비트로 크기 때문에 충돌 가능성을 낮추는 데 더 많은 공간을 쓴다. 문자열 표현은 27자의 Base62 인코딩을 사용한다.

이 방식은 로그 시스템, 이벤트 스트리밍, 글로벌 서비스 같은 환경에서 자주 언급된다.

---

# 7. Sonyflake

Sonyflake는 Snowflake의 변형 구현체이다.

Sony는 Snowflake의 일부 문제를 개선하기 위해 Sonyflake를 개발하였다. Sonyflake는 machine ID 방식을 개선했고 AWS 환경을 고려했으며, 더 긴 수명을 지원한다.

기본 구조는 다음과 같다.

```text
time(39) | sequence(8) | machineId(16)
```

Sonyflake는 기본적으로 10ms 단위의 시간을 사용한다. sequence는 같은 10ms 안에서 여러 ID가 생성될 때 증가하고, machineId는 ID를 생성한 머신을 구분한다.

Snowflake와 비교하면 sequence 비트 수는 줄이고 machineId에 더 많은 비트를 준다. 그래서 한 머신이 아주 짧은 시간에 만들 수 있는 ID 수는 Snowflake보다 적지만, 더 많은 머신을 구분할 수 있고 기본 설정 기준 수명도 더 길다.

---

# 8. 실제 구현 방식

Spring Boot/JPA에서 auto_increment를 사용할 때는 보통 다음과 같이 작성한다.

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

이 방식은 DB가 insert 시점에 ID를 만들어준다.

```text
애플리케이션 → INSERT 요청
DB → auto_increment로 id 생성
DB → 저장
```

하지만 Snowflake, UUIDv7, ULID, KSUID 같은 분산 ID 생성기는 보통 애플리케이션이 insert 전에 ID를 먼저 만든다.

```text
애플리케이션 → id 생성
애플리케이션 → INSERT 요청
DB → 전달받은 id 그대로 저장
```

그래서 `@GeneratedValue`를 사용하지 않고, 저장 전에 `@Id` 필드에 값을 직접 채워 넣는다.

예를 들어 Snowflake처럼 숫자 ID를 사용한다면 다음과 같이 작성할 수 있다.

```java
@Entity
public class Post {

    @Id
    private Long id;

    private String title;

    @PrePersist
    void assignId() {
        if (this.id == null) {
            this.id = SnowflakeIdGenerator.nextId();
        }
    }
}
```

ULID처럼 문자열 ID를 사용한다면 다음과 같이 저장할 수 있다.

```java
@Entity
public class Post {

    @Id
    @Column(length = 26)
    private String id;

    private String title;

    @PrePersist
    void assignId() {
        if (this.id == null) {
            this.id = UlidCreator.getUlid().toString();
        }
    }
}
```

이렇게 하면 서비스 코드에서는 ID를 직접 세팅하지 않아도 된다.

```java
Post post = new Post();
post.setTitle("hello");

postRepository.save(post);
```

흐름만 보면 `AUTO_INCREMENT`와 비슷해 보이지만, 실제로는 DB가 ID를 만드는 것이 아니라 JPA 엔티티가 저장되기 직전에 ID를 생성하는 구조다.

ID 방식에 따라 DB 컬럼 타입도 달라진다.

| 방식      | Java 타입        | DB 컬럼 예시               |
| --------- | ---------------- | -------------------------- |
| Snowflake | Long             | BIGINT                     |
| UUIDv7    | UUID 또는 String | UUID, BINARY(16), CHAR(36) |
| ULID      | String           | CHAR(26)                   |
| KSUID     | String           | CHAR(27)                   |

Snowflake를 직접 구현할 때는 bit shift 연산을 이용하여 ID를 조합한다.

예시는 다음과 같다.

```java
long id =
    ((timestamp - epoch) << 22)
    | (datacenterId << 17)
    | (workerId << 12)
    | sequence;
```

이 방식은 연산이 매우 빠르고 메모리 사용량이 적다. 정수 기반으로 처리할 수 있다는 점도 장점이다.

---

## Sequence Overflow 처리

같은 millisecond에 너무 많은 요청이 들어오면 sequence overflow가 발생할 수 있다.

예를 들어 12비트 sequence라면 다음과 같다.

```text
2^12 = 4096
```

즉 1ms당 최대 4096개까지 ID를 생성할 수 있다.

이 값을 초과하면 보통 다음 millisecond까지 기다리거나 busy waiting을 수행하는 방식으로 처리한다.

---

# 9. 어떤 ID 생성기를 선택해야 하는가?

각 방식은 저마다 trade-off가 있다.

| 방식      | 장점                  | 단점                         |
| --------- | --------------------- | ---------------------------- |
| UUIDv4    | 단순, 범용적          | 정렬 불가능, DB 성능 문제    |
| Snowflake | 빠름, 정렬 가능       | clock 문제, worker 관리 필요 |
| UUIDv7    | UUID 호환 + 정렬 가능 | 아직 상대적으로 신규         |
| ULID      | 가독성 좋음           | 문자열 크기 증가             |
| KSUID     | 글로벌 서비스 친화적  | 상대적으로 덜 보편적         |

---

# 10. 결론

초기의 분산 시스템에서는 단순히 유일한 ID를 생성하는 것 자체가 중요했다. 하지만 최근에는 데이터베이스 성능, 정렬 가능성, 분산 환경 운영, 클라우드 환경까지 함께 고려해야 한다.

이런 흐름 때문에 UUIDv7, ULID, KSUID처럼 정렬 가능한 ID 생성기가 주목받고 있다.

결국 ID 생성 전략도 단순한 구현 문제가 아니라 분산 시스템 설계의 일부라고 볼 수 있다.
