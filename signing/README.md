# Signing continuity (starts at v1.1.1)

Public certificate SHA-256 is pinned in `release-cert.sha256`. No private signing material belongs in Git.

- Application ID remains `com.manhwalens.app`.
- Build/test the APK on GitHub Actions; the release workflow creates only a **draft** with unsigned signing inputs.
- Download the draft input, verify its SHA256SUMS, then sign with the existing PKCS12 release key (alias `manhwalens`). The tested route is the `Sign verified release` workflow: it signs on GitHub after a one-use encrypted transfer.
- Use Android `apksigner` v1/v2/v3 signatures, verify the signed APK, and compare its signer SHA-256 against the pinned value BEFORE publication.
- Delete the unsigned signing-input ZIP from the draft. Upload only the signed `app-release.apk`, its checksum and public certificate digest, then publish.
- Never fall back to an ephemeral debug key or publish the signing key/password.
- New releases must have a higher versionCode. Codes derive from commit time since 2020; create a new version commit for each release and do not move tags backwards.
- The release key currently resides outside the repository in the workspace. Workspace presence is NOT a durable backup guarantee. The owner must retain a private backup and configure GitHub signing secrets for durable automation. If the key becomes unavailable, STOP: never substitute a new key.
- This certificate cannot update old APKs signed with unrelated ephemeral debug keys. No uninstall/clear-data migration is performed. Compatibility starts with the new certificate, not retroactively with v1.0.0/v1.1.0.


## Tested signing route (v1.1.1)

- Build run: 44546838691; successful signing/publish run: 44548908754.
- Start `sign-release.yml` for the draft tag on the current working branch. Its bootstrap push trigger targets v1.1.1 only; future releases should use workflow_dispatch with their tag.
- The runner posts a fresh public RSA transport key to issue #4. The private transport key never leaves the runner.
- Install Python `cryptography` locally, set `SIGNING_KEYSTORE_FILE` and `SIGNING_PASSWORD_FILE` to private file paths (never put password text in arguments), and run `python signing/send_envelope.py RUN_ID`.
- The helper accepts only github-actions[bot] requests for that live signing workflow. It sends an AES-GCM-encrypted payload with an RSA-OAEP-wrapped random key. The public comment contains ciphertext only.
- The runner decrypts in temporary storage, signs, verifies the pinned SHA-256, deletes plaintext files, uploads the signed APK, removes the unsigned archive and publishes the draft.
- Retain an owner-controlled private backup of the key AND password. Never replace a missing key with a fresh one: that breaks all future updates.
