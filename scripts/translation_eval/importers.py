"""Import the RC4 Phase 3 corpus into the versioned evaluation schema.

The source file is ``docs/benchmark-phase3/dataset.tsv``: 40 isolated bubble
texts with an independent Arabic editorial reference, nine scene groups.

What this importer is allowed to do
-----------------------------------
* Read every record and keep ``original_en`` and ``independent_ar_reference``
  byte-for-byte.
* Record provenance pointing at the exact source row.
* Assign classifications that the source actually states.

What it is forbidden to do, by construction
-------------------------------------------
* Invent, generate, paraphrase or "improve" a reference. References are copied,
  never produced.
* Mark anything reviewed. ``REPORT.md`` states plainly that *"no such review has
  yet occurred"*, so every imported case is ``pending_human_review``.
* Guess classifications that the source does not state. ``text_length`` is left
  ``None`` and ``semantic_category`` stays ``unclassified`` for records whose
  category column is not one of the schema's semantic values; the original
  ``category`` string is preserved in ``dialogue_context``/``notes`` so no
  information is lost.

The scene ``category`` column mixes two different things in the source -- a
semantic challenge (``idiom``, ``sarcasm``, ``pronoun``, ``condition``) and a
neutral scene descriptor (``everyday``, ``military``, ``magic``). Mapping the
former is a statement about what the source says; mapping the latter would be a
guess. Only the explicit semantic labels are mapped.
"""

from __future__ import annotations

import csv
from pathlib import Path

from .schema import (
    ContextDependency,
    Dataset,
    EvalCase,
    OcrQuality,
    ReferenceStatus,
    ReviewRecord,
    SCHEMA_VERSION,
    RC4_SOURCE_DATASET,
    SemanticCategory,
    SourceType,
)

#: Semantic categories the source states explicitly. Everything absent from this
#: map stays ``unclassified`` rather than being guessed.
_STATED_CATEGORY: dict[str, SemanticCategory] = {
    "idiom": SemanticCategory.IDIOM,
    "sarcasm": SemanticCategory.SARCASM,
    "humor": SemanticCategory.EMOTIONAL,
    "pronoun": SemanticCategory.PRONOUN,
    "condition": SemanticCategory.COMPLEX_GRAMMAR,
    "interrupted": SemanticCategory.INCOMPLETE,
    "thought": SemanticCategory.IMPLICIT,
    "fear": SemanticCategory.EMOTIONAL,
    "anger": SemanticCategory.EMOTIONAL,
    "sadness": SemanticCategory.EMOTIONAL,
    "relief": SemanticCategory.EMOTIONAL,
    "embarrassment": SemanticCategory.EMOTIONAL,
    "desperation": SemanticCategory.EMOTIONAL,
    "excitement": SemanticCategory.EMOTIONAL,
    "disbelief": SemanticCategory.EMOTIONAL,
    "argument": SemanticCategory.DIALOGUE_DEPENDENT,
    "addressee": SemanticCategory.PRONOUN,
    "suspicion": SemanticCategory.IMPLICIT,
}

#: Source rows whose English was transcribed from an image rather than authored.
#: REPORT.md: "39-40 are visually transcribed from the repository's
#: report25-question.png and report25-watching.png comic crops".
_TRANSCRIBED_IDS = frozenset({"39", "40"})

#: 31/32 are the two-bubble internal thought; REPORT.md describes them as one
#: internal thought split across adjacent bubbles, evaluated jointly.
_CONTEXT_GROUPS: dict[str, tuple[str, int, ContextDependency]] = {
    "31": ("g31-thought", 1, ContextDependency.PREVIOUS_UTTERANCE),
    "32": ("g31-thought", 2, ContextDependency.PREVIOUS_UTTERANCE),
}


def _require_columns(header: list[str], path: Path) -> None:
    expected = {
        "id",
        "scene",
        "category",
        "original_en",
        "context_for_reviewer_only",
        "independent_ar_reference",
    }
    missing = expected - set(header)
    if missing:
        raise ValueError(f"{path}: missing required columns {sorted(missing)}")


def import_rc4_corpus(path: Path) -> Dataset:
    """Read the RC4 TSV and return a :class:`Dataset` with all 40 cases.

    Raises if the file shape is unexpected rather than silently importing a
    partial corpus.
    """
    if not path.is_file():
        raise FileNotFoundError(f"RC4 corpus not found: {path}")

    cases: list[EvalCase] = []
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError(f"{path}: no header row")
        _require_columns(list(reader.fieldnames), path)

        for row in reader:
            raw_id = (row.get("id") or "").strip()
            if not raw_id:
                continue
            source = row.get("original_en") or ""
            reference = row.get("independent_ar_reference") or ""
            category = (row.get("category") or "").strip()
            scene = (row.get("scene") or "").strip()
            reviewer_context = row.get("context_for_reviewer_only") or ""

            group_id, order, dependency = _CONTEXT_GROUPS.get(
                raw_id, (None, None, ContextDependency.ISOLATED)
            )
            transcribed = raw_id in _TRANSCRIBED_IDS

            cases.append(
                EvalCase(
                    case_id=f"rc4-{raw_id}",
                    # Copied verbatim: no strip, no normalisation, no reflow.
                    source_text=source,
                    reference_translation=reference if reference.strip() else None,
                    # REPORT.md: no independent bilingual review has occurred.
                    reference_status=ReferenceStatus.PENDING_HUMAN_REVIEW,
                    review=ReviewRecord(),
                    source_type=(
                        SourceType.OCR_TRANSCRIBED if transcribed else SourceType.CLEAN_TYPED
                    ),
                    ocr_quality=OcrQuality.CLEAN if not transcribed else OcrQuality.UNKNOWN,
                    ocr_corruption_types=(),
                    # Left undetermined on purpose: the source does not state a
                    # length stratum, and deriving one would be a guess.
                    text_length=None,
                    dialogue_context=scene or None,
                    context_group_id=group_id,
                    context_order=order,
                    context_dependency=dependency,
                    # The shipped engine receives one bubble and no neighbours.
                    context_available_to_engine=False,
                    context_for_reviewer_only=reviewer_context or None,
                    semantic_category=_STATED_CATEGORY.get(category, SemanticCategory.UNCLASSIFIED),
                    difficulty_tags=tuple(t for t in [category, scene] if t),
                    notes=(
                        f"imported from {RC4_SOURCE_DATASET}; "
                        f"source category={category or 'none'}; scene={scene or 'none'}"
                    ),
                    dataset_version=SCHEMA_VERSION,
                    source_provenance=f"{RC4_SOURCE_DATASET} row id={raw_id}",
                    upstream_id=raw_id,
                )
            )

    if not cases:
        raise ValueError(f"{path}: imported zero cases")

    dataset = Dataset(
        schema_version=SCHEMA_VERSION,
        dataset_version=SCHEMA_VERSION,
        cases=cases,
    )
    dataset.fingerprint = dataset.compute_fingerprint()
    return dataset
