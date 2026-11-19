# Releasing ManhwaLens

## Release model

- **Package id** `com.manhwalens.app` — permanent. Changing it breaks update compatibility for every installed user.
- **Release signing key** — a PKCS12 keystore generated once. If it is lost, no future APK can update existing installs (users would have to uninstall/reinstall).
- **Version name** comes from the git tag (`v1.2.3` → `1.2.3`); **version code** is the GitHub Actions run number.

## Required repository secrets (Settings → Secrets and variables → Actions)

| Secret | Content |
|---|---|
| `MANHWALENS_KEYSTORE_BASE64` | `base64 -w0 manhwalens-release.p12` |
| `MANHWALENS_KEYSTORE_PASSWORD` | Store password (PKCS12: same for key) |
| `MANHWALENS_KEY_ALIAS` | `manhwalens` |
| `MANHWALENS_KEY_PASSWORD` | Key password |

The release workflow (`.github/workflows/release.yml`) decodes the keystore to a temp file, builds, then deletes it (`if: always()`). The keystore is **never** committed — `*.p12` is git-ignored.

> If `MANHWALENS_KEYSTORE_BASE64` is absent, the workflow falls back to debug signing so the APK remains installable, and prints a warning. Such builds do **not** update over installs signed with the stable key.

## Cutting a release

```bash
git tag -a v1.0.0 -m "ManhwaLens 1.0.0"
git push origin v1.0.0
```

The tag triggers: unit tests → `assembleRelease` (signed with the stable key when secrets are set) → `assembleDebug` → a GitHub Release with both APKs attached.

## Local release build

```bash
export MANHWALENS_KEYSTORE_FILE=/path/to/manhwalens-release.p12
export MANHWALENS_KEYSTORE_PASSWORD=…
export MANHWALENS_KEY_ALIAS=manhwalens
export MANHWALENS_KEY_PASSWORD=…
export VERSION_NAME=1.0.0 VERSION_CODE=1
./gradlew assembleRelease
```

## Verifying the signature

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app-release.apk
```

Package id and certificate SHA-256 must match across releases.
