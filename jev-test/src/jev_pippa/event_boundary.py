from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .jev_client import extract_noul_scores


QUESTION_NAME = "event_boundary"
DIMENSION_NAMES = (
    "time_change",
    "space_change",
    "characters_change",
    "causality_change",
    "object_interactions_change",
    "goals_change",
)
QUESTION_NAMES = {QUESTION_NAME, *DIMENSION_NAMES}


def load_prompt(path: Path) -> dict:
    questions = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(questions, dict) or set(questions) != QUESTION_NAMES:
        raise ValueError(f"Prompt keys must be exactly {sorted(QUESTION_NAMES)}")
    for name, question in questions.items():
        if not isinstance(question, dict) or question.get("type") != "noul":
            raise ValueError(f"Prompt question {name} must use the noul type")
        instructions = question.get("instructions")
        if not isinstance(instructions, str) or not instructions.strip():
            raise ValueError(f"Prompt question {name} requires instructions")
        criteria = question.get("criteria")
        if not isinstance(criteria, dict) or set(criteria) != {"true", "false"}:
            raise ValueError(f"Prompt question {name} requires true and false criteria")
    return questions


def evaluate_state(client, state: str, prompt: dict, model: str) -> dict:
    result = client.evaluate(state, prompt, model)
    scores = extract_noul_scores(result.response, QUESTION_NAMES)
    return {
        "event_transition_score": scores[QUESTION_NAME],
        "dimension_scores": {name: scores[name] for name in DIMENSION_NAMES},
        "state_sha256": hashlib.sha256(state.encode("utf-8")).hexdigest(),
        "model_requested": model,
        "model_returned": result.response.get("model"),
        "latency_ms": result.latency_ms,
        "request_length_chars": result.request_length_chars,
        "raw_response": result.response,
    }
