from __future__ import annotations

import unittest
from pathlib import Path

from jev_pippa.event_boundary import (
    DIMENSION_NAMES,
    evaluate_state,
    load_prompt,
)
from jev_pippa.jev_client import JevResult


PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "event_boundary.json"


class RecordingClient:
    def __init__(self) -> None:
        self.calls = []

    def evaluate(self, state: str, prompt: dict, model: str) -> JevResult:
        self.calls.append((state, prompt, model))
        return JevResult(
            response={
                "model": "jev-test-version",
                "answers": {
                    name: {"type": "noul", "noul": 0.5}
                    for name in prompt
                },
            },
            latency_ms=1.0,
            request_length_chars=100,
        )


class EventBoundaryTests(unittest.TestCase):
    def test_prompt_has_event_boundary_and_six_dimensions(self) -> None:
        prompt = load_prompt(PROMPT)

        self.assertEqual(set(prompt), {"event_boundary", *DIMENSION_NAMES})

    def test_evaluate_state_returns_normalized_scores(self) -> None:
        prompt = load_prompt(PROMPT)
        client = RecordingClient()
        state = 'User: 안녕\nAI: [엘레노어]\n"반가워."'

        result = evaluate_state(client, state, prompt, "jev-latest")

        self.assertEqual(client.calls, [(state, prompt, "jev-latest")])
        self.assertEqual(result["event_transition_score"], 0.5)
        self.assertEqual(
            result["dimension_scores"],
            {name: 0.5 for name in DIMENSION_NAMES},
        )
        self.assertEqual(result["model_returned"], "jev-test-version")


if __name__ == "__main__":
    unittest.main()
