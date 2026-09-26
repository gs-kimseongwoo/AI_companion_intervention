from __future__ import annotations

import hashlib
import json
import random
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

from .data import canonical_json


DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
RETRYABLE_STATUS = {429, 500, 502, 503, 504, 529}


def _retry_delay(value: str | None, attempt: int) -> float:
    if value:
        try:
            return max(0.0, float(value))
        except ValueError:
            try:
                target = parsedate_to_datetime(value)
                if target.tzinfo is None:
                    target = target.replace(tzinfo=timezone.utc)
                return max(0.0, (target - datetime.now(timezone.utc)).total_seconds())
            except (TypeError, ValueError, OverflowError):
                pass
    return min(30.0, 2**attempt)


def questions_sha256(questions: dict) -> str:
    return hashlib.sha256(canonical_json(questions).encode("utf-8")).hexdigest()


def build_state(turns: list[dict], current_index: int) -> dict:
    prefix = [
        {
            "turn_index": str(i),
            "speaker": str(turn["speaker"]),
            "text": str(turn["text"]),
        }
        for i, turn in enumerate(turns[:current_index], start=1)
    ]
    human_turns = [turn for turn in prefix if turn["speaker"] == "human"]
    return {
        "conversation_prefix": prefix,
        "current_turn": prefix[-1],
        "latest_human_turn": human_turns[-1] if human_turns else "No human turn yet.",
    }


@dataclass(frozen=True)
class JevResult:
    response: dict
    latency_ms: float
    request_length_chars: int


class JevClient:
    def __init__(
        self,
        api_key: str,
        *,
        endpoint: str = DEFAULT_ENDPOINT,
        timeout: float = 90.0,
        max_retries: int = 5,
    ) -> None:
        self.api_key = api_key
        self.endpoint = endpoint
        self.timeout = timeout
        self.max_retries = max_retries

    def evaluate(self, state: dict, questions: dict, model: str) -> JevResult:
        payload = {"state": state, "model": model, "questions": questions}
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=encoded,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "User-Agent": "jev-pippa-explorer/0.1",
            },
        )
        started = time.perf_counter()
        for attempt in range(self.max_retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    parsed = json.loads(response.read())
                return JevResult(
                    response=parsed,
                    latency_ms=(time.perf_counter() - started) * 1000,
                    request_length_chars=len(encoded.decode("utf-8")),
                )
            except urllib.error.HTTPError as exc:
                body = exc.read().decode("utf-8", errors="replace")
                if exc.code not in RETRYABLE_STATUS or attempt >= self.max_retries:
                    raise RuntimeError(f"Jev HTTP {exc.code}: {body}") from exc
                delay = _retry_delay(exc.headers.get("Retry-After"), attempt)
            except (urllib.error.URLError, TimeoutError) as exc:
                if attempt >= self.max_retries:
                    raise RuntimeError(f"Jev request failed: {exc}") from exc
                delay = min(30.0, 2**attempt)
            time.sleep(delay + random.random() * 0.25)
        raise AssertionError("unreachable")


class MockJevClient:
    """Deterministic local stand-in for pipeline tests; never presented as Jev data."""

    def evaluate(self, state: dict, questions: dict, model: str) -> JevResult:
        current = state["current_turn"]["text"]
        latest_human = state["latest_human_turn"]
        latest_human_text = (
            latest_human["text"] if isinstance(latest_human, dict) else str(latest_human)
        )
        previous = state["conversation_prefix"][:-1]
        digest = hashlib.sha256(canonical_json(state).encode("utf-8")).digest()
        noise = digest[0] / 2550.0
        context_shift = min(1.0, 0.15 + noise + (0.45 if "*" in current else 0.0))
        breakpoint = min(
            1.0,
            0.1 + noise + (0.55 if current.rstrip().endswith((".", "!", "...")) else 0.0),
        )
        human_short = min(1.0, 30.0 / max(1, len(latest_human_text)))
        engagement = min(1.0, 0.05 + noise + (0.55 * human_short if previous else 0.0))
        answers = {
            "context_shift": {"type": "noul", "noul": context_shift},
            "natural_breakpoint": {"type": "noul", "noul": breakpoint},
            "engagement_drop": {"type": "noul", "noul": engagement},
        }
        return JevResult(
            response={
                "model": "mock-jev (not Jev output)",
                "answers": answers,
                "usage": {"input_tokens": 0, "output_tokens": 0},
            },
            latency_ms=0.0,
            request_length_chars=len(canonical_json(state)),
        )


def extract_noul_scores(response: dict, expected: set[str]) -> dict[str, float]:
    answers = response.get("answers")
    if not isinstance(answers, dict):
        raise ValueError("Jev response has no answers object")
    scores: dict[str, float] = {}
    for name in expected:
        answer = answers.get(name)
        if not isinstance(answer, dict) or answer.get("type") != "noul":
            raise ValueError(f"Answer {name!r} is missing or is not a noul answer")
        value = float(answer["noul"])
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"Answer {name!r} is outside [0, 1]: {value}")
        scores[name] = value
    return scores
