from __future__ import annotations

import csv
import html
import json
import sqlite3
import statistics
from collections import defaultdict
from pathlib import Path

from .data import read_jsonl


METRICS = ("context_shift", "natural_breakpoint", "engagement_drop")
METRIC_LABELS = {
    "context_shift": "Context shift",
    "natural_breakpoint": "Natural breakpoint",
    "engagement_drop": "Engagement drop",
}


def load_run_rows(
    connection: sqlite3.Connection,
    questions_hash: str | None = None,
    model: str | None = None,
) -> tuple[list[dict], str, str]:
    if questions_hash is None or model is None:
        filters = []
        parameters: list[str] = []
        if questions_hash is not None:
            filters.append("questions_sha256=?")
            parameters.append(questions_hash)
        if model is not None:
            filters.append("model_requested=?")
            parameters.append(model)
        where = "WHERE " + " AND ".join(filters) if filters else ""
        latest = connection.execute(
            f"""SELECT questions_sha256, model_requested, MAX(evaluated_at) AS latest
                FROM evaluations {where}
                GROUP BY questions_sha256, model_requested
                ORDER BY latest DESC LIMIT 1""",
            parameters,
        ).fetchone()
        if latest is None:
            raise ValueError("No completed evaluations found")
        questions_hash = questions_hash or latest["questions_sha256"]
        model = model or latest["model_requested"]
    rows = connection.execute(
        """SELECT * FROM evaluations
           WHERE questions_sha256=? AND model_requested=?
           ORDER BY conversation_id, turn_index""",
        (questions_hash, model),
    ).fetchall()
    return [dict(row) for row in rows], questions_hash, model


def add_deltas(rows: list[dict]) -> list[dict]:
    previous: dict[str, dict] = {}
    enriched: list[dict] = []
    for source in rows:
        row = dict(source)
        prior = previous.get(row["conversation_id"])
        for metric in METRICS:
            row[f"{metric}_delta"] = (
                None if prior is None else row[metric] - prior[metric]
            )
        previous[row["conversation_id"]] = row
        enriched.append(row)
    return enriched


def classify_transitions(
    rows: list[dict], *, spike_threshold: float, high_threshold: float
) -> list[dict]:
    transitions: list[dict] = []
    for row in add_deltas(rows):
        for metric in METRICS:
            score = row[metric]
            delta = row[f"{metric}_delta"]
            flags: list[str] = []
            if delta is not None and delta >= spike_threshold:
                flags.append("positive_spike")
            if delta is not None and delta <= -spike_threshold:
                flags.append("negative_drop")
            if score >= high_threshold:
                flags.append("high_score")
            if flags:
                transitions.append(
                    {
                        "conversation_id": row["conversation_id"],
                        "turn_index": row["turn_index"],
                        "normalized_position": row["normalized_position"],
                        "last_speaker": row["last_speaker"],
                        "metric": metric,
                        "score": score,
                        "delta": delta,
                        "flags": ";".join(flags),
                    }
                )
    transitions.sort(
        key=lambda row: max(abs(row["delta"] or 0.0), row["score"]), reverse=True
    )
    return transitions


