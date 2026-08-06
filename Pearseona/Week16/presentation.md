# 청크 분할 전략(CDC, Content-Defined Chunking)

**참고 자료:**

> [FastCDC: A Fast and Efficient Content-Defined Chunking Approach for Data Deduplication - USENIX ATC '16](https://www.usenix.org/conference/atc16/technical-sessions/presentation/xia)

> [Fingerprinting by Random Polynomials - Michael O. Rabin (1981)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9355116)

> [Splitting Data with Content-Defined Chunking - Gopher Academy Blog](https://blog.gopheracademy.com/advent-2018/split-data-with-cdc/)

> [Content-Defined Chunking - EmergentMind](https://www.emergentmind.com/topics/content-defined-chunking-cdc)

> [Breaking and Fixing Content-Defined Chunking - Kien's Personal Blog](https://blog.ktruong.dev/breaking-cdc/)

> [How to Create Backup Deduplication - oneuptime](https://oneuptime.com/blog/post/2026-01-30-backup-deduplication/view)

> [Xet Chunk-Level Deduplication Specification - Hugging Face](https://huggingface.co/docs/xet/en/deduplication)

---

## 1. 문제 정의: 파일을 어떻게 나누어야 "의미 있게" 중복을 찾을 수 있는가

- 대용량 파일 동기화나, 백업, 중복 제거 시스템들이 공통적으로 깔고 가는 전제
    - "파일 전체를 통째로 비교하지 말고, 작은 단위(청크)로 쪼개서 비교하자"
- 진짜 문제: **그 작은 단위를 어디서 자르느냐**
- 절단 기준을 잘못 잡으면?
    - 내용이 거의 안 바뀌었는데도 "완전히 다른 파일"로 인식
- 절단 기준 설계에 따른 두 가지 접근
    - **고정 크기 분할 (Fixed-size Chunking)**: 절대 오프셋 기준으로 일정 크기씩 자름
    - **내용 기반 분할 (Content-Defined Chunking, CDC)**: 데이터 내용 자체가 절단 지점을 결정

---

## 2. 고정 크기 분할의 치명적 결함: 바이트 시프트 문제

가장 직관적인 구현:

```python
def fixed_chunk(data: bytes, chunk_size: int = 8192) -> list[bytes]:
    chunks = []
    for i in range(0, len(data), chunk_size):
        chunks.append(data[i:i + chunk_size])
    return chunks
```
- 설명: 파일을 앞에서부터 8킬로바이트씩 그냥 잘라내는 코드.

    for 문을 돌면서 인덱스를 8192씩 증가시키고, 그 구간을 잘라서 리스트에 담는 방식
- 장점: 구현 간단, 계산 빠름
- 치명적 단점: **바이트 시프트 문제(byte-shifting problem)**
    - <cite index="12-1">한 바이트만 추가해도 이후 모든 청크가 전부 달라 보임</cite>
    - <cite index="12-1">데이터 스트림은 거의 안 바뀌었는데도 저장 공간 절약 효과가 나빠짐</cite>

**허깅페이스 예시로 보는 문제**

허깅페이스의 청크 중복제거 문서에 나온 예시
- 원본: `AAAAAA|BBBBBBBB|CCCCCCCC|DDDDDDDD|EEEEEEEE` (5개 청크)
- 세 번째 청크(C로 이루어진 청크)안에 두 바이트(XX) 삽입 시
    - 콘텐츠 기반 청킹 → <cite index="15-1">청크0·1·3·4 그대로 유지, 세 번째 청크만 변경</cite>
    - 고정 크기 청킹 → <cite index="15-1">삽입 지점 이후 모든 경계가 밀려서 전체가 새 청크로 인식</cite>

**오래전부터 알려진 문제**

- <cite index="14-1">패킷 앞부분에 소수 바이트만 삽입/삭제돼도 고정 크기 청킹은 제대로 동작 안 함</cite>
- 이를 **경계 이동 문제(boundary-shift problem)**라고 부름
- 결과: 문서 맨 앞줄에 제목 한 줄만 추가해도
    - → 뒤의 모든 청크가 "새 청크"로 취급
    - → 델타 동기화나 중복 제거 기능이 무력화

---

## 3. Rabin Fingerprint: 내용 스스로 경계를 정하게 하기

- CDC의 핵심 아이디어
    - 청크 경계를 절대 오프셋이 아니라 **주변 실제 바이트 내용**으로부터 결정
- 대표 도구: **Rabin Fingerprint** (롤링 해시, rolling hash)
    - <cite index="9-1">바이트 시퀀스의 다항식 표현을 기약다항식으로 나눈 나머지로 정의</cite>
    - <cite index="9-1">서로 다른 두 시퀀스가 같은 핑거프린트를 가질 확률이 매우 낮음</cite>

**동작 방식 3단계**

1. 파일 위로 고정 크기, 보통 40~64바이트 정도 되는 **슬라이딩 윈도우** 를 이동시키면서
    - 그 윈도우 안 바이트들의 롤링 해시(핑거프린트) 계산
2. <cite index="9-1">최하위 13비트가 미리 정한 값과 일치 → 그 지점을 청크 경계로 선언</cite>
3. <cite index="9-1">최소·최대 크기 설정 (예: 4KB / 16KB)으로 지나친 크기 방지</cite>

**왜 바이트 삽입에 강한가 — "롤링"의 특성**

- 해시를 매번 처음부터 계산 X
- 한 바이트가 새로 들어오고 한 바이트가 나갈 때 상수시간(O(1))로 갱신
- → 경계 결정 조건이 "절대 위치"가 아니라 "로컬 내용"에만 의존
- → 삽입해도 그 주변 청크 한두 개만 변경, 나머지는 그대로
- 이 성질 = **지역성(locality)**

=> 개념적으로 정리하면 아래의 코드와 같음

```python
# Rabin 스타일 CDC의 개념적 의사코드
WINDOW = 48          # 슬라이딩 윈도우 크기 (byte)
MASK   = 0x1FFF      # 최하위 13비트 검사 → 평균 청크 크기 ≈ 8KB
MIN_SIZE, MAX_SIZE = 4096, 16384

def cdc_chunk(data: bytes) -> list[bytes]:
    chunks, start, fp = [], 0, RollingHash(WINDOW)
    for i, byte in enumerate(data):
        fp.roll(byte)  # O(1)로 윈도우 핑거프린트 갱신
        size = i - start
        if size < MIN_SIZE:
            continue
        if (fp.value() & MASK) == 0 or size >= MAX_SIZE:
            chunks.append(data[start:i + 1])
            start = i + 1
    chunks.append(data[start:])
    return chunks
```

- 코드설명: 윈도우 크기 48바이트, 마스크0x1FFF로 최하위 13비트를 검사해서 평균 청크 크기가 대략 8킬로바이트가 되게 만들고, 최소4096바이트, 최대 16384바이트로 제한을 둠

    한 바이트씩 읽으면서 롤링 해시를 O(1)로 갱신하고, 조건을 만족하거나 최대 크기에 도달하면 그 지점에서 청크를 잘라냄

**실제 사례**

- <cite index="10-1">restic 백업 도구 개발 전 조사 과정에서 라빈의 1981년 논문 발견</cite>
    - <cite index="10-1">롤링 해시로 이동하는 윈도우의 핑거프린트를 효율적으로 계산</cite>
- <cite index="10-1">rsync</cite>: 다른 롤링 해시 사용, 수신 측에 이미 있는 부분을 감지해 효율적 전송
- <cite index="10-1">LBFS (Low Bandwidth Network File System)</cite>: Rabin 핑거프린트 기반 CDC로 필요한 청크만 전송

---

## 4. 고정 크기 분할 vs Rabin CDC 비교

|| 고정 크기 청킹 | Rabin CDC |
|:---|:---|:---|
| **경계 결정 기준** | 파일 오프셋 (절대 위치) | 슬라이딩 윈도우 내용 (상대적 패턴) |
| **삽입/삭제에 대한 반응** | 삽입 지점 이후 전체 경계가 밀림 | 삽입 지점 주변 청크만 변경, 나머지는 그대로 |
| **속도** | 매우 빠름 (단순 슬라이싱) | 상대적으로 느림 — 바이트마다 해시 갱신 필요 |
| **중복 제거 효율** | 낮음 (사소한 수정에도 전체 무효화) | 높음 (변경 없는 구간은 그대로 재사용) |
| **청크 크기 분포** | 완전히 균일 | 가변적 — 최소/최대 크기로만 제어 가능 |

---

## 5. Rabin CDC의 한계와 그 이후의 발전 — Gear, FastCDC

- <cite index="6-1">Rabin 기반 청킹은 느리다는 비판을 자주 받음</cite>
    - → 더 효율적인 롤링 해시 함수 개발로 이어짐
- 그 흐름에서 등장
    - **Gear 해시**
    - **FastCDC** (2016년 USENIX ATC 발표, Gear를 더 다듬음)

**FastCDC의 세 가지 기법**

- <cite index="3-1">해시 판정 로직 단순화·개선</cite>
- <cite index="3-1">최소 크기 미만 구간의 절단 지점 탐색 스킵 → 속도 향상</cite>
- <cite index="3-1">청크 크기 분포를 특정 구간으로 정규화 → 스킵으로 인한 중복 제거 비율 저하 보완</cite>

**결과**

- <cite index="3-1">오픈소스 Rabin 기반 CDC 중 가장 빠른 것보다 약 10배 빠름</cite>
- <cite index="3-1">Gear·AE 기반 CDC보다 약 3배 빠름</cite>
- <cite index="3-1">그러면서도 고전적 Rabin 방식과 거의 같은 중복 제거 비율 유지</cite>

**속도의 대가**

- Rabin 핑거프린트 기반 CDC
    - <cite index="4-1">엄격한 지역성(strict locality) 보장 — 편집 지점에서 먼 내용은 항상 동일하게 청크로 나뉨</cite>
    - <cite index="4-1">단, 청크 크기는 목표 범위가 보장이 아니라 기댓값 — 적대적 입력에선 극단적 크기 가능</cite>
- Gear·FastCDC (앵커 기반)
    - <cite index="4-1">목표 크기에 가깝게 근사, 처리량 3~10배 개선</cite>
    - <cite index="4-1">공식적인 지역성 보장 없음 — 한 바이트만 바뀌어도 이후 경계 전부 이동 가능</cite>

→ 정리: "속도"와 "삽입/삭제에도 경계가 안정적으로 유지되는가"는 트레이드오프
→ 이 트레이드오프를 어디서 타협하느냐가 CDC 알고리즘들 사이의 실질적 차이

---

## 6. 실제 시스템 설계에서 고려해야 할 파라미터와 지점

- **평균 청크 크기 선택**
    - 너무 작음 → 인덱스 레코드 수 폭증 → 조회 성능 저하
    - 너무 큼 → 델타 동기화·중복 제거 효율 저하
    - <cite index="5-1">LBFS를 따라 Rabin 기반 CDC는 보통 기대 평균 청크 크기 8KB로 설정</cite>

- **최소/최대 청크 크기 경계**
    - 최소 크기 없음 → 조건 충족 지점 연속 → 지나치게 작은 청크 대량 생성
    - 최대 크기 없음 → 조건 충족 지점이 안 나타남 → 청크 무한 증가
    - → 두 값 모두 강제 절단 기준으로 함께 설정

- **청크 경계와 암호화의 순서**
    - 청크 단위 암호화 시 (청크마다 별도 키/IV)
    - → 같은 평문 청크도 암호문은 서로 다름
    - → "같은 청크인지" 비교 불가능
    - → 해시 계산은 반드시 **암호화 이전 단계**에서 수행

- **가변 청크와 인덱싱 비용**
    - 고정 크기: "몇 번째 청크 = 몇 바이트째" 상수 시간 계산 가능
    - 가변 크기: 청크 순서 + 각 청크의 오프셋을 별도로 저장해야 함

- **적대적 입력에 대한 방어**
    - Rabin 방식조차 조작된 입력에서는 극단적 청크 크기 가능
    - → 최소/최대 크기 강제 + 정규화된 청킹(normalized chunking) 병행

---

## 7. 결론

1. **청킹은 "얼마나 잘게 자르는가"가 아니라 "무엇을 기준으로 자르는가"의 문제**
    - 고정 크기 청킹 → 절대 위치 기준 → 삽입·삭제 한 번에 전체 무효화
    - CDC → 슬라이딩 윈도우 내용 기준 → 국소적 수정이 국소적 변화로만 이어짐

2. **속도와 안정성은 트레이드오프**
    - Rabin CDC: 엄격한 지역성 보장, 느림
    - Gear·FastCDC: 훨씬 빠름, 지역성 보장 일부 포기
    - "어떤 CDC를 쓸 것인가" = 정확도 vs CPU 비용 사이의 설계 결정

3. **청킹 방식의 선택은 뒤따르는 모든 것에 영향**
    - 암호화를 어느 단계에서 할지
    - 메타데이터 인덱스에 오프셋을 저장할지
    - 평균 청크 크기를 얼마로 잡을지
    - → "블록 단위로 분할한다"는 한 줄 뒤에, 시스템 전체의 성능·정확성을 좌우하는 알고리즘적 선택이 숨어 있음