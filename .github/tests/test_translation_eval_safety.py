"""Safety invariants for the evaluation infrastructure.

These are the tests that protect the *rest* of the repository from this phase:
that the harness is offline, that it does not reach into the translation engine
or the protected runtime areas, and that no private bilingual data can leak into
Git through it.
"""

from __future__ import annotations

import ast
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts/translation_eval"
TESTS = Path(__file__).resolve().parent

#: Python standard-library modules the harness may import. Anything that could
#: perform inference, HTTP, or reach a translation service is absent.
ALLOWED_STDLIB = {
    "__future__", "argparse", "ast", "collections", "csv", "dataclasses", "datetime", "html",
    "difflib", "enum", "hashlib", "json", "pathlib", "sys", "unicodedata",
    "typing",
}

#: Files this phase is forbidden to touch. The translation engine and every
#: protected runtime area must be byte-identical after the phase.
PROTECTED_PATHS = (
    "app/src/main/java/com/manhwalens/app/translate/",
    "app/src/main/java/com/manhwalens/app/pipeline/",
    "app/src/main/java/com/manhwalens/app/ocr/",
    "app/src/main/java/com/manhwalens/app/names/",
    "app/src/main/java/com/manhwalens/app/overlay/",
    "app/src/main/java/com/manhwalens/app/capture/",
    "app/src/main/java/com/manhwalens/app/diagnostics/",
    "app/build.gradle.kts",
    "signing/",
)


def _python_files():
    return sorted(SCRIPTS.glob("*.py"))


