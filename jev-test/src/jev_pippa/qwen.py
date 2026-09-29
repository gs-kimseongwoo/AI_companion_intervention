from __future__ import annotations

import json
import math
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from .jev import JevResult


def _binary_probability(response: dict) -> float:
    """Normalize first-token YES/NO variants from an OpenAI logprobs response."""
    try:
        candidates = response["choices"][0]["logprobs"]["content"][0]["top_logprobs"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError("Qwen response is missing first-token top_logprobs") from exc

    masses = {"YES": 0.0, "NO": 0.0}
    for candidate in candidates:
        label = str(candidate.get("token", "")).strip().upper()
        if label in masses:
            masses[label] += math.exp(float(candidate["logprob"]))
    total = masses["YES"] + masses["NO"]
    if total == 0:
        raise ValueError("Qwen top_logprobs contain neither YES nor NO")
    return masses["YES"] / total


def _prompt(state: dict, question: dict) -> str:
    lines = [
        f'[{turn["turn_index"]}] {turn["speaker"].upper()}: {turn["text"]}'
        for turn in state["conversation_prefix"]
    ]
    criteria = question.get("criteria") or {}
    return "\n".join(
        [
            "CONVERSATION PREFIX (the final item is current_turn):",
            *lines,
            "",
            f'QUESTION: {question["instructions"]}',
            f'YES means: {criteria.get("true", "The proposition is true.")}',
            f'NO means: {criteria.get("false", "The proposition is false.")}',
            "Answer exactly YES or NO.",
        ]
    )


class QwenOpenAIClient:
    """OpenAI-compatible Qwen judge using normalized YES/NO token probabilities."""

    def __init__(
        self,
        *,
        endpoint: str,
        api_key: str = "unused",
        timeout: float = 180.0,
        parallel_questions: int = 3,
    ) -> None:
        self.endpoint = endpoint
        self.api_key = api_key
        self.timeout = timeout
        self.parallel_questions = parallel_questions

    def _evaluate_one(self, state: dict, question: dict, model: str) -> dict:
        payload = {
            "model": model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are a strict conversation-state classifier. "
                        "Follow the supplied definition and answer exactly YES or NO."
                    ),
                },
                {"role": "user", "content": _prompt(state, question)},
            ],
            "reasoning_effort": "none",
            "temperature": 0,
            "max_tokens": 2,
            "logprobs": True,
            "top_logprobs": 20,
            "stream": False,
        }
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=encoded,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "User-Agent": "jev-pippa-qwen-explorer/0.1",
            },
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as result:
                response = json.loads(result.read())
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Qwen HTTP {exc.code}: {body}") from exc
        return {
            "score": _binary_probability(response),
            "latency_ms": (time.perf_counter() - started) * 1000,
            "request_length_chars": len(encoded.decode("utf-8")),
            "response": response,
        }

    def evaluate(self, state: dict, questions: dict, model: str) -> JevResult:
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=self.parallel_questions) as executor:
            futures = {
                name: executor.submit(self._evaluate_one, state, question, model)
                for name, question in questions.items()
            }
            results = {name: future.result() for name, future in futures.items()}

        returned_models = {
            str(item["response"].get("model", "")) for item in results.values()
        }
        if len(returned_models) != 1:
            raise RuntimeError(f"Qwen returned inconsistent model names: {returned_models}")
        usage = {
            "input_tokens": sum(
                int((item["response"].get("usage") or {}).get("prompt_tokens") or 0)
                for item in results.values()
            ),
            "output_tokens": sum(
                int((item["response"].get("usage") or {}).get("completion_tokens") or 0)
                for item in results.values()
            ),
        }
        response = {
            "model": returned_models.pop(),
            "answers": {
                name: {"type": "noul", "noul": item["score"]}
                for name, item in results.items()
            },
            "usage": usage,
            "provider": "openai-compatible-qwen-logprobs",
            "per_question": results,
        }
        return JevResult(
            response=response,
            latency_ms=(time.perf_counter() - started) * 1000,
            request_length_chars=sum(item["request_length_chars"] for item in results.values()),
        )
