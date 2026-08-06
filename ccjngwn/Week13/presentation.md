# 채팅 메시지 검색 인프라는 왜 따로 필요할까?

## Discord의 메시지 검색 시스템으로 보는 검색 색인 설계

---

# 출처

- Discord Engineering - How Discord Indexes Billions of Messages  
  https://discord.com/blog/how-discord-indexes-billions-of-messages

- Discord Engineering - How Discord Indexes Trillions of Messages  
  https://discord.com/blog/how-discord-indexes-trillions-of-messages

- Discord Engineering - How Discord Stores Trillions of Messages  
  https://discord.com/blog/how-discord-stores-trillions-of-messages

- Elasticsearch Documentation - General index settings  
  https://www.elastic.co/docs/reference/elasticsearch/index-settings/index-modules

---

# 왜 이 주제를 골랐는가?

12장에서는 채팅 시스템의 핵심 흐름을 다룬다.

- 채팅 서버가 메시지를 주고받는다.
- 메시지 이력은 키-값 저장소에 저장한다.
- 사용자는 최근 메시지나 특정 시점 이후의 메시지를 읽는다.

이 구조는 "대화방의 최근 메시지 50개를 보여줘" 같은 요청에는 잘 맞는다.

하지만 사용자가 다음처럼 검색하면 문제가 달라진다.

```text
이 서버에서 "장애 회고"라는 단어가 들어간 메시지를 찾아줘
```

키-값 저장소는 보통 `channel_id`, `message_id` 같은 키 기반 조회에 강하다.

반면 검색은 메시지 본문 안의 단어, 작성자, 기간, 채널, 권한을 함께 봐야 한다.

즉 메시지를 잘 저장하는 시스템과 메시지를 잘 검색하는 시스템은 같은 문제가 아니다.

---

# 1. 원본 저장소만으로 검색하면 왜 어려운가?

12장의 메시지 저장소는 대체로 다음 접근 패턴에 최적화되어 있다.

```text
channel_id = C
message_id < X
LIMIT 50
```

특정 채널의 메시지를 시간순으로 가져오는 요청이다.

하지만 키워드 검색은 다음에 가깝다.

```text
guild_id = G
text contains "장애"
author_id = A
created_at between T1 and T2
```

이걸 원본 메시지 저장소에서 직접 처리하면 많은 채널과 메시지를 훑어야 할 수 있다.

그래서 본문 검색에는 보통 역색인(inverted index)이 필요하다.

```text
"장애" -> [message_id_1, message_id_7, message_id_20]
"회고" -> [message_id_7, message_id_42]
```

단어에서 메시지 목록으로 바로 찾아가는 구조다.

Discord도 메시지 원본 저장소와 별도로 Elasticsearch 기반 검색 인프라를 운영했다.

---

# 2. 검색 저장소는 원본 저장소가 아니다

Discord의 초기 검색 설계에서 중요한 점은 Elasticsearch에 메시지 전체를 복제하지 않았다는 것이다.

Elasticsearch에는 검색에 필요한 필드와 식별자를 넣고, 실제 메시지 객체는 원본 저장소에서 다시 가져왔다.

```text
Elasticsearch
- 검색 가능한 토큰
- message_id
- channel_id
- guild_id

Message Store
- 실제 메시지 본문
- 작성자
- 첨부 파일
- 주변 메시지 context
```

검색 결과 화면에는 검색된 메시지 하나만 필요한 것이 아니다.

사용자는 그 메시지 앞뒤 맥락도 함께 보고 싶어 한다.

그래서 검색 엔진이 `message_id`를 찾아주면, 애플리케이션은 원본 메시지 저장소에서 해당 메시지와 주변 메시지를 다시 읽는다.

이 설계는 검색 인프라를 원본 저장소의 완전한 복사본이 아니라, 검색을 위한 보조 인덱스로 보는 방식이다.

대신 주의할 점도 있다.

- 검색 결과와 원본 저장소 사이에 동기화 지연이 생길 수 있다.
- 원본 메시지가 삭제되면 검색 결과에서도 반영해야 한다.
- 검색 결과를 반환하기 전에 권한 검사를 해야 한다.

---

