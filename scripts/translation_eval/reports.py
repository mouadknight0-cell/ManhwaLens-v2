"""Three reports: corpus integrity, evaluation run, regression review.

Every report exists in two forms from one source of truth: a machine-readable
dict (stable key order) and a readable Markdown rendering. Neither contains a
timestamp or run id unless the caller supplies one, so identical inputs produce
identical files.
"""

from __future__ import annotations

from collections import Counter
from typing import Any, Iterable

from .harness import CaseComparison, OutputSet, summarize
from .schema import (
    CaseResult,
    ContextDependency,
    CorpusMatrix,
    Dataset,
    OcrQuality,
    ReferenceStatus,
    SCHEMA_VERSION,
    TextLength,
    SemanticCategory,
    errors,
    validate_dataset,
    validates_reference,
    warnings,
)


def _table(headers: list[str], rows: Iterable[Iterable[Any]]) -> list[str]:
    """Render a Markdown table. Kept simple so output is byte-stable."""
    out = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        cells = []
        for value in row:
            text = "" if value is None else str(value)
            cells.append(text.replace("|", "\\|").replace("\n", " "))
        out.append("| " + " | ".join(cells) + " |")
    return out


# --------------------------------------------------------------------------
# Report A -- corpus integrity
# --------------------------------------------------------------------------


def corpus_integrity(dataset: Dataset) -> dict[str, Any]:
    """Everything about the corpus itself, with no reference to any engine."""
    issues = validate_dataset(dataset)
    error_list = errors(issues)
    warning_list = warnings(issues)

    status_counts = Counter(c.reference_status.value for c in dataset.cases)
    ocr_counts = Counter(c.ocr_quality.value for c in dataset.cases)
    length_counts = Counter(
        c.text_length.value if c.text_length else "unclassified" for c in dataset.cases
    )
    semantic_counts = Counter(c.semantic_category.value for c in dataset.cases)
    source_counts = Counter(c.source_type.value for c in dataset.cases)

    groups: dict[str, list[int]] = {}
    for case in dataset.cases:
        if case.context_group_id:
            groups.setdefault(case.context_group_id, []).append(case.context_order or 0)

    matrix = CorpusMatrix.build(dataset)

    return {
        "report": "corpus_integrity",
        "schema_version": SCHEMA_VERSION,
        "dataset_version": dataset.dataset_version,
        "dataset_fingerprint": dataset.fingerprint or dataset.compute_fingerprint(),
        "total_cases": len(dataset.cases),
        "unique_case_ids": len({c.case_id for c in dataset.cases}),
        "duplicate_ids": sorted(
            cid for cid, n in Counter(c.case_id for c in dataset.cases).items() if n > 1
        ),
        "review_status_counts": dict(sorted(status_counts.items())),
        "references_valid_for_scoring": sum(1 for c in dataset.cases if validates_reference(c)),
        "references_pending": status_counts.get(
            ReferenceStatus.PENDING_HUMAN_REVIEW.value, 0
        ),
        "ocr_quality_distribution": dict(sorted(ocr_counts.items())),
        "text_length_distribution": dict(sorted(length_counts.items())),
        "semantic_category_distribution": dict(sorted(semantic_counts.items())),
        "source_type_distribution": dict(sorted(source_counts.items())),
        "context_groups": {gid: sorted(orders) for gid, orders in sorted(groups.items())},
        "missing_fields": [i.as_dict() for i in warning_list if i.code.startswith("unclassified") or i.code.startswith("missing")],
        "invalid_values": [i.as_dict() for i in error_list],
        "warnings": [i.as_dict() for i in warning_list],
        "error_count": len(error_list),
        "warning_count": len(warning_list),
        "matrix_coverage": matrix.as_dict(),
        "provenance_complete": all((c.source_provenance or "").strip() for c in dataset.cases),
    }


