from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .analysis import analyze, dialogue_window
from .data import DEFAULT_DATASET_URL, deterministic_sample, iter_rows, read_jsonl, write_jsonl
from .jev import JevClient, MockJevClient, build_state, extract_noul_scores, questions_sha256
from .qwen import QwenOpenAIClient
from .storage import (
    connect,
    is_complete,
    make_record,
    returned_model_versions,
    save_error,
    save_evaluation,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_QUESTIONS = PROJECT_ROOT / "config" / "questions.json"
DEFAULT_SAMPLE = PROJECT_ROOT / "data" / "sample.jsonl"
DEFAULT_DB = PROJECT_ROOT / "data" / "results.sqlite3"


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def command_prepare(args: argparse.Namespace) -> int:
    records, stats = deterministic_sample(
        iter_rows(args.source),
        count=args.count,
        seed=args.seed,
        min_turns=args.min_turns,
        max_turns=args.max_turns,
        max_conversation_chars=args.max_conversation_chars,
        start_turn=args.start_turn,
    )
    if len(records) < args.count:
        raise RuntimeError(f"Only {len(records)} eligible conversations found; requested {args.count}")
    write_jsonl(args.output, records)
    manifest = {
        "dataset": "PygmalionAI/PIPPA",
        "config": "pippa_deduped",
        "source": args.source,
        "seed": args.seed,
        "count": args.count,
        "min_turns": args.min_turns,
        "max_turns": args.max_turns,
        "max_conversation_chars": args.max_conversation_chars,
        "start_turn": args.start_turn,
        **stats.__dict__,
    }
    args.output.with_suffix(".manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest, indent=2))
    return 0


def _run_evaluations(args: argparse.Namespace, client, model_key: str) -> int:
    questions = _load_json(args.questions)
    expected = {"context_shift", "natural_breakpoint", "engagement_drop"}
    if set(questions) != expected:
        raise ValueError(f"Question keys must be exactly: {sorted(expected)}")
    question_hash = questions_sha256(questions)

    completed = skipped = failed = 0
    stop = False
    with connect(args.database) as database:
        for conversation in read_jsonl(args.sample):
            for turn_index in range(args.start_turn, len(conversation["turns"]) + 1):
                if args.limit is not None and completed >= args.limit:
                    stop = True
                    break
                cid = conversation["conversation_id"]
                if is_complete(database, cid, turn_index, question_hash, model_key):
                    skipped += 1
                    continue
                state = build_state(conversation["turns"], turn_index)
                try:
                    result = client.evaluate(state, questions, args.model)
                    scores = extract_noul_scores(result.response, expected)
                    returned_model = str(result.response.get("model", ""))
                    existing_versions = returned_model_versions(database, question_hash, model_key)
                    if existing_versions and returned_model not in existing_versions:
                        raise RuntimeError(
                            f"Requested alias {model_key!r} now returned {returned_model!r}, but this "
                            f"run already contains {sorted(existing_versions)}. Pin a versioned model "
                            "or use a new database to avoid mixing model versions."
                        )
                    record = make_record(
                        conversation=conversation,
                        turn_index=turn_index,
                        state=state,
                        scores=scores,
                        result=result,
                        model_requested=model_key,
                        question_hash=question_hash,
                    )
                    save_evaluation(database, record)
                    completed += 1
                    print(
                        f"saved {cid} turn {turn_index}/{len(conversation['turns'])} "
                        f"({completed} new)",
                        flush=True,
                    )
                except Exception as exc:
                    failed += 1
                    save_error(
                        database,
                        conversation_id=cid,
                        turn_index=turn_index,
                        questions_sha256=question_hash,
                        model_requested=model_key,
                        error=str(exc),
                    )
                    print(f"error {cid} turn {turn_index}: {exc}", file=sys.stderr, flush=True)
                    if not args.keep_going:
                        raise
            if stop:
                break
    print(f"done: {completed} saved, {skipped} already present, {failed} failed")
    return 0


def command_run(args: argparse.Namespace) -> int:
    model_key = f"mock:{args.model}" if args.mock else args.model
    if args.mock:
        client = MockJevClient()
    else:
        api_key = os.environ.get("TYPESAFE_API_KEY")
        if not api_key:
            raise RuntimeError("Set TYPESAFE_API_KEY, or use --mock for a local pipeline check")
        client = JevClient(
            api_key,
            endpoint=args.endpoint,
            timeout=args.timeout,
            max_retries=args.max_retries,
        )
    return _run_evaluations(args, client, model_key)


def command_run_qwen(args: argparse.Namespace) -> int:
    client = QwenOpenAIClient(
        endpoint=args.endpoint,
        api_key=args.api_key,
        timeout=args.timeout,
        parallel_questions=args.parallel_questions,
    )
    return _run_evaluations(args, client, f"qwen:{args.model}")


def command_analyze(args: argparse.Namespace) -> int:
    with connect(args.database) as database:
        summary = analyze(
            connection=database,
            sample_path=args.sample,
            output_dir=args.output_dir,
            spike_threshold=args.spike_threshold,
            high_threshold=args.high_threshold,
            context_radius=args.context_radius,
            top=args.top,
            questions_hash=args.questions_hash,
            model=args.model,
        )
    print(json.dumps(summary, indent=2))
    print(f"report: {args.output_dir / 'report.md'}")
    return 0


def command_inspect(args: argparse.Namespace) -> int:
    match = next(
        (row for row in read_jsonl(args.sample) if row["conversation_id"] == args.conversation_id),
        None,
    )
    if match is None:
        raise ValueError(f"Conversation not found: {args.conversation_id}")
    print(dialogue_window(match, args.turn, args.radius))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="Deterministically sample PIPPA")
    prepare.add_argument("--source", default=DEFAULT_DATASET_URL)
    prepare.add_argument("--output", type=Path, default=DEFAULT_SAMPLE)
    prepare.add_argument("--count", type=int, default=20)
    prepare.add_argument("--seed", type=int, default=20260920)
    prepare.add_argument("--min-turns", type=int, default=8)
    prepare.add_argument("--max-turns", type=int, default=50)
    prepare.add_argument("--max-conversation-chars", type=int, default=80000)
    prepare.add_argument("--start-turn", type=int, default=3)
    prepare.set_defaults(func=command_prepare)

    run = subparsers.add_parser("run", help="Evaluate expanding prefixes with Jev")
    run.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    run.add_argument("--database", type=Path, default=DEFAULT_DB)
    run.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    run.add_argument("--model", default="jev-latest")
    run.add_argument("--endpoint", default="https://api.typesafe.ai/v1/systemone")
    run.add_argument("--start-turn", type=int, default=3)
    run.add_argument("--timeout", type=float, default=90.0)
    run.add_argument("--max-retries", type=int, default=5)
    run.add_argument("--limit", type=int)
    run.add_argument("--keep-going", action="store_true")
    run.add_argument("--mock", action="store_true", help="Use fake local scores; never Jev output")
    run.set_defaults(func=command_run)

    qwen = subparsers.add_parser(
        "run-qwen", help="Evaluate expanding prefixes with an OpenAI-compatible Qwen server"
    )
    qwen.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    qwen.add_argument("--database", type=Path, default=DEFAULT_DB)
    qwen.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    qwen.add_argument("--model", default="qwen3.8:27b-q4_K_M")
    qwen.add_argument(
        "--endpoint", default="http://127.0.0.1:11500/v1/chat/completions"
    )
    qwen.add_argument("--api-key", default="unused")
    qwen.add_argument("--start-turn", type=int, default=3)
    qwen.add_argument("--timeout", type=float, default=180.0)
    qwen.add_argument("--parallel-questions", type=int, default=3)
    qwen.add_argument("--limit", type=int)
    qwen.add_argument("--keep-going", action="store_true")
    qwen.set_defaults(func=command_run_qwen)

    analysis = subparsers.add_parser("analyze", help="Create plots and transition tables")
    analysis.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    analysis.add_argument("--database", type=Path, default=DEFAULT_DB)
    analysis.add_argument("--output-dir", type=Path, default=PROJECT_ROOT / "outputs")
    analysis.add_argument("--spike-threshold", type=float, default=0.25)
    analysis.add_argument("--high-threshold", type=float, default=0.75)
    analysis.add_argument("--context-radius", type=int, default=3)
    analysis.add_argument("--top", type=int, default=20)
    analysis.add_argument("--questions-hash")
    analysis.add_argument("--model")
    analysis.set_defaults(func=command_analyze)

    inspect = subparsers.add_parser("inspect", help="Print dialogue around one turn")
    inspect.add_argument("conversation_id")
    inspect.add_argument("turn", type=int)
    inspect.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    inspect.add_argument("--radius", type=int, default=3)
    inspect.set_defaults(func=command_inspect)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (ValueError, RuntimeError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
