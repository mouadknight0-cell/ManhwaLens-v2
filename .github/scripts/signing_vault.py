"""Versioned encrypted keystore; only ciphertext and public certificate enter Git.
The independently backed-up SIGNING_BACKUP_PASSWORD must never enter Git/logs.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VAULT = Path('signing/release-key.enc.json')
PIN = Path('signing/release-cert.sha256')
AAD = b'ManhwaLens release keystore vault v1'
ITERATIONS = 600_000

def password():
    value = os.environ.get('SIGNING_BACKUP_PASSWORD', '')
    if len(value) < 32:
        raise RuntimeError('Set SIGNING_BACKUP_PASSWORD in repository Actions secrets to a password-manager-generated value of at least 32 characters. Back it up privately. No key generated.')
    return value

def derive(value, salt):
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=ITERATIONS).derive(value.encode())

def encrypt(material, value):
    salt, nonce = os.urandom(16), os.urandom(12)
    ciphertext = AESGCM(derive(value, salt)).encrypt(nonce, json.dumps(material).encode(), AAD)
    encode = lambda raw: base64.b64encode(raw).decode()
    return dict(version=1, kdf='PBKDF2-SHA256', iterations=ITERATIONS, salt=encode(salt), nonce=encode(nonce), ciphertext=encode(ciphertext))

def decrypt(envelope, value):
    if envelope.get('version') != 1 or envelope.get('kdf') != 'PBKDF2-SHA256' or envelope.get('iterations') != ITERATIONS:
        raise ValueError('Unsupported signing vault format')
    decode = base64.b64decode
    raw = AESGCM(derive(value, decode(envelope['salt']))).decrypt(decode(envelope['nonce']), decode(envelope['ciphertext']), AAD)
    return json.loads(raw)

def load_material():
    if not VAULT.exists():
        raise RuntimeError('Signing vault not initialized. Restore the original key or explicitly initialize the authorized new signing identity first.')
    return decrypt(json.loads(VAULT.read_text()), password())

def initialize():
    if VAULT.exists():
        raise RuntimeError('Refusing to replace an existing signing vault. Initialization is one-time only.')
    value = password()  # Must succeed BEFORE generating key material.
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        key, certificate = root / 'key.p12', root / 'cert.der'
        env = dict(os.environ, KEYSTORE_PASSWORD=secrets.token_urlsafe(48))
        subprocess.run(['keytool', '-genkeypair', '-alias', 'manhwalens', '-keyalg', 'RSA', '-keysize', '3072',
                        '-validity', '10000', '-storetype', 'PKCS12', '-keystore', str(key),
                        '-storepass:env', 'KEYSTORE_PASSWORD', '-dname', 'CN=ManhwaLens Release, O=ManhwaLens', '-noprompt'],
                       env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['keytool', '-exportcert', '-alias', 'manhwalens', '-keystore', str(key),
                        '-storepass:env', 'KEYSTORE_PASSWORD', '-file', str(certificate)],
                       env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        material = dict(keystore=base64.b64encode(key.read_bytes()).decode(), password=env['KEYSTORE_PASSWORD'])
        envelope = encrypt(material, value)
        # Recovery round-trip is a required gate, not a promise of external backup.
        if decrypt(envelope, value) != material:
            raise RuntimeError('Vault recovery verification failed')
        fingerprint = hashlib.sha256(certificate.read_bytes()).hexdigest()
        VAULT.parent.mkdir(exist_ok=True)
        legacy = VAULT.parent / 'legacy-release-cert.sha256'
        if PIN.exists() and not legacy.exists():
            legacy.write_text(PIN.read_text())
        VAULT.write_text(json.dumps(envelope, indent=2) + '\n')
        PIN.write_text(fingerprint + '\n')
        print('Encrypted vault round-trip verified. Public certificate SHA-256: ' + fingerprint)
        print('Initialization is not complete until the encrypted vault and pin are pushed successfully.')

if __name__ == '__main__':
    initialize()
