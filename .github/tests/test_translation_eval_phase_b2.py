"""Phase B2: app assets, engine/reviewer separation, and export invariants.

These tests guard the properties that make the phone-only evaluation meaningful:

* the application assets are generated from the canonical corpus and nothing else;
* the payload that reaches the translation engine can never contain a reference
  translation, reviewer-only context, or a classification;
* the two payloads stay consistent with each other and with the canonical corpus;
* no private evaluation evidence is committed to the repository.

They do not test translation quality, because translation quality is not
something a Python assertion can observe.
"""

from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.translation_eval.device import (  # noqa: E402
    build_engine_input_asset,
    build_review_asset,
    load_canonical_dataset,
)

CANONICAL = ROOT / "evaluation/corpus/rc4-corpus.json"
ASSET_DIR = ROOT / "app/src/main/assets"
ENGINE_ASSET = ASSET_DIR / "evaluation-engine-input.json"
REVIEW_ASSET = ASSET_DIR / "evaluation-human-review.json"

#: The fingerprint recorded by the Phase A corpus-integrity report. A change here
#: means the corpus changed, which invalidates comparison with earlier runs.
EXPECTED_FINGERPRINT = "5fb61a318d51c91d9e017bf4b09a09b89a90c3dc34f3ca5f66239fc0148014d1"


def strip_comments(source: str) -> str:
    """Drop Kotlin comments so a doc line naming a forbidden API is not a hit.

    The assertions below are about what the code *does*. A comment that says the
    runner does not use MediaProjection is evidence of intent, not a violation.
    """
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def _top_level_tail(body: str) -> str:
    """Return the trailing statement that decides a lambda's type, or "" for a block.

    A `try { ... } finally { ... }` tail closes a block at depth 1, so the lambda's
    value is Unit however many asserts sit inside it. Only text that follows the
    last returned-to-depth-0 closing brace is the actual final expression.
    """
    depth = 0
    last_close = -1
    in_string = False
    for index, char in enumerate(body):
        if char == '"' and (index == 0 or body[index - 1] != "\\"):
            in_string = not in_string
        if in_string:
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                last_close = index
    return body[last_close + 1:].strip()