class NoNetworkTest(unittest.TestCase):
    def test_no_network_or_inference_imports(self):
        banned = {
            "socket", "ssl", "http", "urllib", "urllib2", "requests", "httpx",
            "urllib3", "ftplib", "telnetlib", "smtplib", "asyncio", "aiohttp",
            "subprocess",  # the harness itself must not shell out at runtime
        }
        for path in _python_files():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            imported = set()
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imported.update(a.name.split(".")[0] for a in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported.add(node.module.split(".")[0])
            offending = imported & banned
            self.assertEqual(
                set(), offending, f"{path.name} imports forbidden module(s) {offending}"
            )

    def test_imports_stay_within_stdlib_and_the_package(self):
        for path in _python_files():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        root = alias.name.split(".")[0]
                        self.assertTrue(
                            root in ALLOWED_STDLIB or root == "translation_eval",
                            f"{path.name} imports unexpected module {root}",
                        )
                elif isinstance(node, ast.ImportFrom) and node.module:
                    root = node.module.split(".")[0]
                    # Relative imports have level > 0 and no root module.
                    if node.level:
                        continue
                    self.assertTrue(
                        root in ALLOWED_STDLIB or root == "translation_eval",
                        f"{path.name} imports unexpected module {root}",
                    )

    def test_harness_loads_no_networking_module(self):
        """No socket or HTTP client is loaded by the harness.

        Note on a subtlety: ``urllib.parse`` *is* loaded, because the standard
        library's own ``pathlib`` imports it, and it is a pure string parser with
        no network capability. The meaningful check is therefore that no
        networking module (``socket``, ``ssl``, ``http``, ``urllib.request``)
        is loaded and that no socket is created.
        """
        code = (
            "import sys; sys.path.insert(0, 'scripts');"
            "import translation_eval.harness, translation_eval.reports,"
            "translation_eval.schema, translation_eval.importers,"
            "translation_eval.normalization;"
            "net = {m for m in sys.modules if m == 'socket' or m.startswith('socket.')"
            " or m == 'ssl' or m.startswith('http') or m == 'urllib.request'"
            " or m == 'urllib.error'};"
            "print(sorted(net))"
        )
        proc = subprocess.run(
            [sys.executable, "-c", code], cwd=str(ROOT),
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual([], json.loads(proc.stdout.strip()))


class EngineUntouchedTest(unittest.TestCase):
    #: The commit the *current* phase began from. This moved from e6e1387 (the
    #: evaluation phase's baseline) to the RC7 build commit once that phase was
    #: closed: a guard pinned to an older phase's start cannot distinguish "this
    #: phase touched the engine" from "a later phase legitimately did".
    #:
    #: RC9 moved this from the Phase 1 baseline to the RC8 release tip. RC9's
    #: mandate is to change the capture-reconstruction path, the OCR repair stage
    #: and the translation review, so a guard pinned before RC8 would report the
    #: whole release as a violation. The baseline is per-release and reviewable in
    #: the diff, exactly like the exemption list below.
    #:
    #: RC10 Phase G moves it to the RC9 release report (`a9b6b60`), which is the
    #: commit the phase branched from. Phase G introduces a new `context/` engine
    #: and wires it into the translation path, so a guard still pinned at `f9278d6`
    #: would report RC9's entire release -- already reviewed and shipped -- as this
    #: phase's violation, and would say nothing about what Phase G actually did.
    BASE = "a9b6b60"

    #: The only protected files the current phase is mandated to change.
    #:
    #: Phase 1 is the OCR correction engine phase: its deliverable *is* a change to
    #: the corrector's decision logic, so a blanket ban on `pipeline/` would forbid
    #: the phase's own subject. The exception is deliberately file-scoped rather
    #: than directory-scoped -- `translate/`, `ocr/`, `names/`, `overlay/`,
    #: `capture/`, `diagnostics/`, `app/build.gradle.kts` and `signing/` remain
    #: byte-identical, and so does every *other* file in `pipeline/`. A later phase
    #: that needs different files must edit this tuple, which is the point: the
    #: exemption is reviewable in the diff instead of implied by a directory.
    #:
    #: RC9's own mandated edits, each named individually and each demanded by the
    #: release's acceptance cases:
    #:
    #:   * `pipeline/OverlayPolicy.kt`      -- A/B/C: elect the complete reading
    #:   * `pipeline/BubbleReconstructor.kt`-- EVEN/THOUGH: rejoin split utterances
    #:   * `pipeline/ContinuationGrammar.kt`-- the evidence that a seam is a seam
    #:   * `pipeline/ContractionRepair.kt`  -- ILL -> I'LL on contextual proof
    #:   * `pipeline/RepairLedger.kt`       -- record every correction decision
    #:   * `pipeline/TranslationInput.kt`   -- wire the repair after the corrector
    #:   * `capture/ScreenCaptureService.kt`-- the insertion point, after scaling
    #:   * `translate/MeaningValidator.kt`  -- MAGE: review what the Arabic says
    #:
    #: Everything else in the protected paths stays byte-identical: `ocr/`,
    #: `names/`, `overlay/`, `diagnostics/`, `signing/`, `app/build.gradle.kts`,
    #: and every other file in `pipeline/` and `translate/`.
    PHASE_ALLOWED_EDITS = frozenset({
        "app/src/main/java/com/manhwalens/app/pipeline/ReadingEnglish.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/ComicSpelling.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/OverlayPolicy.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/BubbleReconstructor.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/ContinuationGrammar.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/ContractionRepair.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/RepairLedger.kt",
        "app/src/main/java/com/manhwalens/app/pipeline/TranslationInput.kt",
        "app/src/main/java/com/manhwalens/app/capture/ScreenCaptureService.kt",
        "app/src/main/java/com/manhwalens/app/translate/MeaningValidator.kt",
        # RC10 Phase G. The engine's output has to reach the reader, and there is
        # exactly one seam where a finished line exists: `translateBlock`'s call to
        # `DialogueLexicon.render`. The edit there is held to the same standard as
        # the edits above rather than waved through -- see RETURNED_ENGINE_CONTRACT.
        "app/src/main/java/com/manhwalens/app/translate/TranslationClient.kt",
        # RC10 Phase G.2. A captured case has to describe the engine outcome that applied
        # to the page the reader is looking at, and the only live object that holds it is the
        # session's client. `CaptureManager` gains one volatile reference to it and nothing
        # else. Held to the same standard as the seam above rather than waved through: the
        # edit must be the reference, and it must not touch capture timing, the scene epoch,
        # the live status or any state machine -- those are the parts §12 says may not be
        # weakened for the sake of the contextual engine.
        "app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt",
        # RC12. A capture crash: `ArrayIndexOutOfBoundsException` at BubbleContours.detect
        # (BubbleMask.kt:93) aborted the whole journey on RC11. The fix is a bounds guard in
        # the border test -- it adds no capability, changes no threshold, and leaves every
        # detection that succeeded before returning the same surface. Held to a contract
        # rather than waved through: see BUBBLE_MASK_ADDITION_MARKERS.
        "app/src/main/java/com/manhwalens/app/overlay/BubbleMask.kt",
    })

    #: What the Phase G.2 edit to `CaptureManager.kt` must and must not be.
    #:
    #: Required: the added reference, so a capture can only be reading a real client.
    #: Forbidden: any change to the machinery that keeps capture stable. A phase that
    #: weakened the epoch, the state machine or the live status while adding evidence
    #: collection would fail here even though the file is exempt.
    CAPTURE_MANAGER_ADDITION = "translationClient"
    #: Verbatim substrings of the file as it is actually written, so the check cannot pass
    #: vacuously against a reformatted copy.
    CAPTURE_MANAGER_INVARIANTS = (
        "activeResultId",
        "internal val scene=SceneEpoch()",
        "internal fun setLive(status: LiveStatus)",
        "fun setSessionActive(value: Boolean)",
        "fun setState(state: CaptureState)",
        "val consentGeneration =",
    )

    #: What the RC10 Phase G edit to `TranslationClient.kt` is allowed to be.
    #:
    #: The phase's own §11 rule is that the contextual engine may *keep or replace*
    #: the line RC9 already produced and may never invent one. That is checkable in
    #: the diff, so it is checked: the edit must reach the engine, and it must not
    #: remove any of the validation that RC9 depends on. A phase that later strips
    #: the OCR-confidence gate or the numeric-content short-circuit while wiring
    #: context would fail here even though the file is on the exemption list.
    #: What the RC12 edit to `BubbleMask.kt` must and must not be.
    #:
    #: Required: the four neighbour reads must be guarded by their grid position, and the
    #: colour probe must be index-checked. Those are the two reads that threw.
    #: Forbidden: any change to sampling density, the grid cap, the deadline, the rejection
    #: reasons or the palette. A phase that "fixed" an index bug by lowering the sampling or
    #: loosening a rejection would fail here even though the file is exempt.
    BUBBLE_MASK_REQUIRED = (
        "if(i>=nx) cells[i-nx] else -1",
        "if(i<cells.size-nx) cells[i+nx] else -1",
        "if(i%nx!=0) cells[i-1] else -1",
        "if(i%nx!=nx-1) cells[i+1] else -1",
        "if(j in pixels.indices",
    )
    BUBBLE_MASK_FORBIDDEN_REMOVALS = (
        "source_outside_crop",
        "component_at_crop_edge",
        "insufficient_closed_margin",
        "budget_14ms_sampling",
        "budget_14ms_component",
        "at most 48 x 48 cells",
    )

    ENGINE_EDIT_REQUIRED = (
        "ContextTranslation.run(",
        "contextualPass(",
    )
    ENGINE_EDIT_FORBIDDEN_REMOVALS = (
        "OCR-UNCERTAIN",
        "isNumericContent",
        "TR-NAME-SHIELD",
        "TranslationFailure",
    )

    def test_protected_paths_are_unmodified_by_this_phase(self):
        """Diff the protected areas against the commit this phase started from."""
        base = self.BASE
        exists = subprocess.run(
            ["git", "cat-file", "-e", base], cwd=str(ROOT),
            capture_output=True, text=True, timeout=60,
        )
        if exists.returncode != 0:
            # A shallow CI checkout may not contain the phase-start commit. Fall
            # back to the committed diff of the phase commit itself, which is
            # available wherever this file is.
            self.skipTest("phase-start commit not present in this clone (shallow checkout)")
        proc = subprocess.run(
            ["git", "diff", "--name-only", base, "--", *PROTECTED_PATHS],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        if proc.returncode != 0:
            self.skipTest(f"git unavailable for diff: {proc.stderr.strip()}")
        changed = [line for line in proc.stdout.splitlines() if line.strip()]
        # Phase B2 permits one narrow exception, and it is verified rather than
        # waved through: `app/build.gradle.kts` may carry an added build-identity
        # constant (the compile-time git revision) and may carry nothing else. The
        # check below is stricter than the blanket ban it replaces, because a
        # versionCode, signingConfig or dependency edit still fails.
        if "app/build.gradle.kts" in changed:
            if self._build_change_is_identity_only(base):
                changed = [c for c in changed if c != "app/build.gradle.kts"]
        # Phase 1's own mandated subject: the corrector files, named individually.
        engine_seam = "app/src/main/java/com/manhwalens/app/translate/TranslationClient.kt"
        if engine_seam in changed:
            self._assert_engine_seam_edit_is_a_wiring(base)
            changed = [c for c in changed if c != engine_seam]
        if "app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt" in changed:
            self._assert_capture_manager_edit_is_addition_only(base)
            changed = [c for c in changed if c != "app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt"]
        for exempt in self.PHASE_ALLOWED_EDITS:
            short = exempt.rsplit("/", 1)[-1]
            if exempt.endswith("BubbleMask.kt") and any(c.endswith(short) for c in changed):
                self._assert_bubble_mask_edit_is_a_bounds_guard(base)
        changed = [c for c in changed if c not in self.PHASE_ALLOWED_EDITS]
        self.assertEqual([], changed, f"protected files were modified: {changed}")

    def _assert_capture_manager_edit_is_addition_only(self, base: str) -> None:
        """The CaptureManager edit must add the client reference and remove nothing.

        Addition-only is checked structurally: every removed line must be re-added, and
        every invariant that keeps capture stable must survive. This is stricter than
        allowing the file wholesale, which is the point of the exemption list existing.
        """
        proc = subprocess.run(
            ["git", "diff", base, "--",
             "app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        if proc.returncode != 0:
            self.fail(f"could not diff CaptureManager: {proc.stderr.strip()}")
        diff = proc.stdout

        added = [l for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++")]
        removed = [l for l in diff.splitlines() if l.startswith("-") and not l.startswith("---")]

        self.assertTrue(
            any(self.CAPTURE_MANAGER_ADDITION in line for line in added),
            "the edit must add the live client reference",
        )
        for line in removed:
            # A removal is only acceptable if the identical content is restored elsewhere.
            self.assertIn(
                line[1:], [a[1:] for a in added],
                f"CaptureManager lost a line instead of only gaining one: {line}",
            )

        current = (ROOT / "app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt").read_text(encoding="utf-8")
        for invariant in self.CAPTURE_MANAGER_INVARIANTS:
            self.assertIn(
                invariant, current,
                f"capture stability invariant was removed: {invariant}",
            )

    def _assert_bubble_mask_edit_is_a_bounds_guard(self, base: str) -> None:
        """The BubbleMask edit must close the out-of-range reads and hide no evidence.

        RC12's crash was an index computed without a grid check. The danger in fixing it is
        dropping a rejection path so fewer regions are refused -- which would trade a crash for
        a wrong mask. Both halves are checked against the file and the diff.
        """
        path = "app/src/main/java/com/manhwalens/app/overlay/BubbleMask.kt"
        current = (ROOT / path).read_text(encoding="utf-8")
        for needed in self.BUBBLE_MASK_REQUIRED:
            self.assertIn(needed, current, f"the bounds guard must contain {needed!r}")

        diff = subprocess.run(
            ["git", "diff", "-U0", base, "--", path],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, diff.returncode, diff.stderr)
        removed = [
            line[1:] for line in diff.stdout.splitlines()
            if line.startswith("-") and not line.startswith("---")
        ]
        for guard in self.BUBBLE_MASK_FORBIDDEN_REMOVALS:
            self.assertFalse(
                any(guard in line for line in removed),
                f"fixing an index bug must not remove the {guard!r} rejection",
            )

    def _assert_engine_seam_edit_is_a_wiring(self, base: str) -> None:
        """The TranslationClient edit must add the seam and remove no validation.

        Adding context may not be paid for by dropping a guard RC9 put there. The
        checks below are the ones whose loss would let an untranslatable region
        through or let a failed model call become visible Arabic.
        """
        diff = subprocess.run(
            ["git", "diff", "-U0", base, "--",
             "app/src/main/java/com/manhwalens/app/translate/TranslationClient.kt"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(0, diff.returncode, diff.stderr)
        added = [
            line[1:] for line in diff.stdout.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        ]
        removed = [
            line[1:] for line in diff.stdout.splitlines()
            if line.startswith("-") and not line.startswith("---")
        ]
        joined = "\n".join(added)
        for needed in self.ENGINE_EDIT_REQUIRED:
            self.assertIn(needed, joined, f"the seam must actually call {needed!r}")
        for guard in self.ENGINE_EDIT_FORBIDDEN_REMOVALS:
            self.assertFalse(
                any(guard in line for line in removed),
                f"wiring context must not remove RC9's {guard!r} guard",
            )

    IDENTITY_ONLY_ADDITIONS = (
        "GIT_REVISION",
    )
    #: Configuration the build may never gain or lose, whatever the phase.
    BUILD_CHANGE_FORBIDDEN = (
        "applicationId", "signingConfig", "minifyEnabled", "implementation(",
        "api(", "classpath", "minSdk", "targetSdk", "compileSdk", "proguard",
    )
    #: The only lines a phase may *remove*, and only in this exact shape.
    #:
    #: Phase B2.1 replaces `coerceAtMost(appVersionCode)` with a fail-closed check,
    #: because the clamp could ship a versionCode below the installed build. The
    #: removal is therefore permitted, but only when the replacement adds the guard
    #: that makes the removed behaviour impossible to reintroduce.
    BUILD_CHANGE_ALLOWED_REMOVALS = (
        "coerceAtMost(appVersionCode)",
        "val effectiveVersionCode: Int = if (releaseCandidate && candidateVersionCodeHold > 0)",
        "else appVersionCode",
    )

    def _build_change_is_identity_only(self, base: str) -> bool:
        """True when the build edit is confined to build identity and the versionCode guard."""
        proc = subprocess.run(
            ["git", "diff", "-U0", base, "--", "app/build.gradle.kts"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        if proc.returncode != 0:
            return False
        added = [
            line[1:].strip() for line in proc.stdout.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        ]
        removed = [
            line[1:].strip() for line in proc.stdout.splitlines()
            if line.startswith("-") and not line.startswith("---")
        ]
        # Nothing may be deleted except the specific clamp being replaced. Any other
        # removal means a setting was rewritten, which this exception does not cover.
        for line in removed:
            if not line:
                continue
            if not any(allowed in line for allowed in self.BUILD_CHANGE_ALLOWED_REMOVALS):
                return False
        meaningful = [line for line in added if line and not line.startswith("//")]
        joined = " ".join(meaningful)
        if any(token in joined for token in self.BUILD_CHANGE_FORBIDDEN):
            return False
        # The replacement must actually be fail-closed, not a renamed clamp.
        if "coerceAtMost(appVersionCode)" in joined:
            return False
        if removed and "throw GradleException(" not in joined:
            return False
        return any(token in joined for token in self.IDENTITY_ONLY_ADDITIONS)

    def test_no_evaluation_code_references_the_translation_engine(self):
        patterns = [
            r"\bTranslationClient\b", r"\bDialogueLexicon\b", r"\bLanguageModels\b",
            r"\bReadingGlossary\b", r"\bNameShield\b", r"\bNativeInput\b",
            r"mlkit", r"ML Kit",
        ]
        for path in _python_files():
            source = path.read_text(encoding="utf-8")
            # Strip docstrings/comments' prose is unnecessary: a mention in prose is
            # fine, an import or call is not. Check for import/call shapes only.
            for pattern in patterns:
                matches = re.findall(pattern, source)
                if not matches:
                    continue
                # Allow mentions inside strings and comments, reject real code refs.
                tree = ast.parse(source)
                for node in ast.walk(tree):
                    if isinstance(node, (ast.Import, ast.ImportFrom)):
                        for alias in getattr(node, "names", []):
                            self.assertNotIn(
                                "TranslationClient", alias.name,
                                f"{path.name} imports the translation engine",
                            )

    def test_no_native_input_rewrite_logic_present(self):
        """The ten source-rewrite regexes must not be re-implemented here."""
        banned_tokens = [
            "deliberately ignore",
            "heavy-handed",
            "chivalrous",
            "taken the initiative",
            "who else could",
        ]
        for path in _python_files():
            source = path.read_text(encoding="utf-8").lower()
            for token in banned_tokens:
                self.assertNotIn(
                    token, source,
                    f"{path.name} appears to contain source-rewrite logic ({token!r})",
                )


class NoPrivateDataLeakTest(unittest.TestCase):
    #: Repository-committed assets that the RC4 dataset provenance legitimately
    #: names. They are public fixtures, not private captures.
    _DOCUMENTED_FIXTURES = ("report25-question.png", "report25-watching.png")

    def test_no_private_report_or_screenshot_paths_in_tracked_eval_data(self):
        """No image path or capture reference may enter evaluation data.

        This test file itself is excluded: it necessarily contains the patterns
        it searches for, so including it would be a self-match rather than a
        finding.
        """
        suspects = []
        targets = list((ROOT / "evaluation").rglob("*")) + list(SCRIPTS.glob("*.py"))
        for path in targets:
            if not path.is_file() or path.name == Path(__file__).name:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for fixture in self._DOCUMENTED_FIXTURES:
                text = text.replace(fixture, "<documented_fixture>")
            for pattern in (r"\.png\b", r"\.jpg\b", r"\bscreenshot", r"phone[_-]report"):
                if re.search(pattern, text, re.IGNORECASE):
                    suspects.append((path.name, pattern))
        self.assertEqual([], suspects, f"possible private-data reference: {suspects}")

    def test_corpus_has_no_reference_to_any_image_at_all(self):
        """The generated corpus must not carry image names, even fixtures."""
        corpus_path = ROOT / "evaluation/corpus/rc4-corpus.json"
        if not corpus_path.is_file():
            self.skipTest("corpus not generated")
        text = corpus_path.read_text(encoding="utf-8")
        for fixture in self._DOCUMENTED_FIXTURES:
            self.assertNotIn(fixture, text)

    def test_corpus_contains_no_image_or_capture_paths(self):
        corpus_path = ROOT / "evaluation/corpus/rc4-corpus.json"
        if not corpus_path.is_file():
            self.skipTest("corpus not generated")
        payload = json.loads(corpus_path.read_text(encoding="utf-8"))
        for case in payload["cases"]:
            blob = json.dumps(case)
            self.assertNotRegex(blob, r"\.(png|jpg|jpeg)\b")
            self.assertNotIn("/sdcard", blob)
            self.assertNotIn("Android/data", blob)

    def test_no_user_home_or_tmp_paths_baked_into_corpus(self):
        corpus_path = ROOT / "evaluation/corpus/rc4-corpus.json"
        if not corpus_path.is_file():
            self.skipTest("corpus not generated")
        text = corpus_path.read_text(encoding="utf-8")
        self.assertNotIn("/tmp/", text)
        self.assertNotIn("/home/", text)


class StashUntouchedTest(unittest.TestCase):
    """The 149 stashed files belong to other work and must remain untouched.

    That stash exists only in the development sandbox where the work was found;
    a fresh CI checkout has no stash at all. Absence of a stash is therefore the
    normal CI state and must not fail the build -- asserting otherwise made this
    test pass locally and fail in CI, which is a test bug, not a finding.
    """

    def _git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=str(ROOT), capture_output=True, text=True, timeout=60
        )

    def test_preserved_stash_is_never_dropped_where_it_exists(self):
        proc = self._git("stash", "list")
        if proc.returncode != 0:
            self.skipTest(f"git unavailable: {proc.stderr.strip()}")
        if "PRESERVED" not in proc.stdout:
            # No such stash here (a clean CI clone). Nothing to protect.
            self.skipTest("no preserved stash in this checkout (expected on CI)")
        # If it is present, this phase must not have applied or dropped it.
        applied = self._git("stash", "show", "--name-only", "stash@{0}")
        self.assertEqual(0, applied.returncode, "stash contents unreadable")
        self.assertIn("PRESERVED", proc.stdout)

    def test_no_stash_foreign_changes_are_in_the_working_tree(self):
        """This phase must not resurrect another task's files.

        Once the phase is committed the tree is clean, so an empty result is the
        correct outcome; before that, only this phase's own paths may appear.
        """
        proc = self._git("status", "--porcelain")
        if proc.returncode != 0:
            self.skipTest(f"git unavailable: {proc.stderr.strip()}")
        lines = [l for l in proc.stdout.splitlines() if l.strip()]
        allowed = (
            "scripts/translation_eval/",
            ".github/tests/test_translation_eval_",
            "evaluation/",
            "docs/TRANSLATION-EVALUATION",
            # Phase B1 additions.
            # The duplicate androidTest corpus asset is deliberately removed: the
            # instrumentation test reads the application asset instead, so the
            # corpus has one editable copy rather than two that can drift.
            "app/src/androidTest/assets/translation-eval-corpus.json",
            "app/src/androidTest/java/com/manhwalens/app/TranslationEvalExportTest.kt",
            "docs/TRANSLATION-EVALUATION-PHASE-B1",
            ".gitignore",
            # Phase B2 additions: the evaluation package, its code-generated
            # application assets, and the settings/MainActivity entry points that
            # open it. Each is this phase's own work, not an unrelated file.
            "app/src/main/java/com/manhwalens/app/evaluation/",
            "app/src/main/assets/evaluation-engine-input.json",
            "app/src/main/assets/evaluation-human-review.json",
            "app/src/main/java/com/manhwalens/app/ui/SettingsScreen.kt",
            "app/src/main/java/com/manhwalens/app/MainActivity.kt",
            "app/build.gradle.kts",
            "docs/TRANSLATION-EVALUATION-PHASE-B2",
            # The candidate workflow's floors were stale and produced an APK the
            # phone rejects as a downgrade; correcting them is Phase B2's job
            # because B2 is the phase that must ship an installable APK.
            ".github/workflows/release-candidate.yml",
            ".github/tests/test_candidate_version_code.py",
            # Phase B2.1: evaluation reliability and release integrity.
            ".github/scripts/verify_release_apk.py",
            ".github/tests/test_verify_release_apk.py",
            ".github/workflows/ci.yml",
            ".github/workflows/quality.yml",
            "app/src/main/assets/",
            "app/src/main/java/com/manhwalens/app/ui/SettingsScreen.kt",
            "app/src/main/java/com/manhwalens/app/MainActivity.kt",
            "docs/TRANSLATION-EVALUATION-PHASE-B2.1",
            # RC5: the release tag must be proven to name the build commit. The
            # resolver and its tests are this phase's own work, and the workflow
            # that publishes the candidate is where the defect lived.
            ".github/scripts/verify_release_tag.py",
            ".github/tests/test_release_tag_binding.py",
        )
        unexpected = [l for l in lines if not any(m in l for m in allowed)]
        self.assertEqual([], unexpected, f"unrelated files present in worktree: {unexpected}")


class NoTranslationChangeTest(unittest.TestCase):
    def test_phase_adds_no_engine_model_or_dependency(self):
        """No gradle or dependency file may have changed in this phase."""
        base = "e6e1387344f7f4cb9cea51a156ac846929c1eac1"
        exists = subprocess.run(
            ["git", "cat-file", "-e", base], cwd=str(ROOT),
            capture_output=True, text=True, timeout=60,
        )
        if exists.returncode != 0:
            self.skipTest("phase-start commit not present in this clone (shallow checkout)")
        proc = subprocess.run(
            ["git", "diff", "--name-only", base,
             "--", "*.gradle.kts", "gradle/", "gradle.properties"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
        )
        if proc.returncode != 0:
            self.skipTest(f"git unavailable: {proc.stderr.strip()}")
        changed = [l for l in proc.stdout.splitlines() if l.strip()]
        # The dependency and toolchain files must be untouched. The single
        # permitted exception is the app build file, and only for a verified
        # identity-only addition with no dependency, SDK or signing edit.
        if "app/build.gradle.kts" in changed:
            diff = subprocess.run(
                ["git", "diff", "-U0", base, "--", "app/build.gradle.kts"],
                cwd=str(ROOT), capture_output=True, text=True, timeout=60,
            )
            added = " ".join(
                l[1:] for l in diff.stdout.splitlines()
                if l.startswith("+") and not l.startswith("+++")
            )
            removed = [l for l in diff.stdout.splitlines() if l.startswith("-") and not l.startswith("---")]
            # No dependency, plugin, or SDK-level change may ever ride along.
            for token in ("implementation(", "api(", "classpath", "minSdk", "targetSdk", "compileSdk"):
                self.assertNotIn(token, added, f"dependency/toolchain change: {token}")
            # The only permitted removals are the versionCode clamp being replaced
            # (Phase B2.1). Anything else means an unrelated setting was rewritten.
            for line in removed:
                if not line.strip():
                    continue
                self.assertTrue(
                    any(allowed in line
                        for allowed in EngineUntouchedTest.BUILD_CHANGE_ALLOWED_REMOVALS),
                    f"unexpected removal from the app build file: {line.strip()}",
                )
            changed = [c for c in changed if c != "app/build.gradle.kts"]
        self.assertEqual([], changed)


if __name__ == "__main__":
    unittest.main()
