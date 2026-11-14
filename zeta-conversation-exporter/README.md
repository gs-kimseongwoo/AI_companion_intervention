# Zeta Conversation Exporter

현재 열려 있는 Zeta 대화방의 가상 스크롤 목록을 끝까지 읽어 JSON 또는 Markdown으로 다운로드한다.

## 출력

JSON은 `schema`, 방 URL/ID, 내보낸 시각, 메시지 배열을 담는다. 각 메시지에는 제타 DOM의 안정적인 행 키, 순서, `user`/`character` 역할, 화자, 텍스트, 이미지 포함 여부가 들어간다. Markdown은 사람이 검토하기 쉬운 화자별 형식이다. 이미지 파일 자체는 저장하지 않고 `[이미지]` 여부만 기록한다.

## 설치와 사용

1. `chrome://extensions`에서 개발자 모드를 켠다.
2. 이 폴더를 **압축해제된 확장 프로그램**으로 로드한다.
3. `https://zeta-ai.io/ko/rooms/...` 대화방을 연다.
4. 툴바 아이콘에서 JSON 또는 Markdown을 선택한다.

## 알려진 한계

- DOM 선택자는 `03_codes/zeta-sim/zeta_sim/zeta.py`에서 2026-10-14 이후 사용한 구조를 따른다. Zeta UI가 바뀌면 `content.js` 선택자를 수정해야 한다.
- 내보내는 동안 가상 목록을 스크롤하므로 화면이 잠시 움직인다.
- 삭제된 메시지, 비활성 응답 후보, 화면 DOM에 전혀 로드되지 않는 메타데이터는 포함되지 않는다.