def load_asset(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class AppAssetExistenceTest(unittest.TestCase):
    def test_both_assets_exist(self):
        self.assertTrue(ENGINE_ASSET.is_file(), "engine input asset missing")
        self.assertTrue(REVIEW_ASSET.is_file(), "human review asset missing")

    def test_assets_declare_their_kind(self):
        # A swapped or mis-copied file would otherwise silently feed references to
        # the engine.
        self.assertEqual(load_asset(ENGINE_ASSET)["kind"], "engine_input")
        self.assertEqual(load_asset(REVIEW_ASSET)["kind"], "human_review")

    def test_assets_are_not_empty_stubs(self):
        self.assertGreater(ENGINE_ASSET.stat().st_size, 1000)
        self.assertGreater(REVIEW_ASSET.stat().st_size, 5000)


class PayloadConsistencyTest(unittest.TestCase):
    """The app assets must be exactly what the canonical corpus produces."""

    @classmethod
    def setUpClass(cls):
        cls.dataset = load_canonical_dataset(CANONICAL)
        cls.engine_expected = build_engine_input_asset(cls.dataset)
        cls.review_expected = build_review_asset(cls.dataset)

    def test_engine_asset_matches_regenerated_payload(self):
        self.assertEqual(load_asset(ENGINE_ASSET), self.engine_expected)

    def test_review_asset_matches_regenerated_payload(self):
        self.assertEqual(load_asset(REVIEW_ASSET), self.review_expected)

    def test_canonical_fingerprint_is_unchanged(self):
        # References must not drift: the corpus is the record Phase A scored.
        self.assertEqual(self.dataset.fingerprint, EXPECTED_FINGERPRINT)

    def test_asset_fingerprints_match_the_corpus(self):
        for path in (ENGINE_ASSET, REVIEW_ASSET):
            self.assertEqual(load_asset(path)["dataset_fingerprint"], EXPECTED_FINGERPRINT)

    def test_all_forty_cases_present_in_both_assets(self):
        engine = load_asset(ENGINE_ASSET)["cases"]
        review = load_asset(REVIEW_ASSET)["cases"]
        self.assertEqual(len(engine), 40)
        self.assertEqual(len(review), 40)
        self.assertEqual({c["case_id"] for c in engine}, {c["case_id"] for c in review})

    def test_case_ids_match_the_canonical_corpus(self):
        canonical_ids = [c.case_id for c in self.dataset.cases]
        for path in (ENGINE_ASSET, REVIEW_ASSET):
            asset_ids = [c["case_id"] for c in load_asset(path)["cases"]]
            self.assertEqual(asset_ids, sorted(canonical_ids))

    def test_source_text_is_identical_across_all_three(self):
        canonical = {c.case_id: c.source_text for c in self.dataset.cases}
        for path in (ENGINE_ASSET, REVIEW_ASSET):
            for case in load_asset(path)["cases"]:
                self.assertEqual(case["source_text"], canonical[case["case_id"]], case["case_id"])


class AssetGeneratorCommandTest(unittest.TestCase):
    """The generator must be the thing that produced the committed assets."""

    def test_build_app_assets_reproduces_the_committed_files(self):
        import tempfile

        from scripts.translation_eval.__main__ import main

        with tempfile.TemporaryDirectory() as directory:
            code = main([
                "build-app-assets",
                "--corpus", str(CANONICAL),
                "--out", directory,
            ])
            self.assertEqual(0, code)
            for name in ("evaluation-engine-input.json", "evaluation-human-review.json"):
                produced = (Path(directory) / name).read_text(encoding="utf-8")
                committed = (ASSET_DIR / name).read_text(encoding="utf-8")
                self.assertEqual(
                    committed, produced,
                    f"{name} is stale; regenerate with build-app-assets",
                )

    def test_generator_refuses_a_changed_corpus_fingerprint(self):
        import tempfile

        from scripts.translation_eval.__main__ import main

        with tempfile.TemporaryDirectory() as directory:
            code = main([
                "build-app-assets",
                "--corpus", str(CANONICAL),
                "--out", directory,
                "--expected-fingerprint", "0" * 64,
            ])
            # Fail closed: rewriting the assets from an unexpected corpus would
            # silently invalidate every earlier run.
            self.assertNotEqual(0, code)
            self.assertFalse((Path(directory) / "evaluation-engine-input.json").exists())


class EnginePayloadSeparationTest(unittest.TestCase):
    """Nothing reviewer-only may appear in the payload the engine reads."""

    @classmethod
    def setUpClass(cls):
        cls.engine = load_asset(ENGINE_ASSET)

    def test_engine_cases_carry_only_case_id_and_source_text(self):
        for case in self.engine["cases"]:
            self.assertEqual(set(case), {"case_id", "source_text"}, case["case_id"])

    def test_engine_payload_has_no_reference_translation(self):
        raw = ENGINE_ASSET.read_text(encoding="utf-8")
        self.assertNotIn("reference_translation", raw)

    def test_engine_payload_has_no_reviewer_only_context(self):
        raw = ENGINE_ASSET.read_text(encoding="utf-8")
        self.assertNotIn("context_for_reviewer_only", raw)

    def test_engine_payload_has_no_classification_metadata(self):
        raw = ENGINE_ASSET.read_text(encoding="utf-8")
        for forbidden in ("semantic_category", "ocr_quality", "difficulty_tags", "expected_behavior"):
            self.assertNotIn(forbidden, raw)

    def test_engine_payload_declares_no_native_context(self):
        # The production engine receives one bubble; saying otherwise would let a
        # report claim contextual MT happened.
        self.assertIs(self.engine["native_model_context"], False)

    def test_review_payload_does_carry_references(self):
        # The counterpart assertion: separation must not have been achieved by
        # dropping the references the reviewer needs.
        review = load_asset(REVIEW_ASSET)
        with_reference = [c for c in review["cases"] if c.get("reference_translation")]
        self.assertEqual(len(with_reference), 40)

    def test_every_reference_is_still_pending(self):
        # Phase A/B1 never auto-approve a reference, and B2 must not change that.
        for case in load_asset(REVIEW_ASSET)["cases"]:
            self.assertEqual(case["reference_status"], "pending_human_review", case["case_id"])

    def test_context_never_claims_to_reach_the_engine(self):
        for case in load_asset(REVIEW_ASSET)["cases"]:
            self.assertIs(case["context_available_to_engine"], False, case["case_id"])


class NoCommittedPrivateEvidenceTest(unittest.TestCase):
    """Evaluation decisions and device output stay outside the repository."""

    #: Files the app writes at runtime. Any of these appearing in the tree means
    #: private review decisions or device output were committed.
    RUNTIME_ONLY = (
        "reviews.json",
        "assessments.json",
        "annotations.json",
        "manhwalens-eval-export.json",
        "translation-eval-export.json",
    )

    def test_runtime_stores_are_not_committed(self):
        allowlist = {ENGINE_ASSET, REVIEW_ASSET}
        for name in self.RUNTIME_ONLY:
            for found in ROOT.rglob(name):
                if found in allowlist:
                    continue
                self.fail(f"private evaluation artifact committed: {found.relative_to(ROOT)}")

    def test_no_evaluation_run_records_committed(self):
        # Run records live under filesDir on the device, never in Git.
        for found in ROOT.rglob("run-*.json"):
            self.fail(f"evaluation run record committed: {found.relative_to(ROOT)}")

    def test_app_assets_contain_no_review_decisions(self):
        for path in (ENGINE_ASSET, REVIEW_ASSET):
            raw = path.read_text(encoding="utf-8")
            for forbidden in ('"decided_at":', '"suspected_cause":'):
                self.assertNotIn(forbidden, raw, f"{path.name} carries a human decision")


class RunnerSourceContractTest(unittest.TestCase):
    """Static guarantees about the runner that behaviour tests cannot reach in CI.

    The Android runner cannot execute in CI (no emulator, no ML Kit model), so the
    structural constraints are checked against the source instead. These are
    narrow, deliberate assertions about identifiers and call shape, not a
    substitute for running the app.
    """

    @classmethod
    def setUpClass(cls):
        cls.path = ROOT / "app/src/main/java/com/manhwalens/app/evaluation/EvaluationRunner.kt"
        cls.source = strip_comments(cls.path.read_text(encoding="utf-8"))

    def test_runner_exists(self):
        self.assertTrue(self.path.is_file())

    def test_cache_is_cleared_before_each_case(self):
        # Several cases share identical English; without a clear the second would
        # return the first's translation from cache.
        self.assertIn("cache.clear()", self.source)

    def test_cases_run_sequentially(self):
        # A "for" loop over the targets, awaiting each translation in turn.
        self.assertIn("for (caseId in targets)", self.source)
        self.assertNotIn("async(", self.source)

    def test_no_screen_capture_in_the_runner(self):
        for forbidden in ("MediaProjection", "VirtualDisplay", "createScreenCaptureIntent", "ImageReader"):
            self.assertNotIn(forbidden, self.source)

    def test_no_ocr_in_the_runner(self):
        # Corpus inputs are already clean text; invoking OCR would be a lie about
        # where the text came from.
        self.assertNotIn("TextRecognitionClient", self.source)

    def test_no_alternate_provider(self):
        self.assertNotIn("GoogleTranslate", self.source)
        self.assertNotIn("api.openai", self.source)

    def test_records_native_context_as_false(self):
        self.assertIn("contextSuppliedToNativeEngine = false", self.source)

    def test_model_absent_is_not_run_not_failed(self):
        self.assertIn("STATE_NOT_RUN", self.source)
        self.assertIn("MODEL_UNAVAILABLE", self.source)

    def test_model_is_never_downloaded_silently(self):
        # `ensureModel` must not fall through to `ensureReady()` on the no-download
        # path: that call fetches the language file.
        self.assertIn(
            "if (allowDownload) LanguageModels.ensureReady() else LanguageModels.state.value.ready",
            self.source,
        )

    def test_result_is_saved_incrementally(self):
        # A run that dies mid-way must not lose completed cases.
        self.assertIn("store.saveCaseResult(runId, result)", self.source)

    def test_empty_is_distinguished_from_failed(self):
        self.assertIn("STATE_EMPTY", self.source)
        self.assertIn("STATE_FAILED", self.source)

    def test_revision_is_never_invented(self):
        # Taken from the build, which supplies "unknown" when CI did not set it.
        self.assertIn("BuildConfig.GIT_REVISION", self.source)
        self.assertNotRegex(self.source, r"[0-9a-f]{40}")

    def test_config_is_hashed_not_stored(self):
        self.assertIn("configurationFingerprint", self.source)
        self.assertIn("SHA-256", self.source)


class JUnitVoidTestMethodsTest(unittest.TestCase):
    """JUnit4 only runs test methods that return void.

    `fun foo() = runBlocking { ... Log.i(...) }` returns Int, and JUnit4 rejects
    the entire class with `initializationError` -- so a whole instrumentation suite
    stops running while `compileDebugAndroidTestKotlin` still passes. This phase's
    test had exactly that shape. The check below reads the expression body of
    every `= runBlocking` test and refuses one whose last call returns a value.
    """

    #: Calls whose return type is not Unit. A test whose expression body ends in
    #: one of these becomes a value-returning method.
    #:
    #: org.junit.Assert's assert* methods return void, so they are deliberately
    #: absent: a test ending in assertTrue is a valid Unit expression body.
    VALUE_RETURNING = (
        "Log.",
        "JSONObject(",
        "JSONArray(",
        "put(",
        "getJSONObject(",
        "writeText(",
    )

    @classmethod
    def setUpClass(cls):
        cls.directory = ROOT / "app/src/androidTest/java/com/manhwalens/app"
        cls.files = sorted(cls.directory.rglob("*.kt"))

    def test_instrumentation_tests_exist(self):
        self.assertTrue(self.files, "no androidTest sources found")

    def test_no_test_uses_a_value_returning_expression_body(self):
        offenders = []
        for path in self.files:
            source = strip_comments(path.read_text(encoding="utf-8"))
            if "= runBlocking" not in source:
                continue
            # Find `= runBlocking {` and walk to its matching brace, then inspect
            # the final non-empty statement before the closing brace.
            for match in re.finditer(r"fun\s+(\w+)\s*\([^)]*\)\s*=\s*runBlocking\s*\{", source):
                depth, index = 0, match.end() - 1
                while index < len(source):
                    if source[index] == "{":
                        depth += 1
                    elif source[index] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    index += 1
                body = source[match.end():index]
                tail = _top_level_tail(body)
                if tail and any(token in tail for token in self.VALUE_RETURNING):
                    offenders.append(f"{path.name}::{match.group(1)}")
        self.assertEqual([], offenders, "JUnit4 would reject these classes at init")

    def test_known_good_block_body_still_present(self):
        # The fix itself must not be silently reverted to the failing form.
        source = strip_comments(
            (self.directory / "TranslationEvalExportTest.kt").read_text(encoding="utf-8")
        )
        self.assertIn("fun exportRealEngineOutputForEveryCorpusCase() {", source)
        self.assertNotIn("fun exportRealEngineOutputForEveryCorpusCase() =", source)


class StoreSourceContractTest(unittest.TestCase):
    """Persistence invariants checked on the source of the on-device store."""

    @classmethod
    def setUpClass(cls):
        cls.path = ROOT / "app/src/main/java/com/manhwalens/app/evaluation/EvaluationStore.kt"
        cls.source = strip_comments(cls.path.read_text(encoding="utf-8"))

    def test_store_lives_in_files_dir(self):
        self.assertIn("context.filesDir", self.source)

    def test_writes_are_atomic(self):
        self.assertIn("AtomicFile", self.source)

    def test_does_not_touch_history_or_samples(self):
        # The evaluation store owns its own directory; it must not read or write
        # the user's history or collected samples.
        for forbidden in ("HistoryStore", "TranslationSamples", "translation-samples.json"):
            self.assertNotIn(forbidden, self.source)

    def test_missing_state_exists(self):
        self.assertIn('STATE_MISSING = "missing"', self.source)

    def test_missing_is_not_collapsed_into_failure(self):
        self.assertIn('STATE_NOT_RUN = "not_run"', self.source)
        self.assertIn('STATE_EMPTY = "empty"', self.source)
        self.assertIn('STATE_FAILED = "failed"', self.source)

    def test_interrupted_runs_are_reconciled(self):
        self.assertIn("reconcileInterruptedRuns", self.source)

    def test_export_includes_absent_cases(self):
        # Phase B2.1: export takes the full corpus so a missing case still carries
        # its real English source text instead of an empty string.
        self.assertIn("corpusCases", self.source)
        self.assertIn("CorpusCase", self.source)

    def test_no_automatic_approval(self):
        # Nothing in the store may set a review status without a human decision.
        self.assertIn("STATUS_NOT_REVIEWED", self.source)


class BuildIdentityTest(unittest.TestCase):
    """The one permitted build change must stay identity-only."""

    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

    def test_git_revision_field_exists(self):
        self.assertIn('"GIT_REVISION"', self.source)

    def test_revision_defaults_to_unknown(self):
        # Never invent a SHA when CI did not supply one.
        self.assertIn('?: "unknown"', self.source)

    def test_application_id_is_unchanged(self):
        self.assertIn('applicationId = "com.manhwalens.app"', self.source)

    def test_never_debuggable(self):
        self.assertNotIn("debuggable = true", self.source)


if __name__ == "__main__":
    unittest.main()
