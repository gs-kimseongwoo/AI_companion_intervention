from __future__ import annotations

import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .jev import build_state, extract_noul_scores


EXPECTED_SCORES = {"context_shift", "natural_breakpoint", "engagement_drop"}
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_TURNS = 500
MAX_TEXT_CHARS = 500_000


def validate_live_payload(payload: Any) -> tuple[str, str, list[dict[str, str]]]:
    if not isinstance(payload, dict):
        raise ValueError("Request body must be a JSON object")

    conversation_id = payload.get("conversation_id")
    revision = payload.get("revision")
    raw_turns = payload.get("turns")
    if not isinstance(conversation_id, str) or not conversation_id.strip():
        raise ValueError("conversation_id must be a non-empty string")
    if not isinstance(revision, str) or not revision.strip():
        raise ValueError("revision must be a non-empty string")
    if not isinstance(raw_turns, list) or not raw_turns:
        raise ValueError("turns must be a non-empty array")
    if len(raw_turns) > MAX_TURNS:
        raise ValueError(f"turns exceeds the {MAX_TURNS}-turn limit")

    turns: list[dict[str, str]] = []
    text_chars = 0
    for index, raw_turn in enumerate(raw_turns):
        if not isinstance(raw_turn, dict):
            raise ValueError(f"turns[{index}] must be an object")
        speaker = raw_turn.get("speaker")
        text = raw_turn.get("text")
        if speaker not in {"human", "assistant"}:
            raise ValueError(f"turns[{index}].speaker must be human or assistant")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"turns[{index}].text must be a non-empty string")
        text_chars += len(text)
        if text_chars > MAX_TEXT_CHARS:
            raise ValueError(f"turn text exceeds the {MAX_TEXT_CHARS}-character limit")
        turns.append({"speaker": speaker, "text": text})

    return conversation_id.strip(), revision.strip(), turns


def evaluate_live_payload(payload: Any, *, client, questions: dict, model: str) -> dict:
    conversation_id, revision, turns = validate_live_payload(payload)
    if set(questions) != EXPECTED_SCORES:
        raise ValueError(f"Question keys must be exactly: {sorted(EXPECTED_SCORES)}")

    state = build_state(turns, len(turns))
    result = client.evaluate(state, questions, model)
    scores = extract_noul_scores(result.response, EXPECTED_SCORES)
    return {
        "conversation_id": conversation_id,
        "revision": revision,
        **scores,
        "model": str(result.response.get("model", model)),
        "latency_ms": result.latency_ms,
        "request_length_chars": result.request_length_chars,
    }


def make_handler(*, client, questions: dict, model: str, token: str | None, mode: str):
    class LiveJevHandler(BaseHTTPRequestHandler):
        server_version = "ZetaJevBridge/0.1"

        def do_GET(self) -> None:
            if self.path != "/health":
                self._write_json(404, {"error": "not_found"})
                return
            self._write_json(200, {"status": "ok", "mode": mode, "model": model})

        def do_POST(self) -> None:
            if self.path != "/evaluate":
                self._write_json(404, {"error": "not_found"})
                return
            if token and not self._authorized(token):
                self._write_json(401, {"error": "unauthorized"})
                return

            try:
                content_length = int(self.headers.get("Content-Length", "0"))
                if content_length <= 0 or content_length > MAX_BODY_BYTES:
                    raise ValueError("Request body is empty or too large")
                payload = json.loads(self.rfile.read(content_length))
                response = evaluate_live_payload(
                    payload,
                    client=client,
                    questions=questions,
                    model=model,
                )
                self._write_json(200, response)
            except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
                self._write_json(400, {"error": "invalid_request", "detail": str(exc)})
            except Exception as exc:
                self._write_json(502, {"error": "evaluation_failed", "detail": str(exc)})

        def log_message(self, format: str, *args: object) -> None:
            # Do not place dialogue text in access logs. Standard request metadata is enough.
            super().log_message(format, *args)

        def _authorized(self, expected_token: str) -> bool:
            supplied = self.headers.get("Authorization", "")
            prefix = "Bearer "
            return supplied.startswith(prefix) and hmac.compare_digest(
                supplied[len(prefix):], expected_token
            )

        def _write_json(self, status: int, payload: dict) -> None:
            encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    return LiveJevHandler


def serve_live_bridge(
    *,
    host: str,
    port: int,
    client,
    questions: dict,
    model: str,
    token: str | None,
    mode: str,
) -> None:
    handler = make_handler(
        client=client,
        questions=questions,
        model=model,
        token=token,
        mode=mode,
    )
    server = ThreadingHTTPServer((host, port), handler)
    print(
        f"Zeta Jev bridge listening on http://{host}:{port} "
        f"({mode}, model={model})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
