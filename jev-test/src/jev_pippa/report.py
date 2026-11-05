from __future__ import annotations

import csv
from pathlib import Path
from typing import Iterable


REPORT_COLUMNS = (
    "Turn",
    "Event Boundary",
    "Time Change",
    "Space Change",
    "Characters Change",
    "Causality Change",
    "Object Interactions Change",
    "Goals Change",
)

DIMENSION_COLUMNS = {
    "time_change": "Time Change",
    "space_change": "Space Change",
    "characters_change": "Characters Change",
    "causality_change": "Causality Change",
    "object_interactions_change": "Object Interactions Change",
    "goals_change": "Goals Change",
}


def _score(value: object) -> str:
    if not isinstance(value, (int, float)):
        raise ValueError(f"Expected numeric score, got {value!r}")
    return f"{value:.2f}"


def event_boundary_summary_rows(records: Iterable[dict]) -> list[dict[str, str]]:
    """Convert event-boundary results into one compact row per AI turn."""
    records = list(records)
    run_keys = {
        (
            record.get("dataset_sha256"),
            record.get("prompt_sha256"),
            record.get("model_requested"),
        )
        for record in records
    }
    if len(run_keys) > 1:
        raise ValueError("Report input contains more than one evaluation run")

    rows: list[dict[str, str]] = []
    seen_turns: set[int] = set()
    for record in sorted(records, key=lambda item: item["turn"]):
        if record["turn"] in seen_turns:
            raise ValueError(f"Duplicate evaluation turn: {record['turn']}")
        seen_turns.add(record["turn"])
        dimension_scores = record.get("dimension_scores")
        if not isinstance(dimension_scores, dict):
            raise ValueError("Invalid event-boundary evaluation record")
        if set(dimension_scores) != set(DIMENSION_COLUMNS):
            raise ValueError("Event-boundary dimension scores are incomplete")

        rows.append(
            {
                "Turn": f"Turn {record['turn']}",
                "Event Boundary": _score(record.get("event_transition_score")),
                **{
                    column: _score(dimension_scores[name])
                    for name, column in DIMENSION_COLUMNS.items()
                },
            }
        )
    return rows


def render_markdown_summary(rows: Iterable[dict[str, str]]) -> str:
    rows = list(rows)
    lines = [
        "| " + " | ".join(REPORT_COLUMNS) + " |",
        "| " + " | ".join("---" for _ in REPORT_COLUMNS) + " |",
    ]
    for row in rows:
        values = [row[column].replace("|", "\\|") for column in REPORT_COLUMNS]
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines) + "\n"


def write_event_boundary_summary(
    records: Iterable[dict], csv_path: Path, markdown_path: Path
) -> tuple[int, str]:
    rows = event_boundary_summary_rows(records)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_path.parent.mkdir(parents=True, exist_ok=True)

    with csv_path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)

    markdown = render_markdown_summary(rows)
    markdown_path.write_text(markdown, encoding="utf-8")
    return len(rows), markdown
