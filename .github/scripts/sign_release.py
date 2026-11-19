"""One-run RSA envelope: no private key/password in Git, inputs or comments.
Only a fresh public transport key and authenticated ciphertext cross GitHub API.
The runner verifies the pinned APK signer before publishing; all plaintext is temporary.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

repo = os.environ['GITHUB_REPOSITORY']
run = os.environ['GITHUB_RUN_ID']
tag = os.environ['RELEASE_TAG']
assert tag.startswith('v') and all(c.isalnum() or c in '.-' for c in tag)
pin = Path('signing/release-cert.sha256').read_text().strip()

def api(path, data=None):
    req = urllib.request.Request('https://api.github.com/repos/' + repo + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.load(response)

release = next(r for r in api('/releases?per_page=100') if r['tag_name'] == tag)
assert release['draft'], 'Only drafts may be signed'
private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
public = private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
api('/issues/4/comments', {'body': 'SIGNING_REQUEST ' + run + '\nTag: ' + tag + '\n```\n' + public + '```'})
prefix = 'SIGNING_ENVELOPE ' + run + '\n'
envelope = None
for _ in range(90):
    comments = api('/issues/4/comments?per_page=100&sort=created&direction=desc')
    for comment in reversed(comments):
        if comment['body'].startswith(prefix):
            envelope = json.loads(comment['body'][len(prefix):])
            break
    if envelope is not None:
        break
    time.sleep(10)
assert envelope is not None, 'No encrypted signing envelope received within 15 minutes'
decode = base64.b64decode
secret = private.decrypt(decode(envelope['wrapped']), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
plaintext = AESGCM(secret).decrypt(decode(envelope['nonce']), decode(envelope['ciphertext']), run.encode())
material = json.loads(plaintext)
# Never print material, password or keystore.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    subprocess.run(['gh', 'release', 'download', tag, '--pattern', 'signing-input.zip', '--dir', tmp], check=True)
    with zipfile.ZipFile(root / 'signing-input.zip') as archive:
        for name in ('app-release-unsigned.apk', 'SHA256SUMS'):
            (root / name).write_bytes(archive.read(name))
    expected = (root / 'SHA256SUMS').read_text().split()[0]
    assert hashlib.sha256((root / 'app-release-unsigned.apk').read_bytes()).hexdigest() == expected
    key = root / 'key.p12'
    password = root / 'password.txt'
    key.write_bytes(decode(material['keystore']))
    password.write_text(material['password'])
    key.chmod(0o600)
    password.chmod(0o600)
    signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/apksigner'
    apk = root / 'app-release.apk'
    subprocess.run([str(signer), 'sign', '--ks', str(key), '--ks-key-alias', 'manhwalens',
        '--ks-pass', 'file:' + str(password), '--key-pass', 'file:' + str(password),
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--out', str(apk), str(root / 'app-release-unsigned.apk')], check=True)
    verification = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    assert 'Signer #1 certificate SHA-256 digest: ' + pin in verification, 'Signing certificate mismatch'
    print(verification)
    key.unlink()
    password.unlink()
    (root / 'SHA256SUMS.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  app-release.apk\n')
    (root / 'release-cert.sha256').write_text(pin + '\n')
    subprocess.run(['gh', 'release', 'upload', tag, str(apk), str(root / 'SHA256SUMS.txt'), str(root / 'release-cert.sha256'), '--clobber'], check=True)
    subprocess.run(['gh', 'release', 'delete-asset', tag, 'signing-input.zip', '--yes'], check=True)
    notes = root / 'notes.md'
    notes.write_text('''## ManhwaLens 1.1.1 — stable signing baseline

Download **app-release.apk**. Built and unit-tested on GitHub Actions; APK signature verified against the pinned stable certificate.

Future releases must use this same certificate, package ID `com.manhwalens.app`, and a higher versionCode to preserve update compatibility.
This new certificate does NOT retroactively match old ephemeral debug-signed installations. Do not clear data or uninstall as an upgrade workaround.

Includes corrected bubble dragging and Start/Stop controls, full-display capture consent, progressive spatial translation, Copy Benchmark and saved issue reports.

Physical-device behavior, performance improvements, and in-place upgrade/data preservation have not been tested. Continuous scroll tracking and advanced bubble fitting remain incomplete.
''')
    subprocess.run(['gh', 'release', 'edit', tag, '--draft=false', '--latest', '--notes-file', str(notes)], check=True)
print('Published signed release with pinned certificate: ' + tag)
