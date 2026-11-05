from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Iterator

from .event_boundary import evaluate_state
from .refine_dataset import validate_refined_dataset


EVALUATION_SCHEMA_VERSION = "event-boundary-evaluation-v1"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def _sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def completed_states(dataset: dict) -> Iterator[tuple[int, str]]:
    """Yield the expanding conversation state at each completed AI turn."""
    validate_refined_dataset(dataset)
    history = [
        f"{'AI' if message['role'] == 'assistant' else 'User'}: {message['content']}"
        for message in dataset["preamble"]
    ]
    for item in dataset["turns"]:
        history.append(f"User: {item['user']}")
        if item["assistant"]:
            history.append(f"AI: {item['assistant']}")
            yield item["turn"], "\n".join(history)


def evaluate_dataset(
    *,
    dataset: dict,
    dataset_path: Path,
    output_path: Path,
    client,
    prompt: dict,
    model: str,
    selected_turns: list[int] | None = None,
) -> list[dict]:
    """Evaluate completed turns and return the current run's ordered records."""
    states = dict(completed_states(dataset))
    if not states:
        raise ValueError("Refined dataset contains no completed AI response")

    selected = (
        sorted(set(selected_turns))
        if selected_turns
        else sorted(states)
    )
    missing = set(selected) - set(states)
    if missing:
        raise ValueError(f"No completed AI response at turn(s): {sorted(missing)}")

    previous = read_jsonl(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_hash = _sha256(dataset)
    prompt_hash = _sha256(prompt)
    source_file = str(dataset_path.resolve())

    def belongs(row: dict) -> bool:
        return (
            row.get("schema_version") == EVALUATION_SCHEMA_VERSION
            and row.get("dataset_sha256") == dataset_hash
            and row.get("turn") in selected
            and row.get("model_requested") == model
            and row.get("prompt_sha256") == prompt_hash
        )

    for turn in selected:
        state = states[turn]
        state_hash = hashlib.sha256(state.encode("utf-8")).hexdigest()
        if any(
            belongs(row)
            and row.get("turn") == turn
            and row.get("state_sha256") == state_hash
            for row in previous
        ):
            print(f"turn {turn}: already saved", flush=True)
            continue

        evaluation = evaluate_state(client, state, prompt, model)
        returned_model = evaluation["model_returned"]
        prior_versions = {
            row["model_returned"]
            for row in previous
            if row.get("model_requested") == model
            and row.get("prompt_sha256") == prompt_hash
            and row.get("model_returned")
        }
        if prior_versions and returned_model not in prior_versions:
            raise RuntimeError(
                f"Model alias returned {returned_model!r}; "
                f"existing results use {sorted(prior_versions)}"
            )

        row = {
            "schema_version": EVALUATION_SCHEMA_VERSION,
            "dataset_file": source_file,
            "dataset_sha256": dataset_hash,
            "prompt_sha256": prompt_hash,
            "turn": turn,
            **evaluation,
        }
        with output_path.open("a", encoding="utf-8") as destination:
            destination.write(json.dumps(row, ensure_ascii=False) + "\n")
        previous.append(row)
        print(
            f"turn {turn}: event_boundary="
            f"{evaluation['event_transition_score']:.3f}",
            flush=True,
        )

    current = {row["turn"]: row for row in previous if belongs(row)}
    return [current[turn] for turn in selected]
