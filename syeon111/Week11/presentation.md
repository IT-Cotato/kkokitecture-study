# Discord — 분당 100만 건 푸시 알림 처리 
> 참고 자료
> 
> - Discord Engineering Blog — How Discord handles push request bursts of over a million per minute with Elixir's GenStage
> - Discord Architecture Map
> - Elixir GenStage 공식 문서

---

## 1. 배경

- Discord 사용자 급증 시기, 모바일 푸시 알림 시스템 과부하 발생
- r/Overwatch 등 인기 서버 동시 접속자 25,000명 돌파, 포켓몬고 관련 서버 동시다발 생성 → 알림이 한 번에 몰리는 버스트 트래픽 발생
- 장애 양상: 알림이 늦게 오는 정도가 아니라 아예 전달이 안 되는 케이스까지 발생, 시스템 전체가 느려지거나 멈추는 수준까지 영향
- 단발성 이슈가 아니라 인기 서버가 새로 뜰 때마다 반복될 수 있는 구조적 문제로 판단 → 임시 땜질이 아니라 시스템 재설계로 방향을 잡음

## 2. 병목 지점

- 병목은 Discord 서버가 아니라 **Firebase Cloud Messaging(FCM)으로 보내는 구간**
- 기존엔 HTTP로 FCM에 요청을 보냈는데, HTTP는 요청마다 연결을 새로 맺는 구조라 버스트 트래픽에서 처리량이 빠르게 한계에 부딪힘
- **XMPP**는 하나의 연결을 계속 유지하면서 여러 요청을 흘려보낼 수 있는 구조라 같은 트래픽도 훨씬 적은 오버헤드로 처리 가능 → 프로토콜 전환만으로 처리량 즉시 개선
- 다만 XMPP 전환은 임시방편에 가까웠고, "얼마나 많은 요청을 동시에 흘려보낼 수 있는가"를 시스템이 스스로 제어하지 못하는 근본 문제는 남아있었음 → 요청 처리 구조 자체를 재설계하기로 결정

<img width="100%" alt="Image" src="https://github.com/user-attachments/assets/1de354e8-2643-4e6e-9add-3ef495c627bb" />


## 3. GenStage 기반 2단계 파이프라인

- 전체 구조를 **producer(생산자)** 와 **consumer(소비자)**, 두 개의 GenStage 스테이지로 분리
- **Push Collector (Stage 1, producer)**: 들어오는 모든 푸시 요청을 모으는 역할, 머신당 정확히 1개의 Erlang 프로세스로 동작
- **Pusher (Stage 2, consumer)**: Push Collector에 요청을 요구(demand)해서 가져온 뒤 Firebase로 전달하는 역할, 머신 한 대에 여러 개의 Pusher 프로세스가 동시에 동작
- Pusher가 한 번에 정확히 100개씩만 요구하는 이유: Firebase XMPP 연결 하나가 동시에 처리할 수 있는 pending 요청 한도가 100개이기 때문 — 그 이상 요구하면 Firebase 쪽에서 거절당하거나 연결이 막힘
- 동작 순서
    1. Pusher가 Push Collector에 "최대 100개까지 처리 가능하니 그만큼 달라"고 요구
    2. Push Collector는 요구받은 만큼만 전달 — 절대 먼저 밀어넣지 않음
    3. Pusher가 Firebase로 요청 전송
    4. Firebase가 ack(처리 완료 확인)을 보내면, 그제서야 Pusher가 다음 묶음을 요구
- Erlang/Elixir 특유의 가벼운 프로세스 모델이라 머신 한 대에 Pusher 프로세스를 여러 개 띄워도 부담이 거의 없음 → 처리량을 늘리고 싶으면 Pusher 프로세스 수를 늘리면 됨

## 4. 백프레셔 / 로드셰딩

- **백프레셔**: Pusher가 항상 "내가 처리 가능한 만큼만" 요구하기 때문에, Push Collector에게 아무리 요청이 쏟아져도 Pusher 쪽으로 그 부하가 그대로 전파되지 않음 — 받는 쪽이 속도를 정하는 구조
- 문제는 그 부담이 사라지는 게 아니라 **Push Collector 쪽으로 옮겨간다는 것** — Pusher들이 다 버텨도 Push Collector가 감당 못할 만큼 요청이 쌓이면 결국 거기가 새로운 병목이 됨
- **로드셰딩**: 이를 위해 Push Collector에 `buffer_size`라는 버퍼 한도를 지정 — 평소엔 버퍼가 거의 비어있는 상태로 운영되다가, 한 달에 한 번 정도 있는 극단적인 트래픽 폭주 상황에서만 동작
- 버퍼가 가득 차면 그 이상 들어오는 요청은 그냥 버림(drop) — GenStage에 기본 내장된 기능이라 별도 구현이 필요 없음
- **실제 장애 그래프**
    - 17:50:00경 — Push Collector 버퍼가 차오르기 시작하며 일부 요청을 셰딩
    - 17:50:50경 — 버퍼가 다시 빠지기 시작하며 셰딩 중단
    - 17:51:30경 — 유입 트래픽 자체가 줄어들며 상황 종료
    - 약 1분 30초 동안의 사건이었지만 이 구간 동안 사용자나 다른 시스템에 체감되는 영향은 없었음

<img width="100%" height="360" alt="Image" src="https://github.com/user-attachments/assets/d1df56f6-ab53-4566-9730-552e448bb218" />
<img width="100%" height="360" alt="Image" src="https://github.com/user-attachments/assets/eef396ca-0fbf-4d84-a012-301cabde012d" />


## 5. 결과

- 분당 100만 건 이상의 푸시 요청을 안정적으로 처리 가능해짐
- 이후 대형 게임 출시, 대형 이벤트 등 트래픽 급증 상황에서도 알림 시스템 전체가 다운되지 않고 부하를 흡수
- "완벽하게 모든 요청을 처리"하는 대신 "감당 가능한 만큼만 받고 넘치면 버린다"는 설계 원칙으로 전환한 게 핵심 — 평소엔 거의 작동하지 않다가 진짜 필요한 순간(한 달에 한 번 수준)에만 조용히 동작하는 구조