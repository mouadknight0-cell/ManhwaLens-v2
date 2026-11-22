"""Unit tests for the release-APK gate. No keystore, no network, no APK required."""
import importlib.util
import tempfile
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('verify', Path(__file__).parents[1] / 'scripts/verify_release_apk.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

PIN = 'a' * 64
OTHER = 'b' * 64


def apksigner_output(*digests):
    return '\n'.join('Signer #1 certificate SHA-256 digest: ' + d for d in digests)


class SignerCertificateTests(unittest.TestCase):
    def test_matching_pinned_certificate_passes(self):
        ok, detail = verify.verify_output(apksigner_output(PIN), PIN)
        self.assertTrue(ok)
        self.assertEqual(PIN, detail)

    def test_uppercase_fingerprint_still_matches(self):
        ok, _ = verify.verify_output(apksigner_output(PIN.upper()), PIN)
        self.assertTrue(ok)

    def test_unsigned_apk_fails_closed(self):
        ok, detail = verify.verify_output('Verifies\n', PIN)
        self.assertFalse(ok)
        self.assertIn('unsigned', detail)

    def test_foreign_certificate_fails(self):
        ok, detail = verify.verify_output(apksigner_output(OTHER), PIN)
        self.assertFalse(ok)
        self.assertIn('does not match pinned', detail)

    def test_two_distinct_signers_fail(self):
        ok, detail = verify.verify_output(apksigner_output(PIN, OTHER), PIN)
        self.assertFalse(ok)
        self.assertIn('multiple distinct signers', detail)


class IdentityTests(unittest.TestCase):
    def badging(self, package='com.manhwalens.app', code=217515676, name='1.1.42-rc1'):
        return ("package: name='%s' versionCode='%d' versionName='%s' platformBuildVersionName='15'"
                % (package, code, name))

    def test_identity_passes(self):
        package, code, name = verify.parse_badging(self.badging())
        self.assertEqual('com.manhwalens.app', package)
        self.assertEqual(217515676, code)
        self.assertTrue(verify.check_identity(package, code, name, '1.1.42-rc1', 217442869))

    def test_changed_application_id_is_rejected(self):
        package, code, name = verify.parse_badging(self.badging(package='com.example.app'))
        with self.assertRaises(verify.ReleaseVerificationError):
            verify.check_identity(package, code, name, '1.1.42-rc1', None)

    def test_wrong_version_name_is_rejected(self):
        package, code, name = verify.parse_badging(self.badging(name='1.1.30-dev'))
        with self.assertRaises(verify.ReleaseVerificationError):
            verify.check_identity(package, code, name, '1.1.42-rc1', None)

    def test_non_monotonic_version_code_is_rejected(self):
        package, code, name = verify.parse_badging(self.badging(code=217442869))
        with self.assertRaises(verify.ReleaseVerificationError):
            verify.check_identity(package, code, name, '1.1.42-rc1', 217442869)

    def test_debuggable_release_is_rejected(self):
        with self.assertRaises(verify.ReleaseVerificationError):
            verify.check_debuggable("package: name='com.manhwalens.app' application-debuggable\n")
        self.assertTrue(verify.check_debuggable(self.badging()))


class PinTests(unittest.TestCase):
    def test_pinned_fingerprint_must_be_sha256(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'pin'
            path.write_text('not-a-fingerprint')
            with self.assertRaises(verify.ReleaseVerificationError):
                verify.pinned_certificate(path)

    def test_repository_pin_is_a_valid_fingerprint(self):
        pin = verify.pinned_certificate()
        self.assertEqual(64, len(pin))
        self.assertNotEqual('0' * 64, pin)

    def test_debug_keystore_fingerprint_is_not_the_pin(self):
        # The public debug certificate (AOSP test key) must never be accepted as production.
        debug = '9e8b1c4a6f0b2e5de0a1f6c3b8d47a5c2f9e1b7d6c8a4f0e3b5d7c9a1f2e4b6d'
        ok, _ = verify.verify_output(apksigner_output(debug), verify.pinned_certificate())
        self.assertFalse(ok)


if __name__ == '__main__':
    unittest.main()
