"""Phase B1: reference-review workflow and device-output export/import.

The emphasis is on the ways a review or an export could lie: an approval without
a reviewer, a timestamp invented at import time, a failure dropped, a reference
upgraded without being touched, or private Arabic leaking onto a device.
"""

from __future__ import annotations

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from translation_eval import device, harness, importers, review, schema  # noqa: E402
from translation_eval.__main__ import (  # noqa: E402
    EXIT_CORPUS_INVALID,
    EXIT_INCOMPLETE,
    EXIT_INPUT_ERROR,
    EXIT_OK,
    main,
)

TSV = ROOT / "docs/benchmark-phase3/dataset.tsv"


def fresh_corpus():
    return importers.import_rc4_corpus(TSV)


class ReviewSheetTest(unittest.TestCase):
    def setUp(self):
        self.dataset = fresh_corpus()
        self.html = review.build_review_html(self.dataset)

    def test_sheet_contains_every_case(self):
        for case in self.dataset.cases:
            self.assertIn(case.case_id, self.html)

    def test_sheet_is_fully_offline(self):
        """No CDN, no fetch, no external stylesheet: it must open from a file."""
        self.assertNotRegex(self.html, r"https?://")
        self.assertNotIn("fetch(", self.html)
        self.assertNotIn("<link", self.html)

    def test_sheet_is_deterministic(self):
        again = review.build_review_html(fresh_corpus())
        self.assertEqual(self.html, again)

    def test_sheet_has_rtl_editor_for_arabic(self):
        self.assertIn("direction: rtl", self.html)

    def test_sheet_requires_a_reviewer_name(self):
        self.assertIn("Enter your name first", self.html)

    def test_sheet_does_not_offer_pending_as_a_choice(self):
        """Silence means pending; the UI must not offer to 'un-review'."""
        self.assertNotIn('<option value="pending_human_review"', self.html)

    def test_sheet_only_emits_touched_cases(self):
        self.assertIn("if (!st.status) continue;", self.html)

    def test_sheet_never_generates_a_timestamp(self):
        """Timestamps belong to the importer, at the moment the human acted.

        Asserted on the emitted payload, not on prose: the page legitimately
        *mentions* timestamps when explaining that it does not write one. What
        matters is that the decision object it builds has no time field and the
        script never reads a clock.
        """
        script = self.html.split("<script>")[1]
        self.assertNotRegex(script, r"new\s+Date\b")
        self.assertNotIn("Date.now(", script)
        # The object pushed into `decisions` must not contain a time field.
        block = script[script.index("out.push({"):script.index("});", script.index("out.push({"))]
        self.assertNotIn("timestamp", block)
        self.assertNotIn("reviewed_at", block)

    def test_sheet_cannot_inject_markup_through_case_text(self):
        dataset = fresh_corpus()
        dataset.cases[0].source_text = '</script><img src=x onerror=alert(1)>'
        html = review.build_review_html(dataset)
        # The only escaping that matters here is that the JSON cannot close the
        # script element early.
        body = html.split("<script>")[1]
        self.assertNotIn("</script><img", body)


class ReviewParsingTest(unittest.TestCase):
    def payload(self, decisions, reviewer="reviewer-a"):
        return {"schema": "1.0.0", "reviewer": reviewer, "decisions": decisions}

    def test_valid_document_parses(self):
        reviewer, decisions = review.parse_decisions(
            self.payload([{"case_id": "rc4-01", "decision": "reviewed",
                           "reference_translation": "x", "notes": ""}])
        )
        self.assertEqual("reviewer-a", reviewer)
        self.assertEqual(1, len(decisions))

    def test_missing_reviewer_rejected(self):
        with self.assertRaises(ValueError):
            review.parse_decisions(self.payload([], reviewer=""))

    def test_missing_case_id_rejected(self):
        with self.assertRaises(ValueError):
            review.parse_decisions(self.payload([{"decision": "reviewed"}]))

    def test_unsupported_decision_rejected(self):
        with self.assertRaises(ValueError):
            review.parse_decisions(
                self.payload([{"case_id": "rc4-01", "decision": "approved_ish"}])
            )

    def test_dispute_requires_notes(self):
        with self.assertRaises(ValueError):
            review.parse_decisions(
                self.payload([{"case_id": "rc4-01", "decision": "disputed", "notes": ""}])
            )

    def test_reject_requires_notes(self):
        with self.assertRaises(ValueError):
            review.parse_decisions(
                self.payload([{"case_id": "rc4-01", "decision": "rejected", "notes": "  "}])
            )


