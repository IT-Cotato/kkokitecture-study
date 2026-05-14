<html>
<body>
<!--StartFragment--><h1>8장. URL 단축기 설계</h1>
<h1>1단계 문제 이해 및 설계 범위 확정</h1>
<ul>
<li>트래픽 규모: 매일 1억 개의 단축 URL 생성 가능</li>
<li>단축 URL 길이: 짧을수록 좋음</li>
<li>문자 제한: 숫자, 영문자만 가능</li>
<li>단축 URL 삭제/갱신 불가</li>
</ul>
<p>시스템의 기본적 기능</p>
<ol>
<li>URL 단축</li>
<li>URL 리디렉션: 축약된 URL로 HTTP 요청이 오면 원래 URL로 안내</li>
<li>높은 가용성, 규모 확장성, 장애 감내</li>
</ol>
<h2>개략적 추정</h2>
<ul>
<li>쓰기 연산: 매일 1억 개의 단축 URL 생성</li>
<li>초당 쓰기 연산: 1억 / 24 / 3600 = 1160</li>
<li>읽기 연산과 쓰기 연산 비율은 10:1이라고 했을 때, 읽기 연산은 초당 11,600회 발생</li>
<li>URL 단축 서비스를 10년간 운영한다고 가정하면 1억 X 365 X 10 = 3650억 개의 레코드 보관 필요</li>
<li>축약 전 URL 평균 길이 100</li>
<li>10년 동안 필요한 저장 용량 = 3650억 X 100바이트 = 36.5TB</li>
</ul>
<h1>2단계 개략적 설계안 제시 및 동의 구하기</h1>
<h2>API 엔드포인트</h2>
<p>URL 단축기는 기본적으로 두 개의 엔드포인트를 필요로한다.</p>
<ol>
<li>URL 단축용 엔드포인트
<ul>
<li>새 단축 URL을 생성하고자하는 클라이언트는 이 엔드포인트에 단축할 URL을 인자로 실어서 POST 요청을 보내야 한다.</li>
<li>POST /api/v1/data/shorten
<ul>
<li>인자: {longUrl: longURLstring}</li>
<li>반환: 단축 URL</li>
</ul>
</li>
</ul>
</li>
<li>URL 리디렉션용 엔드포인트
<ul>
<li>단축 URL에 대해 HTTP 요청이 오면 원래 URL로 보내주기 위한 용도의 엔드포인트</li>
<li>GET /api/v1/shortUrl
<ul>
<li>반환: HTTP 리디렉션 목적지가 될 원래 URL</li>
</ul>
</li>
</ul>
</li>
</ol>
<h2>URL 리디렉션</h2>
<p>

<img width="622" height="309" alt="Image" src="https://github.com/user-attachments/assets/4549e1b4-f6f3-4fdb-8dfa-9d322de82eb4" />

</p>
<p>단축 URL을 받은 서버는 그 URL을 원래 URL로 바꿔서 301 응답의 Location 헤더에 넣어 반환한다.</p>
<p>&lt;클라이언트와 서버 사이의 통신 절차&gt;</p>
<p>

<img width="423" height="459" alt="Image" src="https://github.com/user-attachments/assets/90173429-685c-432a-b38b-dc872a92e603" />

</p>
<p>유의할 것은 301 응답과 302 응답의 차이다. 둘 다 리디렉션 응답이지만 차이가 있다.</p>
<h3>301 Permanently Moved</h3>
<ul>
<li>해당 URL에 대한 HTTP 요청의 처리 책임이 영구적으로 Location 헤더에 반환된 URL로 이전되었다는 응답</li>
<li>영구적으로 이전되었으므로, 브라우저는 이 응답을 캐시한다.</li>
<li>추후 같은 단축 URL로 요청을 보낼 필요가 있을 때 브라우저는 캐시된 원래 URL로 요청을 보낸다.</li>
</ul>
<h3>302 Found</h3>
<ul>
<li>주어진 URL로의 요청이 ‘일시적으로’  Location 헤더가 지정하는 URL에 의해 처리되어야 한다는 응답</li>
<li>클라이언트의 요청은 언제나 단축 URL 서버로 먼저 보내진 후에 원래 URL로 리디렉션되어야 한다.</li>
</ul>
<p>서버 부하를 줄이는 것이 중요 → 301 Permanently Moved</p>
<p>첫 번째 요청만 단축 URL 서버로 전송될 것이기 때문이다.</p>
<p>트래픽 분석이 중요 → 302 Found</p>
<p>클릭 발생률이나 발생 위치를 추적하는 데 더 유리할 것이다.</p>
<p>URL 리디렉션을 구현하는 가장 직관적인 방법은 해시 테이블을 사용하는 것이다.</p>
<ul>
<li>&lt;단축 URL, 원래 URL&gt; 쌍 저장</li>
<li>원래 URL = hashTable.get(단축 URL)</li>
<li>301 또는 302 응답 Location 헤더에 원래 URL을 넣은 후 전송</li>
</ul>
<h2>URL 단축 플로우</h2>
<p>중요한 것은 긴 URL을 이 해시 값으로 대응시킬 해시 함수 fx를 찾는 일이다.</p>
<p>해시 함수 요구사항</p>
<ul>
<li>입력으로 주어지는 긴 URL이 다른 값이면 해시 값도 달라야 한다.</li>
<li>계산된 해시 값은 원래 입력으로 주어졌던 긴 URL로 복원될 수 있어야 한다.</li>
</ul>
<h1>3단계 상세 설계</h1>
<h2>데이터 모델</h2>
<p>모든 것을 해시 테이블에 두는 설계는 초기 전략으로는 괜찮지만 실제 시스템에 쓰기에는 곤란하다. 메모리는 유한하며 비싸기 때문이다.</p>
<p>더 나은 방법은 &lt;단축 URL, 원래 URL&gt;의 순서쌍을 관계형 데이터베이스에 저장하는 것이다.</p>
<p>

