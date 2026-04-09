# 5장. 안정 해시 설계

수평적 규모 확장성을 달성하기 위해서는 요청 또는 데이터를 서버에 균등하게 나누는 것이 중요하다.

안정 해시는 이 목표를 달성하기 위해 보편적으로 사용하는 기술이다.

이 해시 기술이 풀려고 하는 문제부터 살펴보자.

# 해시 키 재배치(rehash) 문제

N개의 캐시 서버가 있을 때, 서버 부하를 균등하게 나누는 보편적 방법은 **해시함수**를 사용하는 것이다.

- `serverIndex = hash(key) % N`

## 동작 방식

4대의 서버를 사용한다고 가정

<img width="609" height="447" alt="Image" src="https://github.com/user-attachments/assets/b657c1d7-af65-442e-aa30-ef4ccaa2345d" />

- 특정한 키가 보관된 서버를 알아내기 위해, 나머지 연산을 `f(key) % 4` 와 같이 적용
    - `hash(key0) % 4 = 1`이면, 클라이언트는 캐시에 보관된 데이터를 가져오기 위해 서버 1에 접속

<img width="561" height="320" alt="Image" src="https://github.com/user-attachments/assets/16f8bb61-0014-475f-b7d1-07383ee65c0c" />

### 해시 함수가 잘 동작하는 경우

- 서버 풀(server pool)의 크기가 고정되어 있을 때, 데이터 분포가 균등할 때

### 해시 함수가 잘 동작하지 않는 경우

- 서버가 추가되거나, 기존 서버가 삭제되면 문제가 생김

    - 서버 1이 장애를 일으켜 동작 중단 → 서버 풀의 크기는 3으로 변함
   
        - 키에 대한 해시 값은 변동 X
        - 나머지 연산을 적용해 계산한 서버 인덱스 값은 달라짐

<img width="580" height="425" alt="Image" src="https://github.com/user-attachments/assets/5410e486-60ac-4f90-a26a-23459c01229e" />

<img width="530" height="370" alt="Image" src="https://github.com/user-attachments/assets/6b8764b7-cbbd-4949-a1cd-caa602c622d9" />

- 장애가 발생한 1번 서버의 키뿐만 아니라 대부분의 키가 재분배됨
    - 1번 서버가 죽으면 대부분 캐시 클라이언트가 데이터가 없는 엉뚱한 서버에 접속
        
        ⇒ **대규모 캐시 미스(cache miss)** 발생 → 안정 해시가 이 문제 해결
        

# 안정 해시(consistent hash)

- 해시 테이블 크기가 조정될 때, 평균적으로 **k/n개의 키만 재배치**하는 해시 기술
    - k =  키의 개수
    - n = 슬롯(slot)의 개수
- 전통적인 해시 테이블은 슬롯의 수가 바뀌면 거의 대부분 키를 재배치

## 해시 공간과 해시 링

- 해시 함수 f로 SHA-1 사용
    - SHA-1의 해시 공간 범위 : 0~2^160 - 1
    - 출력 값 범위 : x0, x1, x2 … xn 가정
    

   <img width="603" height="106" alt="Image" src="https://github.com/user-attachments/assets/b067eeaa-a40e-4acf-a564-2d59ea0fff97" />
    
    - 위 해시 공간의 양쪽을 구부려 접으면 해시 링이 만들어짐
    
    <img width="242" height="294" alt="Image" src="https://github.com/user-attachments/assets/43f42ccc-9ee2-49fe-a73d-321b429f3fa3" />
    

## 해시 서버

해시 함수 f를 사용하면 서버 IP나 이름을 이 링 위의 어떤 위치에 대응시킬 수 있다.

<4개의 서버를 해시 링 위에 배치한 결과>

<img width="629" height="422" alt="Image" src="https://github.com/user-attachments/assets/f8505cf5-737c-48a2-b9f2-8c632f9190d7" />

## 해시 키

여기 사용된 해시 함수는 “해시 키 재배치 문제”의 함수와 다르며, 나머지 연산 %은 사용하지 않고 있다.

- 캐시할 키 key0, key1, key2, key3 또한 해시 링의 어느 지점에 배치

<img width="624" height="384" alt="Image" src="https://github.com/user-attachments/assets/0499285a-0438-4e4c-a2a8-f35cc8a283ba" />

## 서버 조회

어떤 키가 저장되는 서버는 해당 키의 위치로부터 시계 방향으로 링을 탐색해 나가면서 만나는 첫 번째 서버다.

<img width="516" height="333" alt="Image" src="https://github.com/user-attachments/assets/9b180cd7-acd6-4f1b-b301-d792e7f38ea5" />

- key1→서버 1, key2→서버 2, key3→서버 3에 저장

## 서버 추가

서버를 추가하더라도 키 가운데 일부만 재배치하면 된다. 

<img width="633" height="460" alt="Image" src="https://github.com/user-attachments/assets/7dd0897b-ade7-4d26-8393-ad21d150b3ae" />

- 서버 4가 추가된 후, key 0만 재배치되고 k1, k2, k3은 같은 서버에 남음
    - 서버 4 추가 후, key 0의 위치에서 시계 방향 순회 시 가장 처음 만나게 되는 서버가 서버 4이므로 key 0만 재배치
    - 다른 키들은 재배치 X

## 서버 제거

하나의 서버가 제거되면 키 가운데 일부만 재배치된다.

