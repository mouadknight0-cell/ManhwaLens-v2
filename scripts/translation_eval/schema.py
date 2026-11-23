"""Corpus schema for the ManhwaLens translation evaluation infrastructure.

This module defines the *contract* only. It deliberately knows nothing about
translation quality: it describes what a case is, what may be recorded about it,
and which combinations are contradictory.

Two rules drive the whole design:

1. Absence of evidence is recorded as absence. A reference that nobody has
   checked is ``pending_human_review``; it is never upgraded by this code, by a
   validator, or by a scorer. There is no code path in this package that writes
   ``reviewed`` without a reviewer and a timestamp.
2. Reference content is immutable. Loading a dataset never rewrites a
   reference string. The 40 RC4 references are reproduced byte-for-byte.

Schema versioning is explicit so that a corpus written by an older version is
rejected rather than silently reinterpreted under newer rules.
"""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Iterable

SCHEMA_VERSION = "1.0.0"

#: Version recorded on cases imported from the RC4 Phase 3 TSV. These came from
#: ``docs/benchmark-phase3/dataset.tsv`` and were never re-reviewed, so the
#: import keeps them on their original status.
RC4_SOURCE_DATASET = "docs/benchmark-phase3/dataset.tsv"


class ReferenceStatus(str, Enum):
    """Review lifecycle for a reference translation.

    Only ``REVIEWED`` may contribute to verified quality scoring. The other
    three exist so that disagreement and rejection are recorded rather than
    deleted, which is what makes a reference review auditable.
    """

    PENDING_HUMAN_REVIEW = "pending_human_review"
    REVIEWED = "reviewed"
    REJECTED = "rejected"
    DISPUTED = "disputed"


class SourceLanguage(str, Enum):
    EN = "en"


class TargetLanguage(str, Enum):
    AR = "ar"


class SourceType(str, Enum):
    """Where the English came from.

    ``clean_typed`` is authored text, not OCR output. ``ocr_transcribed`` was
    read from an image. The two must not be pooled: an error in one is a
    translation defect, an error in the other may be an OCR defect.
    """

    CLEAN_TYPED = "clean_typed"
    OCR_TRANSCRIBED = "ocr_transcribed"


class OcrQuality(str, Enum):
    """Primary OCR stratum. Exactly one per case.

    Per the phase requirements, multiple corruption *labels* are allowed per
    case, but the overall quality is a single ordered stratum so that grouping
    is unambiguous.
    """

    CLEAN = "clean_ocr"
    MINOR = "minor_corruption"
    MODERATE = "moderate_corruption"
    SEVERE = "severe_corruption"
    TRUNCATED = "truncated_text"
    MISSING_LINES = "missing_lines"
    MERGED_LINES = "merged_lines"
    FRAGMENTED = "fragmented_text"
    UNKNOWN = "unknown"

    @property
    def is_clean(self) -> bool:
        return self is OcrQuality.CLEAN


class CorruptionType(str, Enum):
    """Individual corruption labels. May be several per case."""

    LOST_WORDS = "lost_words"
    DUPLICATE_WORDS = "duplicate_words"
    SUBSTITUTION = "substitution"
    OMISSION = "omission"
    FRAGMENT = "fragment"
    LINE_SPLIT_ERROR = "incorrect_line_splitting"
    LINE_JOIN_ERROR = "incorrect_line_joining"
    PARTIAL_CAPTURE = "partial_capture"
    MISSING_LINE = "missing_line"
    MERGED_LINE = "merged_line"
    UNKNOWN = "unknown"


class TextLength(str, Enum):
    SHORT_PHRASE = "short_phrase"
    SINGLE_SENTENCE = "single_sentence"
    MEDIUM = "medium_sentence"
    LONG = "long_sentence"
    MULTILINE = "multi_line"
    EXCEEDS_COVERAGE = "exceeds_effective_coverage"


