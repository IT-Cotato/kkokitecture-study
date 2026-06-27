# 팔로워가 많은 사용자는 왜 Fanout-on-read로 처리할까?

## Fanout-on-write의 쓰기 증폭 문제

---

# 출처

- VMware Tanzu - Staple Yourself to a Tweet to Understand 30 Billion Redis Updates Per Day  
  https://blogs.vmware.com/tanzu/case-study-staple-yourself-to-a-tweet-to-understand-30-billion-redis-updates-per-day/

- High Scalability - How Twitter Uses Redis to Scale  
  https://highscalability.com/how-twitter-uses-redis-to-scale-105tb-ram-39mm-qps-10000-ins/

- High Scalability - Feeding Frenzy: Selectively Materializing Users' Event Feeds  
  https://highscalability.com/paper-feeding-frenzy-selectively-materializing-users-event-f/

- Meta Engineering - Serving Facebook Multifeed  
  https://engineering.fb.com/2015/03/10/production-engineering/serving-facebook-multifeed-efficiency-performance-gains-through-redesign/

- Meta Engineering - How machine learning powers Facebook's News Feed ranking algorithm  
  https://engineering.fb.com/2021/01/26/core-infra/news-feed-ranking/

---

# 문제 배경

11장에서는 뉴스 피드 발행 방식으로 `fanout-on-write`와 `fanout-on-read`를 설명한다.

뉴스 피드 시스템에서는 대부분의 일반 사용자를 `fanout-on-write` 방식으로 처리할 수 있다.

사용자가 글을 작성하는 시점에 친구나 팔로워의 피드 캐시를 미리 갱신해두면, 피드 조회 시점에는 이미 준비된 포스트 ID 목록을 빠르게 읽을 수 있기 때문이다.

하지만 팔로워가 매우 많은 사용자는 같은 방식으로 처리하기 어렵다.

포스팅 하나가 수많은 피드 캐시 쓰기로 확대되면서 팬아웃 큐, 작업 서버, 캐시 서버에 큰 부하를 만들 수 있다.

따라서 대규모 뉴스 피드 설계에서는 일반 사용자와 고팔로워 사용자를 같은 방식으로 처리하지 않고, `fanout-on-write`와 `fanout-on-read`를 함께 사용하는 구조를 고려한다.

---

# 1. Fanout-on-write는 쓰기 1번이 아니다

`fanout-on-write`는 사용자가 글을 작성한 시점에 팔로워들의 피드 캐시를 미리 갱신하는 방식이다.

예를 들어 A가 글을 하나 올렸고 팔로워가 500명이라면, 시스템은 500명의 피드에 A의 글 ID를 넣어야 한다.

```text
A가 post_100 작성

follower_1의 피드에 post_100 삽입
follower_2의 피드에 post_100 삽입
follower_3의 피드에 post_100 삽입
...
follower_500의 피드에 post_100 삽입
```

사용자 입장에서는 글쓰기 한 번이다.

하지만 시스템 입장에서는 팔로워 수만큼 피드 캐시를 수정하는 작업이다.

이렇게 하나의 쓰기가 여러 쓰기로 늘어나는 것을 쓰기 증폭(write amplification)으로 볼 수 있다.

```text
실제 쓰기 비용 ~= 포스팅 수 x 팔로워 수
```

fanout-on-write는 읽기를 빠르게 만드는 대신, 글을 쓰는 순간에 비용을 몰아서 낸다.

---

# 2. 팔로워가 적으면 괜찮다

일반 사용자는 fanout-on-write가 잘 맞는다.

팔로워나 친구 수가 제한적이기 때문이다.

```text
팔로워 300명인 사용자

글 1개 작성
-> 피드 캐시 업데이트 300번
```

300번의 캐시 쓰기는 부담이 될 수는 있지만, 대규모 시스템에서 처리하지 못할 정도는 아니다.

대신 읽기는 아주 빨라진다.

피드를 조회할 때 이미 `newsfeed:{user_id}` 같은 캐시에 포스트 ID 목록이 들어 있기 때문이다.

```text
사용자 B가 피드 조회
-> newsfeed:B 조회
-> post_id 목록 획득
-> 포스트 본문, 작성자 정보 조회
-> 응답
```