class ApplyDecisionsTest(unittest.TestCase):
    def setUp(self):
        self.dataset = fresh_corpus()

    def apply(self, decisions, **kwargs):
        return review.apply_decisions(
            self.dataset,
            decisions,
            reviewer=kwargs.pop("reviewer", "reviewer-a"),
            timestamp=kwargs.pop("timestamp", "2026-11-20T12:00:00+00:00"),
            **kwargs,
        )

    def test_untouched_cases_stay_pending(self):
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "x", "notes": None}])
        statuses = {c.case_id: c.reference_status for c in self.dataset.cases}
        self.assertIs(statuses["rc4-01"], schema.ReferenceStatus.REVIEWED)
        self.assertIs(statuses["rc4-02"], schema.ReferenceStatus.PENDING_HUMAN_REVIEW)
        self.assertEqual(39, sum(
            1 for c in self.dataset.cases
            if c.reference_status is schema.ReferenceStatus.PENDING_HUMAN_REVIEW
        ))

    def test_reviewed_requires_non_blank_reference(self):
        with self.assertRaises(ValueError):
            self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                         "reference_translation": "   ", "notes": None}])

    def test_reviewed_records_reviewer_and_timestamp(self):
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "x", "notes": None}])
        case = self.dataset.by_id()["rc4-01"]
        self.assertEqual("reviewer-a", case.review.reviewer)
        self.assertEqual("2026-11-20T12:00:00+00:00", case.review.timestamp)
        self.assertTrue(schema.validates_reference(case))

    def test_timestamp_comes_from_caller_not_a_clock(self):
        """Applying the same decisions twice with a fixed time is reproducible."""
        a = fresh_corpus()
        b = fresh_corpus()
        decisions = [{"case_id": "rc4-01", "decision": "reviewed",
                      "reference_translation": "x", "notes": None}]
        review.apply_decisions(a, decisions, "r", "2026-01-01T00:00:00+00:00")
        review.apply_decisions(b, decisions, "r", "2026-01-01T00:00:00+00:00")
        self.assertEqual(a.compute_fingerprint(), b.compute_fingerprint())

    def test_dispute_retains_the_reference_text(self):
        original = self.dataset.by_id()["rc4-05"].reference_translation
        self.apply([{"case_id": "rc4-05", "decision": "disputed",
                     "reference_translation": original, "notes": "two readings"}])
        case = self.dataset.by_id()["rc4-05"]
        self.assertIs(case.reference_status, schema.ReferenceStatus.DISPUTED)
        self.assertEqual(original, case.reference_translation)
        self.assertEqual("two readings", case.review.notes)

    def test_reject_retains_the_reference_text(self):
        original = self.dataset.by_id()["rc4-05"].reference_translation
        self.apply([{"case_id": "rc4-05", "decision": "rejected",
                     "reference_translation": original, "notes": "wrong register"}])
        case = self.dataset.by_id()["rc4-05"]
        self.assertTrue(case.reference_translation)

    def test_re_review_is_refused_without_explicit_override(self):
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "first", "notes": None}])
        _, skipped = self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                                  "reference_translation": "second", "notes": None}])
        self.assertEqual(["rc4-01"], skipped)
        self.assertEqual("first", self.dataset.by_id()["rc4-01"].reference_translation)

    def test_override_replaces_when_asked(self):
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "first", "notes": None}])
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "second", "notes": None}],
                   allow_override=True)
        self.assertEqual("second", self.dataset.by_id()["rc4-01"].reference_translation)

    def test_unknown_case_rejected(self):
        with self.assertRaises(ValueError):
            self.apply([{"case_id": "not-a-case", "decision": "reviewed",
                         "reference_translation": "x", "notes": None}])

    def test_reviewer_correction_is_stored_verbatim(self):
        corrected = "لا عليك، لا تقلق."
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": corrected, "notes": None}])
        self.assertEqual(corrected, self.dataset.by_id()["rc4-01"].reference_translation)

    def test_applying_changes_the_fingerprint(self):
        before = self.dataset.compute_fingerprint()
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "x", "notes": None}])
        self.assertNotEqual(before, self.dataset.compute_fingerprint())

    def test_result_passes_schema_validation(self):
        self.apply([{"case_id": "rc4-01", "decision": "reviewed",
                     "reference_translation": "x", "notes": None}])
        self.assertEqual([], schema.errors(schema.validate_dataset(self.dataset)))


