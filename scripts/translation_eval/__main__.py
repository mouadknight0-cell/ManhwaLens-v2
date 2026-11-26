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
import datetime
import json
import sys
from pathlib import Path

if __package__ in (None, ""):  # allow direct execution
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from translation_eval import device, harness, importers, reports, review, schema  # type: ignore
else:
    from . import device, harness, importers, reports, review, schema  # type: ignore

EXIT_OK = 0
EXIT_INCOMPLETE = 1
EXIT_INPUT_ERROR = 2
EXIT_CORPUS_INVALID = 3

DEFAULT_TSV = Path("docs/benchmark-phase3/dataset.tsv")
DEFAULT_CORPUS = Path("evaluation/corpus/rc4-corpus.json")
# The instrumentation test now reads the *application* asset, so the corpus has one
# editable copy instead of two that can drift. The app asset has its own generator
# (scripts/translation_eval/app_assets.py) because it carries a different shape;
# this command produces the legacy flat asset shape for ad-hoc use only.
DEVICE_ASSET = Path("build/translation-eval-corpus.json")


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


def cmd_review_sheet(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    out = review.write_review_html(dataset, Path(args.out))
    print(
        f"review sheet -> {out}  ({len(dataset.cases)} cases, "
        f"{sum(1 for c in dataset.cases if c.reference_status is schema.ReferenceStatus.PENDING_HUMAN_REVIEW)} pending, "
        f"{sum(1 for c in dataset.cases if schema.validates_reference(c))} reviewed)"
    )
    return EXIT_OK


def cmd_apply_review(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    path = Path(args.decisions)
    if not path.is_file():
        raise harness.HarnessError(f"decisions file not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise harness.HarnessError(f"decisions file is not valid JSON: {exc}") from exc

    reviewer, decisions = review.parse_decisions(raw)
    if args.timestamp:
        timestamp = args.timestamp
    else:
        # Recorded at the moment the human saved, never generated at import time.
        timestamp = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()
    dataset, skipped = review.apply_decisions(
        dataset, decisions, reviewer=reviewer, timestamp=timestamp,
        allow_override=args.allow_override,
    )

    integrity = reports.corpus_integrity(dataset)
    if integrity["error_count"]:
        out = Path(args.out_dir or "evaluation/reports")
        _write(out / "corpus-integrity.json", _dump(integrity))
        _write(out / "corpus-integrity.md", reports.render_corpus_integrity(integrity))
        print(
            f"refusing to write corpus: {integrity['error_count']} integrity errors after applying decisions",
            file=sys.stderr,
        )
        return EXIT_CORPUS_INVALID

    _write(Path(args.out), _dump(dataset.as_dict()))
    print(
        f"applied {len(decisions) - len(skipped)} decisions by {reviewer}; "
        f"{len(skipped)} already-reviewed cases skipped "
        f"(use --allow-override to replace them)"
    )
    return EXIT_OK


def cmd_export_device_asset(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    out = device.write_device_corpus_asset(dataset, Path(args.out))
    print(f"device corpus asset -> {out} ({len(dataset.cases)} cases, references excluded)")
    return EXIT_OK


def cmd_build_app_assets(args: argparse.Namespace) -> int:
    """Regenerate the app's two corpus payloads from the canonical corpus.

    Deliberately reads `evaluation/corpus/rc4-corpus.json` through
    `device.load_canonical_dataset` rather than re-importing the TSV, so the app
    and the reports can never describe different corpora.
    """
    dataset = device.load_canonical_dataset(Path(args.corpus))
    if args.expected_fingerprint and dataset.fingerprint != args.expected_fingerprint:
        print(
            f"corpus fingerprint {dataset.fingerprint} does not match the expected "
            f"{args.expected_fingerprint}; refusing to rewrite the app assets",
            file=sys.stderr,
        )
        return EXIT_INPUT_ERROR
    written = device.write_app_assets(dataset, Path(args.out))
    for path in written:
        print(f"app asset -> {path} ({len(dataset.cases)} cases)")
    return EXIT_OK


def cmd_import_device(args: argparse.Namespace) -> int:
    dataset = load_corpus(Path(args.corpus))
    export = device.load_device_export(Path(args.export), dataset)
    out = Path(args.out)
    _write(out, _dump(export.as_output_document()))
    for problem in export.problems:
        print(f"warning: {problem}", file=sys.stderr)
    print(
        f"imported {len(export.outputs)} of {len(dataset.cases)} device outputs "
        f"from {export.engine_id} @ {export.engine_revision} -> {out}"
    )
    if export.problems:
        return EXIT_INCOMPLETE
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

    p_sheet = sub.add_parser("review-sheet", help="Write the offline reference review sheet")
    p_sheet.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_sheet.add_argument("--out", default="evaluation/review/review-sheet.html")
    p_sheet.set_defaults(func=cmd_review_sheet)

    p_apply = sub.add_parser("apply-review", help="Apply review decisions to the corpus")
    p_apply.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_apply.add_argument("--decisions", required=True)
    p_apply.add_argument("--out", default=str(DEFAULT_CORPUS))
    p_apply.add_argument("--out-dir", default="evaluation/reports",
                         help="where to write integrity reports if the result is invalid")
    p_apply.add_argument("--timestamp", default=None,
                         help="ISO-8601 time to record; defaults to now in UTC")
    p_apply.add_argument("--allow-override", action="store_true",
                         help="replace decisions on cases already reviewed")
    p_apply.set_defaults(func=cmd_apply_review)

    p_asset = sub.add_parser("export-device-asset",
                             help="Write the JSON asset the on-device test reads")
    p_asset.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_asset.add_argument("--out", default=str(DEVICE_ASSET))
    p_asset.set_defaults(func=cmd_export_device_asset)

    p_app = sub.add_parser("build-app-assets",
                           help="Regenerate the app's engine-input and human-review assets")
    p_app.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_app.add_argument("--out", default="app/src/main/assets")
    p_app.add_argument("--expected-fingerprint", default="",
                       help="Fail closed instead of rewriting assets from a changed corpus")
    p_app.set_defaults(func=cmd_build_app_assets)

    p_import_dev = sub.add_parser("import-device",
                                  help="Validate a device export into a harness output document")
    p_import_dev.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    p_import_dev.add_argument("--export", required=True, help="device export JSON")
    p_import_dev.add_argument("--out", default="evaluation/device/baseline-outputs.json")
    p_import_dev.set_defaults(func=cmd_import_device)

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