class SemanticCategory(str, Enum):
    """The dominant linguistic challenge in a case. One per case."""

    PLAIN = "plain"
    IDIOM = "idiom"
    SLANG = "slang"
    FIGURATIVE = "figurative"
    SARCASM = "sarcasm"
    EMOTIONAL = "emotional"
    IMPLICIT = "implicit_meaning"
    AMBIGUOUS = "ambiguous"
    ELLIPTICAL = "elliptical"
    INCOMPLETE = "incomplete_sentence"
    COMPLEX_GRAMMAR = "complex_grammar"
    UNUSUAL_ORDER = "unusual_word_order"
    NAME_OR_TITLE = "name_or_title"
    PRONOUN = "pronoun_unclear_referent"
    NEGATION = "negation"
    EMPHASIS = "emphasis"
    DIALOGUE_DEPENDENT = "dialogue_dependent"
    UNCLASSIFIED = "unclassified"


class ContextDependency(str, Enum):
    """How much surrounding dialogue a correct reading needs.

    Kept separate from ``context_available_to_engine`` on purpose: a case may
    *need* the previous line while the engine *cannot* see it. Reporting only
    the former would overstate what the engine was given.
    """

    ISOLATED = "isolated_bubble"
    PREVIOUS_UTTERANCE = "depends_on_previous"
    MULTIPLE_PREVIOUS = "depends_on_multiple_previous"
    FOLLOWING_RESPONSE = "depends_on_following"
    MULTI_BUBBLE_SEQUENCE = "multi_bubble_sequence"
    CHANGES_WITH_CONTEXT = "changes_with_context"


class ExpectedBehavior(str, Enum):
    """What the pipeline is *supposed* to do with this case.

    This is not a quality judgment; it records intent so that a returned
    translation and a deliberate refusal are not confused.
    """

    TRANSLATE = "translate"
    PRESERVE_SOURCE = "preserve_source_unchanged"
    REFUSE_UNCERTAIN = "refuse_when_uncertain"
    PRESERVE_NUMERIC = "preserve_numeric_content"
    LOCAL_LEXICON = "local_lexicon_route"


class ErrorCategory(str, Enum):
    """Error taxonomy. Human-assigned; never inferred from string difference."""

    SEMANTIC_ERROR = "semantic_error"
    MISTRANSLATION = "mistranslation"
    IDIOM_MISTRANSLATION = "idiom_mistranslation"
    CONTEXT_ERROR = "context_error"
    PRONOUN_REFERENCE_ERROR = "pronoun_reference_error"
    NAME_TRANSLATION_ERROR = "name_translation_error"
    GRAMMAR_ERROR = "grammar_error"
    FLUENCY_ERROR = "fluency_error"
    TONE_ERROR = "tone_error"
    OMISSION = "omission"
    ADDITION = "addition"
    TRUNCATION = "truncation"
    OCR_ERROR = "ocr_error"
    LINE_BOUNDARY_ERROR = "line_boundary_error"
    COVERAGE_FAILURE = "coverage_failure"
    FORMATTING_ERROR = "formatting_error"
    UNCLASSIFIED = "unclassified"
    PENDING_HUMAN_REVIEW = "pending_human_review"


class CaseResult(str, Enum):
    """Per-case comparison outcome.

    None of these asserts that meaning improved or degraded. ``*_review_required``
    means only "the strings differ and a human must look", which is the strongest
    statement string comparison can support.
    """

    UNCHANGED = "unchanged"
    CANDIDATE_CHANGED = "candidate_changed"
    CANDIDATE_MISSING = "candidate_missing"
    BASELINE_MISSING = "baseline_missing"
    BOTH_MISSING = "both_missing"
    CANDIDATE_REGRESSION_REVIEW_REQUIRED = "candidate_regression_review_required"
    CANDIDATE_IMPROVEMENT_REVIEW_REQUIRED = "candidate_improvement_review_required"
    UNSCORABLE_REFERENCE = "unscorable_reference"
    EVALUATION_ERROR = "evaluation_error"


class OutputStatus(str, Enum):
    """State of one engine output for one case.

    ``MISSING`` is distinct from ``EMPTY``: the first means no record was
    produced, the second means a record exists and carries no text. Conflating
    them would hide a pipeline that returned nothing.
    """

    OK = "ok"
    EMPTY = "empty"
    FAILED = "failed"
    MISSING = "missing"
    MALFORMED = "malformed"


#: Statuses that count as a usable translation output for comparison purposes.
USABLE_OUTPUT = frozenset({OutputStatus.OK})


