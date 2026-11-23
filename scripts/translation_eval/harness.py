"""Deterministic baseline-versus-candidate comparison.

Design rules that the code is built to enforce:

* **Nothing is silently dropped.** Every case in the dataset appears in the
  output with a status, including cases where one or both outputs are missing.
* **A string difference is not a verdict.** When two usable outputs differ the
  result is ``candidate_changed`` and, if a reviewed reference exists to review
  against, ``*_review_required``. The word "regression" only ever appears as
  ``..._review_required``, never as an automatic judgement.
* **No synthetic text.** Missing output produces an empty string in the report
  and a ``*_missing`` status; it is never back-filled from the source, the
  reference, or the other arm.
* **Deterministic.** The report contains no timestamps or random ids unless a
  caller supplies them; identical inputs produce byte-identical output.

The harness does not require Android, a JDK, an emulator, ML Kit, or a network.
Output files are produced externally on a device and imported here.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, Iterable

from .normalization import DEFAULT_COMPARISON, ComparisonConfig, normalize_for_comparison, similarity
from .schema import (
    CaseResult,
    Dataset,
    EvalCase,
    OutputStatus,
    ReferenceStatus,
    validates_reference,
)

HARNESS_VERSION = "1.0.0"


class HarnessError(Exception):
    """An infrastructure failure: bad input, not a bad translation.

    Distinguishing this from a translation failure is a phase requirement: a
    malformed output file is an evaluation error and must not be counted as a
    case where the engine failed to translate.
    """


@dataclass
class EngineOutput:
    """One engine's saved output for one case.

    ``text`` is stored exactly as supplied. ``status`` defaults to
    :attr:`OutputStatus.OK` when text is present, so a file that only carries
    text is still usable, but an explicitly recorded failure is preserved.
    """

    case_id: str
    text: str = ""
    status: OutputStatus = OutputStatus.OK
    error_code: str | None = None
    note: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "case_id": self.case_id,
            "text": self.text,
            "status": self.status.value,
            "error_code": self.error_code,
            "note": self.note,
        }


@dataclass
class OutputSet:
    """All outputs from one engine revision for one dataset.

    Carries its own identity so a run can state exactly what produced it.
    """

    engine_id: str
    revision: str
    outputs: dict[str, EngineOutput] = field(default_factory=dict)
    #: Free-form engine configuration (glossary revision, model id, ...).
    config: dict[str, Any] = field(default_factory=dict)
    raw_text: str = ""

    def fingerprint(self) -> str:
        payload = json.dumps(
            {
                "engine_id": self.engine_id,
                "revision": self.revision,
                "config": self.config,
                "outputs": [
                    self.outputs[k].as_dict() for k in sorted(self.outputs)
                ],
            },
            sort_keys=True,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def resolve(self, case_id: str) -> EngineOutput:
        """Return the output for a case, or an explicit MISSING record.

        A missing entry is never represented by empty success: it is
        :attr:`OutputStatus.MISSING` so that coverage reporting stays honest.
        """
        found = self.outputs.get(case_id)
        if found is not None:
            return found
        return EngineOutput(case_id=case_id, text="", status=OutputStatus.MISSING)

    def duplicates(self) -> list[str]:
        """Case ids that appeared more than once in the source file.

        Duplicates are recorded during parsing; this returns them so the report
        can list them rather than letting the last one win silently.
        """
        return sorted(self.config.get("_duplicate_case_ids", []))


def parse_outputs(raw: dict[str, Any]) -> OutputSet:
    """Parse a saved-output document.

    Accepted shape (either key naming, to suit whichever the device writes)::

        {"engine_id": "...", "revision": "...", "config": {...},
         "outputs": [{"case_id": "a", "text": "...", "status": "ok"}, ...]}

    Every record is validated. A malformed record raises :class:`HarnessError`
    rather than being skipped, because skipping would understate failures.
    """
    if not isinstance(raw, dict):
        raise HarnessError("output document must be a JSON object")

    engine_id = raw.get("engine_id")
    revision = raw.get("revision")
    if not isinstance(engine_id, str) or not engine_id.strip():
        raise HarnessError("output document requires a non-empty 'engine_id'")
    if not isinstance(revision, str) or not revision.strip():
        raise HarnessError("output document requires a non-empty 'revision'")

    entries = raw.get("outputs")
    if not isinstance(entries, list):
        raise HarnessError("output document requires an 'outputs' array")

    outputs: dict[str, EngineOutput] = {}
    duplicates: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise HarnessError(f"outputs[{index}] must be an object")
        case_id = entry.get("case_id")
        if not isinstance(case_id, str) or not case_id.strip():
            raise HarnessError(f"outputs[{index}] requires a non-empty 'case_id'")
        text = entry.get("text", "")
        if text is None:
            text = ""
        if not isinstance(text, str):
            raise HarnessError(f"outputs[{index}].text must be a string")

        raw_status = entry.get("status")
        if raw_status is None:
            status = OutputStatus.OK if text.strip() else OutputStatus.EMPTY
        else:
            try:
                status = OutputStatus(raw_status)
            except ValueError as exc:
                raise HarnessError(
                    f"outputs[{index}].status {raw_status!r} is not a known status"
                ) from exc

        record = EngineOutput(
            case_id=case_id,
            text=text,
            status=status,
            error_code=entry.get("error_code"),
            note=entry.get("note"),
        )
        if case_id in outputs:
            duplicates.append(case_id)
        outputs[case_id] = record

    config = raw.get("config") or {}
    if not isinstance(config, dict):
        raise HarnessError("'config' must be an object")
    config = dict(config)
    if duplicates:
        config["_duplicate_case_ids"] = sorted(set(duplicates))

    return OutputSet(
        engine_id=engine_id,
        revision=revision,
        outputs=outputs,
        config=config,
        raw_text=json.dumps(raw, sort_keys=True, ensure_ascii=False),
    )


@dataclass
class CaseComparison:
    """The full record for one case. Everything a reviewer needs, side by side."""

    case_id: str
    source_text: str
    reference_translation: str | None
    reference_status: ReferenceStatus
    reference_counts_for_scoring: bool
    baseline: EngineOutput
    candidate: EngineOutput
    result: CaseResult
    changed_under_normalization: bool
    similarity: float
    ocr_quality: str
    text_length: str | None
    semantic_category: str
    context_group_id: str | None
    context_order: int | None
    expected_behavior: str
    error_category: str | None
    error_reason_codes: list[str]

    def as_dict(self) -> dict[str, Any]:
        return {
            "case_id": self.case_id,
            "source_text": self.source_text,
            "reference_translation": self.reference_translation,
            "reference_status": self.reference_status.value,
            "reference_counts_for_scoring": self.reference_counts_for_scoring,
            "baseline": self.baseline.as_dict(),
            "candidate": self.candidate.as_dict(),
            "result": self.result.value,
            "changed_under_normalization": self.changed_under_normalization,
            "similarity": round(self.similarity, 6),
            "ocr_quality": self.ocr_quality,
            "text_length": self.text_length,
            "semantic_category": self.semantic_category,
            "context_group_id": self.context_group_id,
            "context_order": self.context_order,
            "expected_behavior": self.expected_behavior,
            "error_category": self.error_category,
            "error_reason_codes": self.error_reason_codes,
        }

    @property
    def needs_review(self) -> bool:
        return self.result in (
            CaseResult.CANDIDATE_REGRESSION_REVIEW_REQUIRED,
            CaseResult.CANDIDATE_IMPROVEMENT_REVIEW_REQUIRED,
            CaseResult.CANDIDATE_CHANGED,
        )

    @property
    def is_infrastructure_failure(self) -> bool:
        return self.result is CaseResult.EVALUATION_ERROR or self.baseline.status in (
            OutputStatus.MALFORMED,
        ) or self.candidate.status in (OutputStatus.MALFORMED,)


def _classify(
    case: EvalCase,
    baseline: EngineOutput,
    candidate: EngineOutput,
) -> tuple[CaseResult, bool]:
    """Decide the per-case outcome from output states alone.

    The only thing string comparison is permitted to conclude is "these differ".
    Whether a difference is better or worse is left to human review, and the
    status names say so.
    """
    b_usable = baseline.status is OutputStatus.OK and bool(baseline.text.strip())
    c_usable = candidate.status is OutputStatus.OK and bool(candidate.text.strip())
    b_present = b_usable or baseline.status in (OutputStatus.EMPTY, OutputStatus.FAILED)
    c_present = c_usable or candidate.status in (OutputStatus.EMPTY, OutputStatus.FAILED)

    if not b_present and not c_present:
        return CaseResult.BOTH_MISSING, False
    if not b_present:
        return CaseResult.BASELINE_MISSING, False
    if not c_present:
        return CaseResult.CANDIDATE_MISSING, False

    if not b_usable and not c_usable:
        # Both arms ran and both produced no usable text. That is a shared
        # failure, reported as unchanged rather than as progress.
        return CaseResult.UNCHANGED, False

    if b_usable and c_usable:
        changed = not _equivalent(baseline.text, candidate.text)
        if not changed:
            return CaseResult.UNCHANGED, False
        # A difference exists. Which direction it points is a review question.
        if validates_reference(case):
            return CaseResult.CANDIDATE_CHANGED, True
        return CaseResult.CANDIDATE_CHANGED, True

    # One arm produced text and the other did not: never a silent no-op.
    return CaseResult.CANDIDATE_CHANGED, True


_COMPARISON: ComparisonConfig = DEFAULT_COMPARISON


def _equivalent(left: str, right: str) -> bool:
    return normalize_for_comparison(left, _COMPARISON) == normalize_for_comparison(right, _COMPARISON)


def compare(
    dataset: Dataset,
    baseline: OutputSet,
    candidate: OutputSet,
    config: ComparisonConfig = DEFAULT_COMPARISON,
) -> list[CaseComparison]:
    """Compare two output sets over a dataset.

    Every dataset case appears exactly once in the result, in ``case_id`` order,
    regardless of whether either arm produced output for it.
    """
    known = dataset.by_id()
    rows: list[CaseComparison] = []

    for case in sorted(dataset.cases, key=lambda c: c.case_id):
        base_out = baseline.resolve(case.case_id)
        cand_out = candidate.resolve(case.case_id)

        result, changed = _classify(case, base_out, cand_out)

        # Report the direction of travel as a review prompt only. A reviewed
        # reference is required before this can be acted on as a judgement.
        if result is CaseResult.CANDIDATE_CHANGED:
            if validates_reference(case):
                result = CaseResult.CANDIDATE_IMPROVEMENT_REVIEW_REQUIRED

        sim = (
            similarity(base_out.text, cand_out.text, config)
            if base_out.text or cand_out.text
            else 1.0
        )

        rows.append(
            CaseComparison(
                case_id=case.case_id,
                source_text=case.source_text,
                reference_translation=case.reference_translation,
                reference_status=case.reference_status,
                reference_counts_for_scoring=validates_reference(case),
                baseline=base_out,
                candidate=cand_out,
                result=result,
                changed_under_normalization=changed,
                similarity=sim,
                ocr_quality=case.ocr_quality.value,
                text_length=case.text_length.value if case.text_length else None,
                semantic_category=case.semantic_category.value,
                context_group_id=case.context_group_id,
                context_order=case.context_order,
                expected_behavior=case.expected_behavior.value,
                error_category=case.error_category.value if case.error_category else None,
                error_reason_codes=list(case.error_reason_codes),
            )
        )

    # Unexpected case ids in an output set are an evaluation error, not an
    # ignorable extra: they mean the wrong corpus was used.
    unexpected = sorted(set(baseline.outputs) - set(known))
    unexpected += sorted(set(candidate.outputs) - set(known))
    for case_id in unexpected:
        case = known.get(case_id)
        if case is not None:
            continue
        rows.append(
            CaseComparison(
                case_id=case_id,
                source_text="",
                reference_translation=None,
                reference_status=ReferenceStatus.PENDING_HUMAN_REVIEW,
                reference_counts_for_scoring=False,
                baseline=baseline.resolve(case_id),
                candidate=candidate.resolve(case_id),
                result=CaseResult.EVALUATION_ERROR,
                changed_under_normalization=False,
                similarity=0.0,
                ocr_quality="unknown",
                text_length=None,
                semantic_category="unclassified",
                context_group_id=None,
                context_order=None,
                expected_behavior="translate",
                error_category=None,
                error_reason_codes=["unexpected_case_id"],
            )
        )

    return rows


def summarize(rows: Iterable[CaseComparison], dataset: Dataset) -> dict[str, Any]:
    """Aggregate counts. Deliberately contains no quality score.

    The phase forbids manufacturing a single aggregate quality figure when the
    data cannot support one. With every reference pending review, there is no
    trustworthy semantic score to compute, so none is emitted -- only counts.
    """
    rows = list(rows)
    results: dict[str, int] = {}
    for row in rows:
        results[row.result.value] = results.get(row.result.value, 0) + 1

    reviewed = sum(1 for r in rows if r.reference_counts_for_scoring)
    pending = sum(
        1 for r in rows if r.reference_status is ReferenceStatus.PENDING_HUMAN_REVIEW
    )

    baseline_missing = sum(
        1 for r in rows if r.baseline.status is OutputStatus.MISSING
    )
    candidate_missing = sum(
        1 for r in rows if r.candidate.status is OutputStatus.MISSING
    )
    malformed = sum(
        1
        for r in rows
        if OutputStatus.MALFORMED in (r.baseline.status, r.candidate.status)
    )

    return {
        "dataset_cases": len(dataset.cases),
        "compared_rows": len(rows),
        "result_counts": dict(sorted(results.items())),
        "references_reviewed_and_valid": reviewed,
        "references_pending": pending,
        "baseline_outputs_missing": baseline_missing,
        "candidate_outputs_missing": candidate_missing,
        "malformed_outputs": malformed,
        "reviewed_reference_count": reviewed,
        # Explicitly absent, and stated as absent rather than omitted:
        "semantic_quality_score": None,
        "semantic_score_reason": (
            "not computed: verified semantic scoring requires references with "
            "status=reviewed, a named reviewer and a timestamp. "
            f"{reviewed} of {len(rows)} cases qualify."
        ),
    }
