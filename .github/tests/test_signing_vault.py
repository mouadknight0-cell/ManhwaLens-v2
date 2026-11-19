import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('vault', Path(__file__).parents[1] / 'scripts/signing_vault.py')
vault = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vault)

class SigningVaultTests(unittest.TestCase):
    def test_recovery_and_wrong_password(self):
        material = {'keystore': 'dummy-keystore', 'password': 'dummy-password'}
        envelope = vault.encrypt(material, 'test-only-password-' * 3)
        self.assertEqual(material, vault.decrypt(envelope, 'test-only-password-' * 3))
        self.assertNotIn('dummy-keystore', json.dumps(envelope))
        with self.assertRaises(Exception):
            vault.decrypt(envelope, 'another-password-' * 3)
        envelope['ciphertext'] = ('A' if envelope['ciphertext'][0] != 'A' else 'B') + envelope['ciphertext'][1:]
        with self.assertRaises(Exception):
            vault.decrypt(envelope, 'test-only-password-' * 3)

    def test_generation_requires_secret_before_keytool(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(vault, 'VAULT', Path(tmp) / 'missing.json'), patch.dict(os.environ, {'SIGNING_BACKUP_PASSWORD': ''}), patch.object(vault.subprocess, 'run') as run:
                with self.assertRaises(RuntimeError): vault.initialize()
                run.assert_not_called()

    def test_existing_vault_cannot_be_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'vault.json'
            path.write_text('existing encrypted backup')
            with patch.object(vault, 'VAULT', path), patch.object(vault.subprocess, 'run') as run:
                with self.assertRaises(RuntimeError): vault.initialize()
                run.assert_not_called()
            self.assertEqual('existing encrypted backup', path.read_text())

if __name__ == '__main__': unittest.main()