def _write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def _plot_conversation(
    path: Path, rows: list[dict], title: str, high_threshold: float
) -> None:
    width, height = 900, 450
    left, right, top, bottom = 70, 25, 55, 55
    plot_width, plot_height = width - left - right, height - top - bottom
    min_turn = min(row["turn_index"] for row in rows)
    max_turn = max(row["turn_index"] for row in rows)

    def x(turn: int) -> float:
        span = max(1, max_turn - min_turn)
        return left + (turn - min_turn) / span * plot_width

    def y(value: float) -> float:
        return top + (1.0 - value) * plot_height

    colors = {
        "context_shift": "#3b82f6",
        "natural_breakpoint": "#16a34a",
        "engagement_drop": "#dc2626",
    }
    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        f'<text x="{width / 2}" y="25" text-anchor="middle" font-family="sans-serif" font-size="16">{html.escape(title)}</text>',
    ]
    for tick in (0.0, 0.25, 0.5, 0.75, 1.0):
        y_tick = y(tick)
        elements.append(
            f'<line x1="{left}" y1="{y_tick:.1f}" x2="{width-right}" y2="{y_tick:.1f}" stroke="#ddd"/>'
        )
        elements.append(
            f'<text x="{left-10}" y="{y_tick+4:.1f}" text-anchor="end" font-family="sans-serif" font-size="11">{tick:g}</text>'
        )
    threshold_y = y(high_threshold)
    elements.extend(
        [
            f'<line x1="{left}" y1="{threshold_y:.1f}" x2="{width-right}" y2="{threshold_y:.1f}" stroke="#555" stroke-width="1.2" stroke-dasharray="6 5"/>',
            f'<text x="{width-right-4}" y="{threshold_y-6:.1f}" text-anchor="end" font-family="sans-serif" font-size="11" fill="#555">high ≥ {high_threshold:g}</text>',
        ]
    )
    elements.extend(
        [
            f'<line x1="{left}" y1="{top}" x2="{left}" y2="{height-bottom}" stroke="#333"/>',
            f'<line x1="{left}" y1="{height-bottom}" x2="{width-right}" y2="{height-bottom}" stroke="#333"/>',
            f'<text x="{width/2}" y="{height-15}" text-anchor="middle" font-family="sans-serif" font-size="12">Turn index (utterance)</text>',
            f'<text x="18" y="{height/2}" text-anchor="middle" transform="rotate(-90 18 {height/2})" font-family="sans-serif" font-size="12">Jev noul probability</text>',
            f'<text x="{left}" y="{height-bottom+18}" text-anchor="middle" font-family="sans-serif" font-size="11">{min_turn}</text>',
            f'<text x="{width-right}" y="{height-bottom+18}" text-anchor="middle" font-family="sans-serif" font-size="11">{max_turn}</text>',
        ]
    )
    for legend_index, metric in enumerate(METRICS):
        points = " ".join(f"{x(row['turn_index']):.1f},{y(row[metric]):.1f}" for row in rows)
        color = colors[metric]
        elements.append(
            f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="2"/>'
        )
        for row in rows:
            is_high = row[metric] >= high_threshold
            elements.append(
                f'<circle cx="{x(row["turn_index"]):.1f}" cy="{y(row[metric]):.1f}" r="{4.5 if is_high else 2.2}" fill="{color}"'
                + (' stroke="white" stroke-width="1.5"' if is_high else "")
                + "/>"
            )
        maximum = max(rows, key=lambda row: row[metric])
        label_y = y(maximum[metric]) + (-9 if legend_index != 1 else 16)
        label_anchor = "end" if x(maximum["turn_index"]) > width - 150 else "start"
        label_x = x(maximum["turn_index"]) + (-7 if label_anchor == "end" else 7)
        elements.append(
            f'<text x="{label_x:.1f}" y="{label_y:.1f}" text-anchor="{label_anchor}" font-family="sans-serif" font-size="11" fill="{color}">max {maximum[metric]:.2f} @ t{maximum["turn_index"]}</text>'
        )
        legend_x = left + legend_index * 215
        elements.append(
            f'<line x1="{legend_x}" y1="42" x2="{legend_x+22}" y2="42" stroke="{color}" stroke-width="3"/>'
        )
        elements.append(
            f'<text x="{legend_x+28}" y="46" font-family="sans-serif" font-size="12">{metric.replace("_", " ")}</text>'
        )
    elements.append("</svg>")
    path.write_text("\n".join(elements), encoding="utf-8")


def _excerpt(conversation: dict, turn_index: int, limit: int = 90) -> str:
    text = str(conversation["turns"][turn_index - 1]["text"])
    text = " ".join(text.split()).replace("|", "\\|")
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _delta_text(value: float | None) -> str:
    return "—" if value is None else f"{value:+.2f}"


def _table(headers: list[str], rows: list[list[object]]) -> list[str]:
    return [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
        *("| " + " | ".join(str(cell) for cell in row) + " |" for row in rows),
        "",
    ]