<img width="227" height="219" alt="Image" src="https://github.com/user-attachments/assets/b0de1f0c-8214-4a59-a1e3-bfdd6a01f62b" />

</p>
<h2>해시 함수</h2>
<p>편의상 해시 함수가 계산하는 단축 URL 값을 hashValue라고 지칭하겠다.</p>
<h3>해시 값 길이</h3>
<p>hashValue는 [0-9, a-z, A-Z]의 문자들로 구성된다.</p>
<p>따라서 사용할 수 있는 문자 개수는 62개다.</p>
<p>hashValue의 길이를 정하기 위해서는 62^n ≥ 365억인 n의 최솟값을 찾아야 한다.</p>
<p>개략적 추정치에 따르면 이 시스템은 3650억 개의 URL을 만들어 낼 수 있어야 한다.</p>
<p>&lt;hashValue의 길이와 해시 함수가 만들 수 있는 URL 개수의 관계&gt;</p>
<p>

<img width="614" height="409" alt="Image" src="https://github.com/user-attachments/assets/5a0f04d8-68d4-4869-a496-eb4fd93c7819" />

</p>
<p>n = 7이면 3.5조의 URL을 만들 수 있다. 요구사항을 만족시키기 충분하므로 hashValue의 길이는 7로 하자.</p>
<h3>해시 후 충돌 해소</h3>
<p>긴 URL을 줄이려면, 원래 URL을 7글자 문자열로 줄이는 해시 함수가 필요하다.</p>
<p>손쉬운 방법은 CRC32, MD5, SHA-1과 같이 잘 알려진 해시 함수를 이요하는 것이다.</p>
<p>&lt; <a href="https://en.wikipedia.org/wiki/Systems_design%EB%A5%BC">https://en.wikipedia.org/wiki/Systems_design를</a> 축약한 결과&gt;</p>
<p>

<img width="473" height="188" alt="Image" src="https://github.com/user-attachments/assets/b383e11b-1a78-4292-85c2-76658ceedd7f" />

</p>
<p>그러나 가장 짧은 해시값도 7보다 길다.</p>
<p><strong>해결 방법</strong></p>
<ol>
<li>
<p>처음 7개 글자만 사용, 충돌 시 사전 문자열을 해시값에 덧붙임</p>
<ul>
<li>해시 결과 충돌 확률 높아짐</li>
</ul>
<p>

<img width="626" height="401" alt="Image" src="https://github.com/user-attachments/assets/73e69173-d9b0-4898-9429-1780f3d37187" />

</p>
</li>
</ol>
<p>⇒ 충돌은 해소할 수 있으나, 단축 URL을 생성할 때 한 번 이상 데이터베이스 질의를 해야 하므로 오버헤드가 크다.</p>
<p>데이터베이스 대신 블룸 필터를 사용하면 성능을 높을 수 있다.</p>
<ul>
<li>블룸 필터: 어떤 집합에 특정 원소가 있는지 검사할 수 있도록 하는, 확률론에 기초한 공간 효율이 좋은 기술</li>
</ul>
<h3>base-62 변환</h3>
<p>진법 변환(base conversion)은 URL 단축기를 구현할 때 흔히 사용되는 접근법이다.</p>
<p>이 기법은 수의 표현 방식이 다른 두 시스템이 같은 수를 공유하여야 하는 경우에 유용하다.</p>
<p>62진법을 쓰는 이유는 hashValue에 사용할 수 있는 문자 개수가 62개이기 때문이다.</p>
<p><strong>base-62 변환 과정 (10진수로 11157을 62진수로 변환)</strong></p>
<ul>
<li>
<p>62진법: 수를 표현하기 위해 총 62개의 문자를 사용하는 진법</p>
<ul>
<li>0 → 0 , 9 → 9, 10→ a, 11 → b, 35 → z, 36 → A, 61 → Z</li>
</ul>
</li>
<li>
<p>11157 = 2 x 62^2 + 66 x 62^1 + 59 x 62^0 = [2, 55, 59] → [2, T, X] → 2TX</p>
<p>

<img width="539" height="283" alt="Image" src="https://github.com/user-attachments/assets/4e41ec8b-2dba-471c-977a-fb817c6fd905" />