@dataclass(frozen=True)
class ReviewRecord:
    """Who checked a reference, when, and what they were looking at.

    A ``reviewed`` reference without a reviewer and a timestamp is treated as
    invalid by :func:`validate_case`, which is what prevents an unreviewed
    reference silently becoming ground truth.
    """

    reviewer: str | None = None
    timestamp: str | None = None
    notes: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {"reviewer": self.reviewer, "timestamp": self.timestamp, "notes": self.notes}

    @staticmethod
    def from_dict(raw: dict[str, Any] | None) -> "ReviewRecord":
        raw = raw or {}
        return ReviewRecord(
            reviewer=raw.get("reviewer"),
            timestamp=raw.get("timestamp"),
            notes=raw.get("notes"),
        )


@dataclass
class EvalCase:
    """One isolated bubble text and everything recorded about it.

    Fields fall into three groups:

    * identity and content -- ``case_id``, ``source_text``, ``reference_translation``
    * classification -- length, semantic category, OCR stratum, context
    * provenance and review -- ``reference_status``, ``source_provenance``

    Every classification field defaults to an explicit "unknown" rather than a
    guess, because a guessed classification presented as fact is worse than a
    missing one.
    """

    case_id: str
    source_text: str
    reference_translation: str | None = None
    reference_status: ReferenceStatus = ReferenceStatus.PENDING_HUMAN_REVIEW
    review: ReviewRecord = field(default_factory=ReviewRecord)

    source_language: SourceLanguage = SourceLanguage.EN
    target_language: TargetLanguage = TargetLanguage.AR
    source_type: SourceType = SourceType.CLEAN_TYPED

    ocr_quality: OcrQuality = OcrQuality.UNKNOWN
    ocr_corruption_types: tuple[CorruptionType, ...] = ()
    text_length: TextLength | None = None

    dialogue_context: str | None = None
    context_group_id: str | None = None
    context_order: int | None = None
    context_dependency: ContextDependency = ContextDependency.ISOLATED
    #: True only when the harness actually supplies neighbouring text to the
    #: engine. Defaults to False because the shipped engine is bubble-only.
    context_available_to_engine: bool = False
    #: Context shown to a human reviewer but never to the engine.
    context_for_reviewer_only: str | None = None

    semantic_category: SemanticCategory = SemanticCategory.UNCLASSIFIED
    difficulty_tags: tuple[str, ...] = ()
    expected_behavior: ExpectedBehavior = ExpectedBehavior.TRANSLATE

    error_category: ErrorCategory | None = None
    error_reason_codes: tuple[str, ...] = ()

    notes: str | None = None
    dataset_version: str = SCHEMA_VERSION
    source_provenance: str | None = None
    #: Stable identifiers of upstream records, so a case can be traced back to
    #: the exact row it was imported from.
    upstream_id: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "case_id": self.case_id,
            "source_text": self.source_text,
            "reference_translation": self.reference_translation,
            "reference_status": self.reference_status.value,
            "review": self.review.as_dict(),
            "source_language": self.source_language.value,
            "target_language": self.target_language.value,
            "source_type": self.source_type.value,
            "ocr_quality": self.ocr_quality.value,
            "ocr_corruption_types": [c.value for c in self.ocr_corruption_types],
            "text_length": self.text_length.value if self.text_length else None,
            "dialogue_context": self.dialogue_context,
            "context_group_id": self.context_group_id,
            "context_order": self.context_order,
            "context_dependency": self.context_dependency.value,
            "context_available_to_engine": self.context_available_to_engine,
            "context_for_reviewer_only": self.context_for_reviewer_only,
            "semantic_category": self.semantic_category.value,
            "difficulty_tags": list(self.difficulty_tags),
            "expected_behavior": self.expected_behavior.value,
            "error_category": self.error_category.value if self.error_category else None,
            "error_reason_codes": list(self.error_reason_codes),
            "notes": self.notes,
            "dataset_version": self.dataset_version,
            "source_provenance": self.source_provenance,
            "upstream_id": self.upstream_id,
        }

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "EvalCase":
        """Build a case from stored data, rejecting unknown enum values.

        Unknown values raise rather than being coerced to a default: a corpus
        that cannot be interpreted must not be evaluated.
        """
        if not isinstance(raw, dict):
            raise ValueError("case must be an object")
        status = ReferenceStatus(raw.get("reference_status", ReferenceStatus.PENDING_HUMAN_REVIEW.value))
        return EvalCase(
            case_id=str(raw["case_id"]),
            source_text=str(raw.get("source_text", "")),
            reference_translation=raw.get("reference_translation"),
            reference_status=status,
            review=ReviewRecord.from_dict(raw.get("review")),
            source_language=SourceLanguage(raw.get("source_language", "en")),
            target_language=TargetLanguage(raw.get("target_language", "ar")),
            source_type=SourceType(raw.get("source_type", SourceType.CLEAN_TYPED.value)),
            ocr_quality=OcrQuality(raw.get("ocr_quality", OcrQuality.UNKNOWN.value)),
            ocr_corruption_types=tuple(
                CorruptionType(v) for v in raw.get("ocr_corruption_types", [])
            ),
            text_length=TextLength(raw["text_length"]) if raw.get("text_length") else None,
            dialogue_context=raw.get("dialogue_context"),
            context_group_id=raw.get("context_group_id"),
            context_order=raw.get("context_order"),
            context_dependency=ContextDependency(
                raw.get("context_dependency", ContextDependency.ISOLATED.value)
            ),
            context_available_to_engine=bool(raw.get("context_available_to_engine", False)),
            context_for_reviewer_only=raw.get("context_for_reviewer_only"),
            semantic_category=SemanticCategory(
                raw.get("semantic_category", SemanticCategory.UNCLASSIFIED.value)
            ),
            difficulty_tags=tuple(raw.get("difficulty_tags", [])),
            expected_behavior=ExpectedBehavior(
                raw.get("expected_behavior", ExpectedBehavior.TRANSLATE.value)
            ),
            error_category=ErrorCategory(raw["error_category"]) if raw.get("error_category") else None,
            error_reason_codes=tuple(raw.get("error_reason_codes", [])),
            notes=raw.get("notes"),
            dataset_version=str(raw.get("dataset_version", SCHEMA_VERSION)),
            source_provenance=raw.get("source_provenance"),
            upstream_id=raw.get("upstream_id"),
        )


