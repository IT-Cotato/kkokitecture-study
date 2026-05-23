# JavaScript 기반 웹 크롤링과 초대규모 크롤러의 한계

## HTML을 가져오는 것만으로 충분하지 않은 이유

---

# 출처

- Google Search Central - Understand the JavaScript SEO basics  
  https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics

- Google Search Central - Dynamic Rendering as a workaround  
  https://developers.google.com/search/docs/crawling-indexing/javascript/dynamic-rendering

- Hsin-Tsang Lee, Derek Leonard, Xiaoming Wang, Dmitri Loguinov - IRLbot: Scaling to 6 Billion Pages and Beyond  
  https://irl.cs.tamu.edu/people/hsin-tsang/papers/www2008.pdf

- WWW 2008 Refereed Papers - IRLbot: Scaling to 6 Billion Pages and Beyond  
  https://archives.iw3c2.org/www2008/papers/fp865.html

---

# 왜 이 주제를 골랐는가?

9장에서는 웹 크롤러를 HTML 다운로더, URL 추출기, URL 필터, 미수집 URL 저장소 같은 컴포넌트로 나누어 설명한다.

이 구조는 전통적인 웹 페이지에는 잘 맞는다.

하지만 현대 웹에서는 HTML 응답만 받아서는 사용자가 보는 콘텐츠를 얻지 못하는 경우가 많다.

예를 들어 React, Vue, Angular 기반 SPA는 처음에 다음과 비슷한 HTML만 내려줄 수 있다.

```html
<div id="root"></div>
<script src="/bundle.js"></script>
```

이 경우 크롤러가 HTML만 파싱하면 본문도, 상품 목록도, 댓글도 찾지 못한다.

즉 현대 웹 크롤러의 어려움은 단순히 페이지를 많이 다운로드하는 것이 아니라, **JavaScript 실행 이후의 실제 콘텐츠를 비용 효율적으로 수집하는 것**이다.

---

# 1. Google은 JavaScript 페이지를 어떻게 처리하는가?

Google Search Central 문서에 따르면 Google은 JavaScript 웹 앱을 크게 세 단계로 처리한다.

1. Crawling
2. Rendering
3. Indexing

전통적인 페이지에서는 crawling 단계에서 받은 HTML 안에 주요 콘텐츠가 들어 있다.

하지만 JavaScript 기반 페이지에서는 crawling 단계에서 받은 HTML이 빈 껍데기에 가까울 수 있다.

그래서 Googlebot은 페이지를 rendering queue에 넣고, 리소스가 허용될 때 headless Chromium으로 JavaScript를 실행한다.

렌더링이 끝난 뒤의 HTML을 다시 분석해서 링크를 찾고, 그 결과를 인덱싱에 사용한다.

여기서 중요한 점은 Googlebot이 JavaScript를 실행할 수 있다고 해서 모든 문제가 사라지는 것은 아니라는 점이다.

렌더링은 crawling과 별도의 단계로 처리되며, 별도 큐와 자원을 사용한다.

따라서 초기 HTML만으로 바로 처리할 수 있는 페이지보다 인덱싱까지 시간이 더 걸릴 수 있다.

또한 모든 검색 엔진, SNS 봇, 사내 크롤러가 Googlebot처럼 JavaScript를 실행할 수 있는 것도 아니다.

그래서 검색 노출이 중요한 콘텐츠라면 JavaScript 실행 이후에만 보이도록 두는 것보다 초기 HTML에 포함시키는 편이 더 안정적이다.

---

# 2. 렌더링 큐가 생기는 이유

JavaScript 실행은 단순 HTML 파싱보다 훨씬 비싸다.

HTML 파싱은 문자열을 읽고 링크를 추출하는 작업에 가깝지만, JavaScript 렌더링은 브라우저를 실행하는 것에 가깝다.

렌더링 크롤러는 다음 작업을 추가로 수행해야 한다.

- JavaScript 파일 다운로드
- CSS, 이미지, API 요청 같은 추가 리소스 처리
- DOM 생성 및 변경
- 비동기 요청 대기
- 클라이언트 라우팅 처리

따라서 모든 URL을 즉시 렌더링하면 CPU, 메모리, 네트워크 비용이 크게 증가한다.

Google 문서에서도 페이지가 rendering queue에 몇 초 머물 수 있지만, 더 오래 걸릴 수도 있다고 설명한다.

즉 크롤러 관점에서 JavaScript 렌더링은 별도의 대기열과 자원 스케줄링이 필요한 작업이다.

---

# 3. Dynamic Rendering은 어떤 문제를 해결하려 했는가?

Dynamic Rendering은 JavaScript 콘텐츠를 검색 엔진이 제대로 보지 못하는 문제를 우회하기 위한 방식이다.

기본 아이디어는 다음과 같다.

