from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


SCHEMA = """
CREATE TABLE IF NOT EXISTS evaluations (
    conversation_id TEXT NOT NULL,
    turn_index INTEGER NOT NULL,
    total_turns INTEGER NOT NULL,
    normalized_position REAL NOT NULL,
    last_speaker TEXT NOT NULL,
    context_shift REAL NOT NULL,
    natural_breakpoint REAL NOT NULL,
    engagement_drop REAL NOT NULL,
    api_latency_ms REAL NOT NULL,
    input_length_chars INTEGER NOT NULL,
    request_length_chars INTEGER NOT NULL,
    model_requested TEXT NOT NULL,
    model_returned TEXT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    questions_sha256 TEXT NOT NULL,
    raw_response_json TEXT NOT NULL,
    evaluated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, turn_index, questions_sha256, model_requested)
);
CREATE TABLE IF NOT EXISTS errors (
    conversation_id TEXT NOT NULL,
    turn_index INTEGER NOT NULL,
    questions_sha256 TEXT NOT NULL,
    model_requested TEXT NOT NULL,
    error TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, turn_index, questions_sha256, model_requested)
);
CREATE INDEX IF NOT EXISTS evaluation_run_idx
ON evaluations(questions_sha256, model_requested, conversation_id, turn_index);
"""


@contextmanager
def connect(path: Path) -> Iterator[sqlite3.Connection]:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.executescript(SCHEMA)
    try:
        yield connection
    finally:
        connection.close()


def is_complete(
    connection: sqlite3.Connection,
    conversation_id: str,
    turn_index: int,
    question_hash: str,
    model: str,
) -> bool:
    row = connection.execute(
        """SELECT 1 FROM evaluations
           WHERE conversation_id=? AND turn_index=?
             AND questions_sha256=? AND model_requested=?""",
        (conversation_id, turn_index, question_hash, model),
    ).fetchone()
    return row is not None


def returned_model_versions(
    connection: sqlite3.Connection, question_hash: str, model_requested: str
) -> set[str]:
    return {
        str(row[0])
        for row in connection.execute(
            """SELECT DISTINCT model_returned FROM evaluations
               WHERE questions_sha256=? AND model_requested=?""",
            (question_hash, model_requested),
        )
    }


def save_evaluation(connection: sqlite3.Connection, record: dict) -> None:
    columns = ", ".join(record)
    placeholders = ", ".join("?" for _ in record)
    connection.execute(
        f"INSERT OR REPLACE INTO evaluations ({columns}) VALUES ({placeholders})",
        tuple(record.values()),
    )
    connection.execute(
        """DELETE FROM errors WHERE conversation_id=? AND turn_index=?
           AND questions_sha256=? AND model_requested=?""",
        (
            record["conversation_id"],
            record["turn_index"],
            record["questions_sha256"],
            record["model_requested"],
        ),
    )
    connection.commit()


def save_error(
    connection: sqlite3.Connection,
    *,
    conversation_id: str,
    turn_index: int,
    questions_sha256: str,
    model_requested: str,
    error: str,
) -> None:
    connection.execute(
        """INSERT INTO errors VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
           ON CONFLICT(conversation_id, turn_index, questions_sha256, model_requested)
           DO UPDATE SET error=excluded.error, updated_at=CURRENT_TIMESTAMP""",
        (conversation_id, turn_index, questions_sha256, model_requested, error),
    )
    connection.commit()


def make_record(
    *,
    conversation: dict,
    turn_index: int,
    state: dict,
    scores: dict[str, float],
    result,
    model_requested: str,
    question_hash: str,
) -> dict:
    response = result.response
    usage = response.get("usage") or {}
    state_json = json.dumps(state, ensure_ascii=False, separators=(",", ":"))
    total = len(conversation["turns"])
    return {
        "conversation_id": conversation["conversation_id"],
        "turn_index": turn_index,
        "total_turns": total,
        "normalized_position": turn_index / total,
        "last_speaker": conversation["turns"][turn_index - 1]["speaker"],
        "context_shift": scores["context_shift"],
        "natural_breakpoint": scores["natural_breakpoint"],
        "engagement_drop": scores["engagement_drop"],
        "api_latency_ms": result.latency_ms,
        "input_length_chars": len(state_json),
        "request_length_chars": result.request_length_chars,
        "model_requested": model_requested,
        "model_returned": str(response.get("model", "")),
        "input_tokens": usage.get("input_tokens"),
        "output_tokens": usage.get("output_tokens"),
        "questions_sha256": question_hash,
        "raw_response_json": json.dumps(response, ensure_ascii=False),
    }