def dialogue_window(conversation: dict, turn_index: int, radius: int) -> str:
    turns = conversation["turns"]
    start = max(1, turn_index - radius)
    end = min(len(turns), turn_index + radius)
    lines = []
    for index in range(start, end + 1):
        turn = turns[index - 1]
        marker = "→" if index == turn_index else " "
        text = str(turn["text"]).replace("\n", " ").strip()
        lines.append(f"{marker} {index:>3} {turn['speaker']}: {text}")
    return "\n".join(lines)


def analyze(
    *,
    connection: sqlite3.Connection,
    sample_path: Path,
    output_dir: Path,
    spike_threshold: float,
    high_threshold: float,
    context_radius: int,
    top: int,
    questions_hash: str | None,
    model: str | None,
) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    plots_dir = output_dir / "plots"
    plots_dir.mkdir(exist_ok=True)
    rows, selected_hash, selected_model = load_run_rows(connection, questions_hash, model)
    enriched = add_deltas(rows)
    transitions = classify_transitions(rows, spike_threshold=spike_threshold, high_threshold=high_threshold)
    samples = {row["conversation_id"]: row for row in read_jsonl(sample_path)}

    _write_csv(output_dir / "trajectories.csv", enriched)
    _write_csv(output_dir / "interesting_transitions.csv", transitions)

    by_conversation: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_conversation[row["conversation_id"]].append(row)
    for cid, conversation_rows in by_conversation.items():
        bot_name = samples.get(cid, {}).get("bot_name", "")
        _plot_conversation(
            plots_dir / f"{cid}.svg",
            conversation_rows,
            f"{cid} — {bot_name}",
            high_threshold,
        )

    disclaimer = (
        "These are deterministic mock values, not Jev output."
        if selected_model.startswith("mock:")
        else "These are Jev model signals, not ground-truth labels or accuracy measurements."
    )
    report = [
        "# Jev–PIPPA exploratory transition report",
        "",
        f"> {disclaimer}",
        "",
        f"- Model requested: `{selected_model}`",
        f"- Question SHA-256: `{selected_hash}`",
        f"- Evaluated rows: {len(rows)}",
        f"- Conversations: {len(by_conversation)}",
        f"- Spike threshold: |Δ| ≥ {spike_threshold}",
        f"- High-score threshold: score ≥ {high_threshold}",
        "",
        "## At a glance",
        "",
    ]
    summary_rows: list[list[object]] = []
    for metric in METRICS:
        values = [row[metric] for row in enriched]
        deltas = [row[f"{metric}_delta"] for row in enriched if row[f"{metric}_delta"] is not None]
        maximum = max(enriched, key=lambda row: row[metric])
        summary_rows.append(
            [
                METRIC_LABELS[metric],
                f"{statistics.mean(values):.2f}",
                f"{statistics.median(values):.2f}",
                f"**{maximum[metric]:.2f}** at `{maximum['conversation_id']}` t{maximum['turn_index']}",
                sum(value >= high_threshold for value in values),
                sum(delta >= spike_threshold for delta in deltas),
                sum(delta <= -spike_threshold for delta in deltas),
            ]
        )
    report.extend(
        _table(
            ["Signal", "Mean", "Median", "Maximum location", f"High ≥ {high_threshold:g}", f"Δ+ ≥ {spike_threshold:g}", f"Δ− ≤ -{spike_threshold:g}"],
            summary_rows,
        )
    )
    report.extend(
        [
            "## Highest scores by signal",
            "",
            "These tables answer **where the score itself was highest**. Δ is the change from the immediately preceding evaluated turn.",
            "",
        ]
    )
    for metric in METRICS:
        report.extend([f"### {METRIC_LABELS[metric]}", ""])
        metric_rows = sorted(enriched, key=lambda row: row[metric], reverse=True)[:10]
        report.extend(
            _table(
                ["Rank", "Score", "Δ", "Conversation", "Turn", "Speaker", "Current-turn excerpt"],
                [
                    [
                        rank,
                        f"**{row[metric]:.2f}**",
                        _delta_text(row[f"{metric}_delta"]),
                        f"[`{row['conversation_id']}`](plots/{row['conversation_id']}.svg)",
                        row["turn_index"],
                        row["last_speaker"],
                        _excerpt(samples[row["conversation_id"]], row["turn_index"]),
                    ]
                    for rank, row in enumerate(metric_rows, start=1)
                ],
            )
        )

    report.extend(["## Largest consecutive-turn changes", ""])
    for metric in METRICS:
        with_delta = [row for row in enriched if row[f"{metric}_delta"] is not None]
        positive = sorted(with_delta, key=lambda row: row[f"{metric}_delta"], reverse=True)[:5]
        negative = sorted(with_delta, key=lambda row: row[f"{metric}_delta"])[:5]
        change_rows = [("Rise", row) for row in positive] + [("Drop", row) for row in negative]
        report.extend([f"### {METRIC_LABELS[metric]}", ""])
        report.extend(
            _table(
                ["Direction", "Δ", "New score", "Conversation", "Turn", "Speaker", "Current-turn excerpt"],
                [
                    [
                        direction,
                        f"**{row[f'{metric}_delta']:+.2f}**",
                        f"{row[metric]:.2f}",
                        f"[`{row['conversation_id']}`](plots/{row['conversation_id']}.svg)",
                        row["turn_index"],
                        row["last_speaker"],
                        _excerpt(samples[row["conversation_id"]], row["turn_index"]),
                    ]
                    for direction, row in change_rows
                ],
            )
        )

    report.extend(
        [
            "## Conversation overview",
            "",
            "Each cell gives that conversation's maximum score and the turn where it occurred. Select the conversation ID to open its annotated trajectory.",
            "",
        ]
    )
    overview_rows: list[list[object]] = []
    for cid, conversation_rows in by_conversation.items():
        maxima = [max(conversation_rows, key=lambda row: row[metric]) for metric in METRICS]
        overview_rows.append(
            [
                f"[`{cid}`](plots/{cid}.svg)",
                str(samples.get(cid, {}).get("bot_name", "")).replace("|", "\\|"),
                len(samples.get(cid, {}).get("turns", [])),
                *(f"**{row[metric]:.2f}** (t{row['turn_index']})" for metric, row in zip(METRICS, maxima)),
            ]
        )
    overview_rows.sort(key=lambda row: max(float(str(cell).split("**")[1]) for cell in row[3:]), reverse=True)
    report.extend(
        _table(
            ["Conversation", "Bot", "Turns", "Max context shift", "Max breakpoint", "Max engagement drop"],
            overview_rows,
        )
    )

    report.extend(
        [
            "## Selected transition details",
            "",
            "The most salient unique turns are shown below with all three signals and nearby dialogue.",
            "",
        ]
    )
    candidates = []
    for row in enriched:
        deltas = [abs(row[f"{metric}_delta"] or 0.0) for metric in METRICS]
        scores = [row[metric] for metric in METRICS]
        if max(deltas) >= spike_threshold or max(scores) >= high_threshold:
            candidates.append((max(deltas + scores), row))
    candidates.sort(key=lambda item: item[0], reverse=True)
    for _, row in candidates[:top]:
        cid = row["conversation_id"]
        conversation = samples.get(cid)
        if conversation is None:
            continue
        score_line = "; ".join(
            f"{METRIC_LABELS[metric]} **{row[metric]:.2f}** (Δ {_delta_text(row[f'{metric}_delta'])})"
            for metric in METRICS
        )
        report.extend(
            [
                f"### {cid}, turn {row['turn_index']} ({row['last_speaker']})",
                "",
                score_line,
                "",
                f"![Trajectory](plots/{cid}.svg)",
                "",
                "```text",
                dialogue_window(conversation, row["turn_index"], context_radius),
                "```",
                "",
            ]
        )
    (output_dir / "report.md").write_text("\n".join(report), encoding="utf-8")
    return {
        "rows": len(rows),
        "conversations": len(by_conversation),
        "transitions": len(transitions),
        "questions_sha256": selected_hash,
        "model": selected_model,
    }
