# Differential Synchronization (Neil Fraser, 2009)

---

## 1. 기존 방식들이 왜 부족한가

| 방식 | 원리 | 한계 |
| --- | --- | --- |
| **Locking (소유권)** | 한 명만 쓰기 권한 | 실시간 협업 자체가 불가능. 락 신호 유실 시 주인 없는 문서가 됨 |
| **Event Passing (OT)** | 사용자 액션을 전부 캡처해서 전파 | 타이핑뿐 아니라 잘라내기/붙여넣기/드래그/자동수정까지 **전부** 잡아야 함. 하나라도 놓치면 fork |
| **Three-way Merge** | 클라이언트 → 서버 전송 → 병합 → 새 문서 회신 | 왕복 중 편집하면 받은 결과를 버려야 함. 반이중 |

---

## 2. 핵심 아이디어 (Common Shadow 버전)

Shadow = "마지막으로 동기화가 끝났던 시점의 스냅샷"

### 사이클

1. `diff(Client Text, Common Shadow)` → 사용자가 만든 편집 목록 추출
2. Common Shadow ← Client Text 복사 (1번에서 쓴 스냅샷과 **동일한 값**이어야 함)
3. 그 편집들을 Server Text에 **best-effort로** patch
4. 방향을 바꿔서 대칭적으로 반복

### 왜 이게 되는가: 퍼지 패치(fuzzy patch)

패치가 **엄격하지 않음**.
"이 위치에 정확히 이 문자열이 있어야 함"이 아니라, **주변 문맥이 어느 정도 비슷하면 적용**한다.

그리고 **실패해도 괜찮음**. 실패한 편집은 다음 diff에서 자동으로 음수로 잡혀서 되돌려진다.

### 실제 데이터 흐름 예시

```
시작:      "Macs had the original point and click UI."

클라이언트 사용자:  "Macintoshes had the original point and click interface."
서버 사용자:        "Smith & Wesson had the original point and click UI." -> (다른 사용자의 변경 사항 반영된 서버)
```

클라이언트에서 뽑은 diff 2개:

```diff
@@ -1,11 +1,18 @@
 Mac
+intoshe
 s had th
@@ -35,7 +42,14 @@
 ick
-UI
+interface
 .
```

서버에 patch 시도:

- `+intoshe` → **실패** (문맥이 "Smith & Wesson"으로 완전히 바뀌어서 붙일 데가 없음)
- `UI → interface` → **성공** (문맥 그대로)

결과: `"Smith & Wesson had the original point and click interface."`

역방향 diff:

```diff
@@ -1,15 +1,18 @@
-Macintoshes
+Smith & Wesson
 had
```

이걸 클라이언트에 적용하면 → 실패했던 `Macintoshes` 편집이 **저절로 롤백**되고 서버 값으로 대체된다.
성공했던 `interface`는 건드리지 않는다.

---

## 3. Dual Shadow Method (실제 클라이언트-서버용)

Common Shadow는 공통이라 한 머신에만 있을 수 있다 → 클라이언트-서버 구조에서는 못 씀.
그래서 shadow를 둘로 쪼갠다: **Client Shadow / Server Shadow**

### 불변 조건

```
Client Shadow == Server Shadow   (매 half-cycle 종료 시점)
```

### 패치 종류

| 대상 | 패치 성격 | 이유 |
| --- | --- | --- |
| **Shadow** | fragile (엄격) | 완벽히 맞아야 정상. 안 맞으면 뭔가 깨진 것 |
| **Text** | fuzzy (best-effort) | 사용자가 그 사이에 편집했을 수 있음 |

### 체크섬

네트워크는 신뢰할 수 없으니, Client Shadow의 체크섬을 편집과 함께 보내서
패치 후 Server Shadow와 비교한다.
불일치 → 한쪽이 전체 텍스트를 통째로 전송해서 재동기화

---

## 4. Guaranteed Delivery Method

Dual Shadow만으로는 패킷 유실 시 "전체 재전송 → 그 사이 변경분 전부 날아감"이 발생한다.
이걸 막기 위해 **버전 번호 + 편집 스택 + Backup Shadow**를 추가한다.

### 추가 구성요소

- `n` : 클라이언트 버전 번호
- `m` : 서버 버전 번호
- **Edit Stack** : ACK 받을 때까지 편집을 쌓아두고 매 sync마다 재전송
- **Backup Shadow** : 서버만 보유. Server Shadow의 직전 버전

### 정상 동작 흐름

```
1. 사용자가 Client Text 수정
2. diff(Client Text, Client Shadow) → 편집 목록, 태그: 버전 n
3. Client Shadow ← Client Text,  n++
4. 서버로 전송: [편집들 + n + 내가 마지막으로 받은 서버 버전 m]
5. 서버: Server Shadow에 patch → n++ → Backup Shadow에 백업
6. 서버: Server Text에 patch (fuzzy)
7. 응답에 "n 잘 받았음" 포함 → 클라이언트는 스택에서 n 삭제
```