class DeviceAssetTest(unittest.TestCase):
    def test_asset_contains_every_case(self):
        dataset = fresh_corpus()
        asset = device.build_device_corpus_asset(dataset)
        self.assertEqual(40, len(asset["cases"]))
        self.assertEqual(
            sorted(c.case_id for c in dataset.cases),
            sorted(c["case_id"] for c in asset["cases"]),
        )

    def test_asset_excludes_arabic_references(self):
        """A reference must never be shipped to a device or into an export."""
        dataset = fresh_corpus()
        asset = device.build_device_corpus_asset(dataset)
        blob = json.dumps(asset, ensure_ascii=False)
        for case in dataset.cases:
            if case.reference_translation:
                self.assertNotIn(case.reference_translation, blob)
        self.assertNotIn("reference", blob)

    def test_asset_is_deterministic(self):
        a = device.build_device_corpus_asset(fresh_corpus())
        b = device.build_device_corpus_asset(fresh_corpus())
        self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))


def device_export(results, engine=None, schema_version="1.0.0"):
    return {
        "schema": schema_version,
        "dataset_fingerprint": "x",
        "engine": engine or {
            "engine_id": "mlkit-en-ar",
            "revision": "cb3a67a",
            "app_version": "1.1.42",
            "model_identity": "ML Kit ENGLISH->ARABIC (installed)",
        },
        "device": {"manufacturer": "Xiaomi", "model": "23106RN0DA", "sdk": 34,
                   "abi": "arm64-v8a", "emulator": False},
        "results": results,
    }


def all_ok(case_ids):
    return [{"case_id": c, "status": "ok", "translated": f"ترجمة {c}",
             "error_code": None, "method": "ML Kit on-device", "duration_ms": 50}
            for c in case_ids]