<img width="623" height="444" alt="Image" src="https://github.com/user-attachments/assets/2b34dcf9-6c83-4a53-b175-fe99979398f3" />

- 서버 1 삭제된 후 key 1만 서버 2로 재배치

## 기본 구현법의 두 가지 문제

### 안정 해시 알고리즘의 기본 절차

안정 해시 알고리즘은 MIT에서 처음 제안되었다.

- 서버와 키를 균등 분포(uniform distribution) 해시 함수를 사용해 해시 링에 배치
- 키의 위치에서 링을 시계 방향으로 탐색하다 만나는 최초의 서버가 키가 저장될 서버

### 두 가지 문제

1. 서버가 추가/삭제되는 상황을 감안하면 **파티션의 크기를 균등하게 유지하는 것이 불가능**하다.
    - 파티션 : 인접한 서버 사이의 해시 공간
    - 어떤 서버는 작은 해시 공간을 할당, 어떤 서버는 큰 해시 공간을 할당 받는 상황이 가능해짐
    

<img width="607" height="372" alt="Image" src="https://github.com/user-attachments/assets/c422c432-a0cc-47b1-9373-f93cf73195f6" />
    
   → s1이 삭제되어 s2의 파티션이 다른 파티션 대비 거의 2배로 커짐    

2. 키의 균등 분포(uniform distribution)를 달성하기가 어렵다.
    
<img width="620" height="397" alt="Image" src="https://github.com/user-attachments/assets/3d366ff4-3098-4c08-a43e-2f1287b17466" />
    
   - 서버 1, 서버 3은 아무 데이터도 갖지 않는 반면 대부분의 키는 서버 2에 보관

⇒ 이 문제를 해결하기 위해 **가상 노드(virtual node)** 또는 **복제(replica)** 기법이 제안되었다.

## 가상 노드

- 실제 노드 또는 서버를 가리키는 노드
- 하나의 서버는 링 위에 여러 개의 가상 노드를 가질 수 있다.

<img width="628" height="446" alt="Image" src="https://github.com/user-attachments/assets/d19bb72b-1541-418d-a85d-40a97457bdb9" />

- 예시에서 서버 0과 서버 1은 3개의 가상 노드를 가진다.
    - 여기서 숫자 3은 임의로 정한 것이며, 실제 시스템에서는 훨씬 큰 값이 사용된다.
    - 서버 0을 링에 배치하기 위해 s0 하나만 쓰는 대신, s0_0, s0_1, s0_2 세 개의 가상 노드 사용
    
- 각 서버는 하나가 아닌 여러 개 파티션을 관리해야 한다.
    - s0으로 표시된 파티션 : 서버 0이 관리하는 파티션
    - s1으로 표시된 파티션 : 서버 1이 관리하는 파티션

<img width="617" height="409" alt="Image" src="https://github.com/user-attachments/assets/93180fac-abb0-49e6-a0fc-430edf814434" />

- 키의 위치로부터 시계방향으로 링을 탐색하다 만나는 최초의 가상 노드가 해당 키가 저장될 서버가 된다.
    - k0은 가상 노드 s1_1가 나타내는 서버 1에 저장
- 가상 노드의 개수를 늘리면 키의 분포는 점점 더 균등해진다.
    - 표준 편차가 작아져서 데이터가 고르게 분포되기 때문
   
    - 표준 편차 : 데이터가 어떻게 퍼져 나갔는지를 보이는 척도
    
    > 100~200개의 가상 노드를 사용했을 경우, 표준 편차 값은 평균의 5%(가상 노드가 200개인 경우)에서 10%(가상 노드가 100개인 경우) 사이다.
    > 
- 가상 노드 개수를 늘리면 표준 편차는 작아지지만, 가상 노드 데이터를 저장할 공간은 더 많이 필요해진다.
    
    ⇒ tradeoff가 있으므로 시스템 요구사항에 맞도록 가상 노드 개수를 적절히 조정해야 한다.
    

## 재배치할 키 결정

서버가 추가되거나 제거되면 데이터 일부는 재배치해야 한다.

<img width="615" height="456" alt="Image" src="https://github.com/user-attachments/assets/d40c7b82-aa04-4922-9501-f77236f0b058" />

- 서버 4가 추가된 경우
    - 이에 영향 받은 범위는 s4부터 반시계 방향에 있는 최초 서버 s3까지
        
        → s3부터 s4 사이의 키들을 s4로 재배치해야 함

<img width="626" height="460" alt="Image" src="https://github.com/user-attachments/assets/ce61b16a-2944-454c-8515-caf1500b4527" />

- 서버 s1이 삭제된 경우
    - s1부터 반시계 방향에 있는 최초 서버 s0 사이의 키들이 s2로 재배치되어야 함

---

### **안정 해시의 이점**

- 서버가 추가/삭제될 때 재배치되는 키의 수가 최소화된다.
- 데이터가 보다 균등하게 분포하게 되므로 수평적 규모 확장성을 달성하기 쉽다.
- 핫스팟 키 문제를 줄인다.
    - 특정 샤드에 대한 접근이 지나치게 빈번하면 서버 과부화 문제가 생길 수 잇다.
    - 유명인의 데이터가 전부 같은 샤드에 몰리는 상황

### **안정 해시 기술의 예시**

- 아마존 DynamoDB의 파티셔닝 관련 컴포넌트
- Apache Cassandra 클러스터에서의 데이터 파티셔닝
- 디스코드 채팅 어플리케이션
- 아카마이 CDN
- Meglev 네트워크 부하 분산기