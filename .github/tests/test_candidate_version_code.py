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



class WorkflowFloorDefaultsTest(unittest.TestCase):
    """The workflow's built-in floors must not be stale.

    A candidate whose versionCode is at or below the build already on the phone is
    rejected by Android as a downgrade. That happened: the defaults were pinned
    before releases 1.1.39-1.1.41 and before the installed build, so the workflow
    computed 217442871 while the phone already had 217442875. The arithmetic in
    candidate_version_code.py was correct; the inputs were not.
    """

    #: versionCode on the owner's device. Raising this line is the only correct way
    #: to record a newer installed build; nothing may lower it.
    INSTALLED_ON_DEVICE = 217442875

    @classmethod
    def setUpClass(cls):
        import re
        cls.text = (
            Path(__file__).parents[1] / 'workflows/release-candidate.yml'
        ).read_text(encoding='utf-8')
        cls.floors = [int(v) for v in re.findall(r'21744\d{4}', cls.text)]

    def test_defaults_clear_the_installed_build(self):
        self.assertTrue(self.floors, 'no versionCode floor found in the workflow')
        for value in self.floors:
            # A floor equal to the installed code still yields floor + 1, which is
            # installable, so >= is the correct bound here.
            self.assertGreaterEqual(
                value, self.INSTALLED_ON_DEVICE,
                f'workflow floor {value} is below the installed build '
                f'{self.INSTALLED_ON_DEVICE}; a candidate built from it would be '
                'rejected as a downgrade',
            )

    def test_every_floor_is_an_input_and_not_guessed(self):
        # Both inputs and both env fallbacks must be present, else one code path
        # silently reverts to a stale value.
        # Three inputs declare a default (version name, installed code, min code).
        self.assertEqual(3, self.text.count('default:'))
        self.assertIn('INSTALLED_VERSION_CODE:', self.text)
        self.assertIn('MIN_INSTALLED_VERSION_CODE:', self.text)

    def test_the_stale_floor_would_have_produced_a_lower_code(self):
        # The numbers that produced the un-installable APK: floor 217442870 gives
        # 217442871, below the installed 217442875.
        stale, stale_floor = candidate.candidate_version_code(
            217442870, 217442870, 217442870, LATE_COMMIT)
        self.assertLess(stale, self.INSTALLED_ON_DEVICE)
        # The corrected floor clears it.
        value, floor = candidate.candidate_version_code(
            217442870, self.INSTALLED_ON_DEVICE, self.INSTALLED_ON_DEVICE, LATE_COMMIT)
        self.assertGreater(value, self.INSTALLED_ON_DEVICE)
        self.assertGreaterEqual(floor, self.INSTALLED_ON_DEVICE)


if __name__ == '__main__':
    unittest.main()