뉴스 피드는 쓰기보다 읽기가 훨씬 자주 발생한다.

그래서 일반 사용자에게는 쓰기 시점에 조금 더 일하고, 읽기 시점에는 빠르게 응답하는 편이 유리하다.

---

# 3. 팔로워가 많으면 숫자가 달라진다

문제는 팔로워가 아주 많은 사용자다.

예를 들어 팔로워가 1,000만 명인 사용자가 글을 하나 올렸다고 하자.

```text
팔로워 1,000만 명
x 글 1개
= 피드 캐시 업데이트 1,000만 번
```

여기에 복제까지 들어가면 쓰기 수는 더 커진다.

```text
피드 캐시 복제본 3개

1,000만 명 x 3
= 캐시 쓰기 3,000만 번
```

글 하나 때문에 수천만 개의 캐시 쓰기가 생긴다.

여기서 문제는 단순히 데이터가 많다는 정도가 아니다.

- 팬아웃 작업이 큐에 한꺼번에 쌓인다.
- 워커가 한 사람의 글을 처리하느라 오래 묶인다.
- Redis 같은 캐시 서버에 쓰기 트래픽이 몰린다.
- 일부 사용자는 피드 반영이 늦어진다.
- 같은 유명인을 팔로우하는 사용자가 많아 특정 포스트가 핫해진다.

팔로워가 많다는 것은 단순히 인기 있는 계정이라는 뜻이 아니다.

시스템 입장에서는 글 하나가 너무 많은 파생 작업을 만든다는 뜻에 가깝다.

---

# 4. 실제로 보지 않는 사람에게도 쓰게 된다

fanout-on-write의 또 다른 문제는 비활성 사용자다.

팔로워가 1,000만 명이라고 해서 그 1,000만 명이 모두 오늘 피드를 보는 것은 아니다.

예를 들어 오늘 피드를 여는 사용자가 100만 명뿐이라면 나머지 900만 명에게는 미리 피드를 만들어 둔 셈이 된다.

```text
fanout-on-write

1,000만 명의 피드에 모두 push
실제로 오늘 보는 사람은 100만 명
나머지 900만 명에게 한 쓰기는 당장은 사용되지 않음
```

일반 사용자라면 이 낭비가 작다.

하지만 유명인 계정에서는 이 낭비가 매우 커진다.

접속하지 않는 사용자들의 피드까지 매번 갱신하면 캐시 메모리와 워커 시간이 계속 소모된다.

---

# 5. Fanout-on-read는 비용을 없애는 방식이 아니다

`fanout-on-read`는 유명인의 글을 모든 팔로워 피드에 미리 넣지 않는다.

대신 사용자가 피드를 볼 때, 그 사용자가 팔로우한 유명인의 최신 글을 가져와 기존 피드와 합친다.

```text
B가 피드 조회

1. B의 일반 친구 글은 미리 계산된 피드 캐시에서 조회
2. B가 팔로우한 유명인 글은 작성자별 저장소에서 조회
3. 두 결과를 합침
4. 시간순 또는 랭킹순으로 정렬
```

물론 fanout-on-read가 공짜는 아니다.

읽을 때 해야 할 일이 늘어난다.

하지만 한 순간에 1,000만 명의 피드를 수정하는 것보다는 낫다.

유명인의 글 작성은 한 순간에 발생하지만, 팔로워들이 피드를 여는 시간은 분산된다.

```text
fanout-on-write
-> 글 작성 순간에 1,000만 명에게 작업 집중

fanout-on-read
-> 실제 조회 요청이 들어올 때마다 조금씩 처리
```

fanout-on-read는 비용을 없애기보다, 한 번에 몰리는 쓰기 비용을 여러 읽기 요청으로 나눠서 치르는 쪽에 가깝다.

---

# 6. Twitter의 Redis 타임라인 사례

Twitter는 홈 타임라인을 빠르게 보여주기 위해 Redis를 사용했다.

트윗이 작성되면 fanout daemon이 팔로워 목록을 가져오고, 각 팔로워의 홈 타임라인 Redis 리스트에 트윗 정보를 넣는다.