- 일반 사용자는 기존 CSR 페이지를 받는다.
- JavaScript 처리에 문제가 있는 크롤러는 미리 렌더링된 HTML을 받는다.
- 서버는 User-Agent 등을 보고 요청을 분기한다.

예를 들어 사용자는 React 앱을 받고, 크롤러는 렌더링 서버가 만든 정적 HTML을 받는 식이다.

이 방식은 검색 엔진이 JavaScript를 실행하지 못하거나, 실행 비용이 너무 큰 상황에서 유용했다.

하지만 Google은 Dynamic Rendering을 장기적인 해법으로 권장하지 않는다.

이유는 복잡도가 크기 때문이다.

- 사용자용 응답과 크롤러용 응답을 따로 관리해야 한다.
- 두 응답의 내용이 달라지면 cloaking으로 오해받을 수 있다.
- 렌더링 서버 운영 비용이 추가된다.
- 캐시, 오류 처리, 배포 파이프라인이 복잡해진다.

그래서 Google은 현재 Dynamic Rendering보다 SSR, Static Rendering, Hydration 같은 방식을 더 권장한다.

Dynamic Rendering이 항상 cloaking으로 간주되는 것은 아니다.

사용자에게 보이는 내용과 크롤러에게 제공하는 내용이 실질적으로 같다면 Googlebot은 일반적으로 cloaking으로 보지 않는다.

하지만 사용자에게는 A 내용을 보여주고 크롤러에게는 B 내용을 보여주는 식으로 달라지면 문제가 된다.

결국 Dynamic Rendering의 위험은 의도적인 조작뿐 아니라, 사용자용 화면과 크롤러용 HTML이 운영 중에 서로 달라질 수 있다는 데 있다.

---

# 4. SSR, SSG, Hydration은 왜 더 나은가?

Dynamic Rendering은 크롤러를 위해 별도 HTML을 만들어 주는 우회 방식이다.

반면 SSR, SSG, Hydration은 사용자와 크롤러 모두에게 더 완성된 HTML을 제공하는 방향이다.

## SSR

SSR(Server-Side Rendering)은 요청 시점에 서버에서 HTML을 만들어 내려준다.

크롤러는 JavaScript 실행 전에도 주요 콘텐츠를 볼 수 있다.

사용자도 초기 화면을 더 빨리 볼 수 있다.

## SSG

SSG(Static Site Generation)는 빌드 시점에 HTML을 미리 만들어 둔다.

콘텐츠가 자주 바뀌지 않는 문서, 블로그, 상품 상세 페이지 등에 유리하다.

크롤러 입장에서는 일반 정적 HTML 페이지와 비슷하게 처리할 수 있다.

## Hydration

Hydration은 서버가 먼저 HTML을 내려주고, 브라우저에서 JavaScript가 붙으면서 상호작용을 활성화하는 방식이다.

크롤러는 HTML 콘텐츠를 먼저 읽을 수 있고, 사용자는 이후 동적인 UI를 사용할 수 있다.

---

# 5. 9장 설계와 연결되는 지점

9장의 HTML 다운로더는 HTTP로 페이지를 내려받는 컴포넌트다.

하지만 JavaScript 기반 웹에서는 HTML 다운로더만으로는 부족할 수 있다.

따라서 실제 크롤러에는 다음 판단이 추가되어야 한다.

- 이 페이지는 HTML만 파싱해도 충분한가?
- JavaScript 렌더링이 필요한가?
- 렌더링이 필요하다면 모든 페이지를 렌더링할 것인가?
- 렌더링 실패, API 지연, 무한 로딩은 어떻게 처리할 것인가?
- 렌더링 결과에서 추출한 링크를 기존 URL frontier에 어떻게 다시 넣을 것인가?

이 관점에서 보면 현대 크롤러는 단순한 HTML 다운로더가 아니라, **HTML 크롤러와 렌더링 크롤러를 함께 운영하는 시스템**에 가깝다.

---

# 6. IRLbot 자료에서 같이 볼 점

IRLbot 논문은 JavaScript 렌더링 자체를 다루는 자료는 아니다.

하지만 초대규모 크롤러가 실제 웹에서 어떤 한계에 부딪히는지를 잘 보여준다.

논문은 기존 크롤링 알고리즘이 다음 문제를 효과적으로 처리하기 어렵다고 말한다.

- URL 중복 확인 비용이 크롤 규모와 함께 증가한다.
- BFS 방식은 스팸 사이트나 무한 URL 공간에 빠질 수 있다.
- 고정된 host별 rate limit은 특정 사이트의 URL이 많이 쌓일 때 크롤러 전체를 느리게 만들 수 있다.
- 서버 사이드 스크립트가 무한히 URL을 만들어낼 수 있다.

