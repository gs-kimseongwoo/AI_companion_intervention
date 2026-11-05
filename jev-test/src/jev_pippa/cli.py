from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from .event_boundary import load_prompt
from .jev_client import JevClient
from .pipeline import evaluate_dataset, load_json, read_jsonl, write_json
from .refine_dataset import refine_dataset
from .report import write_event_boundary_summary


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROMPT = PROJECT_ROOT / "prompts" / "event_boundary.json"
DEFAULT_DATA = PROJECT_ROOT / "data"


def _client(args: argparse.Namespace) -> JevClient:
    api_key = os.environ.get("TYPESAFE_API_KEY")
    if not api_key:
        raise RuntimeError("Set TYPESAFE_API_KEY before evaluating a dataset")
    return JevClient(
        api_key,
        endpoint=args.endpoint,
        timeout=args.timeout,
        max_retries=args.max_retries,
    )


def _refine(source: Path, destination: Path) -> dict:
    dataset = refine_dataset(load_json(source))
    write_json(destination, dataset)
    print(f"refined dataset: {destination}")
    return dataset


def _evaluate(
    *,
    dataset: dict,
    dataset_path: Path,
    output_path: Path,
    args: argparse.Namespace,
) -> list[dict]:
    return evaluate_dataset(
        dataset=dataset,
        dataset_path=dataset_path,
        output_path=output_path,
        client=_client(args),
        prompt=load_prompt(args.prompt),
        model=args.model,
        selected_turns=args.turn,
    )


def command_refine(args: argparse.Namespace) -> int:
    _refine(args.input, args.output)
    return 0


def command_evaluate(args: argparse.Namespace) -> int:
    records = _evaluate(
        dataset=load_json(args.dataset),
        dataset_path=args.dataset,
        output_path=args.output,
        args=args,
    )
    print(f"evaluations ({len(records)} turns): {args.output}")
    return 0


def command_report(args: argparse.Namespace) -> int:
    records = read_jsonl(args.evaluations)
    count, _ = write_event_boundary_summary(
        records,
        csv_path=args.csv,
        markdown_path=args.markdown,
    )
    print(f"report ({count} turns): {args.csv}")
    print(f"report ({count} turns): {args.markdown}")
    return 0


def command_run(args: argparse.Namespace) -> int:
    args.output_dir.mkdir(parents=True, exist_ok=True)
    refined_path = args.output_dir / "dataset.refined.json"
    evaluations_path = args.output_dir / "evaluations.jsonl"
    csv_path = args.output_dir / "report.csv"
    markdown_path = args.output_dir / "report.md"

    dataset = _refine(args.input, refined_path)
    records = _evaluate(
        dataset=dataset,
        dataset_path=refined_path,
        output_path=evaluations_path,
        args=args,
    )
    write_event_boundary_summary(
        records,
        csv_path=csv_path,
        markdown_path=markdown_path,
    )
    print(f"evaluations: {evaluations_path}")
    print(f"report: {csv_path}")
    print(f"report: {markdown_path}")
    return 0


def _add_evaluation_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--prompt", type=Path, default=DEFAULT_PROMPT)
    parser.add_argument("--turn", type=int, action="append")
    parser.add_argument("--model", default="jev-latest")
    parser.add_argument(
        "--endpoint",
        default="https://api.typesafe.ai/v1/systemone",
    )
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--max-retries", type=int, default=5)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Refine, evaluate, and report a Zeta role-play dataset."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    refine = commands.add_parser("refine", help="Create the standard dataset JSON")
    refine.add_argument("--input", required=True, type=Path)
    refine.add_argument("--output", required=True, type=Path)
    refine.set_defaults(func=command_refine)

    evaluate = commands.add_parser("evaluate", help="Evaluate a refined dataset with Jev")
    evaluate.add_argument("--dataset", required=True, type=Path)
    evaluate.add_argument("--output", required=True, type=Path)
    _add_evaluation_options(evaluate)
    evaluate.set_defaults(func=command_evaluate)

    report = commands.add_parser("report", help="Create CSV and Markdown reports")
    report.add_argument("--evaluations", required=True, type=Path)
    report.add_argument("--csv", required=True, type=Path)
    report.add_argument("--markdown", required=True, type=Path)
    report.set_defaults(func=command_report)

    run = commands.add_parser("run", help="Run refine, evaluate, and report")
    run.add_argument("--input", required=True, type=Path)
    run.add_argument("--output-dir", type=Path, default=DEFAULT_DATA / "run")
    _add_evaluation_options(run)
    run.set_defaults(func=command_run)
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
