# Zeta × JEV Feasibility Probe

이 확장 프로그램은 intervention의 효과를 구현하는 것이 아니라, **새 Zeta 응답을 사용자에게 보여주기 전에 JEV 판단을 기다리는 방식이 실제 사용 가능한 속도인지** 측정한다.

## 동작

1. 확장 프로그램을 활성화한 시점에 이미 있던 대화는 baseline으로 두고 건드리지 않는다.
2. 새 사용자 메시지가 DOM에 나타나는 즉시 현재 보이는 최근 대화 prefix와 3개 `noul` 질문을 JEV에 보낸다. Zeta 답변 생성과 JEV 판단은 병렬로 진행된다.
3. JEV가 처리 중일 때 새 캐릭터 응답 DOM이 나타나면 해당 말풍선만 즉시 `visibility: hidden` 처리한다.
4. JEV 응답, HTTP 오류, 네트워크 오류, timeout 중 무엇이 먼저 끝나든 말풍선을 공개한다. JEV가 Zeta보다 먼저 끝났다면 답변은 전혀 가리지 않는다. 실제 intervention UI는 표시하지 않는다.
5. JEV 전체 왕복, 캐릭터 답변이 실제로 가려진 시간, 성공 여부, 원 응답을 Chrome 로컬 저장소에 최대 500건 기록한다.

## 설치와 사용

1. `chrome://extensions`에서 개발자 모드를 켜고 이 폴더를 로드한다.
2. 확장 프로그램 세부정보의 **확장 프로그램 옵션**에서 TypeSafe API key를 입력한다.
3. Zeta 대화방을 새로고침한다.
4. 툴바 팝업에서 **Feasibility 측정 활성화**를 켠 뒤 새 메시지를 보낸다.
5. 여러 turn을 시험한 후 팝업에서 로그 JSON을 저장한다.

## 로그 해석

- `api_latency_ms`: service worker에서 측정한 JEV HTTP 왕복 시간.
- `decision_round_trip_ms`: content script가 요청을 시작해 결과를 받을 때까지의 전체 왕복 시간.
- `assistant_hidden_ms`: Zeta 답변이 화면에 처음 생긴 뒤 JEV 완료로 공개될 때까지 실제로 가린 시간. 체감 추가 지연의 핵심 지표다.
- `jev_finished_before_zeta_reply`: `true`이면 JEV가 먼저 끝나 체감 지연이 0이었다.
- `success`: HTTP 2xx와 JSON 응답 수신 여부. 높은 JEV 점수가 intervention에 적합하거나 정확하다는 뜻은 아니다.

최소한 median뿐 아니라 p90/p95, timeout/error 비율, 체감 지연 0인 비율, 대화 길이에 따른 latency를 함께 본다. 팝업은 성공 요청의 실제 가림 시간 p50/p90과 JEV API p50을 바로 보여준다.

## 중요한 한계

- 가상 스크롤 때문에 JEV에는 **현재 DOM에 로드된 최근 대화만** 들어간다. feasibility latency 측정에는 쓸 수 있지만 full-context 판단 실험에는 별도 대화 캐시가 필요하다.
- 판단 대상 `current_turn`은 JEV 요청을 병렬화하기 위해 새 사용자 메시지다. 캐릭터 답변까지 포함한 boundary 판단이 필요하다면 답변 완료 후 요청하는 별도 조건과 비교해야 한다.
- 새 응답을 감지하고 CSS를 적용하기 전 한 프레임 정도 내용이 보일 가능성이 있다. 완전한 pre-paint 차단이 필요하면 제타 네트워크 스트림을 가로채는 다른 구조가 필요하다.
- service worker가 중단되거나 확장 프로그램을 끄면 안전하게 내용을 공개하도록 설계했지만, 실제 참여자 연구 전에는 장시간/오류/탭 전환/재생성 시나리오를 별도로 시험해야 한다.
- 대화 내용이 외부 API로 전송된다. 참여자 동의, 데이터 최소화, 보관 정책을 먼저 정해야 한다.
