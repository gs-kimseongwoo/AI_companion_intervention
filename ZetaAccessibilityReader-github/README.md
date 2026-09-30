# Zeta Accessibility Reader

이 폴더가 실제 **외부 Android 앱** 프로젝트입니다. Python/ADB 수집기가 아니라 휴대폰 안에서 실행되는 `AccessibilityService`가 제타 화면을 직접 읽습니다.

## 동작 순서

1. 앱을 설치하고 `접근성 설정 열기`를 누릅니다.
2. Android 설정에서 `제타 대화 읽기` 서비스를 사용 설정합니다.
3. 앱의 `제타 열기` 버튼으로 제타 채팅방에 들어갑니다.
4. 서비스가 `com.scatterlab.messenger`의 화면 변경 이벤트만 받습니다.
5. 메시지를 `내 응답`, `내 서술`, `AI 대사`, 화자별 `인물 서술`, `배경 설명` 구간으로 나눠 대화방별 현재 상태에 병합합니다.
6. 최신 AI 답변처럼 아직 ID가 없는 응답은 하나의 임시 턴에서 계속 갱신하고, 다음 사용자 메시지 뒤에 ID가 생기면 같은 턴으로 확정합니다.
7. A/B/C 대화방을 오가면 캐릭터 이름이 아니라 보이는 정식 메시지 ID와 기존 상태의 교집합으로 방을 다시 찾습니다. 최근 사용한 대화방 10개만 유지합니다.
8. 현재 대화방을 나가면 방문 세션을 별도 파일로 보존하며, Reader 앱에서 현재 대화의 revision과 분석 준비 상태를 확인할 수 있습니다.
9. 대화방에 들어올 때 이미 있던 내용은 JEV 기준선으로만 등록하고 보내지 않습니다. 입장 뒤 새 정식 `USER RESPONSE` ID가 나타나면 그 턴부터 JEV 세션을 시작합니다.
10. 디버그 빌드는 2초간 안정된 JEV 세션 범위를 컴퓨터의 로컬 브리지로 보내고 관찰값만 표시합니다. 자동 종료나 개입은 아직 하지 않습니다.

제타 화면의 `실시간 읽기` 오버레이를 5초간 계속 누르면 Reader가 열리고 현재 세션 파일 전체가 표시됩니다. 손을 떼면 열기가 취소됩니다.

## 핵심 파일

- `ZetaAccessibilityService.kt`: 제타 이벤트 수신, 화면 트리 복사, 시간 측정
- `ZetaDialogueParser.kt`: 발화자·본문·내 응답/AI 응답 분류
- `ConversationState.kt`: 대화방 분리, 메시지 병합, JEV용 턴과 revision 생성
- `ConversationStatePersistence.kt`: 대화방별 현재 상태를 앱 재시작 뒤에도 복원
- `CaptureStore.kt`: 대화 상태와 방문 세션 파일 저장
- `JevEvaluationCoordinator.kt`: 최신 revision 전송, 오래된 응답 폐기, 관찰값 표시 상태 관리
- `MainActivity.kt`: 권한 상태와 결과를 화면에 표시
- `res/xml/zeta_accessibility_service.xml`: 제타 패키지만 접근하도록 제한

## 측정값

- **화면 트리 분석 시간**: `rootInActiveWindow`를 읽기 시작해서 분류가 끝날 때까지
- **변화 감지/저장 시간**: 접근성 이벤트가 있으면 이벤트부터 저장까지, 이벤트가 없으면 500ms 폴링을 기준으로 한 최대 예상 시간

React Native가 접근성 이벤트를 빠뜨리는 경우에 대비해 제타가 전면에 있는 동안 500ms 간격으로도 화면을 확인합니다.

## 로컬 JEV 연결

JEV API 키는 Android 앱에 넣지 않습니다. `../jev-test`에서 브리지를 실행하고 USB로 연결된 기기에 로컬 포트를 전달합니다.

```bash
cd ../jev-test
PYTHONPATH=src python3 -m jev_pippa.cli serve --mock
adb reverse tcp:8765 tcp:8765
```

디버그 APK는 `http://127.0.0.1:8765/evaluate`를 사용합니다. 응답의 대화방과 revision이 현재 화면과 정확히 일치할 때만 결과를 채택합니다. JEV 요청에는 방 전체 기록이 아니라 입장 뒤 새로 생긴 `USER RESPONSE`부터의 턴만 들어갑니다. 대화를 계속 사용 중이면 자정이 지나도 세션을 강제로 나누지 않습니다. 실제 JEV 평가에는 컴퓨터 환경에 `TYPESAFE_API_KEY`를 설정하고 `--mock`을 제거합니다.

## 저장 위치

실행 중인 최근 결과는 `files/latest_capture.txt`에 저장하고, 각 방문 기록은 `files/sessions/zeta_session_날짜_시간.txt`에 보존합니다. 대화방별 현재 상태는 `files/conversations/*.conversation`에 별도로 저장하므로 다른 방을 방문하거나 Reader 프로세스가 다시 시작돼도 복원할 수 있습니다.

하나의 AI 메시지 안에서 화자가 바뀌면 화자별 대사와 배경 설명을 순서대로 분리합니다. 같은 메시지 ID의 현재 구간만 유지하고, ID가 없는 최신 AI 답변의 수정·재생성은 과거 버전을 쌓지 않고 같은 임시 턴을 덮어씁니다. revision은 역할과 현재 본문 순서로 계산하므로 임시 AI 턴에 정식 ID만 붙는 경우에는 바뀌지 않습니다. 다른 앱은 이 파일에 접근할 수 없습니다.

## 중요한 Android 제한

- 접근성 권한은 앱이 스스로 켤 수 없고 사용자가 Android 설정에서 직접 허용해야 합니다.
- 제타가 실제로 화면에 렌더링한 접근성 노드만 읽을 수 있습니다.
- 화면에 아직 로드되지 않은 오래된 메시지는 자동으로 위로 스크롤하지 않는 한 읽히지 않습니다.