IRLbot은 41일 동안 단일 서버로 63억 개의 유효 HTML 페이지를 크롤링했고, 76억 번의 연결 요청을 처리했다.

또한 1억 1700만 개 이상의 host, 3940억 개의 링크, 410억 개의 unique node를 다루었다.

이 수치는 웹 크롤러 설계에서 “큐에 URL을 넣고 하나씩 처리한다”는 설명만으로는 부족하다는 점을 보여준다.

---

# 7. IRLbot과 JavaScript 크롤링을 같이 보면 보이는 것

두 자료를 함께 보면 크롤러의 병목은 두 방향에서 생긴다.

첫 번째는 **페이지 내부의 복잡도**다.

JavaScript 기반 페이지는 HTML을 받은 뒤에도 실행, 렌더링, 비동기 요청을 거쳐야 실제 콘텐츠가 나온다.

두 번째는 **웹 전체의 규모와 품질 문제**다.

IRLbot이 보여주듯이 웹에는 스팸, 무한 URL, 매우 큰 사이트, 동적으로 생성되는 host가 섞여 있다.

즉 현대 크롤러는 다음 두 질문에 동시에 답해야 한다.

```text
이 페이지를 제대로 보려면 렌더링해야 하는가?
이 페이지를 렌더링할 만큼 가치가 있는가?
```

렌더링은 비싸기 때문에 모든 URL에 적용하기 어렵다.

따라서 중요도, 신뢰도, 도메인 평판, 콘텐츠 변화 가능성 등을 바탕으로 렌더링 예산을 배분해야 한다.

모든 페이지를 headless browser로 크롤링하면 기능적으로는 많은 문제가 해결될 수 있다.

하지만 브라우저 렌더링은 CPU와 메모리를 많이 쓰고, 추가 네트워크 요청도 만든다.

무한 스크롤, 광고 스크립트, 느린 API, 무한 로딩까지 만나면 하나의 URL이 크롤러 자원을 오래 붙잡을 수 있다.

그래서 실제 시스템에서는 HTML 크롤러와 렌더링 크롤러를 분리하고, 필요한 URL만 렌더링하는 방식이 더 현실적이다.

이 지점에서 JavaScript 렌더링 문제와 crawler trap 문제가 연결된다.

crawler trap은 무한한 URL을 만들어 큐를 오염시키고, JavaScript 렌더링은 URL 하나를 처리하는 비용을 크게 만든다.

둘이 결합하면 크롤러는 비싼 렌더링 작업을 하면서도 가치 낮은 URL을 계속 발견하게 된다.

---

# 8. 설계에 추가한다면?

9장의 구조에 JavaScript 크롤링을 추가한다면 다음 컴포넌트를 고려할 수 있다.

## 렌더링 필요성 판별기

HTML 응답을 보고 이 페이지가 렌더링이 필요한지 판단한다.

예시는 다음과 같다.

- 본문 텍스트가 거의 없는 경우
- `<div id="root"></div>`처럼 app shell만 있는 경우
- 주요 링크가 HTML의 `href`에 거의 없는 경우
- 특정 프레임워크 번들이 포함된 경우

## 렌더링 큐

렌더링이 필요한 URL만 별도 큐에 넣는다.

HTML 크롤링 큐와 분리해야 전체 크롤링 처리량이 렌더링 비용에 끌려가지 않는다.

## Headless Browser Worker

Chromium 같은 브라우저 엔진으로 JavaScript를 실행한다.

이 worker는 일반 HTML 다운로더보다 비싸므로 concurrency, timeout, memory limit을 더 엄격하게 둔다.

## 렌더링 예산 관리자

도메인별, 우선순위별, 페이지 유형별로 렌더링 예산을 배분한다.

IRLbot의 평판 기반 예산 배분 아이디어를 렌더링에도 적용할 수 있다.

신뢰도가 낮거나 무한 URL을 만드는 사이트에는 렌더링 예산을 줄인다.

---

# 9. 결론

9장의 웹 크롤러 설계는 크롤러의 기본 골격을 설명한다.

추가 자료를 통해 알 수 있는 점은 이 골격만으로 현대 웹을 충분히 다루기 어렵다는 것이다.

JavaScript 기반 웹에서는 HTML 응답과 실제 사용자 화면이 다를 수 있다.

IRLbot 사례는 대규모 웹에서 URL 공간 자체가 크롤러를 압도할 수 있음을 보여준다.

따라서 현대 크롤러 설계의 핵심 질문은 다음과 같다.

```text
어떤 URL을 가져올 것인가?
그 URL을 HTML만으로 처리할 것인가, 렌더링까지 할 것인가?
비싼 렌더링 자원을 어디에 먼저 쓸 것인가?
```

결국 웹 크롤러는 다운로드 시스템을 넘어, 제한된 자원을 가치 있는 페이지에 배분하는 스케줄링 시스템에 가깝다.