### 장애 시나리오 정리

| 시나리오 | 서버가 보는 것 | 처리 |
| --- | --- | --- |
| **중복 패킷** | 들어온 n < Server Shadow의 n | 이미 처리한 편집 → 무시하고 평소대로 응답 |
| **요청 유실** | 아무것도 안 옴 | ACK 없음 → 클라는 스택에 계속 쌓아두고 다음에 전부 재전송 |
| **응답 유실** | m이 Server Shadow와 불일치, 하지만 n,m 둘 다 **Backup Shadow**와 일치 | "아 내 응답이 유실됐구나" 판단 → Backup Shadow를 Shadow로 복원 → 첫 편집은 버리고(중복) 두 번째부터 처리 |
| **순서 뒤바뀜** | 유실 시나리오 후 뒤늦게 도착 | 유실 시나리오 → 중복 시나리오 순으로 처리됨 |
| **메모리/네트워크 손상** | 체크섬 불일치 or 버전이 미래로 점프 | 재초기화. 한쪽 데이터 손실은 있지만 **무한 폴링 루프에는 절대 안 빠짐** |

### 비대칭성: 왜 서버만 Backup Shadow를 갖는가

클라이언트-서버는 **연결을 시작할 수 있는 쪽이 클라이언트뿐**이라서 비대칭이다.

가능한 결과는 3가지뿐:

1. 클라이언트 → 서버 전송이 유실
2. 클라이언트 → 서버는 성공, 서버 → 클라이언트 응답이 유실
3. 왕복 성공

**"클라이언트 데이터는 유실됐는데 서버 데이터는 도착"하는 경우가 없다.**
서버가 뭔가 보냈다는 건 이미 클라의 연결이 성공했다는 뜻.
→ 서버가 "일방적으로 계속 보내는데 클라에서는 아무것도 안 오는" 상황이 불가능 → 클라이언트는 백업 불필요.

---

## 5. 토폴로지 / 확장

Server Text는 모든 동기화 루프가 **공유**한다.
Client 1이 바꾸면 → 다음 사이클에 Server Text 반영 → 그 다음 사이클에 다른 클라이언트들로 전파.

### 스케일아웃 방법 2가지

**① DB 분리**
알고리즘 서버 여러 대 + 공통 DB 1개.
모든 서버가 같은 DB 뷰를 보면 어느 서버로 붙어도 일관성 유지.

**② 서버-서버 토폴로지**
서버끼리도 클라이언트-서버와 **완전히 동일한 방식**으로 연결한다.

---

## 6. Diff와 Patch — 실제 품질을 좌우하는 부분

Differential Sync는 **콘텐츠 종류를 안 가린다**. diff 알고리즘 + fuzzy patch 알고리즘만 있으면
평문, 리치 텍스트, 비트맵, 벡터 그래픽 다 가능.

### diff는 두 가지 역할을 한다

| 역할 | 난이도 | 설명 |
| --- | --- | --- |
| Shadow 갱신 | 쉬움 | 세 텍스트를 동일하게 만들면 끝. 전체 전송으로도 가능 |
| Server Text 갱신 | **어려움** | 그 사이 Server Text가 바뀌었을 수 있음  |

### 최소 diff vs 의미 diff

`cat` → `hag` 로 바꾼 상황.

```
Client Text:   The cat is here.
Client Shadow: The hag is here.

Minimal Diff:  The c[h]a[t→g] is here.   ← 'a'가 같으니 1,3번째 글자만 교체
Semantic Diff: The [cat→hag] is here.    ← 단어 통째로 교체
```

'a'가 겹친 건 **순전히 우연**이다. 사용자는 글자가 아니라 **단어**를 바꾼 것.

왜 중요한가 → 동시에 서버 쪽에서 `cat` → `cut` 으로 바꿨다고 하자.

| diff 종류 | 병합 결과 |
| --- | --- |
| Minimal | `hug` ❌ — **아무도 입력한 적 없는 단어** |
| Semantic | `hag` (client wins) 또는 `cut` (server wins) ✅ |

→ 최소 diff를 **의미 있는 단위로 확장하는 알고리즘**이 반드시 필요하다.

### patch에 요구되는 것

- 예상 텍스트와 **정확히 일치하지 않아도** 주변에 적용 가능해야 함
- 덮어쓸 필요 없는 변경은 건드리지 않기

---

## 7. Adaptive Timing (동기화 주기 자동 조절)

### 트레이드오프

- 주기가 너무 길면 → diff/patch 비용 증가, 큰 충돌, 병합 실패
- 주기가 너무 짧으면 → 네트워크 트래픽 + 서버 부하 증가

### 자주 하는 게 계산상 유리

diff는 **O(n²)** (n = 변경분 길이).
동기화가 **변경 1개 간격**으로 일어나면 사실상 **O(1)** 수준이 된다.

---