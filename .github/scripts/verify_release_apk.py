"""Verify a built RELEASE APK before it is offered for physical-device testing.

Fail-closed checks, in order:
  1. the APK is signed (not an unsigned artefact, never a debug signer);
  2. the signing certificate SHA-256 equals the pinned production certificate;
  3. the package id and versionName are the expected ones;
  4. the versionCode is strictly monotonic against the last published release.

Nothing here prints, copies or decodes private key material. The keystore itself lives
inside the Gradle signing config, decoded from the versioned vault in the same job.

Usage:
  python3 .github/scripts/verify_release_apk.py --apk PATH --apk-id AAPT
      [--expected-version-name 1.1.42-rc1] [--min-version-code N]
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

PIN = Path('signing/release-cert.sha256')
EXPECTED_PACKAGE = 'com.manhwalens.app'

# apksigner prints: "Signer #1 certificate SHA-256 digest: <hex>"
SIGNER_SHA256 = re.compile(r'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
BADGING = re.compile(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'")


class ReleaseVerificationError(RuntimeError):
    pass


def pinned_certificate(path=PIN):
    value = Path(path).read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ReleaseVerificationError('pinned release certificate is not a SHA-256 fingerprint')
    return value


def verify_output(apksigner_output, pin):
    """Return (ok, detail) for the signer digests in apksigner's verbose output."""
    digests = [d.lower() for d in SIGNER_SHA256.findall(apksigner_output)]
    if not digests:
        return False, 'no signer certificate reported (unsigned or unverifiable APK)'
    if len(set(digests)) != 1:
        return False, 'multiple distinct signers: ' + ','.join(sorted(set(digests)))
    if digests[0] != pin.lower():
        return False, 'signer ' + digests[0] + ' does not match pinned ' + pin.lower()
    return True, digests[0]


def parse_badging(output):
    match = BADGING.search(output)
    if not match:
        raise ReleaseVerificationError('aapt badging has no package/version block')
    name, code, version = match.groups()
    return name, int(code), version


def check_identity(package, version_code, version_name, expected_name, min_code, expected_code=None):
    if package != EXPECTED_PACKAGE:
        raise ReleaseVerificationError('applicationId changed: ' + package)
    if expected_name and version_name != expected_name:
        raise ReleaseVerificationError('versionName ' + version_name + ' != expected ' + expected_name)
    if expected_code is not None and version_code != expected_code:
        # The candidate must carry exactly the code the workflow computed above both floors;
        # a build that silently clamped it could not be installed over the current one.
        raise ReleaseVerificationError(
            'versionCode ' + str(version_code) + ' != planned ' + str(expected_code))
    if min_code is not None and version_code <= min_code:
        raise ReleaseVerificationError(
            'versionCode ' + str(version_code) + ' is not greater than installed/last released ' + str(min_code))
    return True


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def check_debuggable(badging_output):
    """A release must not advertise android:debuggable=true."""
    lowered = badging_output.lower()
    if 'application-debuggable' in lowered or 'debuggable' in lowered:
        raise ReleaseVerificationError('APK reports android:debuggable')
    return True


def run(cmd):
    try:
        return subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as failure:
        raise ReleaseVerificationError(Path(cmd[0]).name + ' failed: ' + (failure.output or '')[:400])


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True)
    parser.add_argument('--apk-id', required=True, help='aapt binary')
    parser.add_argument('--apksigner', default=None, help='apksigner binary (default: beside aapt)')
    parser.add_argument('--expected-version-name', default=None)
    parser.add_argument('--min-version-code', type=int, default=None,
                        help='highest versionCode already out there (installed or published); the new one must be greater')
    parser.add_argument('--expected-version-code', type=int, default=None,
                        help='exact versionCode the workflow planned for this candidate')
    parser.add_argument('--out', default=None, help='write the identity report here')
    parser.add_argument('--git-revision', default=None,
                        help='full 40-hex commit the APK was built from; recorded so an '
                             'on-device evaluation run can be traced back to it')
    parser.add_argument('--require-git-revision', action='store_true',
                        help='fail when no valid revision was supplied (official candidates)')
    args = parser.parse_args(argv)

    apk = Path(args.apk)
    if not apk.exists():
        raise ReleaseVerificationError('APK missing: ' + str(apk))
    apksigner = args.apksigner or str(Path(args.apk_id).with_name('apksigner'))
    pin = pinned_certificate()

    # Signature first: never inspect an unsigned artefact as if it were a release.
    signature = run([apksigner, 'verify', '--verbose', '--print-certs', str(apk)])
    ok, detail = verify_output(signature, pin)
    if not ok:
        raise ReleaseVerificationError(detail)

    badging = run([args.apk_id, 'dump', 'badging', str(apk)])
    package, version_code, version_name = parse_badging(badging)
    check_identity(package, version_code, version_name, args.expected_version_name,
                   args.min_version_code, args.expected_version_code)
    check_debuggable(badging)
    digest = sha256_of(apk)

    # Build identity. A malformed value is an error rather than a warning: an APK
    # that cannot be tied to a commit cannot produce a reproducible evaluation run.
    revision = (args.git_revision or '').strip().lower()
    if revision and not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ReleaseVerificationError(
            'git revision must be a full 40-hex commit SHA, got ' + repr(args.git_revision))
    if not revision and args.require_git_revision:
        raise ReleaseVerificationError('no git revision supplied for an official candidate')

    report = [
        'package=' + package,
        'versionName=' + version_name,
        'versionCode=' + str(version_code),
        'planned_versionCode=' + str(args.expected_version_code if args.expected_version_code is not None else 'any'),
        'version_floor=' + str(args.min_version_code if args.min_version_code is not None else 'none'),
        'signer_certificate_sha256=' + detail,
        'pinned_certificate_sha256=' + pin,
        'apk_sha256=' + digest,
        'apk_bytes=' + str(apk.stat().st_size),
        'signed_release=true',
        'git_revision=' + (revision or 'unknown'),
    ]
    text = '\n'.join(report) + '\n'
    print(text, end='')
    if args.out:
        Path(args.out).write_text(text)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except ReleaseVerificationError as error:
        print('RELEASE VERIFICATION FAILED: ' + str(error), file=sys.stderr)
        sys.exit(1)
