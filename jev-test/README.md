# Jev event-boundary pipeline

Zeta 역할극 대화를 정제하고, Jev로 평가한 뒤, 읽기 쉬운 report를 만드는 최소 파이프라인입니다.

## 구조

```text
원본 dataset.json
  → refine
  → dataset.refined.json
  → evaluate + prompts/event_boundary.json
  → evaluations.jsonl
  → report
  → report.csv + report.md
```

각 파일의 책임을 분리했습니다.

- `refine_dataset.py`: 입력 JSON 검증과 표준 dataset 생성
- `pipeline.py`: 누적 대화 state 생성, Jev 실행, 중복 실행 방지
- `event_boundary.py`: Prompt 검증과 Jev 점수 해석
- `report.py`: 평가 결과를 CSV와 Markdown으로 변환
- `cli.py`: 위 단계를 명령어로 연결

Stage 2와 최종 pause 판정은 포함하지 않습니다.

## 1. 입력 dataset

파이프라인은 아래 형식의 JSON이 들어온다고 가정합니다.

```json
{
  "preamble": [
    {
      "speaker": "assistant",
      "text": "[배경]\n비가 내린다."
    }
  ],
  "turn_count": 2,
  "turns": [
    {
      "turn": 1,
      "user_response": "누구야?",
      "ai_response": "[엘레노어]\n\"나야.\""
    },
    {
      "turn": 2,
      "user_response": "잠깐.",
      "ai_response": ""
    }
  ]
}
```

`preamble` 대신 하나의 `intro` 문자열을 사용할 수도 있습니다.

AI 응답은 이미 원하는 표현으로 만들어졌다고 가정합니다.

- 서술: `[배경]` 또는 `[엘레노어]` 아래 일반 본문
- 대사: 따옴표로 감싼 본문
- 같은 화자의 연속 블록: refine 단계에서 하나로 결합
- 화자 추측, narration/dialogue 자동 분류: 하지 않음

## 2. 설치

```bash
python3 -m venv .venv
.venv/bin/pip install -e .
export TYPESAFE_API_KEY="..."
```

macOS에서는 `scripts/save_typesafe_api_key.sh`로 Keychain에 저장할 수 있습니다.

## 3. 단계별 실행

### Refine

```bash
.venv/bin/jev-pipeline refine \
  --input /absolute/path/to/dataset.json \
  --output data/run/dataset.refined.json
```

표준 dataset은 다음처럼 단순한 필드만 가집니다.

```json
{
  "schema_version": "jev-roleplay-dataset-v1",
  "preamble": [
    {"role": "assistant", "content": "[배경]\n비가 내린다."}
  ],
  "turns": [
    {"turn": 1, "user": "누구야?", "assistant": "[엘레노어]\n\"나야.\""}
  ]
}
```

### Evaluate

실제 Prompt는 [`prompts/event_boundary.json`](prompts/event_boundary.json)에 있습니다.

```bash
.venv/bin/jev-pipeline evaluate \
  --dataset data/run/dataset.refined.json \
  --output data/run/evaluations.jsonl
```

각 완료된 AI turn까지의 전체 대화가 하나의 Jev state가 됩니다. `ai_response`가 빈 마지막 turn은 평가하지 않습니다.

특정 turn만 평가할 때는 `--turn 12 --turn 18`처럼 반복합니다.

### Report

```bash
.venv/bin/jev-pipeline report \
  --evaluations data/run/evaluations.jsonl \
  --csv data/run/report.csv \
  --markdown data/run/report.md
```

Report에는 turn별로 다음 점수가 들어갑니다.

- Event Boundary
- Time Change
- Space Change
- Characters Change
- Causality Change
- Object Interactions Change
- Goals Change

## 4. 전체 파이프라인 실행

```bash
scripts/run_pipeline.sh \
  --input /absolute/path/to/dataset.json \
  --output-dir data/my_run
```

생성 파일:

```text
data/my_run/
  dataset.refined.json
  evaluations.jsonl
  report.csv
  report.md
```

동일한 dataset, state, Prompt, 모델의 평가가 이미 있으면 Jev API를 다시 호출하지 않습니다.

## 테스트

```bash
.venv/bin/python -m unittest discover -s tests -v
```

실제 채팅 데이터와 `data/` 결과는 Git에 포함하지 않습니다. Jev의 `noul` 값은 모델 확률 신호이며 사람 라벨이나 보정된 측정값이 아닙니다.
