# Zeta × JEV Feasibility Probe

이 확장 프로그램은 intervention의 효과를 구현하는 것이 아니라, **새 Zeta 응답을 사용자에게 보여주기 전에 JEV 판단을 기다리는 방식이 실제 사용 가능한 속도인지** 측정한다.

## 동작

1. 확장 프로그램을 활성화한 시점에 이미 있던 대화는 baseline으로 두고 건드리지 않는다.
2. 사용자가 입력창에서 Enter를 누르거나 Send 버튼을 클릭하는 순간 현재 보이는 최근 대화 prefix와 새 사용자 메시지를 `context_shift` `noul` 질문 하나로 JEV에 보낸다. Zeta 답변 생성과 JEV 판단은 병렬로 진행된다.
3. JEV가 처리 중일 때 새 캐릭터 응답 DOM이 나타나면 해당 말풍선을 즉시 `display: none` 처리한다. 숨은 스트리밍 텍스트가 길어져도 빈 말풍선 공간이 커지지 않도록 한다.
4. 요청 실패 또는 `context_shift < 0.4`이면 말풍선을 즉시 공개한다.
5. `context_shift >= 0.4`이면 현재 또는 이후 도착하는 Zeta 응답을 계속 가리고 팝업을 표시한다. 사용자가 **응답 보기** 또는 닫기 버튼을 눌러야 응답이 공개된다.
6. 입력 이벤트를 놓친 경우에도 DOM의 실제 최신 turn이 새 human turn일 때만 요청한다. Zeta AI turn 자체는 feasibility trigger로 사용하지 않는다.
7. JEV 전체 왕복, 캐릭터 답변이 실제로 가려진 시간, context shift 값과 팝업 표시 여부를 Chrome 로컬 저장소에 최대 500건 기록한다.

JEV 결과 로그는 응답이 반환되는 즉시 `intervention_holding` 또는 `decision_complete` 상태로 저장한다. 높은 점수의 팝업을 사용자가 닫지 않아도 결과는 남으며, 나중에 응답을 공개하면 같은 `event_id` 레코드에 공개 시각과 가림 시간이 갱신된다.

## 설치와 사용

1. `chrome://extensions`에서 개발자 모드를 켜고 이 폴더를 로드한다.
2. 확장 프로그램 세부정보의 **확장 프로그램 옵션**에서 TypeSafe API key를 입력한다.
3. Zeta 대화방을 새로고침한다.
4. 툴바 팝업에서 **Feasibility 측정 활성화**를 켠 뒤 새 메시지를 보낸다.
5. 여러 turn을 시험한 후 팝업에서 로그 JSON을 저장한다.

## 로그 해석

각 사람 메시지를 보내는 즉시 `decision: waiting_for_jev`인 레코드를 먼저 만든다. 따라서 API가 오래 걸리거나 timeout되어도 요청이 시작됐는지 확인할 수 있다. JEV가 반환되면 같은 `event_id`의 레코드를 갱신한다.

다음 필드를 중심으로 읽는다.

- `jev_response`: JEV가 실제 반환한 원본 JSON.
- `jev_context_shift_score`: `jev_response.answers.context_shift.noul`에서 꺼낸 판단값.
- `jev_latency_ms`: service worker에서 측정한 JEV HTTP 왕복 시간.
- `zeta_response_detected_at`: Zeta AI 응답 말풍선이 처음 나타난 시각. 아직 나타나지 않았으면 `null`이며, 이후 나타나면 같은 로그가 갱신된다.
- `jev_returned_before_zeta_response`: JEV 결과가 Zeta AI 응답보다 먼저 왔으면 `true`.
- `extra_wait_after_zeta_response_ms`: Zeta AI 응답이 먼저 왔을 때, JEV 결과를 기다리느라 추가로 가린 시간. JEV가 먼저 왔으면 `0`.
- `decision`: `hold_and_popup`, `show_zeta_response`, `fail_open` 중 하나.
- `released_at`, `release_reason`: 가렸던 응답을 실제로 공개한 시각과 이유.

툴바 팝업에는 최근 요청의 score, JEV 응답 시간, JEV/Zeta 도착 순서, 추가 대기 시간과 최종 결정을 문장으로 표시한다. 내려받은 JSON에는 `jev_response` 원본이 포함된다.

## 중요한 한계

- 가상 스크롤 때문에 JEV에는 **현재 DOM에 로드된 최근 대화와 전송 직전 입력 메시지만** 들어간다. feasibility latency 측정에는 쓸 수 있지만 full-context 판단 실험에는 별도 대화 캐시가 필요하다.
- 판단 대상 `current_turn`은 JEV 요청을 병렬화하기 위해 새 사용자 메시지다. 캐릭터 답변까지 포함한 boundary 판단이 필요하다면 답변 완료 후 요청하는 별도 조건과 비교해야 한다.
- 새 응답을 감지하고 CSS를 적용하기 전 한 프레임 정도 내용이 보일 가능성이 있다. 완전한 pre-paint 차단이 필요하면 제타 네트워크 스트림을 가로채는 다른 구조가 필요하다.
- service worker가 중단되거나 확장 프로그램을 끄면 안전하게 내용을 공개하도록 설계했지만, 실제 참여자 연구 전에는 장시간/오류/탭 전환/재생성 시나리오를 별도로 시험해야 한다.
- 대화 내용이 외부 API로 전송된다. 참여자 동의, 데이터 최소화, 보관 정책을 먼저 정해야 한다.