# 3. 검색은 꼭 실시간이어야 할까?

Discord는 메시지 검색을 실시간 기능으로 보지 않았다.

방금 보낸 메시지를 바로 검색하는 경우보다, 과거 대화를 찾는 경우가 더 많다고 판단했다.

그래서 검색 색인은 비동기 파이프라인으로 처리할 수 있다.

```text
메시지 생성
  -> 원본 메시지 저장소에 기록
  -> 색인 큐에 이벤트 추가
  -> 색인 작업자가 여러 메시지를 모아 Elasticsearch에 bulk indexing
```

이 방식에서는 메시지가 전송된 직후 검색에 바로 나타나지 않을 수 있다.

하지만 사용자는 채팅 화면에서 방금 보낸 메시지를 이미 볼 수 있다.

검색은 약간 늦어도 괜찮은 보조 기능이므로, 시스템은 검색 신선도보다 처리량과 안정성을 우선할 수 있다.

이 지점이 중요하다.

채팅 전송 경로는 낮은 지연시간이 중요하고, 검색 색인 경로는 높은 처리량과 재시도 가능성이 중요하다.

두 경로의 요구사항이 다르기 때문에 분리하는 편이 안전하다.

---

# 4. Lazy Indexing

Discord의 초기 검색 요구사항 중 하나는 lazy indexing이었다.

모든 서버의 모든 메시지를 처음부터 색인하지 않고, 사용자가 검색을 시도한 서버부터 색인하는 방식이다.

```text
검색 요청 발생
  -> 최근 메시지부터 먼저 색인
  -> 검색 기능을 빠르게 사용 가능하게 만듦
  -> 오래된 메시지는 background job으로 천천히 색인
```

검색을 거의 쓰지 않는 서버의 메시지까지 전부 색인하면 비용이 너무 크다.

lazy indexing은 실제로 검색이 필요한 데이터부터 비용을 쓰는 절충안이다.

다만 첫 검색 요청이 느릴 수 있고, 색인 진행 중 새로 들어오는 메시지도 함께 처리해야 한다.

즉 검색은 단순히 Elasticsearch에 데이터를 넣는 문제가 아니라, 색인 작업 자체를 장기 실행 작업으로 관리하는 문제다.

---

# 5. Elasticsearch refresh 비용

Elasticsearch는 문서를 색인했다고 해서 즉시 검색 가능한 것은 아니다.

refresh가 발생해야 새로 색인된 문서가 검색 대상에 포함된다.

Elasticsearch의 기본 refresh interval은 일반적으로 1초다.

하지만 Discord처럼 수많은 인덱스에 메시지를 계속 넣는 시스템에서는 매초 refresh가 큰 비용이 될 수 있다.

Discord는 검색이 필요한 순간에 애플리케이션 레벨에서 refresh를 제어하는 방식으로 비용을 줄였다.

```text
1. 색인 작업자가 메시지를 bulk indexing 한다.
2. Redis에 "이 guild의 index는 dirty 상태"라고 표시한다.
3. 사용자가 검색을 시도한다.
4. dirty 상태라면 해당 index를 refresh 한다.
5. 검색을 수행한다.
```

검색을 거의 하지 않는 서버라면 매초 refresh할 필요가 없다.

검색 요청이 들어온 순간에만 refresh해도 사용자 입장에서는 충분할 수 있다.

이 설계는 "검색 결과는 항상 1초 안에 최신이어야 한다"는 가정을 버렸기 때문에 가능했다.

---

# 6. Bulk indexing도 장애를 키울 수 있다

Elasticsearch는 문서를 하나씩 넣는 것보다 bulk indexing을 선호한다.

하지만 batch 안의 메시지가 여러 cluster와 index로 흩어져 있으면 장애 범위가 커질 수 있다.

Discord의 2025년 글에서는 다음 예를 든다.

```text
Elasticsearch cluster: 100 nodes
bulk batch: 50 messages
failed node: 1 node
```

50개 메시지가 여러 노드에 고르게 흩어진다면, 단일 노드 장애가 있어도 batch 하나가 그 장애 노드를 건드릴 확률이 커진다.