def render_corpus_integrity(report: dict[str, Any]) -> str:
    lines = [
        "# Report A — Corpus integrity",
        "",
        f"- Schema version: `{report['schema_version']}`",
        f"- Dataset fingerprint: `{report['dataset_fingerprint'][:32]}…`",
        f"- Total cases: **{report['total_cases']}** (unique ids: {report['unique_case_ids']})",
        f"- Duplicate ids: {report['duplicate_ids'] or 'none'}",
        f"- Errors: {report['error_count']} · warnings: {report['warning_count']}",
        f"- Provenance complete: {report['provenance_complete']}",
        "",
        "## Reference review status",
        "",
        "Only `reviewed` references with a named reviewer and timestamp may take part in",
        "verified quality scoring. Nothing in this corpus has been upgraded by tooling.",
        "",
    ]
    lines += _table(
        ["status", "cases"],
        [[k, v] for k, v in report["review_status_counts"].items()],
    )
    lines += [
        "",
        f"Valid for scored evaluation: **{report['references_valid_for_scoring']}** of "
        f"{report['total_cases']}.",
        "",
        "## OCR quality strata",
        "",
    ]
    lines += _table(
        ["stratum", "cases"],
        [[k, v] for k, v in report["ocr_quality_distribution"].items()],
    )
    lines += ["", "## Text length", ""]
    lines += _table(
        ["length", "cases"],
        [[k, v] for k, v in report["text_length_distribution"].items()],
    )
    lines += ["", "## Semantic categories", ""]
    lines += _table(
        ["category", "cases"],
        [[k, v] for k, v in report["semantic_category_distribution"].items()],
    )
    lines += ["", "## Context groups", ""]
    if report["context_groups"]:
        lines += _table(
            ["group", "orders"],
            [[k, ", ".join(str(o) for o in v)] for k, v in report["context_groups"].items()],
        )
    else:
        lines.append("_No ordered context groups in this corpus._")

    lines += ["", "## Matrix coverage", ""]
    lines += _table(
        ["dimension", "value", "status"],
        [
            [r["dimension"], r["value"], "covered" if r["present"] else "**gap**"]
            for r in report["matrix_coverage"]["rows"]
        ],
    )
    gaps = report["matrix_coverage"]["gaps"]
    lines += [
        "",
        f"Coverage gaps: **{len(gaps)}** of "
        f"{len(report['matrix_coverage']['rows'])} matrix cells.",
        "",
    ]
    if report["invalid_values"]:
        lines += ["## Invalid values", ""]
        lines += _table(
            ["code", "case", "message"],
            [[i["code"], i["case_id"], i["message"]] for i in report["invalid_values"]],
        )
        lines.append("")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# Report B -- evaluation run
# --------------------------------------------------------------------------


