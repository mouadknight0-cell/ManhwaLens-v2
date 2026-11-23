"""Phase B2.1: evaluation reliability, review-data integrity, APK stability.

Each test here corresponds to a defect that was found by reading the B2 code and
confirmed against it before anything was changed. They are written against the
Kotlin source because the Android evaluation path cannot execute in CI: there is no
emulator, no ML Kit model and no device. That is a real limitation, and these
assertions are deliberately about structure and invariants rather than pretending
to be a behavioural test of translation.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

EVAL = ROOT / "app/src/main/java/com/manhwalens/app/evaluation"
STORE = EVAL / "EvaluationStore.kt"
RUNNER = EVAL / "EvaluationRunner.kt"
SCREEN = EVAL / "EvaluationScreen.kt"
CORPUS = EVAL / "EvaluationCorpus.kt"
BUILD = ROOT / "app/build.gradle.kts"
CANDIDATE_WF = ROOT / ".github/workflows/release-candidate.yml"
CI_WF = ROOT / ".github/workflows/ci.yml"
QUALITY_WF = ROOT / ".github/workflows/quality.yml"

EXPECTED_FINGERPRINT = "5fb61a318d51c91d9e017bf4b09a09b89a90c3dc34f3ca5f66239fc0148014d1"


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def read(path: Path) -> str:
    return strip_comments(path.read_text(encoding="utf-8"))


class NamesReachTheEngineTest(unittest.TestCase):
    """Defect 3: the benchmark never passed the confirmed names to the engine."""

    @classmethod
    def setUpClass(cls):
        cls.runner = read(RUNNER)
        cls.client = read(ROOT / "app/src/main/java/com/manhwalens/app/translate/TranslationClient.kt")

    def test_client_signature_still_takes_names_second(self):
        # The runner's argument order must match the real client, not a guess.
        match = re.search(r"suspend fun translateBlock\(([^)]*)\)", self.client)
        self.assertIsNotNone(match)
        params = [p.strip() for p in match.group(1).split(",")]
        self.assertIn("glossaryNames", params[1], params)

    def test_runner_passes_names_positionally(self):
        # translateBlock(region, names, previous) -- names in the second position.
        self.assertRegex(
            self.runner,
            r"translateBlock\(\s*invocation\.region,\s*invocation\.glossaryNames,",
        )

    def test_invocation_carries_confirmed_names(self):
        self.assertIn("glossaryNames = config.names", self.runner)

    def test_only_confirmed_names_are_used(self):
        # translationNames() is the confirmed set; auto-suggestions must never be
        # pulled in through a different accessor.
        self.assertIn("glossary.translationNames()", self.runner)
        for forbidden in ("suggestedNames", "all()", "learnedNames"):
            self.assertNotIn(forbidden, self.runner, f"unconfirmed names reached the runner: {forbidden}")

    def test_fingerprint_covers_exactly_what_is_sent(self):
        # Corrections, readings and names are the three inputs; the fingerprint
        # must hash all three, or it cannot describe the configuration.
        for key in ("corrections", "readings", "names"):
            self.assertIn(key, self.runner)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\")", self.runner)

    def test_corpus_assets_are_pinned_to_the_reviewed_fingerprint(self):
        # Derived-from-the-file validation would agree with any edit; the app must
        # compare the asset against the value agreed in review.
        corpus = read(CORPUS)
        self.assertIn("EXPECTED_FINGERPRINT", corpus)
        self.assertIn(EXPECTED_FINGERPRINT, corpus)
        self.assertIn("engineFingerprint != EXPECTED_FINGERPRINT", corpus)

    def test_fingerprint_serialisation_is_deterministic(self):
        # Sorted iteration: an incidental map-order change must not alter the hash.
        self.assertIn("config.corrections.entries.sortedBy { it.key }", self.runner)
        self.assertIn("config.readings.entries.sortedBy { it.key }", self.runner)
        self.assertIn("config.names.sorted()", self.runner)

    def test_glossary_contents_are_not_written_to_the_record(self):
        # Only the hash is stored; the glossaries may hold personal terminology.
        self.assertNotIn("put(\"corrections\"", self.runner)
        self.assertNotIn("put(\"names\"", self.runner)


class BuildRevisionTest(unittest.TestCase):
    """Defect 4: GIT_REVISION was never supplied, so every run recorded `unknown`."""

    def test_gradle_validates_the_sha_shape(self):
        source = BUILD.read_text(encoding="utf-8")
        self.assertIn("[0-9a-f]{40}", source)
        self.assertIn("GIT_REVISION", source)

    def test_malformed_revision_fails_the_build(self):
        # Not a warning: a short SHA or branch name cannot identify a commit.
        source = BUILD.read_text(encoding="utf-8")
        self.assertIn("require(Regex", source)

    def test_candidate_workflow_supplies_the_full_sha(self):
        source = CANDIDATE_WF.read_text(encoding="utf-8")
        self.assertIn('GIT_REVISION=$revision', source)
        self.assertIn("git rev-parse HEAD", source)

    def test_candidate_refuses_to_build_without_a_revision(self):
        source = CANDIDATE_WF.read_text(encoding="utf-8")
        self.assertIn("--require-git-revision", source)

    def test_other_workflows_supply_a_revision(self):
        for path in (CI_WF, QUALITY_WF):
            self.assertIn("GIT_REVISION=$(git rev-parse HEAD)", path.read_text(encoding="utf-8"))

    def test_identity_report_is_checked_against_the_commit(self):
        source = CANDIDATE_WF.read_text(encoding="utf-8")
        self.assertIn("git_revision", source)
        self.assertIn("does not match the commit under test", source)

    def test_verifier_records_and_validates_the_revision(self):
        source = (ROOT / ".github/scripts/verify_release_apk.py").read_text(encoding="utf-8")
        self.assertIn("--git-revision", source)
        self.assertIn("git_revision=", source)
        self.assertIn("[0-9a-f]{40}", source)


class AtomicPersistenceTest(unittest.TestCase):
    """Defect 5: loadRun bypassed AtomicFile, so backup recovery never happened."""

    @classmethod
    def setUpClass(cls):
        cls.store = read(STORE)

    def test_reads_go_through_atomic_file(self):
        self.assertIn("AtomicFile(runFile(runId))", self.store)
        self.assertIn("file.openRead()", self.store)

    def test_raw_file_read_is_gone(self):
        # The bug was exactly this call.
        self.assertNotIn("runFile(runId).readText()", self.store)

    def test_corrupt_is_distinct_from_absent(self):
        self.assertIn("object Absent", self.store)
        self.assertIn("data class Corrupt", self.store)

    def test_writes_remain_atomic(self):
        self.assertIn("failWrite", self.store)
        self.assertIn("finishWrite", self.store)

    def test_run_ids_are_not_timestamp_only(self):
        self.assertIn("fun newRunId", self.store)
        self.assertIn("UUID.randomUUID()", self.store)
        # The bare timestamp form was the collision risk.
        self.assertNotIn('run-${System.currentTimeMillis()}"', self.store)

    def test_completed_results_are_not_overwritten_on_resume(self):
        runner = read(RUNNER)
        self.assertIn("isAttempt(prior.processingStatus)", runner)

    def test_failed_and_empty_are_attempts_not_misses(self):
        self.assertIn("status == STATE_OK || status == STATE_EMPTY || status == STATE_FAILED", self.store)

    def test_schema_bump_keeps_old_records_readable(self):
        self.assertIn("SCHEMA_VERSION = 2", self.store)
        # A missing scope field must stay null, never be reconstructed.
        self.assertIn('optJSONArray("requested_case_ids")', self.store)


class RunScopeTest(unittest.TestCase):
    """Defect 6: no stored scope, so a one-case run advertised 39 pending."""

    @classmethod
    def setUpClass(cls):
        cls.store = read(STORE)
        cls.runner = read(RUNNER)
        cls.screen = read(SCREEN)

    def test_scope_is_persisted(self):
        self.assertIn("requestedCaseIds", self.store)
        self.assertIn("requested_case_ids", self.store)
        self.assertIn("requested_case_count", self.store)

    def test_scope_is_recorded_when_the_run_starts(self):
        self.assertIn("requestedCaseIds = requested", self.runner)

    def test_resume_uses_the_stored_scope(self):
        self.assertIn("targets = scope", self.runner)

    def test_resume_never_widens_scope(self):
        self.assertIn("scope", self.runner)
        # A resume must not fall back to the full corpus.
        self.assertNotIn("pendingIn(runId, engineCases.map", self.screen)

    def test_unknown_scope_is_not_invented(self):
        self.assertIn("ResumeScopeUnknown", self.runner)
        self.assertIn("?: throw ResumeScopeUnknown", self.runner)

    def test_progress_counts_attempts_only(self):
        self.assertIn("isAttempt", self.runner)
        self.assertNotIn("var completed = existing.size", self.runner)

    def test_ui_separates_scope_from_coverage(self):
        self.assertIn("نطاق التشغيل", self.screen)
        self.assertIn("تغطية corpus", self.screen)

    def test_old_records_are_flagged_as_scope_unknown(self):
        self.assertIn("requestedCaseIds == null", self.screen)


class ResumeCompatibilityTest(unittest.TestCase):
    """Defect 7: results from different configurations could share one record."""

    @classmethod
    def setUpClass(cls):
        cls.runner = read(RUNNER)

    def test_every_identity_field_is_compared(self):
        for field in ("datasetVersion", "datasetFingerprint", "engineId", "engineRevision",
                      "appVersion", "versionCode", "configurationFingerprint"):
            self.assertIn(field, self.runner, f"{field} is not checked before resume")

    def test_mismatch_refuses_the_resume(self):
        self.assertIn("ResumeRejected", self.runner)

    def test_model_unavailable_run_is_not_silently_upgraded(self):
        self.assertIn("RUN_MODEL_UNAVAILABLE", self.runner)

    def test_rejection_keeps_the_old_run(self):
        # Refusal must not touch the record it refuses to continue.
        body = self.runner[self.runner.index("compatibilityProblem(previous"):]
        self.assertNotIn("store.saveCaseResult", body[:400])


class AssessmentBindingTest(unittest.TestCase):
    """Defect 8: assessments were keyed by case id alone."""

    @classmethod
    def setUpClass(cls):
        cls.store = read(STORE)
        cls.screen = read(SCREEN)

    def test_assessments_load_per_run(self):
        self.assertIn("fun loadAssessmentsForRun", self.store)
        self.assertIn("filter { it.runId == runId }", self.store)

    def test_export_uses_run_bound_assessments(self):
        self.assertIn("loadAssessmentsForRun(runId)", self.store)
        self.assertNotIn("loadCandidateAssessments().associateBy { it.caseId }", self.store)

    def test_legacy_unbound_assessments_are_preserved(self):
        self.assertIn("fun loadUnboundAssessments", self.store)
        self.assertIn("filter { it.runId == null }", self.store)

    def test_saving_requires_a_reviewer(self):
        self.assertIn("a reviewer name is required", self.store)

    def test_ui_reloads_when_the_run_changes(self):
        self.assertIn("LaunchedEffect(selectedRunId)", self.screen)
        self.assertIn("selectedRunId", self.screen)

    def test_draft_is_keyed_on_run_and_assessment(self):
        self.assertIn("remember(case.caseId, runId, assessment?.decidedAt", self.screen)

    def test_cannot_assess_a_missing_translation(self):
        self.assertIn("fun canAssess", self.screen)
        self.assertIn("STATE_OK", self.screen)

    def test_failed_and_empty_results_are_not_assessable(self):
        # Neither a failed nor an empty result has a translation to judge, so the
        # only assessable status is `ok` with non-blank output. Asserting the gate
        # is an equality (not an `isAttempt` membership) keeps `failed`/`empty` out.
        start = self.screen.index("private fun canAssess")
        body = self.screen[start:start + 400]
        self.assertIn("result.processingStatus == EvaluationStore.STATE_OK", body)
        self.assertIn("!result.finalTranslation.isNullOrBlank()", body)

    def test_one_criterion_change_leaves_the_others_alone(self):
        self.assertIn("fun with(key: String, value: String): AssessmentDraft", self.screen)


class RatingVocabularyTest(unittest.TestCase):
    """Defect 9: not_applicable was documented but not representable."""

    @classmethod
    def setUpClass(cls):
        cls.store = read(STORE)
        cls.screen = read(SCREEN)

    def test_vocabulary_is_explicit(self):
        self.assertIn('RATING_NOT_REVIEWED = "not_reviewed"', self.store)
        self.assertIn('RATING_NOT_APPLICABLE = "not_applicable"', self.store)

    def test_ratings_are_strings_not_nullable_ints(self):
        self.assertRegex(self.store, r"val meaning: String = RATING_NOT_REVIEWED")
        self.assertNotRegex(self.store, r"val meaning: Int\?")

    def test_legacy_integers_are_migrated(self):
        self.assertIn("is Int ->", self.store)
        self.assertIn("raw.toString()", self.store)

    def test_ui_offers_not_applicable(self):
        self.assertIn("RATING_NOT_APPLICABLE", self.screen)
        self.assertIn("RATING_OPTIONS", self.screen)
        self.assertIn("لا ينطبق", self.screen)

    def test_all_not_reviewed_cannot_be_saved_as_reviewed(self):
        self.assertIn("fun assessmentProblem", self.screen)
        self.assertIn("not_reviewed", self.screen)

    def test_serious_defect_requires_a_reason(self):
        self.assertIn('it == "0" || it == "1"', self.screen)

    def test_no_automatic_aggregate_score(self):
        for forbidden in ("averageScore", "overallScore", "qualityScore"):
            self.assertNotIn(forbidden, self.store)
            self.assertNotIn(forbidden, self.screen)


class ExportIntegrityTest(unittest.TestCase):
    """Defect 10: missing cases exported with an empty source text."""

    @classmethod
    def setUpClass(cls):
        cls.store = read(STORE)
        cls.runner = read(RUNNER)

    def test_export_takes_the_corpus_not_ids(self):
        self.assertIn("corpusCases: List<CorpusCase>", self.store)

    def test_missing_case_keeps_the_real_source_text(self):
        self.assertIn("sourceText = corpusCase.sourceText", self.store)
        self.assertNotIn('sourceText = "",', self.store)

    def test_all_four_states_stay_distinct(self):
        for state in ("STATE_OK", "STATE_EMPTY", "STATE_FAILED", "STATE_NOT_RUN", "STATE_MISSING"):
            self.assertIn(state, self.store)

    def test_scope_and_coverage_reported_separately(self):
        for key in ("requested_case_count", "attempted_count", "pending_case_count",
                    "out_of_scope_case_count", "missing_case_count"):
            self.assertIn(key, self.store)

    def test_export_claims_no_quality(self):
        self.assertIn('put("translation_quality_claimed", false)', self.store)
        self.assertIn('put("native_model_context", false)', self.store)

    def test_txt_includes_ratings_and_scope(self):
        self.assertIn("scope_known=", self.runner)
        # Every criterion is emitted, including "not_applicable" values, so an
        # inapplicable rating is never confused with an unjudged one.
        self.assertIn('"terminology", "completeness"', self.runner)
        self.assertIn('optString(it, EvaluationStore.RATING_NOT_REVIEWED)', self.runner)


class RunBrowserTest(unittest.TestCase):
    """Defect 11: the run list was loaded but never shown."""

    @classmethod
    def setUpClass(cls):
        cls.screen = read(SCREEN)

    def test_runs_are_rendered(self):
        self.assertIn("سجل التشغيلات السابقة", self.screen)
        self.assertRegex(self.screen, r"items\(runs")

    def test_a_past_run_can_be_opened_and_exported(self):
        self.assertIn("runner?.loadRun(summary.runId)", self.screen)
        self.assertIn("pendingExport = summary.runId", self.screen)

    def test_review_does_not_assume_the_latest_run(self):
        # It may default to the newest, but the choice must be visible and changeable.
        self.assertIn("selectedRunId = runs.firstOrNull()?.runId", self.screen)
        self.assertIn("اختر التشغيل الذي تريد تقييم ترجماته", self.screen)


class CorpusAssetIntegrityTest(unittest.TestCase):
    """Defect 13: nothing at runtime validated the asset pair before a run."""

    @classmethod
    def setUpClass(cls):
        cls.corpus = read(CORPUS)
        cls.runner = read(RUNNER)

    def test_runtime_validation_exists(self):
        self.assertIn("fun validate()", self.corpus)

    def test_run_refuses_inconsistent_assets(self):
        self.assertIn("corpus.validate()", self.runner)

    def test_engine_payload_is_checked_for_leaks(self):
        self.assertIn('setOf("case_id", "source_text")', self.corpus)
        self.assertIn("native_model_context", self.corpus)

    def test_canonical_corpus_is_intact(self):
        corpus = json.loads((ROOT / "evaluation/corpus/rc4-corpus.json").read_text(encoding="utf-8"))
        ids = [c["case_id"] for c in corpus["cases"]]
        self.assertEqual(40, len(ids))
        self.assertEqual(len(ids), len(set(ids)))
        for case in corpus["cases"]:
            self.assertTrue(case["source_text"].strip(), case["case_id"])
            self.assertIs(case["context_available_to_engine"], False, case["case_id"])
        self.assertEqual(EXPECTED_FINGERPRINT, corpus["fingerprint"])

    def test_assets_agree_with_the_corpus(self):
        engine = json.loads((ROOT / "app/src/main/assets/evaluation-engine-input.json").read_text(encoding="utf-8"))
        review = json.loads((ROOT / "app/src/main/assets/evaluation-human-review.json").read_text(encoding="utf-8"))
        self.assertEqual(40, len(engine["cases"]))
        self.assertEqual([c["case_id"] for c in engine["cases"]],
                         [c["case_id"] for c in review["cases"]])
        self.assertEqual(engine["dataset_fingerprint"], review["dataset_fingerprint"])
        for case in review["cases"]:
            self.assertIs(case["context_available_to_engine"], False, case["case_id"])


class ReleaseSafetyTest(unittest.TestCase):
    """Defect 12/14: an un-installable APK would force an uninstall and lose data."""

    @classmethod
    def setUpClass(cls):
        cls.workflow = CANDIDATE_WF.read_text(encoding="utf-8")
        cls.build = BUILD.read_text(encoding="utf-8")

    def test_prereleases_are_excluded_from_the_release_floor(self):
        self.assertIn("--exclude-pre-releases", self.workflow)

    def test_candidate_version_code_is_never_clamped_down(self):
        # The clamp could produce a code below the installed build, so the
        # executable line must be gone. Comments explaining the removal are fine.
        code = strip_comments(self.build)
        self.assertNotIn("coerceAtMost(appVersionCode)", code)
        self.assertIn("throw GradleException(", code)

    def test_candidate_name_is_unique_per_publish(self):
        # rc3 already shipped; a later candidate must not republish under its tag.
        self.assertIn("1.1.42-rc4", self.workflow)
        self.assertNotIn("rc-1.1.42-rc2", self.workflow)
        self.assertNotIn("'1.1.42-rc2'", self.workflow)

    def test_application_id_and_signing_are_untouched(self):
        self.assertIn('applicationId = "com.manhwalens.app"', self.build)
        self.assertIn("signingConfigs", self.build)


if __name__ == "__main__":
    unittest.main()
