from __future__ import annotations

import hashlib
import heapq
import json
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, Iterator


DEFAULT_DATASET_URL = (
    "https://huggingface.co/datasets/PygmalionAI/PIPPA/resolve/main/"
    "pippa_deduped.jsonl"
)


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def conversation_id(row: dict) -> str:
    identity = {
        "submission_timestamp": row.get("submission_timestamp"),
        "bot_id": row.get("bot_id"),
        "conversation": row.get("conversation", []),
    }
    return hashlib.sha256(canonical_json(identity).encode("utf-8")).hexdigest()[:24]


def normalize_conversation(row: dict) -> list[dict[str, object]]:
    turns: list[dict[str, object]] = []
    for item in row.get("conversation") or []:
        if not isinstance(item, dict):
            continue
        message = item.get("message")
        if message is None:
            continue
        turns.append(
            {
                "speaker": "human" if bool(item.get("is_human")) else "assistant",
                "text": str(message),
            }
        )
    return turns


def _open_source(source: str, timeout: float = 120.0) -> BinaryIO:
    if source.startswith(("https://", "http://")):
        request = urllib.request.Request(
            source,
            headers={"User-Agent": "jev-pippa-explorer/0.1"},
        )
        return urllib.request.urlopen(request, timeout=timeout)  # type: ignore[return-value]
    return Path(source).expanduser().open("rb")


def iter_rows(source: str) -> Iterator[tuple[int, dict]]:
    with _open_source(source) as stream:
        for source_index, raw_line in enumerate(stream):
            if not raw_line.strip():
                continue
            yield source_index, json.loads(raw_line)


@dataclass(frozen=True)
class SampleStats:
    scanned: int
    eligible: int
    selected: int
    planned_calls: int
    planned_prefix_text_chars: int


def deterministic_sample(
    rows: Iterable[tuple[int, dict]],
    *,
    count: int,
    seed: int,
    min_turns: int,
    max_turns: int,
    max_conversation_chars: int,
    start_turn: int,
) -> tuple[list[dict], SampleStats]:
    """Select the smallest seeded hashes, independent of input ordering."""
    heap: list[tuple[int, str, int, dict]] = []
    scanned = 0
    eligible = 0
    for source_index, row in rows:
        scanned += 1
        turns = normalize_conversation(row)
        total_chars = sum(len(str(t["text"])) for t in turns)
        if not (min_turns <= len(turns) <= max_turns):
            continue
        if total_chars > max_conversation_chars:
            continue
        eligible += 1
        cid = conversation_id(row)
        priority = int.from_bytes(
            hashlib.sha256(f"{seed}:{cid}".encode("utf-8")).digest(), "big"
        )
        prepared = {
            "conversation_id": cid,
            "source_index": source_index,
            "submission_timestamp": row.get("submission_timestamp"),
            "bot_id": row.get("bot_id", ""),
            "bot_name": row.get("bot_name", ""),
            "categories": row.get("categories") or [],
            "turns": turns,
        }
        entry = (-priority, cid, source_index, prepared)
        if len(heap) < count:
            heapq.heappush(heap, entry)
        elif entry > heap[0]:
            heapq.heapreplace(heap, entry)

    selected = [entry[3] for entry in heap]
    selected.sort(key=lambda x: x["conversation_id"])
    planned_calls = sum(max(0, len(row["turns"]) - start_turn + 1) for row in selected)
    planned_chars = 0
    for row in selected:
        running = 0
        for index, turn in enumerate(row["turns"], start=1):
            running += len(str(turn["text"]))
            if index >= start_turn:
                planned_chars += running
    return selected, SampleStats(
        scanned=scanned,
        eligible=eligible,
        selected=len(selected),
        planned_calls=planned_calls,
        planned_prefix_text_chars=planned_chars,
    )


def write_jsonl(path: Path, records: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    temporary.replace(path)


def read_jsonl(path: Path) -> Iterator[dict]:
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)