def evaluation_run(
    dataset: Dataset,
    baseline: OutputSet,
    candidate: OutputSet,
    rows: list[CaseComparison],
    run_meta: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """A reproducible record of one comparison."""
    summary = summarize(rows, dataset)
    reviewed = [r for r in rows if r.reference_counts_for_scoring]

    def group(key) -> dict[str, dict[str, int]]:
        buckets: dict[str, Counter] = {}
        for row in rows:
            label = key(row) or "unclassified"
            buckets.setdefault(label, Counter())[row.result.value] += 1
        return {k: dict(sorted(v.items())) for k, v in sorted(buckets.items())}

    return {
        "report": "evaluation_run",
        "harness_version": run_meta.get("harness_version") if run_meta else None,
        "dataset_version": dataset.dataset_version,
        "dataset_fingerprint": dataset.fingerprint or dataset.compute_fingerprint(),
        "baseline": {
            "engine_id": baseline.engine_id,
            "revision": baseline.revision,
            "config": baseline.config,
            "output_fingerprint": baseline.fingerprint(),
            "duplicate_case_ids": baseline.duplicates(),
        },
        "candidate": {
            "engine_id": candidate.engine_id,
            "revision": candidate.revision,
            "config": candidate.config,
            "output_fingerprint": candidate.fingerprint(),
            "duplicate_case_ids": candidate.duplicates(),
        },
        "normalization": run_meta.get("normalization") if run_meta else None,
        "summary": summary,
        "references_pending_excluded_from_scoring": len(rows) - len(reviewed),
        "by_ocr_quality": group(lambda r: r.ocr_quality),
        "by_text_length": group(lambda r: r.text_length),
        "by_semantic_category": group(lambda r: r.semantic_category),
        "by_context_group": group(lambda r: r.context_group_id),
        "per_case": [r.as_dict() for r in rows],
    }


def render_evaluation_run(report: dict[str, Any]) -> str:
    s = report["summary"]
    lines = [
        "# Report B — Evaluation run",
        "",
        "## Run identity",
        "",
        f"- Harness version: `{report['harness_version']}`",
        f"- Dataset version: `{report['dataset_version']}`",
        f"- Dataset fingerprint: `{report['dataset_fingerprint'][:32]}…`",
        f"- Baseline: `{report['baseline']['engine_id']}` @ `{report['baseline']['revision']}`",
        f"-   outputs `{report['baseline']['output_fingerprint'][:32]}…`",
        f"- Candidate: `{report['candidate']['engine_id']}` @ `{report['candidate']['revision']}`",
        f"-   outputs `{report['candidate']['output_fingerprint'][:32]}…`",
        f"- Normalization: `{report['normalization']}`",
        "",
        "## Completeness",
        "",
        f"- Dataset cases: {s['dataset_cases']} · compared rows: {s['compared_rows']}",
        f"- Baseline outputs missing: **{s['baseline_outputs_missing']}**",
        f"- Candidate outputs missing: **{s['candidate_outputs_missing']}**",
        f"- Malformed outputs: {s['malformed_outputs']}",
        f"- References valid for scoring: {s['references_reviewed_and_valid']}",
        f"- References pending (excluded from scoring): {s['references_pending']}",
        "",
        "## Semantic quality score",
        "",
        f"**Not computed.** {s['semantic_score_reason']}",
        "",
        "No aggregate quality figure is emitted, because producing one from pending",
        "references would be a fabricated measurement.",
        "",
        "## Per-case results",
        "",
    ]
    lines += _table(
        ["result", "cases"], [[k, v] for k, v in s["result_counts"].items()]
    )

    for title, key in (
        ("Results by OCR quality", "by_ocr_quality"),
        ("Results by text length", "by_text_length"),
        ("Results by semantic category", "by_semantic_category"),
        ("Results by context group", "by_context_group"),
    ):
        lines += ["", f"## {title}", ""]
        buckets = report[key]
        if not buckets:
            lines.append("_None._")
            continue
        header = ["group"] + sorted({k for b in buckets.values() for k in b})
        body = []
        for name, counts in buckets.items():
            body.append([name] + [counts.get(col, 0) for col in header[1:]])
        lines += _table(header, body)

    lines += ["", "## Per-case differences", ""]
    lines += _table(
        ["case", "result", "baseline", "candidate", "ref", "similarity"],
        [
            [
                r["case_id"],
                r["result"],
                r["baseline"]["status"],
                r["candidate"]["status"],
                r["reference_status"],
                f"{r['similarity']:.3f}",
            ]
            for r in report["per_case"]
        ],
    )
    lines.append("")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# Report C -- regression review
# --------------------------------------------------------------------------


def regression_review(rows: list[CaseComparison]) -> dict[str, Any]:
    """Every changed case a human must look at, grouped by why.

    Nothing here asserts that a change is better or worse. The grouping exists so
    a reviewer can start with the highest-risk categories: a vanished output, a
    truncated output, or a multi-line coverage failure.
    """

    def bucket(predicate) -> list[dict[str, Any]]:
        return [r.as_dict() for r in rows if predicate(r)]

    return {
        "report": "regression_review",
        "requires_review_total": sum(1 for r in rows if r.needs_review),
        "candidate_missing_where_baseline_exists": bucket(
            lambda r: r.result is CaseResult.CANDIDATE_MISSING
        ),
        "baseline_missing_where_candidate_exists": bucket(
            lambda r: r.result is CaseResult.BASELINE_MISSING
        ),
        "both_missing": bucket(lambda r: r.result is CaseResult.BOTH_MISSING),
        "changed_with_reviewed_reference": bucket(
            lambda r: r.needs_review and r.reference_counts_for_scoring
        ),
        "changed_with_pending_reference": bucket(
            lambda r: r.needs_review and not r.reference_counts_for_scoring
        ),
        "ocr_related": bucket(
            lambda r: r.ocr_quality
            not in (OcrQuality.CLEAN.value, OcrQuality.UNKNOWN.value)
        ),
        "truncated_outputs": bucket(
            lambda r: any(
                t in (r.error_reason_codes or []) or t == r.error_category
                for t in ("truncation", "coverage_failure")
            )
        ),
        "multiline_coverage": bucket(
            lambda r: r.text_length == TextLength.MULTILINE.value
        ),
        "context_sensitive": bucket(
            lambda r: r.context_group_id is not None
        ),
    }


def render_regression_review(report: dict[str, Any]) -> str:
    lines = [
        "# Report C — Regression review",
        "",
        "Every case below changed between baseline and candidate, or lost an output.",
        "**A change is not evidence of improvement or regression.** Each row needs a human",
        "bilingual decision; the groupings only order the work by risk.",
        "",
        f"Cases requiring review: **{report['requires_review_total']}**",
        "",
    ]
    sections = [
        ("Candidate output missing where baseline exists", "candidate_missing_where_baseline_exists"),
        ("Baseline output missing where candidate exists", "baseline_missing_where_candidate_exists"),
        ("Both outputs missing", "both_missing"),
        ("Changed, with a reviewed reference", "changed_with_reviewed_reference"),
        ("Changed, reference still pending", "changed_with_pending_reference"),
        ("OCR-related", "ocr_related"),
        ("Truncated / coverage failure", "truncated_outputs"),
        ("Multi-line coverage", "multiline_coverage"),
        ("Context-sensitive", "context_sensitive"),
    ]
    for title, key in sections:
        entries = report[key]
        lines += [f"## {title} ({len(entries)})", ""]
        if not entries:
            lines += ["_None._", ""]
            continue
        lines += _table(
            ["case", "result", "source", "baseline text", "candidate text"],
            [
                [
                    e["case_id"],
                    e["result"],
                    e["source_text"][:60],
                    e["baseline"]["text"][:60],
                    e["candidate"]["text"][:60],
                ]
                for e in entries
            ],
        )
        lines.append("")
    return "\n".join(lines) + "\n"