여기서 Redis에 저장하는 것은 트윗 본문 전체가 아니다.

주로 트윗 ID, 작성자 ID처럼 타임라인을 다시 구성하는 데 필요한 작은 데이터다.

```text
home_timeline:user_1 -> [tweet_9, tweet_8, tweet_7]
home_timeline:user_2 -> [tweet_9, tweet_3, tweet_1]
```

이 구조는 피드 읽기를 빠르게 만든다.

하지만 팔로워가 많은 계정이 트윗을 올리면 많은 Redis 리스트를 동시에 갱신해야 한다.

VMware Tanzu 글에서는 Twitter가 하루 300억 건 규모의 Redis 업데이트를 처리했다고 설명한다.

이 사례를 보면 fanout-on-write가 단순한 캐시 저장이 아니라, 엄청난 양의 쓰기 작업이라는 점이 드러난다.

---

# 7. 기준은 팔로워 수만이 아니다

단순하게는 팔로워 수를 기준으로 나눌 수 있다.

```text
팔로워 수가 적다
-> fanout-on-write

팔로워 수가 많다
-> fanout-on-read
```

하지만 실제로는 이것만으로 부족하다.

`Feeding Frenzy` 논문은 생산자와 소비자의 행동을 같이 봐야 한다고 말한다.

여기서 생산자는 글을 쓰는 사람이고, 소비자는 피드를 읽는 사람이다.

생산자가 자주 글을 쓰는데 소비자가 잘 보지 않는다면, 미리 push하는 비용은 낭비가 된다.

반대로 소비자가 자주 피드를 본다면, 미리 계산해두는 편이 조회 시간을 줄인다.

```text
글을 자주 쓰는 사람 + 잘 안 보는 팔로워
-> 미리 push하면 낭비가 큼

가끔 글을 쓰는 사람 + 자주 보는 팔로워
-> 미리 push해두는 편이 유리
```

그래서 기준을 조금 더 현실적으로 보면 다음 항목들이 같이 들어간다.

- 작성자의 팔로워 수
- 작성자의 포스팅 빈도
- 팔로워들의 접속률
- 팔로워들의 피드 조회 빈도
- 피드 조회 지연 시간 목표
- 팬아웃 큐와 캐시가 감당할 수 있는 쓰기 처리량

---

# 8. Hybrid 방식으로 섞기

대규모 뉴스 피드에서는 한 가지 방식만 쓰기 어렵다.

일반 사용자의 글은 fanout-on-write로 처리한다.

```text
일반 사용자 글
-> 팔로워 피드 캐시에 미리 삽입
-> 읽을 때 빠름
```

유명인이나 팔로워가 많은 사용자의 글은 fanout-on-read로 처리한다.

```text
유명인 글
-> 모든 팔로워 피드에 미리 넣지 않음
-> 사용자가 피드를 열 때 가져와서 합침
```

피드 조회 시에는 두 결과를 합친다.

```text
미리 계산된 일반 피드
+ pull로 가져온 유명인 글
= 최종 뉴스 피드
```

이렇게 섞으면 일반적인 경우에는 피드를 빠르게 읽을 수 있고, 팔로워가 많은 계정에서는 쓰기 폭증을 피할 수 있다.

---

# 9. 정리

fanout-on-write는 읽기 성능을 위해 쓰기 시점에 미리 계산하는 방식이다.

팔로워가 적으면 이 비용은 감당 가능하다.

하지만 팔로워가 수백만 명이면 글 하나가 수백만 개의 피드 캐시 쓰기로 바뀐다.

이때 fanout-on-read를 사용하면 모든 팔로워에게 미리 push하지 않고, 실제로 피드를 읽는 사용자에게만 유명인의 글을 가져와서 합칠 수 있다.

팔로워가 많은 사용자를 fanout-on-read로 처리하는 이유는 결국 여기에 있다.

```text
글 하나가 만드는 쓰기 폭발을 막기 위해서다.
```

뉴스 피드 설계에서 hybrid 방식은 fanout-on-write와 fanout-on-read 중 하나를 고르는 문제가 아니다.

사용자별로 비용이 발생하는 위치를 다르게 두는 전략에 가깝다.
