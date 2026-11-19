# Signing continuity (starts at v1.1.1)

Public certificate SHA-256 is pinned in `release-cert.sha256`. No private signing material belongs in Git.

- Application ID remains `com.manhwalens.app`.
- Build/test the APK on GitHub Actions; the release workflow creates only a **draft** with unsigned signing inputs.
- Download the draft input, verify its SHA256SUMS, then sign offline with the existing PKCS12 release key (alias `manhwalens`).
- Use Android `apksigner` v1/v2/v3 signatures, verify the signed APK, and compare its signer SHA-256 against the pinned value BEFORE publication.
- Delete the unsigned signing-input ZIP from the draft. Upload only the signed `app-release.apk`, its checksum and public certificate digest, then publish.
- Never fall back to an ephemeral debug key or publish the signing key/password.
- New releases must have a higher versionCode. Codes derive from commit time since 2020; create a new version commit for each release and do not move tags backwards.
- The release key currently resides outside the repository in the secure workspace. The owner must retain a private backup and configure GitHub signing secrets for durable automation. If the key becomes unavailable, STOP: never substitute a new key.
- This certificate cannot update old APKs signed with unrelated ephemeral debug keys. No uninstall/clear-data migration is performed. Compatibility starts with the new certificate, not retroactively with v1.0.0/v1.1.0.
