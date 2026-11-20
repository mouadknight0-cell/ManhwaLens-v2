"""Sign from a durable encrypted vault; verify the pinned signer before publication.
Plaintext key material exists only inside the runner's temporary directory.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

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
# No ephemeral-envelope fallback: every new release must be recoverable from
# the versioned ciphertext plus the independently retained repository secret.
from signing_vault import load_material
material = load_material()
decode = base64.b64decode
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
        '--ks-pass', 'file:' + str(password),
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--out', str(apk), str(root / 'app-release-unsigned.apk')], check=True)
    verification = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    assert 'Signer #1 certificate SHA-256 digest: ' + pin in verification, 'Signing certificate mismatch'
    print(verification)
    aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt'
    identity = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True).splitlines()[0]
    assert "name='com.manhwalens.app'" in identity
    assert "versionName='" + tag[1:] + "'" in identity
    print(identity)
    (root / 'APK-IDENTITY.txt').write_text(identity + '\n' + verification)
    key.unlink()
    password.unlink()
    (root / 'SHA256SUMS.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  app-release.apk\n')
    (root / 'release-cert.sha256').write_text(pin + '\n')
    subprocess.run(['gh', 'release', 'upload', tag, str(apk), str(root / 'SHA256SUMS.txt'), str(root / 'release-cert.sha256'), str(root / 'APK-IDENTITY.txt'), '--clobber'], check=True)
    subprocess.run(['gh', 'release', 'delete-asset', tag, 'signing-input.zip', '--yes'], check=True)
    notes = root / 'notes.md'
    notes.write_text(f'''## ManhwaLens {tag} — live capture and session reliability

Download **app-release.apk**. Built and unit-tested on GitHub Actions; APK signature verified against the pinned stable certificate.

Future releases must use this same certificate, package ID `com.manhwalens.app`, and a higher versionCode to preserve update compatibility.
SIGNING RESET: this newly authorized identity does NOT match v1.1.1–v1.1.6 or older debug-signed installations. This APK cannot update those installations in place. Do NOT uninstall or clear their data as an automatic workaround. Data migration requires a separate, verified plan. Future releases must reuse this vault and certificate.

Bounded OCR confusion cleanup with confirmed-name protection and unresolved-input gating; reviewed natural sound/inner-voice phrases; one transient-execution retry; per-item failure isolation. Manual reader now translation-only by default, measured and capped at 30% screen area, with internal source toggle/close and floating-icon hide/restore at 18% area. Same offline ML Kit and signer. Unknown words/names, scene/speaker-aware cross-bubble context and full-device latency parity remain unresolved. Cleanup-only emulator benchmark is not end-to-end performance evidence. See PROJECT_STATE.md and docs/QUALITY-READER-1.1.22.md. Updates from v1.1.10 reuse the same signing certificate. Back up signing/release-key.enc.json and its independent password privately. No plaintext private key is committed.

Physical-device behavior, performance improvements, and in-place upgrade/data preservation have not been tested. Live visual-motion thresholds, continuous reading and advanced bubble fitting require physical-device validation; contextual Arabic quality remains limited by ML Kit.
''')
    subprocess.run(['gh', 'release', 'edit', tag, '--draft=false', '--latest', '--notes-file', str(notes)], check=True)
print('Published signed release with pinned certificate: ' + tag)
