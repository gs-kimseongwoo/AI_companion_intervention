from __future__ import annotations

import csv
import tempfile
import unittest
from pathlib import Path

from jev_pippa.report import (
    event_boundary_summary_rows,
    render_markdown_summary,
    write_event_boundary_summary,
)


class ReportTests(unittest.TestCase):
    def setUp(self) -> None:
        self.records = [
            {
                "turn": 4,
                "event_transition_score": 0.2,
                "dimension_scores": {
                    "time_change": 0.4,
                    "space_change": 0.3,
                    "characters_change": 0.2,
                    "causality_change": 0.1,
                    "object_interactions_change": 0.5,
                    "goals_change": 0.6,
                },
            },
            {
                "turn": 2,
                "event_transition_score": 0.61234567,
                "dimension_scores": {
                    "time_change": 0.1,
                    "space_change": 0.8,
                    "characters_change": 0.2,
                    "causality_change": 0.7,
                    "object_interactions_change": 0.3,
                    "goals_change": 0.9,
                },
            },
        ]

    def test_builds_event_boundary_summary_columns(self) -> None:
        rows = event_boundary_summary_rows(self.records)

        self.assertEqual(rows[0]["Turn"], "Turn 2")
        self.assertEqual(rows[0]["Event Boundary"], "0.61")
        self.assertEqual(rows[0]["Space Change"], "0.80")
        self.assertEqual(rows[0]["Goals Change"], "0.90")
        self.assertEqual(rows[1]["Turn"], "Turn 4")
        self.assertNotIn("Boundary Candidate", rows[0])
        self.assertFalse(any(column.startswith("Stage 2") for column in rows[0]))

    def test_markdown_contains_only_event_boundary_results(self) -> None:
        markdown = render_markdown_summary(event_boundary_summary_rows(self.records))

        self.assertIn("| Turn | Event Boundary | Time Change |", markdown)
        self.assertIn("| Turn 4 | 0.20 | 0.40 | 0.30 | 0.20 | 0.10 | 0.50 | 0.60 |", markdown)
        self.assertNotIn("Stage 2", markdown)

    def test_rejects_mixed_evaluation_runs(self) -> None:
        records = [
            {**self.records[0], "dataset_sha256": "dataset-a"},
            {**self.records[1], "dataset_sha256": "dataset-b"},
        ]

        with self.assertRaisesRegex(ValueError, "more than one evaluation run"):
            event_boundary_summary_rows(records)

    def test_writes_csv_and_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            csv_path = Path(directory) / "summary.csv"
            markdown_path = Path(directory) / "summary.md"

            count, markdown = write_event_boundary_summary(
                self.records, csv_path, markdown_path
            )

            self.assertEqual(count, 2)
            self.assertEqual(markdown_path.read_text(encoding="utf-8"), markdown)
            with csv_path.open(encoding="utf-8", newline="") as source:
                rows = list(csv.DictReader(source))
            self.assertEqual(rows[0]["Turn"], "Turn 2")
            self.assertEqual(rows[1]["Goals Change"], "0.60")


if __name__ == "__main__":
    unittest.main()
