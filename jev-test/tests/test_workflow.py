from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from jev_pippa.event_boundary import load_prompt
from jev_pippa.jev_client import JevResult
from jev_pippa.pipeline import completed_states, evaluate_dataset
from jev_pippa.refine_dataset import refine_dataset


PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "event_boundary.json"


class RecordingClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, str]] = []

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


class WorkflowTests(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = refine_dataset(
            {
                "preamble": [
                    {"speaker": "assistant", "text": "[배경]\n시작한다."}
                ],
                "turn_count": 2,
                "turns": [
                    {
                        "turn": 1,
                        "user_response": "하나",
                        "ai_response": '[엘레노어]\n"첫 답."',
                    },
                    {
                        "turn": 2,
                        "user_response": "둘",
                        "ai_response": '[엘레노어]\n"둘째 답."',
                    },
                ],
            }
        )

    def test_builds_expanding_states_from_refined_dataset(self) -> None:
        states = dict(completed_states(self.dataset))

        self.assertEqual(
            states[1],
            'AI: [배경]\n시작한다.\nUser: 하나\nAI: [엘레노어]\n"첫 답."',
        )
        self.assertTrue(
            states[2].endswith('User: 둘\nAI: [엘레노어]\n"둘째 답."')
        )

    def test_resume_skips_an_identical_saved_evaluation(self) -> None:
        prompt = load_prompt(PROMPT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dataset_path = root / "dataset.refined.json"
            output_path = root / "evaluations.jsonl"

            first_client = RecordingClient()
            with redirect_stdout(io.StringIO()):
                first = evaluate_dataset(
                    dataset=self.dataset,
                    dataset_path=dataset_path,
                    output_path=output_path,
                    client=first_client,
                    prompt=prompt,
                    model="jev-latest",
                    selected_turns=[1],
                )
                second_client = RecordingClient()
                second = evaluate_dataset(
                    dataset=self.dataset,
                    dataset_path=dataset_path,
                    output_path=output_path,
                    client=second_client,
                    prompt=prompt,
                    model="jev-latest",
                    selected_turns=[1],
                )

        self.assertEqual(len(first_client.calls), 1)
        self.assertEqual(second_client.calls, [])
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
