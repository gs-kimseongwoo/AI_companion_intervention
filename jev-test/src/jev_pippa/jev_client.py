from __future__ import annotations

import json
import random
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime


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
                return max(
                    0.0,
                    (target - datetime.now(timezone.utc)).total_seconds(),
                )
            except (TypeError, ValueError, OverflowError):
                pass
    return min(30.0, 2**attempt)


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
        endpoint: str,
        timeout: float,
        max_retries: int,
    ) -> None:
        self.api_key = api_key
        self.endpoint = endpoint
        self.timeout = timeout
        self.max_retries = max_retries

    def evaluate(self, state: str, prompt: dict, model: str) -> JevResult:
        payload = {"state": state, "model": model, "questions": prompt}
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=encoded,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "User-Agent": "jev-event-boundary-pipeline/0.1",
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


def extract_noul_scores(response: dict, expected: set[str]) -> dict[str, float]:
    answers = response.get("answers")
    if not isinstance(answers, dict):
        raise ValueError("Jev response has no answers object")

    scores: dict[str, float] = {}
    for name in expected:
        answer = answers.get(name)
        if not isinstance(answer, dict) or answer.get("type") != "noul":
            raise ValueError(f"Answer {name!r} is missing or is not noul")
        value = float(answer["noul"])
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"Answer {name!r} is outside [0, 1]: {value}")
        scores[name] = value
    return scores
