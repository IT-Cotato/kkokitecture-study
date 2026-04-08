# 13기 아키텍처 스터디

대규모 시스템에서의 아키텍처 설계 능력을 키우고, 시스템 설계 면접에 대비하기 위한 스터디입니다.

교재는『가상 면접 사례로 배우는 대규모 시스템 설계 기초 1권』을 기반으로 합니다.

대규모 트래픽 처리 원리를 이해하고 시스템 설계 면접 패턴을 학습하며 주요 아키텍처 요소를 실무적으로 이해하는 것을 목표로 합니다.

---

## Members

| <a href="https://github.com/ccjngwn"><img src="https://github.com/ccjngwn.png" width="120"/></a> | <a href="https://github.com/syeon111"><img src="https://github.com/syeon111.png" width="120"/></a> | <a href="https://github.com/hyunj24"><img src="https://github.com/hyunj24.png" width="120"/></a> | <a href="https://github.com/pearseona"><img src="https://github.com/pearseona.png" width="120"/></a> |
| :----------------------------------------------------------------------------------------------: | :------------------------------------------------------------------------------------------------: | :----------------------------------------------------------------------------------------------: | :--------------------------------------------------------------------------------------------------: |
|                         <a href="https://github.com/ccjngwn">채정원</a>                          |                          <a href="https://github.com/syeon111">박수연</a>                          |                         <a href="https://github.com/hyunj24">박현정</a>                          |                          <a href="https://github.com/pearseona">배선아</a>                           |

| <a href="https://github.com/hyeonszz"><img src="https://github.com/hyeonszz.png" width="120"/></a> | <a href="https://github.com/Junseung-Ock"><img src="https://github.com/Junseung-Ock.png" width="120"/></a> | <a href="https://github.com/leehwx"><img src="https://github.com/leehwx.png" width="120"/></a> | <a href="https://github.com/junjunseo"><img src="https://github.com/junjunseo.png" width="120"/></a> |
| :------------------------------------------------------------------------------------------------: | :--------------------------------------------------------------------------------------------------------: | :--------------------------------------------------------------------------------------------: | :--------------------------------------------------------------------------------------------------: |
|                          <a href="https://github.com/hyeonszz">신현주</a>                          |                            <a href="https://github.com/Junseung-Ock">옥준승</a>                            |                         <a href="https://github.com/leehwx">이해원</a>                         |                          <a href="https://github.com/junjunseo">임준서</a>                           |

---

## 스터디 일정

- 정기 모임: 매주 일요일 오전 9시
- 진행 방식: 디스코드 비대면
- 과제 제출: 매주 일요일 정기 모임 전까지

---

## 스터디 커리큘럼

| **주차** | **챕터**    | **챕터 내용**                                |
| -------- | ----------- | -------------------------------------------- |
| 1주차    | 1장         | 사용자 수에 따른 규모 확장성                 |
| 2주차    | 2장 + 3장   | 개략적인 규모 추정 + 시스템 설계 면접 공략법 |
| 3주차    | 4장         | 처리율 제한 장치의 설계                      |
| 4주차    | 5장         | 안정 해시 설계                               |
| 5주차    | 6장         | 키-값 저장소 설계                            |
| 6주차    | 7장         | 분산 시스템을 위한 유일 ID 생성기 설계       |
| 7주차    | 8장         | URL 단축기 설계                              |
| 8주차    | 9장         | 웹 크롤러 설계                               |
| 9주차    | 10장        | 알림 시스템 설계                             |
| 10주차   | 11장        | 뉴스 피드 시스템 설계                        |
| 11주차   | 12장        | 채팅 시스템 설계                             |
| 12주차   | 13장        | 검색어 자동완성 시스템                       |
| 13주차   | 14장        | 유튜브 설계                                  |
| 14주차   | 15장 + 16장 | 구글 드라이브 설계 + 배움은 계속된다         |

---

## 스터디 진행 방식

- 매주 정해진 파트를 읽고 정리한다.
- 파트와 관련된 심화 내용을 정리하여 발표한다.
  - 참고 문헌, 기술 블로그, 코드 구현, 다른 책의 심화 내용, 직접 설계한 내용 등 자유

---

## Directory Structure

```
│
├─ kkokitecture-study
│     │
│     ├─ ccjngwn/                       # github 핸들명
│     │     ├─ Week01/
│     │     │    ├─ chapter01.md        # 책 내용 정리
│     │     │    └─ presentation.md    # 심화 발표 자료
│     │     │
│     │     ├─ Week02/
│     │     │    ├─ chapter02.md
│     │     │    └─ presentation.md
│     │     │
│     │     └─ ... 이하 동일
│     │
│     ├─ <github-id>/                   # 다른 구성원도 동일 구조
│
```

---

## GitHub 운영 방식

### 브랜치 원칙

- 참가자는 해당 주차 문서를 **개인 주차 브랜치**에서 작업한다.
  - 예: `ccjngwn-week-01`
- 모든 PR의 대상(base)은 **develop** 이다.
- 주차 마감 시 스터디장이 PR을 확인하고 develop에 머지한다.

### 브랜치 네이밍 규칙

- `<github핸들명>-week-<NN>`
- 예: `ccjngwn-week-01`, `hong-week-03`

---

### 작업 흐름 (로컬 → PR)

#### 1. 저장소 최초 설정

처음 참여하는 경우, 저장소를 로컬로 clone한다.

```bash
git clone https://github.com/IT-Cotato/kkokitecture-study.git
```

#### 2. 기준 브랜치 최신화

```bash
git switch develop
git pull origin develop --ff-only
```

> `--ff-only`: 내 로컬에 새 커밋이 없고, 원격이 더 앞서 있을 때만 포인터를 앞으로 이동시켜 안전하게 최신화한다. (fast-forward merge)

#### 3. 주차 브랜치 생성

```bash
git switch -c <github-id>-week-01
```

#### 4. 산출물 작성 및 커밋 후 원격 푸시

```bash
git add .
git commit -m "docs: n주차 chapter0n.md 생성"
git push -u origin <github-id>-week-01
```

커밋 메시지 예시:

- `docs: 1주차 chapter01.md 생성`
- `docs: 2주차 presentation.md 수정`

#### 5. GitHub에서 PR 생성

- **base**: `develop`
- **compare**: `<github-id>-week-01`
- PR 제목 예시: `이름 [1주차] 과제 제출`

#### 6. 머지 후 로컬 정리

스터디장이 PR을 승인하고 Merge하면, 아래 명령어로 로컬을 정리한다.

```bash
git switch develop
git pull origin develop --ff-only
git branch -d <github-id>-week-01
```

> branch 삭제는 모든 파일을 develop 브랜치에 올린 후 수행할 것
