"""Regression tests for the CI-side keystore decode helper.

The helper is the only code path that turns the encrypted vault into a plaintext PKCS#12
inside a runner. These tests prove it fails closed: no pinned fingerprint, a wrong
fingerprint, a broken vault payload, and a missing passphrase must all raise *before*
anything is written, so a release build can never silently fall back to another key.
"""
import base64
import importlib.util
import os
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = pathlib.Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))

spec = importlib.util.spec_from_file_location('decode_keystore', SCRIPTS / 'decode_release_keystore.py')
decode_keystore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(decode_keystore)

GOOD_PIN = 'a' * 64


class DecodeReleaseKeystoreTests(unittest.TestCase):
    def test_rejects_missing_or_malformed_pin(self):
        with tempfile.TemporaryDirectory() as tmp:
            for pin in ('', '  ', 'not-a-sha256', 'a' * 63):
                with self.assertRaises(decode_keystore.DecodeError):
                    decode_keystore.decode(pathlib.Path(tmp), pin)
                self.assertFalse((pathlib.Path(tmp) / 'release.p12').exists())

    def test_rejects_certificate_that_is_not_the_pinned_one(self):
        # A decrypting vault carrying any other key must be refused, not shipped.
        payload = base64.b64encode(b'not-a-real-pkcs12').decode()
        material = {'keystore': payload, 'password': 'pw'}
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(decode_keystore, 'load_material', return_value=material), \
                 patch.object(decode_keystore, 'certificate_sha256', return_value='b' * 64):
                with self.assertRaises(decode_keystore.DecodeError) as caught:
                    decode_keystore.decode(pathlib.Path(tmp), GOOD_PIN)
                self.assertIn('does not match the pinned', str(caught.exception))
                self.assertFalse((pathlib.Path(tmp) / 'release.p12').exists())

    def test_rejects_non_base64_vault_payload(self):
        material = {'keystore': '!!!not base64!!!', 'password': 'pw'}
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(decode_keystore, 'load_material', return_value=material):
                with self.assertRaises(decode_keystore.DecodeError):
                    decode_keystore.decode(pathlib.Path(tmp), GOOD_PIN)
                self.assertFalse((pathlib.Path(tmp) / 'release.p12').exists())

    def test_writes_only_when_pin_matches(self):
        payload = base64.b64encode(b'pkcs12-bytes').decode()
        material = {'keystore': payload, 'password': 'pw'}
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(decode_keystore, 'load_material', return_value=material), \
                 patch.object(decode_keystore, 'certificate_sha256', return_value=GOOD_PIN.upper()):
                target, password = decode_keystore.decode(pathlib.Path(tmp), GOOD_PIN)
            self.assertEqual(b'pkcs12-bytes', target.read_bytes())
            self.assertEqual('pw', password)
            self.assertEqual(0o600, target.stat().st_mode & 0o777)

    def test_missing_secret_from_vault_fails_closed(self):
        # signing_vault.load_material raises; nothing may be swallowed.
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {'SIGNING_BACKUP_PASSWORD': ''}):
                with self.assertRaises(Exception):
                    decode_keystore.decode(pathlib.Path(tmp), GOOD_PIN)
                self.assertFalse((pathlib.Path(tmp) / 'release.p12').exists())


if __name__ == '__main__':
    unittest.main()
