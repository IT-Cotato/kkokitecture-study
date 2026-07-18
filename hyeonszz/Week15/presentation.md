# SVE: Distributed Video Processing at Facebook Scale

→ Facebook 규모의 동영상 업로드/인코딩 시스템을 기존의 단일 처리 방식(MES)에서 분산 스트리밍 처리 시스템(SVE)으로 전환한 설계

**핵심 아이디어**

> 동영상을 하나의 큰 파일로 처리하지 말고, 작은 조각으로 나누어 여러 서버가 동시에 처리하고, 처리 과정을 DAG 기반 파이프라인으로 관리하자
> 

## 배경

- 페이스북에는 15개 이상의 서비스가 비디오 기능을 사용하고 있음
- 서비스마다 요구되는 비디오 처리 파이프라인이 상이함
    - Facebook 비디오 게시물: 업로드당 평균 153개의 처리 작업
    - Messenger 비디오: 업로드당 평균 18개 작업
    - Instagram: 업로드당 평균 22개 작업
    - 360도 비디오: 업로드당 수천 개 작업
- 하루 평균 80억 회 이상 비디오 조회, 매일 수천만 건의 업로드
- 핵심 지표는 **time-to-share**: 사용자가 업로드를 시작한 시점부터 공유 가능해지는 시점까지 걸리는 시간

⇒ 사용자가 영상을 업로드하면 바로 공유할 수 있는 것이 아님

업로드 후 해야 하는 작업:

1. 영상 포맷 검증
2. 여러 화질 생성
3. 여러 코덱 변환
4. 썸네일 생성
5. 음성/영상 분석
6. 컴퓨터 비전 처리
7. 저장 및 배포

## 기존 시스템(MES)의 한계

- SVE 이전에는 **MES(Monolithic Encoding Script)**라는 단일 스크립트로 비디오를 처리
- 배치 기반의 순차적 파이프라인: 업로드 → 저장 → 처리 트리거 → 처리 완료 후 재저장 → 공유 가능
- 구조:  Client → Frontend → Storage → Encoder → Processed Video
    - 즉 upload → store → encode → share 순서로 진행됨

<img width="431" height="184" alt="Image" src="https://github.com/user-attachments/assets/43a242c9-76d4-4073-b2b8-2ecd49caf07e" />

- 문제점
    - 업로드만으로도 수 초~수 분 소요 → Latency 증가
    - 인코딩 시간도 대용량 비디오에서는 상당함
    - 여러 앱의 서로 다르고 계속 변화하는 요구사항에 대응하기 어려워 유지보수·모니터링이 힘듦 → 하나의 Encoder 장애로 전제 파이프라인 멈춤
    - 큰 영상 하나를 하나의 서버가 처리하기 때문에 병렬 처리 불가능 → 서버 자원 충분히 활용 X

## SVE 요구사항

1. **낮은 지연시간**: 인터랙티브한 애플리케이션 지원을 위해 필요
2. **유연한 프로그래밍 모델**: 프로그래머가 쉽게 작성 가능하고, 효율적 처리와 신뢰성 향상을 지원해야 함
3. **과부하·장애에 대한 강건성**

<img width="465" height="190" alt="Image" src="https://github.com/user-attachments/assets/bd44102a-56eb-43f8-bff9-51ede9680694" />


## **핵심 설계**

### **①  Streaming + Chunking**

- **기존**: 한 번에 처리
- **SVE**: 영상을 작은 segment로 나누고 각각 병렬 처리

#### (1) Stream-of-Tracks 추상화

하나의 동영상이라고 생각하는 파일 안에는 사실 영상, 음성, 자막, 메타데이터 등 여러 데이터가 함께 들어있다. SVE는 이것들을 하나의 파일로 보지 않고 Video Track, Audio Track, Metadata Track 처럼 각각 따로 분리해서 생각한다. 

- 모든 작업이 영상을 다 사용할 필요는 없기 때문에 분리한다 → 필요한 데이터만 처리하면 되므로 훨씬 효율적이다
    - ex) 음성 자막 만들기는 오디오만 필요하고 영상은 필요 없다
    - ex) 얼굴 인식은 오디오는 필요 없고 영상이 필요하다

#### (2) Segment 세그먼트

Track보다 더 잘게 자른 것이 Segment이다

→ 10분 영상을 2+2+2+2+2분으로 나누는 것

Segment1 → Worker1 
Segment2 → Worker2
Segment3 → Worker3
Segment4 → Worker4
Segment5 → Worker5

| 작업 | 필요한 데이터 |
| --- | --- |
| 음성→텍스트 | Audio Track |
| 얼굴 인식 | Video Track |
| 인코딩 | Video Segment |
| 영상 분류 | 전체 Video |

동시에 처리

→ 처리 시간이 크게 감소. MES 대비 공유 가능 상태까지 걸리는 시간을 **2.3배~9.3배 감소**시킴

1. **업로드와 처리의 오버랩**: 업로드가 끝나야 처리를 시작하는 게 아니라 동시에 진행
2. **GOP 단위 청크 병렬 처리**: 비디오를 작은 단위로 쪼개 대규모 클러스터에서 각 청크를 병렬 처리. 가능하면 클라이언트 단에서 분할 수행, 각 GOP는 독립적으로 디코딩·재생 가능
3. **저장과 처리의 병렬화**: 업로드된 원본을 저장하는 작업과 처리 작업을 동시에 진행

### ② Upload와 Processing 병렬화

- 기존: Upload 완료 → Processing 시작
- SVE: Upload 중 chunk1 업로드 → Processing 시작 & chunk 2 업로드 → Processing
    - 업로드와 처리를 겹치는 pipeline 방식

<img width="456" height="333" alt="Image" src="https://github.com/user-attachments/assets/c3f948f5-ef80-4326-829d-45c28b3b85e0" />

### ③ DAG 기반 Processing Model

SVE의 중요한 특징: 비디오 처리를 하나의 코드가 아니라 **DAG(Directed Acyclic Graph)**로 표현한다

<img width="470" height="264" alt="Image" src="https://github.com/user-attachments/assets/89160ebc-ce6e-496c-865e-424bcc248f4d" />

- DAG: 작업 순서를 그림으로 표현한 것
- SVE는 비디오마다 DAG가 달라진다

EX) Facebook Post 

- Upload → Encode → Thumbnail → Face Detection → Recommendation

EX) Messenger

- Upload → Encode → Send

위 예시처럼 각 서비스마다 다른 DAG를 사용할 수 있다.

### ④ Fault Tolerance

worker 장애, 네트워크 문제, overload 등 장애가 생기는 것을 고려한다

- Worker A가 Task X를 수행하는데 실패하면 Worker B가 Task X를 재수행한다
- Storage와 Processing을 분리해서 원본 영상은 안정적으로 저장하고 처리는 별도로 수행하게 한다

## SVE가 해결한 3가지 핵심 문제

| 문제 | MES | SVE |
| --- | --- | --- |
| Latency 지연시간 | 순차 처리 | 병렬 처리 |
| Flexibility 유연성 | 코드 수정 필요 | DAG 기반 |
| Reliability 신뢰성 | 장애 취약 | Task 재실행 |