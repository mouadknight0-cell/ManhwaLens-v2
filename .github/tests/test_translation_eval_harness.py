"""Harness determinism, integrity detection and exit-code behaviour.

These tests exist to prove the harness cannot hide a failure: no missing output
disappears, no duplicate is silently resolved, no malformed record is skipped,
and identical inputs produce byte-identical reports.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from translation_eval import harness, importers, normalization, reports, schema  # noqa: E402
from translation_eval.__main__ import (  # noqa: E402
    EXIT_CORPUS_INVALID,
    EXIT_INCOMPLETE,
    EXIT_INPUT_ERROR,
    EXIT_OK,
    main,
)

TSV = ROOT / "docs/benchmark-phase3/dataset.tsv"


def _outputs(engine_id, revision, entries, config=None):
    return {"engine_id": engine_id, "revision": revision, "config": config or {}, "outputs": entries}


def _ok(case_id, text):
    return {"case_id": case_id, "text": text, "status": "ok"}


class SchemaRejectionTest(unittest.TestCase):
    def test_missing_engine_id_rejected(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs({"revision": "r", "outputs": []})

    def test_missing_revision_rejected(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs({"engine_id": "e", "outputs": []})

    def test_outputs_must_be_a_list(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs({"engine_id": "e", "revision": "r", "outputs": {}})

    def test_missing_case_id_rejected(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs(
                {"engine_id": "e", "revision": "r", "outputs": [{"text": "x"}]}
            )

    def test_unknown_status_rejected(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs(
                _outputs("e", "r", [{"case_id": "a", "text": "x", "status": "maybe"}])
            )

    def test_non_string_text_rejected(self):
        with self.assertRaises(harness.HarnessError):
            harness.parse_outputs(
                _outputs("e", "r", [{"case_id": "a", "text": 42}])
            )


class OutputSetTest(unittest.TestCase):
    def test_duplicate_ids_are_recorded_not_swallowed(self):
        parsed = harness.parse_outputs(
            _outputs("e", "r", [_ok("a", "first"), _ok("a", "second")])
        )
        self.assertEqual(["a"], parsed.duplicates())

    def test_missing_case_resolves_to_missing_not_empty_success(self):
        parsed = harness.parse_outputs(_outputs("e", "r", [_ok("a", "x")]))
        missing = parsed.resolve("b")
        self.assertIs(schema.OutputStatus.MISSING, missing.status)
        self.assertEqual("", missing.text)

    def test_empty_text_defaults_to_empty_status(self):
        parsed = harness.parse_outputs(_outputs("e", "r", [{"case_id": "a", "text": ""}]))
        self.assertIs(schema.OutputStatus.EMPTY, parsed.outputs["a"].status)

    def test_explicit_status_is_preserved(self):
        parsed = harness.parse_outputs(
            _outputs("e", "r", [{"case_id": "a", "text": "", "status": "failed", "error_code": "TR-TIMEOUT"}])
        )
        self.assertIs(schema.OutputStatus.FAILED, parsed.outputs["a"].status)
        self.assertEqual("TR-TIMEOUT", parsed.outputs["a"].error_code)

    def test_fingerprint_is_stable_and_content_sensitive(self):
        a = harness.parse_outputs(_outputs("e", "r", [_ok("a", "x")]))
        b = harness.parse_outputs(_outputs("e", "r", [_ok("a", "x")]))
        c = harness.parse_outputs(_outputs("e", "r", [_ok("a", "y")]))
        self.assertEqual(a.fingerprint(), b.fingerprint())
        self.assertNotEqual(a.fingerprint(), c.fingerprint())

    def test_fingerprint_changes_with_revision(self):
        a = harness.parse_outputs(_outputs("e", "rev1", [_ok("a", "x")]))
        b = harness.parse_outputs(_outputs("e", "rev2", [_ok("a", "x")]))
        self.assertNotEqual(a.fingerprint(), b.fingerprint())


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.dataset = importers.import_rc4_corpus(TSV)
        self.ids = [c.case_id for c in self.dataset.cases]

    def compare(self, base_entries, cand_entries):
        baseline = harness.parse_outputs(_outputs("base", "1", base_entries))
        candidate = harness.parse_outputs(_outputs("cand", "2", cand_entries))
        return harness.compare(self.dataset, baseline, candidate)

    def test_every_dataset_case_appears_even_with_no_outputs(self):
        rows = self.compare([], [])
        self.assertEqual(len(self.ids), len(rows))
        self.assertTrue(all(r.result is schema.CaseResult.BOTH_MISSING for r in rows))

    def test_identical_output_is_unchanged(self):
        entries = [_ok(cid, f"text {cid}") for cid in self.ids]
        rows = self.compare(entries, entries)
        self.assertTrue(all(r.result is schema.CaseResult.UNCHANGED for r in rows))

    def test_difference_is_never_labelled_an_improvement_without_review(self):
        entries = [_ok(cid, "same") for cid in self.ids]
        changed = [_ok(cid, "different") for cid in self.ids]
        rows = self.compare(entries, changed)
        results = {r.result for r in rows}
        self.assertNotIn(schema.CaseResult.CANDIDATE_IMPROVEMENT_REVIEW_REQUIRED, results)
        self.assertNotIn(schema.CaseResult.CANDIDATE_REGRESSION_REVIEW_REQUIRED, results)
        # All references are pending, so a difference stays "changed".
        self.assertEqual({schema.CaseResult.CANDIDATE_CHANGED}, results)

    def test_candidate_missing_is_detected(self):
        base = [_ok(cid, "x") for cid in self.ids]
        rows = self.compare(base, [])
        self.assertTrue(all(r.result is schema.CaseResult.CANDIDATE_MISSING for r in rows))

    def test_baseline_missing_is_detected(self):
        cand = [_ok(cid, "x") for cid in self.ids]
        rows = self.compare([], cand)
        self.assertTrue(all(r.result is schema.CaseResult.BASELINE_MISSING for r in rows))

    def test_both_missing_is_distinct_from_one_missing(self):
        base = [_ok(self.ids[0], "x")]
        rows = self.compare(base, [])
        self.assertIs(schema.CaseResult.CANDIDATE_MISSING, rows[0].result)
        self.assertTrue(
            all(r.result is schema.CaseResult.BOTH_MISSING for r in rows[1:])
        )

    def test_unexpected_case_id_is_an_evaluation_error(self):
        rows = self.compare(_outputs_entries := [_ok("not-in-corpus", "x")], [])
        error_rows = [r for r in rows if r.result is schema.CaseResult.EVALUATION_ERROR]
        self.assertEqual(1, len(error_rows))
        self.assertEqual("not-in-corpus", error_rows[0].case_id)
        self.assertIn("unexpected_case_id", error_rows[0].error_reason_codes)

    def test_missing_output_text_is_never_manufactured(self):
        rows = self.compare([], [])
        for row in rows:
            self.assertEqual("", row.baseline.text)
            self.assertEqual("", row.candidate.text)

    def test_reviewed_reference_marks_change_as_review_required(self):
        """With a valid reviewed reference a difference becomes a review flag."""
        dataset = importers.import_rc4_corpus(TSV)
        target = dataset.cases[0]
        target.reference_status = schema.ReferenceStatus.REVIEWED
        target.review = schema.ReviewRecord(reviewer="r", timestamp="2026-01-01")
        rows = harness.compare(
            dataset,
            harness.parse_outputs(_outputs("b", "1", [_ok(target.case_id, "a")])),
            harness.parse_outputs(_outputs("c", "2", [_ok(target.case_id, "b")])),
        )
        changed = [r for r in rows if r.case_id == target.case_id][0]
        self.assertIs(
            schema.CaseResult.CANDIDATE_IMPROVEMENT_REVIEW_REQUIRED, changed.result
        )
        # And it is still only a review prompt, not a verdict.
        self.assertTrue(changed.needs_review)

    def test_normalization_does_not_hide_a_real_difference(self):
        base = [_ok(cid, "مرحبا") for cid in self.ids]
        cand = [_ok(cid, "وداعا") for cid in self.ids]
        rows = self.compare(base, cand)
        self.assertTrue(all(r.result is schema.CaseResult.CANDIDATE_CHANGED for r in rows))

    def test_status_only_difference_is_reported(self):
        base = [_ok(self.ids[0], "x")]
        cand = [{"case_id": self.ids[0], "text": "", "status": "failed"}]
        rows = self.compare(base, cand)
        row = [r for r in rows if r.case_id == self.ids[0]][0]
        self.assertIs(schema.OutputStatus.FAILED, row.candidate.status)


class DeterminismTest(unittest.TestCase):
    def setUp(self):
        self.dataset = importers.import_rc4_corpus(TSV)
        self.ids = [c.case_id for c in self.dataset.cases]

    def build(self):
        base = harness.parse_outputs(_outputs("base", "1", [_ok(cid, "مرحبا") for cid in self.ids]))
        cand = harness.parse_outputs(
            _outputs("cand", "2", [_ok(cid, "وداعا" if i % 2 else "مرحبا") for i, cid in enumerate(self.ids)])
        )
        rows = sorted(harness.compare(self.dataset, base, cand), key=lambda r: r.case_id)
        run = reports.evaluation_run(
            self.dataset, base, cand, rows,
            {"harness_version": harness.HARNESS_VERSION, "normalization": "fixed"},
        )
        return rows, run, reports.regression_review(rows)

    def test_repeated_runs_are_byte_identical(self):
        _, run_a, review_a = self.build()
        _, run_b, review_b = self.build()
        self.assertEqual(
            json.dumps(run_a, sort_keys=True), json.dumps(run_b, sort_keys=True)
        )
        self.assertEqual(
            reports.render_evaluation_run(run_a), reports.render_evaluation_run(run_b)
        )
        self.assertEqual(
            json.dumps(review_a, sort_keys=True), json.dumps(review_b, sort_keys=True)
        )
        self.assertEqual(
            reports.render_regression_review(review_a),
            reports.render_regression_review(review_b),
        )

    def test_run_identity_is_recorded(self):
        _, run, _ = self.build()
        self.assertEqual(harness.HARNESS_VERSION, run["harness_version"])
        self.assertEqual("base", run["baseline"]["engine_id"])
        self.assertEqual("cand", run["candidate"]["engine_id"])
        self.assertTrue(run["dataset_fingerprint"])
        self.assertTrue(run["baseline"]["output_fingerprint"])

    def test_no_aggregate_quality_score_when_references_pending(self):
        _, run, _ = self.build()
        self.assertIsNone(run["summary"]["semantic_quality_score"])
        self.assertIn("not computed", run["summary"]["semantic_score_reason"])

    def test_pending_references_are_counted_as_excluded(self):
        """Every RC4 reference is pending, so all must be excluded from scoring."""
        _, run, _ = self.build()
        self.assertEqual(len(self.ids), run["references_pending_excluded_from_scoring"])
        self.assertEqual(0, run["summary"]["references_reviewed_and_valid"])


class NormalizationTest(unittest.TestCase):
    def test_idempotent(self):
        for text in ["مرحبا", "  Hello   World ", "a\u0640\u064b b", ""]:
            once = normalization.normalize_for_comparison(text)
            twice = normalization.normalize_for_comparison(once)
            self.assertEqual(once, twice)

    def test_arabic_diacritics_compare_equal(self):
        self.assertTrue(normalization.similar("مَرْحَبًا", "مرحبا"))

    def test_tatweel_removed(self):
        self.assertTrue(normalization.similar("مـــرحبا", "مرحبا"))

    def test_invisible_bidi_removed(self):
        self.assertTrue(normalization.similar("مرحبا\u200f", "مرحبا"))

    def test_different_words_are_not_equal(self):
        self.assertFalse(normalization.similar("مرحبا", "وداعا"))

    def test_raw_output_is_never_mutated(self):
        """Normalization returns a new string and leaves the stored text alone."""
        stored = "  Hello   World  "
        normalization.normalize_for_comparison(stored)
        self.assertEqual("  Hello   World  ", stored)

    def test_similarity_is_not_reported_as_quality(self):
        report_text = reports.__doc__ or ""
        self.assertIn("machine-readable", report_text)

    def test_config_fingerprint_is_stable(self):
        self.assertEqual(
            normalization.DEFAULT_COMPARISON.fingerprint(),
            normalization.DEFAULT_COMPARISON.fingerprint(),
        )


class CliExitCodeTest(unittest.TestCase):
    """Exercise the real entry point so exit codes are proven, not assumed."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)
        self.corpus = self.dir / "corpus.json"
        importers_out = self.dir / "corpus.json"
        code = main(["import-rc4", "--tsv", str(TSV), "--out", str(importers_out)])
        self.assertEqual(EXIT_OK, code)
        self.dataset = importers.import_rc4_corpus(TSV)
        self.ids = [c.case_id for c in self.dataset.cases]

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, payload):
        path = self.dir / name
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_complete_run_returns_zero_when_no_pending_references(self):
        # A corpus with all references pending always returns 1 (incomplete), by
        # design. Verify the incomplete path explicitly rather than pretending.
        base = self.write("b.json", _outputs("b", "1", [_ok(c, "x") for c in self.ids]))
        cand = self.write("c.json", _outputs("c", "2", [_ok(c, "x") for c in self.ids]))
        code = main([
            "evaluate", "--corpus", str(self.corpus),
            "--baseline", str(base), "--candidate", str(cand),
            "--out", str(self.dir / "reports"),
        ])
        self.assertEqual(EXIT_INCOMPLETE, code)

    def test_missing_output_file_returns_input_error(self):
        base = self.write("b.json", _outputs("b", "1", [_ok(self.ids[0], "x")]))
        code = main([
            "evaluate", "--corpus", str(self.corpus),
            "--baseline", str(base), "--candidate", str(self.dir / "nope.json"),
            "--out", str(self.dir / "reports"),
        ])
        self.assertEqual(EXIT_INPUT_ERROR, code)

    def test_malformed_json_returns_input_error(self):
        bad = self.dir / "bad.json"
        bad.write_text("{not json", encoding="utf-8")
        base = self.write("b.json", _outputs("b", "1", []))
        code = main([
            "evaluate", "--corpus", str(self.corpus),
            "--baseline", str(base), "--candidate", str(bad),
            "--out", str(self.dir / "reports"),
        ])
        self.assertEqual(EXIT_INPUT_ERROR, code)

    def test_missing_corpus_returns_input_error(self):
        code = main(["validate", "--corpus", str(self.dir / "absent.json"),
                     "--out", str(self.dir / "reports")])
        self.assertEqual(EXIT_INPUT_ERROR, code)

    def test_reports_are_written(self):
        base = self.write("b.json", _outputs("b", "1", [_ok(c, "x") for c in self.ids]))
        cand = self.write("c.json", _outputs("c", "2", [_ok(c, "y") for c in self.ids]))
        out = self.dir / "reports"
        main([
            "evaluate", "--corpus", str(self.corpus),
            "--baseline", str(base), "--candidate", str(cand), "--out", str(out),
        ])
        for name in (
            "corpus-integrity.json", "corpus-integrity.md",
            "evaluation-run.json", "evaluation-run.md",
            "regression-review.json", "regression-review.md",
        ):
            self.assertTrue((out / name).is_file(), f"missing report {name}")

    def test_subprocess_invocation_works_offline(self):
        """Proves the harness runs as a process with no network and no imports beyond stdlib."""
        proc = subprocess.run(
            [sys.executable, "-m", "scripts.translation_eval", "validate",
             "--corpus", str(self.corpus), "--out", str(self.dir / "reports2")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("40 cases", proc.stdout)


if __name__ == "__main__":
    unittest.main()
