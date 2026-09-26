# Jev × PIPPA conversation-state explorer

An end-to-end exploratory prototype for asking whether Jev's turn-by-turn score
trajectories expose human-interpretable transitions in AI-companion conversations.
It is deliberately **not** a benchmark, a ground-truth labeling pipeline, or an
intervention system.

The implementation follows the current TypeSafe HTTP API directly:

- `POST https://api.typesafe.ai/v1/systemone`
- model alias `jev-latest` (the exact returned version is stored per row)
- one structured `state` and three parallel `noul` questions per request
- exponential backoff for `429`, `529`, and transient server errors

Official references: [API](https://docs.typesafe.ai/api),
[State](https://docs.typesafe.ai/concepts/state),
[Primitives](https://docs.typesafe.ai/primitives), and
[Models](https://docs.typesafe.ai/models).

## What is evaluated

For every utterance index from `start_turn` through the end, the complete prefix
is sent as structured JSON. PIPPA's `conversation` entries are treated as turns
(individual utterances), with `is_human=true` mapped to `human` and false mapped
to `assistant`.

The editable questions live in [`config/questions.json`](config/questions.json):

- `context_shift`
- `natural_breakpoint`
- `engagement_drop`

Each result is a `noul` value: the model's probability of “yes,” from 0 to 1.
It is a model signal, not a calibrated construct measurement or a human label.

## Setup

```bash
cd 03_codes/test/jev-test
python3 -m venv .venv
.venv/bin/pip install -e .
```

PIPPA contains NSFW and potentially disturbing material. The sample and reports
therefore remain git-ignored by default.

## 1. Prepare a deterministic sample

```bash
.venv/bin/jev-pippa prepare
```

Defaults select 20 conversations from the official
`PygmalionAI/PIPPA` `pippa_deduped.jsonl`, using the 20 smallest stable seeded
hashes among conversations with 8–50 utterances and at most 80,000 text
characters. The entire remote JSONL is streamed once, but only the 20 selected
records are retained. No `datasets` SDK or full local dataset copy is required.

Outputs:

- `data/sample.jsonl`: sampled conversations
- `data/sample.manifest.json`: source, seed, eligibility constraints, planned API
  calls, and cumulative prefix-text characters

The length filters make the first experiment bounded and reduce the risk of full
prefixes exceeding Jev's documented context limit. Character count is only a
conservative proxy—not a tokenizer—so the API's token count remains authoritative.
The filters introduce a selection constraint and should be reported with findings.

Use a local downloaded file, a different sample size, or wider length range as
needed:

```bash
.venv/bin/jev-pippa prepare --source /path/to/pippa_deduped.jsonl --count 30 \
  --min-turns 6 --max-turns 80 --seed 20260920
```

## 2. Run evaluations (resumable)

First validate the whole pipeline without calling Jev:

```bash
.venv/bin/jev-pippa run --mock --limit 20
.venv/bin/jev-pippa analyze --model mock:jev-latest
```

Mock values are conspicuously stored under `mock:jev-latest` and must never be
interpreted as Jev output.

For the real API:

```bash
export TYPESAFE_API_KEY='...'
.venv/bin/jev-pippa run
```

Useful cautious first call:

```bash
.venv/bin/jev-pippa run --limit 5
```

Successful requests are committed immediately to `data/results.sqlite3`. The
unique key is conversation + turn + question-file hash + requested model, so a
restart skips completed work. A changed question file or model creates a separate
run rather than silently mixing results. Failures are recorded but remain
retryable. Add `--keep-going` to continue after a failed prefix.

Stored fields include conversation ID, 1-based turn index, total utterances,
normalized position, last speaker, all three values, latency, state and request
lengths, token usage, requested and returned model names, question hash, and the
complete raw JSON response.

Because `jev-latest` is a moving alias, `model_returned` is important for
reproducibility. To pin the version documented on 2026-09-20, pass
`--model jev-1.13.0`; otherwise the default follows the current stable model.
If an alias changes during a resumed run, the runner stops instead of mixing
returned model versions in one trajectory set.

### Run the laboratory Qwen judge

The OpenAI-compatible Qwen runner asks each question independently, disables
thinking, and converts the first-token `YES`/`NO` log probabilities into a
normalized score. The three questions are submitted concurrently and their raw
responses and per-question latency are retained inside `raw_response_json`.

When the laboratory endpoint is directly reachable:

```bash
.venv/bin/jev-pippa run-qwen \
  --endpoint http://suzy.kaist.ac.kr:11500/v1/chat/completions \
  --model qwen3.8:27b-q4_K_M \
  --keep-going
```

If direct access is unavailable, create the tunnel in a separate terminal and
use the command's default localhost endpoint:

```bash
ssh -N -L 11500:127.0.0.1:11500 suzy.kaist.ac.kr
.venv/bin/jev-pippa run-qwen --keep-going
```

Qwen rows use the distinct requested-model key
`qwen:qwen3.8:27b-q4_K_M`, so they coexist with Jev rows and resume without
duplicate calls. Generate a separate Qwen report with:

```bash
.venv/bin/jev-pippa analyze \
  --model 'qwen:qwen3.8:27b-q4_K_M' \
  --output-dir outputs/qwen
```

## 3. Analyze trajectories and transitions

```bash
.venv/bin/jev-pippa analyze
```

This creates:

- `outputs/plots/<conversation_id>.svg`: one three-line trajectory per conversation
- `outputs/trajectories.csv`: scores and consecutive-turn deltas
- `outputs/interesting_transitions.csv`: positive spikes, negative drops, and
  high-score regions
- `outputs/report.md`: top transitions with the trajectory and nearby original
  dialogue turns

Thresholds and context size are exploratory controls:

```bash
.venv/bin/jev-pippa analyze --spike-threshold 0.20 \
  --high-threshold 0.70 --context-radius 4 --top 80
```

To inspect any point directly:

```bash
.venv/bin/jev-pippa inspect CONVERSATION_ID 17 --radius 4
```

## Local web explorer

Export the latest real Jev run and start the interactive localhost view:

```bash
python3 scripts/export_web_data.py
cd web
npm install
npm run dev
```

The explorer lists all conversations with their average API latency. Selecting a
turn in the trajectory scrolls the complete dialogue log to that turn and shows
its three scores and per-turn latency. The exported dialogue JSON is ignored by
Git because PIPPA may contain sensitive or NSFW material.

## Interpretation cautions

- Look for coherent, recurring trajectory shapes and inspect the source dialogue;
  do not call a high score “correct.”
- Adjacent prefixes are highly dependent observations.
- Scores on assistant-ending prefixes can change even when the latest human turn
  is unchanged, because the newly added assistant response changes its context.
- Question wording, model version, conversation length, role-play style, and the
  sampling filters can all affect trajectories.
- `engagement_drop` is especially underdetermined early in a conversation; its
  question explicitly favors a low value when prior human evidence is insufficient.

## Tests

```bash
.venv/bin/python -m unittest discover -s tests -v
```
