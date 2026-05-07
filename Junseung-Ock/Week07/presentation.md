# Bigtable 개요
### 참고 자료
- [Bigtable: A Distributed Storage System for Structured Data (OSDI '06)](https://static.googleusercontent.com/media/research.google.com/en//archive/bigtable-osdi06.pdf)

## 1. 정의 및 목표
- **정의**: 페타바이트 규모의 방대한 구조화된 데이터를 처리할 수 있도록 설계된 분산 스토리지 시스템
- **목표**: 뛰어난 확장성, 고성능, 높은 가용성
- **사용처**: Google Earth, Google Finance, 웹 인덱싱 등 60개 이상의 Google 프로젝트에서 활용

## 2. 데이터 모델 
Bigtable은 행 키, 열 키, 타임스탬프로 인덱싱되는  
희소하고 분산된 영구 다차원 정렬 맵입니다.

### 행 
- 행 키는 임의의 문자열 사용 가능
- 단일 행 키 내의 읽기/쓰기 연산은 **원자성** 보장
- 데이터는 **사전식 순서**로 정렬
- 행 범위 단위로 분할된 **태블릿**이 분산 및 로드 밸런싱의 기본 단위
- **예시**: URL의 호스트명을 역순으로 저장 → 동일 도메인 페이지가 연속적으로 위치

### 열 패밀리 
- 열 키들은 **열 패밀리 단위**로 그룹화
- 접근 제어의 기본 단위
- 동일 패밀리 내 데이터는 보통 같은 유형으로 저장 → 압축률 향상

### 타임스탬프 
- 각 셀은 여러 버전의 데이터 보유 가능
- 내림차순으로 저장 → 최신 버전을 먼저 읽음
- 오래된 데이터는 자동 삭제 가능

### 전체적인 구조

Row Key → Column Family → Column Qualifier → Cell Value (with timestamp)
- 행 키를 기반으로 하는 맵에서 값을 찾으면 열 패밀리 맵이 나옴

- 열 패밀리 맵에서 키를 찾으면 열 이름 맵이 나옴

- 열 이름 맵에서 키를 찾으면 타임스탬프별 Cell Value가 나옴
---

Row Key: `user123`
- Column Family: `profile`
    - Column Qualifier: `name`
        - [timestamp=2026-05-01] → "abc""
        - [timestamp=2026-04-20] → "def"
    - Column Qualifier: `email`
        - [timestamp=2026-05-01] → "jun@example.com"

- Column Family: `settings`
    - Column Qualifier: `theme`
        - [timestamp=2026-05-02] → "dark"
    - Column Qualifier: `language`
        - [timestamp=2026-05-02] → "ko"


## 3. 기본 빌딩 블록 
- **GFS (Google File System)**: 로그 및 데이터 파일 저장
- **SSTable**: 키-값 쌍을 정렬된 불변 맵 형태로 저장, 단일 디스크 탐색 가능
- **Chubby**: Paxos 기반 분산 락 서비스 → 마스터 선출, 스키마 저장, 태블릿 서버 모니터링

## 4. 핵심 구성 요소 및 동작 방식
### 시스템 구성
- **클라이언트 라이브러리**
- **마스터 서버**
- **여러 태블릿 서버**

### 태블릿 서버 
- 태블릿(약 100-200MB)을 10-1,000개 관리
- 데이터 읽기/쓰기 요청 처리
- 태블릿 크기가 커지면 **분할(Split)** 수행

### 태블릿 위치 탐색 
3계층 구조:
1. Chubby 파일 → 루트 태블릿 위치 저장 (태블릿 서버 위치 확인)
2. 루트 태블릿 → METADATA 테이블 위치 저장 
3. METADATA 테이블 → 사용자 태블릿 위치 저장

### 컴팩션 (데이터 압축 및 병합)
- **Minor Compaction**: 
  - 쓰기 요청이 들어오면 메모리(Memtable)에 먼저 쌓임
  - 메모리의 크기가 특정 임계치에 도달 → 메모리에 있는 데이터를 정렬된 불변의 파일인 SSTable 형태로 변환하여 GFS에 기록
  - ex) 사용자가 1,000건의 데이터를 입력 → 이 데이터가 메모리에 쌓여 있다가 크기가 커지면 SSTable-1이라는 파일로 만들어 디스크에 저장
- **Major Compaction**: 
  - 시스템 내의 모든 SSTable들을 읽어 들여 하나의 새로운 SSTable로 병합
  - 이미 삭제된 데이터나 업데이트되어 더 이상 필요 없는 이전 버전의 데이터를 완전히 제거
  - ex) 디스크에 SSTable-1, SSTable-2, SSTable-3 파일 존재 → 파일들을 모두 읽어 들여, 수정된 최신 데이터만 남기고 삭제된 데이터는 완전히 지운 뒤 하나의 깔끔한 SSTable-Final 파일로 병합 
## 5. 주요 성능 최적화 기법 
- **지역성 그룹**: 자주 함께 접근하지 않는 열 패밀리를 분리 → 읽기 효율 향상
- **압축**: 호스트별 유사 데이터 모아 높은 압축률 달성
- **Bloom Filter**: 존재하지 않는 행/열에 대한 불필요한 디스크 접근 최소화