한 메시지만 실패해도 전체 bulk 요청을 다시 큐에 넣으면, 정상 노드로 가야 했던 메시지까지 재시도된다.

이러면 작은 장애가 큐 backlog와 재시도 폭증으로 번진다.

그래서 Discord의 새 구조는 메시지를 아무렇게나 묶지 않고, 목적지 cluster와 index 기준으로 묶는다.

```text
나쁜 batch
  [index-a, index-b, index-c, index-d]

좋은 batch
  [index-a, index-a, index-a, index-a]
```

이렇게 하면 특정 index나 node 문제가 전체 색인 파이프라인으로 번질 가능성을 줄일 수 있다.

---

# 7. 큰 서버는 다른 전략이 필요하다

대부분의 Discord 서버는 하나의 Elasticsearch shard에 메시지를 모아두는 편이 유리하다.

검색 쿼리가 여러 shard로 fanout되지 않기 때문이다.

```text
일반 guild
  -> 하나의 index
  -> 하나의 primary shard
  -> fanout 없이 검색
```

하지만 매우 큰 서버는 이 규칙이 깨진다.

Discord는 이런 서버를 BFG, 즉 Big Freaking Guild라고 부른다.

큰 guild는 단일 Lucene index의 문서 수 한계에 가까워질 수 있다.

이 경우 단일 shard에 모두 넣는 전략은 더 이상 지속 가능하지 않다.

그래서 큰 guild는 전용 Elasticsearch cell과 여러 primary shard를 사용한다.

```text
BFG
  -> 전용 cell
  -> 여러 primary shard
  -> query fanout 발생
  -> 하지만 병렬 처리 이득이 더 큼
```

중요한 점은 "항상 shard를 많이 쓰자"가 아니다.

일반 guild에서는 fanout 비용이 더 크고, BFG에서는 병렬 검색 이득이 더 크다.

같은 채팅 검색이라도 데이터 크기에 따라 최적의 sharding 전략이 달라진다.

---

# 8. 12장 설계에 검색을 붙인다면?

12장의 채팅 시스템에 검색을 추가한다면, 메시지 전송 경로에 검색을 직접 끼워 넣으면 안 된다.

대신 다음처럼 비동기 파이프라인을 둔다.

```text
Client
  -> Chat Server
  -> Message Store
  -> Index Event Queue
  -> Search Index Worker
  -> Search Engine
```

검색 요청은 별도 Search API가 처리한다.

```text
Client
  -> Search API
  -> Search Engine에서 message_id 검색
  -> Message Store에서 원본 메시지와 주변 context 조회
  -> 권한 검사 후 결과 반환
```

여기서 확인해야 할 질문은 다음과 같다.

- 검색 결과가 몇 초 늦게 반영되어도 되는가?
- 메시지 삭제와 수정은 검색 인덱스에 어떻게 반영할 것인가?
- 검색 결과를 반환하기 전에 권한 검사를 어디서 수행할 것인가?
- 검색 엔진 장애가 메시지 전송 경로에 영향을 주지 않도록 분리되어 있는가?
- 큰 서버가 다른 사용자의 검색 성능을 갉아먹지 않는가?

---

# 정리

채팅 시스템에서 메시지를 저장하는 것과 검색하는 것은 다른 문제다.

메시지 저장소는 시간순 조회와 동기화에 최적화된다.

검색 인프라는 본문 토큰, 색인 지연, 재시도, refresh 비용, shard fanout을 다뤄야 한다.

Discord 사례에서 배울 수 있는 점은 다음과 같다.

- 검색은 원본 저장소 위에 얹는 별도 읽기 모델로 보는 편이 좋다.
- 모든 메시지를 즉시 검색 가능하게 만들 필요는 없을 수 있다.
- bulk indexing은 처리량을 높이지만, 목적지가 섞이면 장애 범위를 키울 수 있다.
- 일반 서버와 매우 큰 서버는 다른 sharding 전략이 필요하다.

결국 좋은 채팅 검색 설계는 "Elasticsearch를 붙인다"가 아니라, 검색이라는 별도 읽기 경로의 비용과 실패를 채팅 전송 경로에서 얼마나 잘 분리하느냐에 달려 있다.