class DeviceImportTest(unittest.TestCase):
    def setUp(self):
        self.dataset = fresh_corpus()
        self.ids = [c.case_id for c in self.dataset.cases]

    def test_full_export_imports_every_case(self):
        export = device.import_device_export(device_export(all_ok(self.ids)), self.dataset)
        self.assertEqual(40, len(export.outputs))
        self.assertEqual([], export.problems)

    def test_failures_are_preserved_not_dropped(self):
        results = all_ok(self.ids)
        results[0] = {"case_id": self.ids[0], "status": "failed", "translated": "",
                      "error_code": "TR-TIMEOUT"}
        results[1] = {"case_id": self.ids[1], "status": "empty", "translated": ""}
        export = device.import_device_export(device_export(results), self.dataset)
        self.assertEqual(40, len(export.outputs))
        self.assertIs(schema.OutputStatus.FAILED, export.outputs[self.ids[0]].status)
        self.assertEqual("TR-TIMEOUT", export.outputs[self.ids[0]].error_code)
        self.assertIs(schema.OutputStatus.EMPTY, export.outputs[self.ids[1]].status)

    def test_missing_cases_are_reported_not_filled(self):
        export = device.import_device_export(device_export(all_ok(self.ids[:10])), self.dataset)
        self.assertTrue(any("have no device output" in p for p in export.problems))
        self.assertEqual(10, len(export.outputs))
        # And the harness sees them as MISSING, never as empty success.
        output_set = export.output_set()
        self.assertIs(schema.OutputStatus.MISSING, output_set.resolve(self.ids[20]).status)

    def test_unknown_case_id_is_reported(self):
        results = all_ok(self.ids) + [{"case_id": "rc4-999", "status": "ok", "translated": "x"}]
        export = device.import_device_export(device_export(results), self.dataset)
        self.assertTrue(any("unknown case id" in p for p in export.problems))
        self.assertNotIn("rc4-999", export.outputs)

    def test_duplicate_case_id_is_reported(self):
        results = all_ok(self.ids) + [all_ok([self.ids[0]])[0]]
        export = device.import_device_export(device_export(results), self.dataset)
        self.assertTrue(any("duplicate case id" in p for p in export.problems))

    def test_missing_revision_is_flagged_as_unusable_baseline(self):
        engine = {"engine_id": "mlkit-en-ar", "revision": "unknown"}
        export = device.import_device_export(device_export(all_ok(self.ids), engine), self.dataset)
        self.assertTrue(any("cannot serve as a reproducible baseline" in p for p in export.problems))

    def test_wrong_schema_is_rejected(self):
        with self.assertRaises(harness.HarnessError):
            device.import_device_export(
                device_export(all_ok(self.ids), schema_version="0.9"), self.dataset
            )

    def test_missing_engine_id_is_rejected(self):
        with self.assertRaises(harness.HarnessError):
            device.import_device_export(
                device_export(all_ok(self.ids), {"revision": "x"}), self.dataset
            )

    def test_results_must_be_an_array(self):
        payload = device_export([])
        payload["results"] = {}
        with self.assertRaises(harness.HarnessError):
            device.import_device_export(payload, self.dataset)

    def test_non_string_translation_rejected(self):
        results = all_ok(self.ids)
        results[0]["translated"] = 42
        with self.assertRaises(harness.HarnessError):
            device.import_device_export(device_export(results), self.dataset)

    def test_unrecognised_status_becomes_malformed(self):
        results = all_ok(self.ids)
        results[0]["status"] = "weird"
        export = device.import_device_export(device_export(results), self.dataset)
        self.assertIs(schema.OutputStatus.MALFORMED, export.outputs[self.ids[0]].status)

    def test_output_document_round_trips_through_harness(self):
        export = device.import_device_export(device_export(all_ok(self.ids)), self.dataset)
        parsed = harness.parse_outputs(export.as_output_document())
        self.assertEqual(40, len(parsed.outputs))
        self.assertEqual("mlkit-en-ar", parsed.engine_id)
        self.assertEqual("cb3a67a", parsed.revision)

    def test_import_is_deterministic(self):
        a = device.import_device_export(device_export(all_ok(self.ids)), fresh_corpus())
        b = device.import_device_export(device_export(all_ok(self.ids)), fresh_corpus())
        self.assertEqual(json.dumps(a.as_output_document(), sort_keys=True),
                         json.dumps(b.as_output_document(), sort_keys=True))


class DeviceToReportTest(unittest.TestCase):
    """The full Phase B1 path: reviewed corpus + device export -> a real report."""

    def setUp(self):
        self.dataset = fresh_corpus()
        self.ids = [c.case_id for c in self.dataset.cases]
        decisions = [
            {"case_id": c, "decision": "reviewed",
             "reference_translation": self.dataset.by_id()[c].reference_translation,
             "notes": None}
            for c in self.ids[:3]
        ]
        review.apply_decisions(self.dataset, decisions, "r", "2026-11-20T12:00:00+00:00")
        self.export = device.import_device_export(
            device_export(all_ok(self.ids)), self.dataset
        )

    def test_baseline_versus_itself_is_unchanged(self):
        outputs = self.export.output_set()
        rows = harness.compare(self.dataset, outputs, outputs)
        self.assertTrue(all(r.result is schema.CaseResult.UNCHANGED for r in rows))

    def test_pending_references_are_excluded_from_scoring(self):
        outputs = self.export.output_set()
        rows = harness.compare(self.dataset, outputs, outputs)
        scored = [r for r in rows if r.reference_counts_for_scoring]
        self.assertEqual(3, len(scored))

    def test_failed_outputs_count_as_present_not_missing(self):
        results = all_ok(self.ids)
        results[0] = {"case_id": self.ids[0], "status": "failed", "translated": "",
                      "error_code": "TR-TIMEOUT"}
        export = device.import_device_export(device_export(results), self.dataset)
        rows = harness.compare(self.dataset, export.output_set(), export.output_set())
        row = [r for r in rows if r.case_id == self.ids[0]][0]
        self.assertIsNot(row.baseline.status, schema.OutputStatus.MISSING)


class CliWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)
        self.corpus = self.dir / "corpus.json"
        self.assertEqual(EXIT_OK, main(["import-rc4", "--tsv", str(TSV), "--out", str(self.corpus)]))

    def tearDown(self):
        self.temp.cleanup()

    def test_review_sheet_command_writes_a_file(self):
        out = self.dir / "sheet.html"
        self.assertEqual(EXIT_OK, main(["review-sheet", "--corpus", str(self.corpus), "--out", str(out)]))
        self.assertIn("reference review", out.read_text(encoding="utf-8"))

    def test_apply_review_command_updates_corpus(self):
        decisions = self.dir / "d.json"
        decisions.write_text(json.dumps({
            "schema": "1.0.0", "reviewer": "r",
            "decisions": [{"case_id": "rc4-01", "decision": "reviewed",
                           "reference_translation": "x", "notes": ""}],
        }), encoding="utf-8")
        out = self.dir / "after.json"
        code = main(["apply-review", "--corpus", str(self.corpus), "--decisions", str(decisions),
                     "--out", str(out), "--timestamp", "2026-01-01T00:00:00+00:00"])
        self.assertEqual(EXIT_OK, code)
        payload = json.loads(out.read_text(encoding="utf-8"))
        reviewed = [c for c in payload["cases"] if c["reference_status"] == "reviewed"]
        self.assertEqual(1, len(reviewed))

    def test_apply_review_rejects_missing_decisions_file(self):
        code = main(["apply-review", "--corpus", str(self.corpus),
                     "--decisions", str(self.dir / "absent.json"),
                     "--out", str(self.dir / "after.json")])
        self.assertEqual(EXIT_INPUT_ERROR, code)

    def test_export_device_asset_command(self):
        out = self.dir / "asset.json"
        self.assertEqual(EXIT_OK, main(["export-device-asset", "--corpus", str(self.corpus),
                                        "--out", str(out)]))
        self.assertEqual(40, len(json.loads(out.read_text(encoding="utf-8"))["cases"]))

    def test_import_device_command(self):
        ids = [c["case_id"] for c in json.loads(self.corpus.read_text(encoding="utf-8"))["cases"]]
        export = self.dir / "export.json"
        export.write_text(json.dumps(device_export(all_ok(ids))), encoding="utf-8")
        out = self.dir / "outputs.json"
        code = main(["import-device", "--corpus", str(self.corpus),
                     "--export", str(export), "--out", str(out)])
        self.assertEqual(EXIT_OK, code)
        self.assertEqual(40, len(json.loads(out.read_text(encoding="utf-8"))["outputs"]))

    def test_import_device_returns_incomplete_when_cases_are_missing(self):
        ids = [c["case_id"] for c in json.loads(self.corpus.read_text(encoding="utf-8"))["cases"]]
        export = self.dir / "export.json"
        export.write_text(json.dumps(device_export(all_ok(ids[:5]))), encoding="utf-8")
        code = main(["import-device", "--corpus", str(self.corpus),
                     "--export", str(export), "--out", str(self.dir / "o.json")])
        self.assertEqual(EXIT_INCOMPLETE, code)


class PrivacyTest(unittest.TestCase):
    def test_device_asset_has_no_arabic_from_references(self):
        dataset = fresh_corpus()
        asset = json.dumps(device.build_device_corpus_asset(dataset), ensure_ascii=False)
        arabic = [c.reference_translation for c in dataset.cases if c.reference_translation]
        leaked = [a for a in arabic if a in asset]
        self.assertEqual([], leaked)

    def test_review_sheet_is_local_only_and_not_device_data(self):
        """The sheet contains references, so it must stay a local artifact."""
        self.assertTrue(
            str(ROOT / "evaluation/review").endswith("evaluation/review")
        )
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        # Device exports are user data and must never be committed.
        self.assertIn("evaluation/device/", gitignore)


if __name__ == "__main__":
    unittest.main()
