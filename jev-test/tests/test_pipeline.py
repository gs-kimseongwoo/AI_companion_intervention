from __future__ import annotations

import unittest

from jev_pippa.analysis import add_deltas, classify_transitions, dialogue_window
from jev_pippa.data import conversation_id, deterministic_sample, normalize_conversation
from jev_pippa.jev import MockJevClient, _retry_delay, build_state, extract_noul_scores
from jev_pippa.qwen import _binary_probability


def row(index: int, turns: int = 5) -> dict:
    return {
        "submission_timestamp": index,
        "bot_id": f"bot-{index}",
        "bot_name": "Bot",
        "conversation": [
            {"message": f"message {index}-{i}", "is_human": bool(i % 2)}
            for i in range(turns)
        ],
    }


class PipelineTests(unittest.TestCase):
    def test_deterministic_sample_is_order_independent(self) -> None:
        rows = list(enumerate(row(i) for i in range(20)))
        first, stats = deterministic_sample(
            rows, count=5, seed=42, min_turns=3, max_turns=10,
            max_conversation_chars=10000, start_turn=3,
        )
        second, _ = deterministic_sample(
            reversed(rows), count=5, seed=42, min_turns=3, max_turns=10,
            max_conversation_chars=10000, start_turn=3,
        )
        self.assertEqual(
            [x["conversation_id"] for x in first],
            [x["conversation_id"] for x in second],
        )
        self.assertEqual(stats.planned_calls, 15)

    def test_state_and_mock_response(self) -> None:
        turns = normalize_conversation(row(1))
        state = build_state(turns, 3)
        self.assertEqual(len(state["conversation_prefix"]), 3)
        self.assertEqual(state["current_turn"]["turn_index"], "3")
        response = MockJevClient().evaluate(state, {}, "jev-latest").response
        scores = extract_noul_scores(
            response, {"context_shift", "natural_breakpoint", "engagement_drop"}
        )
        self.assertTrue(all(0 <= value <= 1 for value in scores.values()))
        self.assertEqual(_retry_delay("3", 0), 3.0)

    def test_deltas_transitions_and_dialogue(self) -> None:
        rows = [
        {
            "conversation_id": "c",
            "turn_index": 1,
            "normalized_position": 0.5,
            "last_speaker": "assistant",
            "context_shift": 0.1,
            "natural_breakpoint": 0.2,
            "engagement_drop": 0.1,
        },
        {
            "conversation_id": "c",
            "turn_index": 2,
            "normalized_position": 1.0,
            "last_speaker": "human",
            "context_shift": 0.8,
            "natural_breakpoint": 0.1,
            "engagement_drop": 0.9,
        },
        ]
        enriched = add_deltas(rows)
        self.assertAlmostEqual(enriched[1]["context_shift_delta"], 0.7)
        transitions = classify_transitions(rows, spike_threshold=0.25, high_threshold=0.75)
        self.assertTrue(any(item["metric"] == "context_shift" for item in transitions))
        conversation = {"turns": normalize_conversation(row(1, 4))}
        self.assertIn("→   2 human", dialogue_window(conversation, 2, 1))

    def test_qwen_binary_probability_aggregates_case_variants(self) -> None:
        response = {
            "choices": [{"logprobs": {"content": [{"top_logprobs": [
                {"token": "YES", "logprob": -0.2},
                {"token": "Yes", "logprob": -2.0},
                {"token": "NO", "logprob": -1.6},
                {"token": "No", "logprob": -3.0},
            ]}]}}]
        }
        score = _binary_probability(response)
        self.assertGreater(score, 0.7)
        self.assertLess(score, 1.0)


if __name__ == "__main__":
    unittest.main()
