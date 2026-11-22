"""Tests for the release-candidate versionCode floor. No network, no Gradle, no keystore."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'candidate', Path(__file__).parents[1] / 'scripts/candidate_version_code.py')
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)

# A commit comfortably after the 2020-01-01 epoch: 2026-11-22T00:00:00Z.
LATE_COMMIT = 1795305600
CODE = candidate.release_code(LATE_COMMIT)


class CandidateAboveAllFloors(unittest.TestCase):
    def test_candidate_clears_the_installed_build(self):
        value, floor = candidate.candidate_version_code(217442800, 217442870, 217442700, LATE_COMMIT)
        self.assertEqual(217442871, value)
        self.assertEqual(217442870, floor)

    def test_candidate_clears_the_newest_release(self):
        value, floor = candidate.candidate_version_code(217442960, 217442870, 217442700, LATE_COMMIT)
        self.assertEqual(217442961, value)
        self.assertEqual(217442960, floor)

    def test_candidate_clears_the_explicit_minimum(self):
        value, floor = candidate.candidate_version_code(217442800, 217442870, 217442990, LATE_COMMIT)
        self.assertEqual(217442991, value)
        self.assertEqual(217442990, floor)

    def test_candidate_is_strictly_above_every_floor(self):
        value, _ = candidate.candidate_version_code(217442800, 217442870, 217442990, LATE_COMMIT)
        for bound in (217442800, 217442870, 217442990):
            self.assertGreater(value, bound)

    def test_floor_never_borrows_the_commit_release_code(self):
        # The per-commit code is far larger; the candidate must stay near the floor so the
        # eventual tagged release keeps its own number.
        value, _ = candidate.candidate_version_code(217442870, 217442870, 217442870, LATE_COMMIT)
        self.assertEqual(217442871, value)
        self.assertLess(value, CODE)


class FailsClosed(unittest.TestCase):
    def test_missing_minimum_is_not_treated_as_no_floor(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(217442800, 217442870, None, LATE_COMMIT)

    def test_empty_minimum_fails(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(217442800, 217442870, '  ', LATE_COMMIT)

    def test_malformed_minimum_fails(self):
        for bad in ('2174428x0', '1.5e9', '-1', '0', '+217442870'):
            with self.subTest(bad=bad):
                with self.assertRaises(candidate.VersionFloorError):
                    candidate.candidate_version_code(217442800, 217442870, bad, LATE_COMMIT)

    def test_missing_installed_version_fails(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(217442800, None, 217442700, LATE_COMMIT)

    def test_missing_last_release_fails(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(None, 217442870, 217442700, LATE_COMMIT)

    def test_commit_older_than_the_newest_release_fails(self):
        # An old commit cannot produce a candidate above a release that already exists.
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(CODE + 10, 217442870, 217442700, LATE_COMMIT)

    def test_commit_before_the_release_epoch_fails(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.release_code(1577836800)

    def test_candidate_past_the_android_maximum_fails(self):
        with self.assertRaises(candidate.VersionFloorError):
            candidate.candidate_version_code(candidate.MAX_CANDIDATE, 217442870, 217442700, LATE_COMMIT)


if __name__ == '__main__':
    unittest.main()