@dataclass
class Dataset:
    """A loaded corpus plus the identity needed to reproduce a run."""

    schema_version: str
    dataset_version: str
    cases: list[EvalCase]
    #: SHA-256 over the canonical JSON of all cases. Two datasets with the same
    #: fingerprint have identical content, which is what makes a comparison
    #: reproducible.
    fingerprint: str = ""

    def compute_fingerprint(self) -> str:
        payload = json.dumps(
            [c.as_dict() for c in sorted(self.cases, key=lambda c: c.case_id)],
            sort_keys=True,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def by_id(self) -> dict[str, EvalCase]:
        return {c.case_id: c for c in self.cases}

    def as_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "dataset_version": self.dataset_version,
            "fingerprint": self.fingerprint or self.compute_fingerprint(),
            "cases": [c.as_dict() for c in self.cases],
        }


class Issue_:
    """A single validation finding.

    ``severity`` is either ``error`` (the dataset cannot be evaluated) or
    ``warning`` (something is incomplete but evaluation is still meaningful --
    typically an unpublished classification, which is allowed and recorded).
    """

    ERROR = "error"
    WARNING = "warning"

    __slots__ = ("code", "message", "case_id", "severity")

    def __init__(self, code: str, message: str, case_id: str | None = None, severity: str = ERROR):
        self.code = code
        self.message = message
        self.case_id = case_id
        self.severity = severity

    def as_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "case_id": self.case_id,
            "severity": self.severity,
        }

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"Issue({self.severity}, {self.code}, {self.case_id}, {self.message})"


