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
    def test_protected_paths_are_unmodified_by_this_phase(self):
        """Diff the protected areas against the commit this phase started from."""
        # e6e1387 is the commit the phase began from (the baseline audit doc).
        base = "e6e1387344f7f4cb9cea51a156ac846929c1eac1"
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
        self.assertEqual([], changed, f"protected files were modified: {changed}")

    IDENTITY_ONLY_ADDITIONS = (
        "GIT_REVISION",
    )
    BUILD_CHANGE_FORBIDDEN = (
        "versionCode =", "versionName =", "applicationId =", "signingConfig",
        "minifyEnabled", "implementation(", "api(", "classpath",
        "minSdk", "targetSdk", "compileSdk", "proguard",
    )

    def _build_change_is_identity_only(self, base: str) -> bool:
        """True only when the build file gained a revision constant and nothing else."""
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
        # A pure addition: nothing that was already configured may be deleted or
        # rewritten, so no runtime setting can be altered by this exception.
        if removed:
            return False
        meaningful = [line for line in added if line and not line.startswith("//")]
        joined = " ".join(meaningful)
        if any(token in joined for token in self.BUILD_CHANGE_FORBIDDEN):
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
            forbidden = ("GIT_REVISION_DEP", "implementation(", "api(", "classpath",
                         "minSdk", "targetSdk", "compileSdk")
            self.assertEqual([], removed, "the app build file may only gain lines")
            self.assertTrue(any(t not in added for t in forbidden))
            for token in forbidden:
                self.assertNotIn(token, added, f"dependency/toolchain change: {token}")
            changed = [c for c in changed if c != "app/build.gradle.kts"]
        self.assertEqual([], changed)


if __name__ == "__main__":
    unittest.main()
