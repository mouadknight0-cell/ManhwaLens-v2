"""Corpus schema, import fidelity and review-status enforcement.

The tests that matter most here are the *negative* ones: that an unreviewed
reference cannot become scored, that imported Arabic is byte-identical to the RC4
source, and that a bad classification is rejected rather than coerced.
"""

from __future__ import annotations

import csv
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from translation_eval import importers, schema  # noqa: E402

TSV = ROOT / "docs/benchmark-phase3/dataset.tsv"


def _issue_codes(issues, severity=None):
    return {
        i.code
        for i in issues
        if severity is None or i.severity == severity
    }


class Rc4ImportTest(unittest.TestCase):
    def setUp(self):
        self.dataset = importers.import_rc4_corpus(TSV)

    def test_all_forty_records_imported(self):
        self.assertEqual(40, len(self.dataset.cases))
        self.assertEqual(40, len({c.case_id for c in self.dataset.cases}))

    def test_source_and_reference_are_byte_identical_to_the_rc4_tsv(self):
        """The corpus must reproduce RC4's data exactly; nothing may be rewritten."""
        with TSV.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        by_upstream = {c.upstream_id: c for c in self.dataset.cases}
        self.assertEqual(40, len(by_upstream))
        for row in rows:
            case = by_upstream[row["id"]]
            self.assertEqual(row["original_en"], case.source_text, f"source drift in {row['id']}")
            self.assertEqual(
                row["independent_ar_reference"],
                case.reference_translation,
                f"reference drift in {row['id']}",
            )

    def test_nothing_is_marked_reviewed(self):
        """REPORT.md states no independent bilingual review has occurred."""
        statuses = {c.reference_status for c in self.dataset.cases}
        self.assertEqual({schema.ReferenceStatus.PENDING_HUMAN_REVIEW}, statuses)
        self.assertEqual(0, sum(1 for c in self.dataset.cases if schema.validates_reference(c)))

    def test_import_assigns_no_reviewer(self):
        for case in self.dataset.cases:
            self.assertIsNone(case.review.reviewer)
            self.assertIsNone(case.review.timestamp)

    def test_unstated_classifications_are_not_guessed(self):
        # text_length is never stated by the source, so it must stay undetermined.
        self.assertTrue(all(c.text_length is None for c in self.dataset.cases))

    def test_stated_semantic_categories_are_mapped(self):
        by_id = {c.upstream_id: c for c in self.dataset.cases}
        self.assertEqual(schema.SemanticCategory.IDIOM, by_id["06"].semantic_category)
        self.assertEqual(schema.SemanticCategory.SARCASM, by_id["19"].semantic_category)
        self.assertEqual(schema.SemanticCategory.INCOMPLETE, by_id["34"].semantic_category)

    def test_unknown_category_is_not_invented(self):
        by_id = {c.upstream_id: c for c in self.dataset.cases}
        # "everyday" is a scene descriptor, not a semantic challenge.
        self.assertEqual(schema.SemanticCategory.UNCLASSIFIED, by_id["01"].semantic_category)

    def test_image_transcribed_cases_are_labelled(self):
        by_id = {c.upstream_id: c for c in self.dataset.cases}
        self.assertEqual(schema.SourceType.OCR_TRANSCRIBED, by_id["39"].source_type)
        self.assertEqual(schema.SourceType.CLEAN_TYPED, by_id["01"].source_type)

    def test_context_group_preserves_pair_order(self):
        by_id = {c.upstream_id: c for c in self.dataset.cases}
        self.assertEqual(by_id["31"].context_group_id, by_id["32"].context_group_id)
        self.assertIsNotNone(by_id["31"].context_group_id)
        self.assertLess(by_id["31"].context_order, by_id["32"].context_order)

    def test_no_case_claims_engine_visible_context(self):
        """The shipped engine receives one bubble; claiming otherwise would lie."""
        self.assertTrue(all(c.context_available_to_engine is False for c in self.dataset.cases))

    def test_every_case_has_provenance(self):
        for case in self.dataset.cases:
            self.assertIn("dataset.tsv", case.source_provenance)
            self.assertIsNotNone(case.upstream_id)

    def test_import_is_deterministic(self):
        again = importers.import_rc4_corpus(TSV)
        self.assertEqual(self.dataset.compute_fingerprint(), again.compute_fingerprint())

    def test_missing_file_raises(self):
        with self.assertRaises(FileNotFoundError):
            importers.import_rc4_corpus(ROOT / "docs/does-not-exist.tsv")