class CorpusMatrix:
    """Coverage of the five required evaluation dimensions.

    Each cell is a (dimension, value) pair that is either represented in the
    corpus or a gap. Reporting gaps as first-class output is the point: a corpus
    that silently omits a dimension looks complete.
    """

    __slots__ = ("rows",)

    def __init__(self, rows: list[dict[str, Any]]):
        self.rows = rows

    @staticmethod
    def build(dataset: "Dataset") -> "CorpusMatrix":
        rows: list[dict[str, Any]] = []

        def add(dimension: str, value: str, present: bool, count: int) -> None:
            rows.append(
                {
                    "dimension": dimension,
                    "value": value,
                    "present": present,
                    "count": count,
                }
            )

        lengths = Counter(
            c.text_length.value if c.text_length else "unclassified" for c in dataset.cases
        )
        for length in TextLength:
            add("text_length", length.value, lengths.get(length.value, 0) > 0, lengths.get(length.value, 0))

        semantics = Counter(c.semantic_category.value for c in dataset.cases)
        for category in SemanticCategory:
            if category is SemanticCategory.UNCLASSIFIED:
                continue
            add(
                "semantic_category",
                category.value,
                semantics.get(category.value, 0) > 0,
                semantics.get(category.value, 0),
            )

        contexts = Counter(c.context_dependency.value for c in dataset.cases)
        for dependency in ContextDependency:
            add(
                "context_dependency",
                dependency.value,
                contexts.get(dependency.value, 0) > 0,
                contexts.get(dependency.value, 0),
            )

        ocr = Counter(c.ocr_quality.value for c in dataset.cases)
        for quality in OcrQuality:
            add("ocr_quality", quality.value, ocr.get(quality.value, 0) > 0, ocr.get(quality.value, 0))

        corruptions = Counter()
        for case in dataset.cases:
            for corruption in case.ocr_corruption_types:
                corruptions[corruption.value] += 1
        for corruption in CorruptionType:
            add(
                "corruption_type",
                corruption.value,
                corruptions.get(corruption.value, 0) > 0,
                corruptions.get(corruption.value, 0),
            )

        return CorpusMatrix(rows)

    @property
    def gaps(self) -> list[dict[str, Any]]:
        return [r for r in self.rows if not r["present"]]

    def as_dict(self) -> dict[str, Any]:
        return {"rows": self.rows, "gaps": self.gaps, "gap_count": len(self.gaps)}



def validates_reference(case: EvalCase) -> bool:
    """True only when this case may contribute to verified quality scoring.

    Requires a reviewed status, a non-empty reference, a named reviewer and a
    timestamp. Anything less is excluded from verified metrics and reported
    separately.
    """
    return (
        case.reference_status is ReferenceStatus.REVIEWED
        and bool((case.reference_translation or "").strip())
        and bool((case.review.reviewer or "").strip())
        and bool((case.review.timestamp or "").strip())
    )


def validate_case(case: EvalCase) -> list[Issue_]:
    """Check one case against the schema contract."""
    issues: list[Issue_] = []
    cid = case.case_id

    if not cid.strip():
        issues.append(Issue_("empty_case_id", "case_id must not be empty", cid))
    if not case.source_text.strip():
        issues.append(Issue_("empty_source", "source_text must not be empty", cid))
    if case.dataset_version != SCHEMA_VERSION:
        issues.append(
            Issue_(
                "schema_version_mismatch",
                f"dataset_version {case.dataset_version!r} is not {SCHEMA_VERSION!r}",
                cid,
            )
        )

    # Review integrity: an upgraded status with no evidence is rejected outright.
    if case.reference_status is ReferenceStatus.REVIEWED:
        if not (case.review.reviewer or "").strip():
            issues.append(Issue_("review_without_reviewer", "reviewed status requires a reviewer", cid))
        if not (case.review.timestamp or "").strip():
            issues.append(Issue_("review_without_timestamp", "reviewed status requires a timestamp", cid))
        if not (case.reference_translation or "").strip():
            issues.append(Issue_("review_without_reference", "reviewed status requires a reference", cid))

    # A rejection or dispute is a recorded finding and keeps its reference.
    if case.reference_status in (ReferenceStatus.REJECTED, ReferenceStatus.DISPUTED):
        if not (case.reference_translation or "").strip():
            issues.append(
                Issue_(
                    "dispute_without_reference",
                    "rejected/disputed reference must be retained, not blanked",
                    cid,
                )
            )
        if not (case.review.notes or "").strip():
            issues.append(
                Issue_("dispute_without_reason", "rejected/disputed reference requires notes", cid)
            )

    if case.reference_status is ReferenceStatus.PENDING_HUMAN_REVIEW and (case.review.reviewer or "").strip():
        # Recording a reviewer on a pending case is a contradiction: it implies
        # the review happened. Force the author to state which is true.
        issues.append(
            Issue_(
                "pending_with_reviewer",
                "a pending reference must not carry a reviewer",
                cid,
            )
        )

    # Undetermined classifications are allowed but flagged, so a report can say
    # how much of the matrix is actually classified rather than assumed.
    if case.text_length is None:
        issues.append(Issue_("unclassified_length", "text_length not yet determined", cid, Issue_.WARNING))
    if case.semantic_category is SemanticCategory.UNCLASSIFIED:
        issues.append(
            Issue_("unclassified_semantic", "semantic_category not yet determined", cid, Issue_.WARNING)
        )

    # Context coherence.
    if case.context_group_id is not None and case.context_order is None:
        issues.append(Issue_("context_order_missing", "context group requires context_order", cid))
    if case.context_order is not None and case.context_group_id is None:
        issues.append(Issue_("context_group_missing", "context_order requires context_group_id", cid))
    if case.context_dependency is not ContextDependency.ISOLATED and case.context_group_id is None:
        issues.append(
            Issue_(
                "context_dependency_without_group",
                "a context-dependent case must belong to a context group",
                cid,
            )
        )

    # Engine context must not be claimed without evidence.
    if case.context_available_to_engine and case.context_dependency is ContextDependency.ISOLATED:
        issues.append(
            Issue_(
                "context_claim_without_dependency",
                "isolated cases must not claim engine-visible context",
                cid,
                Issue_.WARNING,
            )
        )

    # OCR coherence: a clean case cannot carry corruption labels.
    if case.ocr_quality.is_clean and case.ocr_corruption_types:
        issues.append(
            Issue_(
                "clean_ocr_with_corruption",
                "clean_ocr must not carry corruption labels",
                cid,
            )
        )
    if case.ocr_quality is not OcrQuality.CLEAN and case.ocr_quality is not OcrQuality.UNKNOWN:
        if not case.ocr_corruption_types:
            issues.append(
                Issue_(
                    "corruption_without_label",
                    "a degraded OCR stratum requires at least one corruption label",
                    cid,
                )
            )

    # Provenance.
    if not (case.source_provenance or "").strip():
        issues.append(Issue_("missing_provenance", "source_provenance is required", cid))
    if case.source_type is SourceType.OCR_TRANSCRIBED and case.ocr_quality is OcrQuality.UNKNOWN:
        issues.append(
            Issue_(
                "ocr_transcribed_unknown_quality",
                "transcribed cases should record an OCR stratum",
                cid,
                Issue_.WARNING,
            )
        )

    return issues


