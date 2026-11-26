#!/usr/bin/env python3
"""Decode the pinned release keystore from the versioned vault, inside a CI runner.

The vault (``signing/release-key.enc.json``) is the durable backup of the production
signing key; the passphrase is an independent Actions secret. This helper is the only
place that turns the two into a plaintext PKCS#12 file, and it does so exclusively in
the runner's temporary directory:

* nothing is ever printed apart from the output path and the certificate pin,
* the password reaches Gradle through ``$GITHUB_ENV`` (the value is a masked secret),
* the caller deletes the file with an ``if: always()`` step when the job ends.

It refuses to invent anything: a missing passphrase, a missing vault, or a vault that
does not decrypt raises instead of falling back to a debug key.
"""

from __future__ import annotations

import base64
import os
import pathlib
import sys

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from signing_vault import load_material  # noqa: E402  (path set up above)

KEY_ALIAS = "manhwalens"
PIN_FILE = pathlib.Path("signing/release-cert.sha256")


class DecodeError(RuntimeError):
    """Raised when the production keystore cannot be materialised safely."""


def certificate_sha256(keystore_bytes: bytes, password: str) -> str:
    """Return the lower-case SHA-256 fingerprint of the signing certificate."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.serialization import pkcs12 as pkcs12_module

    key, cert, _ = pkcs12_module.load_key_and_certificates(
        keystore_bytes, password.encode("utf-8")
    )
    if key is None or cert is None:
        raise DecodeError("the vault holds no usable signing key/certificate pair")
    return cert.fingerprint(hashes.SHA256()).hex()


def decode(directory: pathlib.Path, expected_sha256: str) -> tuple[pathlib.Path, str]:
    """Write the vault keystore into *directory*; return its path and the password.

    Fails closed when the decoded certificate does not match *expected_sha256*, so a
    replacement or regenerated key can never be shipped as an official release.
    """
    expected = (expected_sha256 or "").strip().lower()
    if len(expected) != 64:
        raise DecodeError("the pinned certificate fingerprint is missing or malformed")

    material = load_material()
    try:
        pkcs12 = base64.b64decode(material["keystore"], validate=True)
    except (KeyError, TypeError, ValueError) as exc:
        raise DecodeError("the vault payload is not valid base64") from exc

    password = material["password"]
    actual = certificate_sha256(pkcs12, password).strip().lower()
    if actual != expected:
        raise DecodeError(
            "decoded certificate %s does not match the pinned production certificate %s; "
            "refusing to build" % (actual, expected)
        )

    target = pathlib.Path(directory) / "release.p12"
    target.write_bytes(pkcs12)
    target.chmod(0o600)
    return target, password


def main(argv: list[str] | None = None) -> int:  # pragma: no cover - exercised in CI
    directory = pathlib.Path(
        os.environ.get("RUNNER_TEMP") or os.environ.get("TMPDIR") or "/tmp"
    )
    env_file = os.environ.get("GITHUB_ENV")
    if not env_file:
        print("GITHUB_ENV is not set; this helper only runs inside the release job", file=sys.stderr)
        return 2
    if not PIN_FILE.exists():
        print("::error::%s is missing; refusing to decode a keystore" % PIN_FILE, file=sys.stderr)
        return 1

    target, password = decode(directory, PIN_FILE.read_text(encoding="utf-8"))
    with open(env_file, "a", encoding="utf-8") as env:
        env.write("MANHWALENS_KEYSTORE_FILE=%s\n" % target)
        env.write("MANHWALENS_KEYSTORE_PASSWORD=%s\n" % password)
        env.write("MANHWALENS_KEY_PASSWORD=%s\n" % password)
        env.write("MANHWALENS_KEY_ALIAS=%s\n" % KEY_ALIAS)
    print("Production keystore decoded for this runner only; certificate pin matches %s"
          % PIN_FILE.read_text(encoding="utf-8").strip())
    return 0


if __name__ == "__main__":  # pragma: no cover
    try:
        raise SystemExit(main())
    except DecodeError as exc:
        # Fingerprint/pin problems: a deliberate refusal, never a fallback.
        print("::error::%s" % exc, file=sys.stderr)
        raise SystemExit(1)
    except Exception as exc:  # noqa: BLE001 - the message must reach the CI annotation
        # E.g. the vault passphrase secret is absent or the vault cannot be decrypted.
        # Report the class and message only -- no key material is ever in scope here.
        print("::error::cannot decode the release keystore: %s: %s"
              % (type(exc).__name__, exc), file=sys.stderr)
        raise SystemExit(1)
