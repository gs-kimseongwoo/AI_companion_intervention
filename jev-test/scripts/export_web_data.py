from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATABASE = ROOT / "data" / "results.sqlite3"
SAMPLE = ROOT / "data" / "sample.jsonl"
PUBLIC = ROOT / "web" / "public"
EXPORTS = (
    ("jev-latest", "Jev 1.13", "jev-results.json"),
    ("qwen:qwen3.8:27b-q4_K_M", "Qwen 3.8 27B Q4", "qwen-results.json"),
)


def build_payload(
    database: sqlite3.Connection,
    samples: dict[str, dict],
    model: str,
    model_label: str,
) -> dict:
    latest = database.execute(
        """SELECT questions_sha256, MAX(evaluated_at)
           FROM evaluations WHERE model_requested=?
           GROUP BY questions_sha256 ORDER BY MAX(evaluated_at) DESC LIMIT 1""",
        (model,),
    ).fetchone()
    if latest is None:
        raise RuntimeError(f"No evaluations found for {model}")
    question_hash = latest["questions_sha256"]
    rows = database.execute(
        """SELECT * FROM evaluations
           WHERE model_requested=? AND questions_sha256=?
           ORDER BY conversation_id, turn_index""",
        (model, question_hash),
    ).fetchall()

    grouped: dict[str, list[dict]] = {}
    for row in rows:
        grouped.setdefault(row["conversation_id"], []).append(dict(row))

    conversations = []
    for number, conversation_id in enumerate(sorted(grouped), start=1):
        sample = samples[conversation_id]
        evaluations = grouped[conversation_id]
        evaluations_by_turn = {row["turn_index"]: row for row in evaluations}
        turns = []
        for turn_index, source_turn in enumerate(sample["turns"], start=1):
            evaluation = evaluations_by_turn.get(turn_index)
            turns.append(
                {
                    "index": turn_index,
                    "speaker": source_turn["speaker"],
                    "text": source_turn["text"],
                    "scores": None
                    if evaluation is None
                    else {
                        "context_shift": evaluation["context_shift"],
                        "natural_breakpoint": evaluation["natural_breakpoint"],
                        "engagement_drop": evaluation["engagement_drop"],
                    },
                    "latencyMs": None if evaluation is None else evaluation["api_latency_ms"],
                }
            )
        conversations.append(
            {
                "number": number,
                "id": conversation_id,
                "botName": sample.get("bot_name", ""),
                "categories": sample.get("categories", []),
                "totalTurns": len(sample["turns"]),
                "evaluatedTurns": len(evaluations),
                "averageLatencyMs": sum(row["api_latency_ms"] for row in evaluations)
                / len(evaluations),
                "modelReturned": evaluations[0]["model_returned"],
                "turns": turns,
            }
        )

    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "modelRequested": model,
        "modelLabel": model_label,
        "questionsSha256": question_hash,
        "conversationCount": len(conversations),
        "evaluationCount": len(rows),
        "conversations": conversations,
    }


def main() -> None:
    samples = {}
    with SAMPLE.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            samples[row["conversation_id"]] = row

    PUBLIC.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DATABASE) as database:
        database.row_factory = sqlite3.Row
        for model, label, filename in EXPORTS:
            payload = build_payload(database, samples, model, label)
            output = PUBLIC / filename
            temporary = output.with_suffix(".json.tmp")
            temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            temporary.replace(output)
            print(
                f"Exported {payload['conversationCount']} conversations and "
                f"{payload['evaluationCount']} {label} evaluations to {output}"
            )


if __name__ == "__main__":
    main()
