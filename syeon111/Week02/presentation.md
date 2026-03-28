# AWS us-east-1 장애로 보는 고가용성 설계

> 참고 문헌
> - [The Pragmatic Engineer - What caused the large AWS outage?](https://blog.pragmaticengineer.com/aws-outage-us-east-1/)
> - [InfoQ - AWS US-EAST-1 Outage: Postmortem and Lessons Learned](https://www.infoq.com/news/2021/12/aws-outage-postmortem/)
> - [Pluralsight - What happened with the AWS outage?](https://www.pluralsight.com/resources/blog/cloud/what-happened-with-the-aws-outage)
> - [thundergolfer - More Than DNS: The 14 hour AWS us-east-1 outage](https://thundergolfer.com/blog/aws-us-east-1-outage-oct20)

---

## 1. us-east-1이 뭔데 이렇게 중요한가?

AWS는 전 세계에 30개 이상의 리전을 운영한다. 그 중 **us-east-1(북버지니아)**은 AWS 최초의 리전으로, 가장 크고 복잡한 리전이다.

문제는 단순히 "가장 오래된 리전"이 아니라는 점이다.
- AWS의 핵심 글로벌 서비스들(IAM, Route53, STS 등)이 us-east-1에 종속되어 있음
- 수많은 기업들이 us-east-1을 기본 리전으로 사용
- **us-east-1이 터지면 다른 리전도 영향을 받는 구조**

---

## 2. 실제 장애 사례

### 사례 1: 2021년 12월 7일 — 셀프 DDoS

**무슨 일이 일어났나?**

오전 10시 30분, AWS 내부 네트워크의 용량을 늘리려는 자동화 작업 하나가 실행됐다. 그런데 이 작업이 내부 네트워크의 수많은 클라이언트들에게 **예상치 못한 동작**을 유발했고, 모두가 동시에 연결을 시도하면서 내부 네트워크가 순식간에 마비됐다. AWS가 스스로를 DDoS한 셈이다.

**연쇄 피해 (Cascading Failure)**

내부 네트워크 → 모니터링 시스템 마비 → 원인 파악 지연 → 복구 지연
```
내부 네트워크 혼잡
    ↓
모니터링/운영 툴 먹통 (원인도 못 찾는 상황)
    ↓
EC2, Lambda, CloudWatch, API Gateway 등 연쇄 장애
    ↓
Netflix, Disney+, Slack, Coinbase, Amazon 자체 서비스까지 다운
```

**피해 규모**
- Netflix, Disney+, Slack, Robinhood, Coinbase 등 수천 개 서비스 중단
- Amazon 자체 물류센터 앱(Flex, AtoZ)도 먹통 → 배송기사들이 경로 확인 불가
- 복구까지 약 **10시간 이상** 소요

**왜 이렇게 오래 걸렸나?**
- 장애를 파악하는 모니터링 시스템 자체가 같은 내부 네트워크에 있었음
- 즉, 불을 끄려는데 소방차도 같은 불 속에 있는 상황

---

### 사례 2: 2025년 10월 — DNS 레코드 전체 삭제

**무슨 일이 일어났나?**

DynamoDB의 DNS 엔트리를 관리하는 내부 시스템에서 **레이스 컨디션(Race Condition)**이 발생했다. 두 개의 프로세스가 동시에 같은 DNS 설정을 건드리면서, 결과적으로 us-east-1의 DynamoDB DNS 레코드가 **전부 삭제**됐다.
```
dynamodb.us-east-1.amazonaws.com → 응답 없음 (NXDOMAIN)
```

DNS가 사라지니 DynamoDB 자체가 인터넷에서 증발한 것처럼 보였고, DynamoDB에 의존하는 수십 개의 AWS 서비스가 연달아 장애를 일으켰다.

**피해 규모**
- Lambda, S3, EC2 오토스케일링, EKS 등 85개 이상의 서비스 영향
- Snapchat, Ring, Roblox 등 수천 개 서비스 장애
- 영국 국세청(HMRC) 접속 불가, 프리미어리그 경기 중계 중단
- 복구까지 약 **14시간** 소요

---

## 3. 왜 하필 us-east-1인가?

두 사례 모두 us-east-1에서 발생했다. 우연이 아니다.

- us-east-1은 AWS에서 **가장 크고 복잡한 리전**
- 트래픽이 많을수록 예상치 못한 타이밍에 버그가 터질 확률이 높아짐
- 전문가들도 "us-east-1은 가능하면 피하라"고 권고할 정도

> "us-east-1 리전을 포함하는 멀티 리전 페일오버 전략은 사실상 불가능하다. 너무 많은 것들이 이 리전에 종속되어 있다." — Corey Quinn, Cloud Economist

---

## 4. 책 내용과 연결: 가용성 SLA가 실제로 의미하는 것

책에서 나온 가용성 표를 다시 보면:

| 가용성 | 연간 다운타임 |
|--------|-------------|
| 99.9% | 8.77시간 |
| 99.99% | 52.60분 |

2021년 장애는 약 10시간 → **99.9% SLA도 이 장애 하나로 연간 한도 초과**
2025년 장애는 약 14시간 → **더욱 심각**

AWS는 공식적으로 99.99% SLA를 보장하지만, 이런 장애가 발생하는 순간 SLA는 의미를 잃는다. 즉, SLA는 목표치이지 보장이 아니다.

---

## 5. 그래서 어떻게 설계해야 하나?

### 이 장애들에서 배운 교훈

**① 단일 리전에 의존하지 말 것 (멀티 리전)**
- 중요한 서비스는 us-east-1 외에 us-west-2 등 다른 리전에도 복제
- 한 리전이 죽어도 다른 리전이 트래픽을 받을 수 있도록 설계

**② 모니터링 시스템은 독립적으로 구성**
- 2021년 장애의 핵심 원인 중 하나는 모니터링 시스템도 같이 죽었다는 것
- 감시하는 시스템은 감시 대상과 분리된 환경에 두어야 함

**③ 재시도 폭풍(Retry Storm) 방지**
- DNS 실패 시 모든 클라이언트가 동시에 재시도 → 서버 과부하 악화
- **지수 백오프(Exponential Backoff) + Jitter** 적용 필수

**④ 서킷 브레이커 패턴**
- 의존하는 서비스가 죽으면 계속 요청을 보내지 말고 일정 시간 차단
- 핵심 기능만 유지하고 부가 기능은 graceful degradation

**⑤ 카오스 엔지니어링**
- 평소에 일부러 장애를 유발해 시스템이 버티는지 테스트
- Netflix의 Chaos Monkey가 대표적 사례

---

## 6. 요약

| 항목 | 내용 |
|------|------|
| 장애 원인 | 자동화 스크립트 오작동, DNS 레이스 컨디션 |
| 피해 확산 이유 | 서비스 간 의존성, 모니터링 시스템도 동시 마비 |
| 핵심 교훈 | SLA는 보장이 아님, 단일 리전 의존은 위험 |
| 설계 방향 | 멀티 리전, 독립적 모니터링, 재시도 제어, 서킷 브레이커 |
