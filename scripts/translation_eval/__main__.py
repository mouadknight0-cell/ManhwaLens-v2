"""Command-line entry point for the translation evaluation infrastructure.

Three subcommands, all offline and dependency-free:

``validate``   Report A -- corpus integrity only.
``evaluate``   Reports A, B and C from a dataset plus two saved-output files.
``import-rc4`` (Re)generate the versioned corpus from the RC4 Phase 3 TSV.

Exit codes are meaningful and stable, because a CI job must be able to tell an
infrastructure failure from a data problem from a clean run:

===  ==========================================================================
0    Success; no integrity errors, run complete.
1    Evaluation completed but data is incomplete (missing outputs, pending
     references, or corpus warnings). The comparison itself is valid.
2    Input error: a file was missing, malformed, or the wrong shape. This is an
     infrastructure failure and is never reported as a translation failure.
3    Corpus contains integrity errors and cannot be evaluated.
===  ==========================================================================

The harness never requires Android, a JDK, an emulator, ML Kit, or a network.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

if __package__ in (None, ""):  # allow direct execution
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from translation_eval import harness, importers, reports, schema  # type: ignore
else:
    from . import harness, importers, reports, schema  # type: ignore

EXIT_OK = 0
EXIT_INCOMPLETE = 1
EXIT_INPUT_ERROR = 2
EXIT_CORPUS_INVALID = 3

DEFAULT_TSV = Path("docs/benchmark-phase3/dataset.tsv")
DEFAULT_CORPUS = Path("evaluation/corpus/rc4-corpus.json")


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _dump(obj) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def load_corpus(path: Path) -> schema.Dataset:
    """Load a corpus JSON produced by ``import-rc4``."""
    if not path.is_file():
        raise harness.HarnessError(f"corpus file not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise harness.HarnessError(f"corpus file is not valid JSON: {exc}") from exc
    if not isinstance(raw, dict) or "cases" not in raw:
        raise harness.HarnessError("corpus file must be an object with a 'cases' array")
    dataset = schema.Dataset(
        schema_version=str(raw.get("schema_version", "")),
        dataset_version=str(raw.get("dataset_version", "")),
        cases=[schema.EvalCase.from_dict(c) for c in raw["cases"]],
    )
    dataset.fingerprint = dataset.compute_fingerprint()
    return dataset


def load_outputs(path: Path) -> harness.OutputSet:
    if not path.is_file():
        raise harness.HarnessError(f"output file not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise harness.HarnessError(f"output file is not valid JSON: {exc}") from exc
    return harness.parse_outputs(raw)


def cmd_validate(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    report = reports.corpus_integrity(dataset)
    out = Path(args.out)
    _write(out / "corpus-integrity.json", _dump(report))
    _write(out / "corpus-integrity.md", reports.render_corpus_integrity(report))
    print(
        f"corpus: {report['total_cases']} cases, {report['error_count']} errors, "
        f"{report['warning_count']} warnings, {len(report['matrix_coverage']['gaps'])} matrix gaps"
    )
    if report["error_count"]:
        return EXIT_CORPUS_INVALID
    return EXIT_OK


def cmd_evaluate(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    integrity = reports.corpus_integrity(dataset)
    if integrity["error_count"]:
        # Never evaluate an invalid corpus: conclusions drawn from it would be
        # built on data that did not pass its own contract.
        out = Path(args.out)
        _write(out / "corpus-integrity.json", _dump(integrity))
        _write(out / "corpus-integrity.md", reports.render_corpus_integrity(integrity))
        print(
            f"corpus has {integrity['error_count']} integrity errors; refusing to evaluate",
            file=sys.stderr,
        )
        return EXIT_CORPUS_INVALID

    baseline = load_outputs(Path(args.baseline))
    candidate = load_outputs(Path(args.candidate))
    rows = harness.compare(dataset, baseline, candidate)
    rows = sorted(rows, key=lambda r: r.case_id)

    run_meta = {
        "harness_version": harness.HARNESS_VERSION,
        "normalization": schema_comparison_fingerprint(),
    }
    run_report = reports.evaluation_run(dataset, baseline, candidate, rows, run_meta)
    review_report = reports.regression_review(rows)

    out = Path(args.out)
    _write(out / "corpus-integrity.json", _dump(integrity))
    _write(out / "corpus-integrity.md", reports.render_corpus_integrity(integrity))
    _write(out / "evaluation-run.json", _dump(run_report))
    _write(out / "evaluation-run.md", reports.render_evaluation_run(run_report))
    _write(out / "regression-review.json", _dump(review_report))
    _write(out / "regression-review.md", reports.render_regression_review(review_report))

    summary = run_report["summary"]
    print(
        f"evaluated {summary['compared_rows']} cases: "
        f"baseline missing {summary['baseline_outputs_missing']}, "
        f"candidate missing {summary['candidate_outputs_missing']}, "
        f"reviewing {review_report['requires_review_total']}"
    )
    incomplete = (
        summary["baseline_outputs_missing"] > 0
        or summary["candidate_outputs_missing"] > 0
        or summary["malformed_outputs"] > 0
        or summary["references_pending"] > 0
    )
    return EXIT_INCOMPLETE if incomplete else EXIT_OK


def schema_comparison_fingerprint() -> str:
    from .normalization import DEFAULT_COMPARISON

    return DEFAULT_COMPARISON.fingerprint()


def cmd_import_rc4(args: argparse.Namespace) -> int:
    dataset = importers.import_rc4_corpus(Path(args.tsv))
    out = Path(args.out)
    _write(out, _dump(dataset.as_dict()))
    print(f"imported {len(dataset.cases)} cases -> {out} (fingerprint {dataset.fingerprint[:16]}…)")
    return EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="translation_eval",
        description="Offline, deterministic translation evaluation harness.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_validate = sub.add_parser("validate", help="Report A: corpus integrity")
    p_validate.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_validate.add_argument("--out", default="evaluation/reports")
    p_validate.set_defaults(func=cmd_validate)

    p_eval = sub.add_parser("evaluate", help="Reports A, B and C from saved outputs")
    p_eval.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_eval.add_argument("--baseline", required=True, help="baseline outputs JSON")
    p_eval.add_argument("--candidate", required=True, help="candidate outputs JSON")
    p_eval.add_argument("--out", default="evaluation/reports")
    p_eval.set_defaults(func=cmd_evaluate)

    p_import = sub.add_parser("import-rc4", help="Regenerate the corpus from the RC4 TSV")
    p_import.add_argument("--tsv", default=str(DEFAULT_TSV))
    p_import.add_argument("--out", default=str(DEFAULT_CORPUS))
    p_import.set_defaults(func=cmd_import_rc4)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except harness.HarnessError as exc:
        print(f"input error: {exc}", file=sys.stderr)
        return EXIT_INPUT_ERROR
    except (ValueError, KeyError) as exc:
        print(f"invalid data: {exc}", file=sys.stderr)
        return EXIT_INPUT_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
