from __future__ import annotations

import re
from typing import Any


SCHEMA_VERSION = "jev-roleplay-dataset-v1"
SECTION_LABEL = re.compile(r"^\[([^\[\]:\n]+)\]$")


def _merge_consecutive_sections(text: str) -> str:
    """Merge adjacent blocks with the same speaker label."""
    lines = text.strip().splitlines()
    if not any(SECTION_LABEL.fullmatch(line.strip()) for line in lines):
        return text.strip()

    sections: list[tuple[str, list[str]]] = []
    current_label: str | None = None
    current_lines: list[str] = []

    def flush() -> None:
        nonlocal current_lines
        content = "\n".join(current_lines).strip()
        if current_label is None:
            if content:
                raise ValueError("AI text before the first [speaker] label is not allowed")
            current_lines = []
            return
        if not content:
            current_lines = []
            return
        if sections and sections[-1][0] == current_label:
            sections[-1][1].extend(current_lines)
        else:
            sections.append((current_label, list(current_lines)))
        current_lines = []

    for line in lines:
        match = SECTION_LABEL.fullmatch(line.strip())
        if match:
            flush()
            current_label = match.group(1)
        else:
            current_lines.append(line)
    flush()

    blocks = [
        f"[{label}]\n" + "\n".join(content).strip()
        for label, content in sections
    ]
    return "\n".join(blocks)


def refine_dataset(raw: Any) -> dict:
    """Convert the supported Zeta JSON shape into the pipeline schema."""
    if not isinstance(raw, dict):
        raise ValueError("Dataset must be a JSON object")

    raw_turns = raw.get("turns")
    if not isinstance(raw_turns, list):
        raise ValueError("Dataset turns must be a list")
    if raw.get("turn_count") != len(raw_turns):
        raise ValueError("Dataset turn_count does not match turns")

    raw_preamble = raw.get("preamble")
    if raw_preamble is None:
        intro = raw.get("intro", "")
        if not isinstance(intro, str):
            raise ValueError("Dataset intro must be text")
        raw_preamble = (
            [{"speaker": "assistant", "text": intro}]
            if intro.strip()
            else []
        )
    if not isinstance(raw_preamble, list):
        raise ValueError("Dataset preamble must be a list")

    role_aliases = {
        "assistant": "assistant",
        "AI": "assistant",
        "human": "user",
        "User": "user",
        "user": "user",
    }
    preamble: list[dict[str, str]] = []
    for message in raw_preamble:
        if not isinstance(message, dict):
            raise ValueError("Each preamble message must be an object")
        role = role_aliases.get(message.get("speaker"))
        text = message.get("text")
        if role is None or not isinstance(text, str) or not text.strip():
            raise ValueError("Preamble messages require a valid speaker and text")
        preamble.append(
            {
                "role": role,
                "content": (
                    _merge_consecutive_sections(text)
                    if role == "assistant"
                    else text.strip()
                ),
            }
        )

    turns: list[dict[str, Any]] = []
    previous_turn = 0
    for index, raw_turn in enumerate(raw_turns):
        if not isinstance(raw_turn, dict):
            raise ValueError("Each turn must be an object")
        turn = raw_turn.get("turn")
        user = raw_turn.get("user_response")
        assistant = raw_turn.get("ai_response")
        if not isinstance(turn, int) or isinstance(turn, bool) or turn <= previous_turn:
            raise ValueError("Turn numbers must be unique increasing integers")
        if not isinstance(user, str) or not user.strip():
            raise ValueError(f"Turn {turn} requires a user_response")
        if not isinstance(assistant, str):
            raise ValueError(f"Turn {turn} ai_response must be text")
        if not assistant.strip() and index != len(raw_turns) - 1:
            raise ValueError("Only the final turn may have an empty ai_response")
        turns.append(
            {
                "turn": turn,
                "user": user.strip(),
                "assistant": _merge_consecutive_sections(assistant),
            }
        )
        previous_turn = turn

    return {
        "schema_version": SCHEMA_VERSION,
        "preamble": preamble,
        "turns": turns,
    }


def validate_refined_dataset(dataset: Any) -> dict:
    if not isinstance(dataset, dict) or dataset.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(
            f"Expected a refined dataset with schema_version {SCHEMA_VERSION!r}"
        )
    preamble = dataset.get("preamble")
    turns = dataset.get("turns")
    if not isinstance(preamble, list) or not isinstance(turns, list):
        raise ValueError("Refined dataset requires preamble and turns lists")

    previous_turn = 0
    for message in preamble:
        if (
            not isinstance(message, dict)
            or message.get("role") not in {"user", "assistant"}
            or not isinstance(message.get("content"), str)
            or not message["content"]
        ):
            raise ValueError("Invalid refined preamble message")

    for index, item in enumerate(turns):
        if not isinstance(item, dict):
            raise ValueError("Invalid refined turn")
        turn = item.get("turn")
        if not isinstance(turn, int) or isinstance(turn, bool) or turn <= previous_turn:
            raise ValueError("Refined turn numbers must be unique and increasing")
        if not isinstance(item.get("user"), str) or not item["user"]:
            raise ValueError(f"Refined turn {turn} requires user text")
        if not isinstance(item.get("assistant"), str):
            raise ValueError(f"Refined turn {turn} requires assistant text")
        if not item["assistant"] and index != len(turns) - 1:
            raise ValueError("Only the final refined turn may have no assistant text")
        previous_turn = turn
    return dataset