class SchemaValidationTest(unittest.TestCase):
    def case(self, **kwargs):
        base = dict(
            case_id="t1",
            source_text="Hello there.",
            reference_translation="مرحبا.",
            source_provenance="test",
            dataset_version=schema.SCHEMA_VERSION,
            text_length=schema.TextLength.SINGLE_SENTENCE,
            semantic_category=schema.SemanticCategory.PLAIN,
        )
        base.update(kwargs)
        return schema.EvalCase(**base)

    def test_minimal_valid_case_has_no_errors(self):
        issues = schema.validate_case(self.case())
        self.assertEqual([], schema.errors(issues))

    def test_reviewed_without_reviewer_is_rejected(self):
        case = self.case(reference_status=schema.ReferenceStatus.REVIEWED)
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("review_without_reviewer", codes)

    def test_reviewed_without_timestamp_is_rejected(self):
        case = self.case(
            reference_status=schema.ReferenceStatus.REVIEWED,
            review=schema.ReviewRecord(reviewer="r", timestamp=None),
        )
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("review_without_timestamp", codes)

    def test_reviewed_without_reference_is_rejected(self):
        case = self.case(
            reference_status=schema.ReferenceStatus.REVIEWED,
            reference_translation="",
            review=schema.ReviewRecord(reviewer="r", timestamp="2026-01-01"),
        )
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("review_without_reference", codes)

    def test_fully_recorded_review_is_accepted_and_counts(self):
        case = self.case(
            reference_status=schema.ReferenceStatus.REVIEWED,
            review=schema.ReviewRecord(reviewer="reviewer-a", timestamp="2026-01-01T00:00:00Z"),
        )
        self.assertEqual([], schema.errors(schema.validate_case(case)))
        self.assertTrue(schema.validates_reference(case))

    def test_pending_with_reviewer_is_a_contradiction(self):
        case = self.case(review=schema.ReviewRecord(reviewer="ghost"))
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("pending_with_reviewer", codes)

    def test_disputed_reference_is_retained_not_deleted(self):
        case = self.case(
            reference_status=schema.ReferenceStatus.DISPUTED,
            review=schema.ReviewRecord(notes="two valid readings"),
        )
        self.assertEqual([], schema.errors(schema.validate_case(case)))
        self.assertEqual("مرحبا.", case.reference_translation)

    def test_disputed_without_reason_is_rejected(self):
        case = self.case(reference_status=schema.ReferenceStatus.DISPUTED)
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("dispute_without_reason", codes)

    def test_rejected_without_reference_is_rejected(self):
        case = self.case(
            reference_status=schema.ReferenceStatus.REJECTED,
            reference_translation=None,
            review=schema.ReviewRecord(notes="wrong register"),
        )
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("dispute_without_reference", codes)

    def test_empty_source_rejected(self):
        codes = _issue_codes(schema.errors(schema.validate_case(self.case(source_text="   "))))
        self.assertIn("empty_source", codes)

    def test_schema_version_mismatch_rejected(self):
        codes = _issue_codes(
            schema.errors(schema.validate_case(self.case(dataset_version="0.9.0")))
        )
        self.assertIn("schema_version_mismatch", codes)

    def test_clean_ocr_cannot_carry_corruption(self):
        case = self.case(
            ocr_quality=schema.OcrQuality.CLEAN,
            ocr_corruption_types=(schema.CorruptionType.LOST_WORDS,),
        )
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("clean_ocr_with_corruption", codes)

    def test_degraded_ocr_requires_a_label(self):
        case = self.case(ocr_quality=schema.OcrQuality.SEVERE, ocr_corruption_types=())
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("corruption_without_label", codes)

    def test_multiple_corruption_labels_allowed(self):
        case = self.case(
            ocr_quality=schema.OcrQuality.MODERATE,
            ocr_corruption_types=(
                schema.CorruptionType.LOST_WORDS,
                schema.CorruptionType.SUBSTITUTION,
            ),
        )
        self.assertEqual([], schema.errors(schema.validate_case(case)))

    def test_context_dependency_requires_group(self):
        case = self.case(context_dependency=schema.ContextDependency.PREVIOUS_UTTERANCE)
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("context_dependency_without_group", codes)

    def test_context_order_requires_group_id(self):
        case = self.case(context_order=1)
        codes = _issue_codes(schema.errors(schema.validate_case(case)))
        self.assertIn("context_group_missing", codes)

    def test_unclassified_fields_warn_but_do_not_fail(self):
        case = self.case(text_length=None, semantic_category=schema.SemanticCategory.UNCLASSIFIED)
        issues = schema.validate_case(case)
        self.assertEqual([], schema.errors(issues))
        self.assertIn("unclassified_length", _issue_codes(issues, schema.Issue_.WARNING))

    def test_missing_provenance_rejected(self):
        codes = _issue_codes(schema.errors(schema.validate_case(self.case(source_provenance=""))))
        self.assertIn("missing_provenance", codes)

    def test_unknown_enum_value_raises_instead_of_defaulting(self):
        raw = self.case().as_dict()
        raw["ocr_quality"] = "totally_clean"
        with self.assertRaises(ValueError):
            schema.EvalCase.from_dict(raw)


