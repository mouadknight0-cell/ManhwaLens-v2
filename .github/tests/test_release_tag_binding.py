"""Regression tests for B2.1 RC5: releasing a signed APK under a tag that names it.

The failure this guards against already happened. `rc-1.1.42-rc4`'s tag resolved to
`360a2b16` -- a merge commit on `main` from two days earlier -- while the APK attached
to that release recorded `756c3ee` as its build commit. Every existing check passed,
because none of them asked what the tag pointed at.

Two independent things are tested here:

  * the workflow text, so the `--target` and fallback defects cannot come back;
  * the tag-resolution logic against a fake Git API, because resolving an annotated
    tag and resolving a lightweight one are different code paths and only one of them
    was ever exercised by a real release.
"""
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github' / 'workflows' / 'release-candidate.yml'
SCRIPT = ROOT / '.github' / 'scripts' / 'verify_release_tag.py'

BUILD = '756c3ee08b7f3c39b6841ecf694dce90f0c69e3f'
OTHER = '360a2b1638eb36da07aad3d1bb4f1bcbdc5ccdf1'


def _load_script():
    spec = importlib.util.spec_from_file_location('verify_release_tag', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class FakeApi:
    """Minimal stand-in for ``gh api`` that serves a fixed object graph."""

    def __init__(self, tags: dict[str, dict], tag_objects: dict[str, dict] | None = None):
        self.tags = tags
        self.tag_objects = tag_objects or {}
        self.calls: list[str] = []

    #: The script's own error type, so a fake API fails the way the real one does.
    #: Set by each test class from the module it loaded.
    error: type = RuntimeError

    def __call__(self, path: str) -> dict:
        self.calls.append(path)
        prefix = 'repos/{owner}/{repo}/git/ref/tags/'
        if path.startswith(prefix):
            name = path[len(prefix):]
            if name not in self.tags:
                raise self.error(f'gh api {path} failed: Not Found')
            return {'ref': f'refs/tags/{name}', 'object': self.tags[name]}
        tag_prefix = 'repos/{owner}/{repo}/git/tags/'
        if path.startswith(tag_prefix):
            sha = path[len(tag_prefix):]
            if sha not in self.tag_objects:
                raise self.error(f'gh api {path} failed: Not Found')
            return self.tag_objects[sha]
        raise self.error(f'unexpected path {path}')


def _lightweight(sha: str) -> dict:
    return {'sha': sha, 'type': 'commit'}


def _annotated(tag_object_sha: str) -> dict:
    return {'sha': tag_object_sha, 'type': 'tag'}


def _tag_object(target_sha: str, target_type: str = 'commit') -> dict:
    """A git tag object as the API returns it: the target is nested under `object`."""
    return {'tag': 'rc-x', 'object': {'sha': target_sha, 'type': target_type}}


class TagResolutionTest(unittest.TestCase):
    """Defect 3: annotated tags must be dereferenced; lightweight ones read directly."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_script()
        FakeApi.error = cls.mod.TagError

    def test_lightweight_tag_resolves_to_its_commit(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        resolution = self.mod.resolve_tag_commit('rc-x', api=api)
        self.assertEqual(BUILD, resolution.commit)
        self.assertEqual('lightweight', resolution.kind)
        # A lightweight tag needs exactly one API call: the ref itself.
        self.assertEqual(1, len(api.calls))

    def test_annotated_tag_is_dereferenced_to_its_commit(self):
        api = FakeApi(
            {'rc-x': _annotated('aaaa')},
            {'aaaa': _tag_object(BUILD)},
        )
        resolution = self.mod.resolve_tag_commit('rc-x', api=api)
        self.assertEqual(BUILD, resolution.commit)
        self.assertEqual('annotated', resolution.kind)
        # Ref, then the tag object: the second call is what makes dereferencing real.
        self.assertEqual(2, len(api.calls))
        self.assertIn('/git/tags/aaaa', api.calls[1])

    def test_nested_annotated_tags_are_followed(self):
        api = FakeApi(
            {'rc-x': _annotated('aaaa')},
            {'aaaa': _tag_object('bbbb', 'tag'), 'bbbb': _tag_object(BUILD)},
        )
        resolution = self.mod.resolve_tag_commit('rc-x', api=api)
        self.assertEqual(BUILD, resolution.commit)
        self.assertEqual('annotated', resolution.kind)

    def test_a_tag_pointing_at_a_tree_is_refused(self):
        api = FakeApi({'rc-x': {'sha': 'cccc', 'type': 'tree'}})
        with self.assertRaises(self.mod.TagError):
            self.mod.resolve_tag_commit('rc-x', api=api)

    def test_missing_tag_is_reported_not_guessed(self):
        api = FakeApi({})
        with self.assertRaises(self.mod.TagError):
            self.mod.resolve_tag_commit('rc-absent', api=api)

    def test_refs_prefix_is_accepted(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        resolution = self.mod.resolve_tag_commit('refs/tags/rc-x', api=api)
        self.assertEqual(BUILD, resolution.commit)


class TagMismatchTest(unittest.TestCase):
    """Defect 1: rc4's tag named different code from its APK, and nothing noticed."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_script()
        FakeApi.error = cls.mod.TagError

    def test_tag_on_the_wrong_commit_is_rejected(self):
        api = FakeApi({'rc-1.1.42-rc4': _lightweight(OTHER)})
        with self.assertRaises(self.mod.TagError) as caught:
            self.mod.assert_tag_matches('rc-1.1.42-rc4', BUILD, api=api)
        # The message must name both SHAs, or the operator cannot see what went wrong.
        self.assertIn(OTHER, str(caught.exception))
        self.assertIn(BUILD, str(caught.exception))

    def test_annotated_tag_on_the_wrong_commit_is_rejected(self):
        api = FakeApi({'rc-x': _annotated('aaaa')}, {'aaaa': _tag_object(OTHER)})
        with self.assertRaises(self.mod.TagError):
            self.mod.assert_tag_matches('rc-x', BUILD, api=api)

    def test_tag_on_the_right_commit_passes(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        resolution = self.mod.assert_tag_matches('rc-x', BUILD, api=api)
        self.assertEqual(BUILD, resolution.commit)

    def test_abbreviated_expected_sha_is_never_accepted(self):
        # A short SHA does not identify a commit, so it cannot prove a tag matches.
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        with self.assertRaises(self.mod.TagError):
            self.mod.assert_tag_matches('rc-x', BUILD[:7], api=api)

    def test_expected_sha_is_compared_case_insensitively(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        self.assertEqual(BUILD, self.mod.assert_tag_matches('rc-x', BUILD.upper(), api=api).commit)


class WorkflowFallbackTest(unittest.TestCase):
    """Defect 4: push fallbacks were lower than the dispatch defaults."""

    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding='utf-8')

    def _default(self, field: str) -> str:
        # Fields are declared with description/required/default in any order, so the
        # default is found by scanning forward from the field name to the next default.
        match = re.search(rf'{field}:[^\n]*\n((?:\s+[A-Za-z_]+:.*\n)+?)\s+default: (.+)', self.text)
        assert match, f'no default for {field}'
        return match.group(2).strip().strip("'")

    def _fallback(self, key: str) -> str:
        match = re.search(rf"{key}: \$\{{\{{ inputs\.\w+ \|\| '([^']+)' \}}\}}", self.text)
        assert match, f'no fallback for {key}'
        return match.group(1)

    def test_push_fallbacks_equal_the_dispatch_defaults(self):
        # A push-triggered run supplies no inputs, so the fallback *is* the value used.
        # When the two disagree, the push path silently builds against an older floor.
        pairs = [
            ('version_name', 'VERSION_NAME'),
            ('installed_version_code', 'INSTALLED_VERSION_CODE'),
            ('min_installed_version_code', 'MIN_INSTALLED_VERSION_CODE'),
        ]
        for field, key in pairs:
            with self.subTest(field=field):
                self.assertEqual(self._default(field), self._fallback(key))

    def test_floors_clear_the_published_candidate(self):
        # rc4 shipped 217442876, so anything at or below it is a downgrade.
        for key in ('INSTALLED_VERSION_CODE', 'MIN_INSTALLED_VERSION_CODE'):
            with self.subTest(key=key):
                self.assertGreaterEqual(int(self._fallback(key)), 217442876)

    def test_candidate_name_is_a_new_unused_rc10(self):
        # RC10: rc9 is now published, so its tag is taken and a default naming it could only
        # ever fail the "tag already taken" guard. The pin advances with the release, which is
        # what this test exists to force: a stale default is a broken release job.
        self.assertEqual('1.1.42-rc10', self._default('version_name'))
        self.assertEqual('1.1.42-rc10', self._fallback('VERSION_NAME'))
        # rc2-rc9 are published; their tags must not be reused. Only the *resolved*
        # name is checked -- history is allowed to mention earlier candidates.
        for taken in ('rc2', 'rc3', 'rc4', 'rc5', 'rc6', 'rc7', 'rc8', 'rc9'):
            self.assertNotIn(f"|| '1.1.42-{taken}'", self.text)
        self.assertNotIn("default: 1.1.42-rc9", self.text)

    def test_floors_clear_the_published_rc7(self):
        # rc7 shipped 217442877, so a candidate must be strictly above it to install.
        self.assertGreaterEqual(int(self._fallback('INSTALLED_VERSION_CODE')), 217442877)
        self.assertGreaterEqual(int(self._fallback('MIN_INSTALLED_VERSION_CODE')), 217442878)

    def test_floors_clear_the_published_rc9(self):
        # RC9 shipped versionCode 217442880 and is what the tester has installed, so an RC10
        # candidate at or below it would be rejected as a downgrade -- and the only way past
        # that on a phone is an uninstall, which destroys history, samples and benchmarks.
        # The candidate code is computed as floor + 1, so a floor of >= 217442880 is required.
        for key in ('INSTALLED_VERSION_CODE', 'MIN_INSTALLED_VERSION_CODE'):
            with self.subTest(key=key):
                self.assertGreaterEqual(int(self._fallback(key)), 217442880)


class WorkflowTagTargetTest(unittest.TestCase):
    """Defect 2/3: reuse of an existing release, and a create without --target."""

    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding='utf-8')
        start = cls.text.index('Publish the APK as a direct download')
        end = cls.text.index('Verify the direct download really serves the tested APK')
        cls.publish = cls.text[start:end]

    def test_create_targets_the_checked_out_commit(self):
        # `gh release create` without --target tags the DEFAULT BRANCH. That is exactly
        # how rc4's tag ended up on main while its APK came from a feature commit.
        self.assertIn('gh release create "$TAG"', self.publish)
        self.assertIn('--target "$GITHUB_SHA"', self.publish)

    def test_create_is_not_conditional_on_an_existing_release(self):
        # The old shape was `gh release view ... || gh release create ...`, i.e. silently
        # reuse whatever tag exists. A taken tag must fail instead.
        self.assertNotIn('gh release view "$TAG" >/dev/null 2>&1 ||', self.publish)
        self.assertNotIn('release edit', self.publish)

    def test_assets_are_never_clobbered(self):
        # --clobber exists to refresh a rerun of the same build. With a tag that must be
        # fresh, re-uploading over anything is never correct.
        self.assertNotIn('--clobber', self.publish)

    def test_tag_is_verified_through_the_git_api_before_upload(self):
        self.assertIn('verify_release_tag.py --tag "$TAG" --expect "$GITHUB_SHA"', self.publish)
        # The assertion has to happen *before* the assets are attached.
        self.assertLess(
            self.publish.index('verify_release_tag.py --tag'),
            self.publish.index('gh release upload'),
        )

    def test_apk_identity_is_checked_against_the_commit_under_test(self):
        self.assertIn('git_revision=', self.publish)
        self.assertIn('apk_commit', self.publish)
        self.assertIn('"$apk_commit" != "$GITHUB_SHA"', self.publish)

    def test_an_existing_tag_is_refused_before_publishing(self):
        self.assertIn('Refuse a candidate name whose tag is already taken', self.text)
        self.assertIn('--exists', self.text)
        # Exit code 3 is the "absent, therefore usable" signal; anything else is fatal.
        self.assertIn('if [ "$status" = 3 ]', self.text)

    def test_release_floor_lookup_still_excludes_drafts_and_prereleases(self):
        self.assertIn('--exclude-drafts --exclude-pre-releases', self.text)


class ScriptCliTest(unittest.TestCase):
    """The CLI is what the workflow runs, so its exit codes are part of the contract."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_script()
        FakeApi.error = cls.mod.TagError

    def test_exists_returns_3_for_an_absent_tag(self):
        api = FakeApi({})
        self.assertEqual(3, self.mod.main(['--tag', 'rc-absent', '--exists'], api=api))

    def test_exists_returns_0_for_a_present_tag(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        self.assertEqual(0, self.mod.main(['--tag', 'rc-x', '--exists'], api=api))

    def test_expect_fails_on_a_mismatch(self):
        api = FakeApi({'rc-x': _lightweight(OTHER)})
        self.assertEqual(1, self.mod.main(['--tag', 'rc-x', '--expect', BUILD], api=api))

    def test_expect_succeeds_on_a_match(self):
        api = FakeApi({'rc-x': _lightweight(BUILD)})
        self.assertEqual(0, self.mod.main(['--tag', 'rc-x', '--expect', BUILD], api=api))

    def test_expect_without_a_commit_is_refused(self):
        api = FakeApi({})
        self.assertEqual(1, self.mod.main(['--tag', 'rc-x'], api=api))


class RealRepositoryStateTest(unittest.TestCase):
    """The rc4 defect, checked against the repository instead of a fixture."""

    def test_rc4_tag_still_disagrees_with_its_apk_commit(self):
        # This is the evidence that the defect was real. If it ever starts passing
        # because someone moved the tag, that is a different (worse) problem: a
        # published ref was rewritten.
        result = subprocess.run(
            ['gh', 'api', 'repos/mouadknight0-cell/ManhwaLens-v2/git/ref/tags/rc-1.1.42-rc4'],
            capture_output=True, text=True, timeout=60,
        )
        if result.returncode != 0:
            self.skipTest('gh api unavailable in this environment')
        sha = json.loads(result.stdout)['object']['sha']
        self.assertEqual(OTHER, sha)
        self.assertNotEqual(BUILD, sha)


if __name__ == '__main__':
    unittest.main()