</p>
</li>
<li>
<p>따라서 단축 URL은 httpsL//tinyurl.com/2TX 가 된다.</p>
</li>
</ul>
<h3>두 접근법 비교</h3>

해시 후 충돌 해소 전략 | base-62 변환
-- | --
단축 URL의 길이가 고정됨 | 단축 URL의 길이가 가변적. ID 값이 커지면 같이 길어짐
유일성이 보장되는 ID 생성기가 필요치 않음 | 유일성 보장 ID 생성기가 필요
충돌이 가능해서 해소 전략이 필요 | ID의 유일성이 보장된 후에야 적용 가능한 전략이라 충돌은 아예 불가능
ID로부터 단축 URL을 계산하는 방식이 아니라서 다음에 쓸 수 있는 URL을 알아내는 것이 불가능 | ID가 1씩 증가하는 값이라고 가정하면 다음에 쓸 수 있는 단축 URL이 무엇인지 쉽게 알아낼 수 있어서 보안상 문제가 될 소지가 있음


<h2>URL 단축기 상세 설계</h2>
<p>

<img width="537" height="424" alt="Image" src="https://github.com/user-attachments/assets/d12a847b-3b3b-4899-96b9-c7b1a4c553ad" />

</p>
<ol>
<li>입력으로 긴 URL을 받는다.</li>
<li>데이터베이스에 해당 URL이 있는지 검사한다.</li>
<li>데이터베이스에 있다면 해당 URL에 대한 단축 URL을 만든 적이 있는 것이다.
<ul>
<li>데이터베이스에서 해당 단축 URL을 가져와서 클라이언트에게 반환한다.</li>
</ul>
</li>
<li>데이터베이스에 없는 경우에는 해당 URL은 새로 접수된 것이므로 유일한 ID를 생성한다.
<ul>
<li>데이터베이스의 기본 키로 사용된다.</li>
</ul>
</li>
<li>62진법 변환을 적용, ID를 단축 URL로 만든다.</li>
<li>ID, 단축 URL, 원래 URL로 새 데이터베이스 레코드를 만든 후 단축 URL을 클라이언트에 전달한다.</li>
</ol>
<p>이 생성기의 주된 용도는 단축 URL을 만들 때 사용할 ID를 만드는 것이고, 이 ID는 전역적 유일성이 보장되는 것이어야 한다.</p>
<h2>URL 리디렉션 상세 설계</h2>
<p>쓰기보다 읽기를 더 자주 하는 시스템이라, &lt;단축 URL, 원래 URL&gt;의 쌍을 캐시에 저장하여 성능을 높였다.</p>
<p>

<img width="537" height="424" alt="Image" src="https://github.com/user-attachments/assets/7d5b4b9c-f2f2-4cb1-ae1d-12b7be396ce8" />

</p>
<h3>로드밸런서 동작 흐름</h3>
<ol>
<li>사용자가 단축 URL을 클릭한다.</li>
<li>로드밸런서가 해당 클릭으로 발생한 요청을 웹 서버에 전달한다.</li>
<li>단축 URL이 이미 캐시에 있는 경우에는 원래 URL을 바로 꺼내서 클라이언트에게 전달한다.</li>
<li>캐시에 해당 단축 URL이 없는 경우에는 데이터베이스에서 꺼낸다.
<ul>
<li>데이터베이스에 없다면 아마 사용자가 잘못된 단축 URL을 입력한 경우일 것이다.</li>
</ul>
</li>
<li>데이터베이스에서 꺼낸 URL을 캐시에 넣은 후 사용자에게 반환한다.</li>
</ol>
<h1>4단계 마무리</h1>
<p>처리율 제한 장치</p>
<ul>
<li>지금까지 살펴본 시스템은 엄청난 양의 URL 단축 요청이 밀려들 경우 무력화될 수 있다는 잠재적 보안 결함을 갖고 있다.</li>
<li>처리율 제한 장치를 두면, IP 주소를 비롯한 필터링 규칙들을 이용해 요청을 걸러낼 수 있다.</li>
</ul>
<p>웹 서버의 규모 확장</p>
<ul>
<li>본 설계에 포함된 웹 계층은 무상태 계층이므로, 웹 서버를 자유로이 증설/삭제할 수 있다.</li>
</ul>
<p>데이터베이스의 규모 확장</p>
<ul>
<li>데이터베이스를 다중화하거나 샤딩하여 규모 확장성을 달성할 수 있다.</li>
</ul>
<p>데이터 분석 솔루션</p>
<ul>
<li>URL 단축기에 데이터 분석 솔루션을 통합해 두면 어떤 링크를 얼마나 많은 사용자가 클릭했는지, 언제 주로 클릭했는지 등 중요한 정보를 알아낼 수 있을 것이다.</li>
</ul>
<p>가용성, 데이터 일관성, 안정성</p>
<ul>
<li>대규모 시스템이 성공적으로 운영되기 위해서는 반드시 갖추어야 할 속성들이다.</li>
</ul>
<!-- notionvc: 35431486-a3d2-4a48-b526-ef22ec332aae --><!--EndFragment-->
</body>
</html>