def validate_dataset(dataset: Dataset) -> list[Issue_]:
    """Check the whole dataset: per-case rules plus cross-case integrity."""
    issues: list[Issue_] = []
    seen: dict[str, int] = {}
    for case in dataset.cases:
        issues.extend(validate_case(case))
        seen[case.case_id] = seen.get(case.case_id, 0) + 1

    for cid, count in sorted(seen.items()):
        if count > 1:
            issues.append(Issue_("duplicate_case_id", f"case_id appears {count} times", cid))

    # Context groups must have unique, contiguous ordering or the sequence
    # cannot be reconstructed by a reviewer.
    groups: dict[str, list[EvalCase]] = {}
    for case in dataset.cases:
        if case.context_group_id:
            groups.setdefault(case.context_group_id, []).append(case)
    for gid, members in sorted(groups.items()):
        orders = [c.context_order for c in members]
        if any(o is None for o in orders):
            issues.append(Issue_("context_group_unordered", "group has members without order", gid))
            continue
        if len(set(orders)) != len(orders):
            issues.append(Issue_("context_order_duplicate", "duplicate context_order in group", gid))
        if sorted(orders) != list(range(min(orders), min(orders) + len(orders))):
            issues.append(Issue_("context_order_gap", "context_order must be contiguous from its first value", gid))
        if len(members) < 2:
            issues.append(Issue_("context_group_singleton", "a context group should hold 2+ cases", gid, Issue_.WARNING))

    if dataset.schema_version != SCHEMA_VERSION:
        issues.append(
            Issue_(
                "dataset_schema_mismatch",
                f"dataset schema {dataset.schema_version!r} is not {SCHEMA_VERSION!r}",
            )
        )
    if not dataset.cases:
        issues.append(Issue_("empty_dataset", "dataset contains no cases"))
    return issues


def errors(issues: Iterable[Issue_]) -> list[Issue_]:
    return [i for i in issues if i.severity == Issue_.ERROR]


def warnings(issues: Iterable[Issue_]) -> list[Issue_]:
    return [i for i in issues if i.severity == Issue_.WARNING]