class DatasetValidationTest(unittest.TestCase):
    def dataset(self, cases):
        d = schema.Dataset(
            schema_version=schema.SCHEMA_VERSION,
            dataset_version=schema.SCHEMA_VERSION,
            cases=cases,
        )
        d.fingerprint = d.compute_fingerprint()
        return d

    def case(self, cid, **kwargs):
        fields = dict(
            source_text="s",
            reference_translation="r",
            source_provenance="test",
            dialogue_context=None,
            text_length=schema.TextLength.SHORT_PHRASE,
            semantic_category=schema.SemanticCategory.PLAIN,
        )
        # Allow a caller to override a default without duplicating the keyword.
        fields.update(kwargs)
        return schema.EvalCase(case_id=cid, **fields)

    def test_duplicate_ids_detected(self):
        codes = _issue_codes(
            schema.errors(schema.validate_dataset(self.dataset([self.case("a"), self.case("a")])))
        )
        self.assertIn("duplicate_case_id", codes)

    def test_context_order_gap_detected(self):
        cases = [
            self.case("a", context_group_id="g", context_order=1),
            self.case("b", context_group_id="g", context_order=3),
        ]
        codes = _issue_codes(schema.errors(schema.validate_dataset(self.dataset(cases))))
        self.assertIn("context_order_gap", codes)

    def test_context_order_duplicate_detected(self):
        cases = [
            self.case("a", context_group_id="g", context_order=1),
            self.case("b", context_group_id="g", context_order=1),
        ]
        codes = _issue_codes(schema.errors(schema.validate_dataset(self.dataset(cases))))
        self.assertIn("context_order_duplicate", codes)

    def test_contiguous_context_group_is_valid(self):
        cases = [
            self.case("a", context_group_id="g", context_order=1),
            self.case("b", context_group_id="g", context_order=2),
        ]
        self.assertEqual([], schema.errors(schema.validate_dataset(self.dataset(cases))))

    def test_empty_dataset_rejected(self):
        codes = _issue_codes(schema.errors(schema.validate_dataset(self.dataset([]))))
        self.assertIn("empty_dataset", codes)

    def test_fingerprint_ignores_case_order(self):
        a = self.dataset([self.case("a"), self.case("b")])
        b = self.dataset([self.case("b"), self.case("a")])
        self.assertEqual(a.compute_fingerprint(), b.compute_fingerprint())

    def test_fingerprint_changes_with_content(self):
        a = self.dataset([self.case("a")])
        b = self.dataset([self.case("a", reference_translation="different")])
        self.assertNotEqual(a.compute_fingerprint(), b.compute_fingerprint())


class MatrixCoverageTest(unittest.TestCase):
    def test_rc4_corpus_reports_its_gaps_rather_than_hiding_them(self):
        dataset = importers.import_rc4_corpus(TSV)
        matrix = schema.CorpusMatrix.build(dataset)
        gaps = {g["value"] for g in matrix.gaps}
        # The RC4 corpus is all clean dialogue: corruption strata are absent and
        # that must be visible in the report.
        self.assertIn(schema.OcrQuality.SEVERE.value, gaps)
        self.assertIn(schema.OcrQuality.TRUNCATED.value, gaps)
        self.assertIn(schema.TextLength.MULTILINE.value, gaps)
        self.assertTrue(gaps)


if __name__ == "__main__":
    unittest.main()
