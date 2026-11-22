# Release candidate 1.1.42-rc3 — download and identity

A single place that always carries the current candidate download. If this file and the
GitHub prerelease ever disagree, **the APK SHA-256 below wins** and a rebuild is required.

## Direct signed APK

**https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc3/ManhwaLens-1.1.42-rc3-release.apk**

That URL serves the `.apk` itself (HTTP 302 to the release asset,
`Content-Type: application/vnd.android.package-archive`) — not a workflow-artifact zip.

| Item | Value |
| --- | --- |
| versionName | `1.1.42-rc3` |
| versionCode | `217442872` |
| APK SHA-256 | `bab3d7e03d3be4624328144cb3d61c201c767aaf5f447db6cada161135cf70e5` |
| APK size | 120,363,526 bytes |
| Signer certificate SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` |
| Pinned certificate SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` (identical) |
| Built from commit | `62b7685` |
| Build run | [45049495177](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45049495177) |
| Release page | https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/tag/rc-1.1.42-rc3 |
| Unit tests at build | 64 suites / 320 tests / 0 failures / 0 errors / 0 skipped |
| R8 / minify | disabled (unchanged) |
| applicationId | `com.manhwalens.app` (unchanged) |

## Install requirements

- Requires versionCode **≥ 217442872** to install over an existing build.
- Same pinned production certificate as the installed official app, so an update installs
  without uninstalling. A signature mismatch here would mean the APK is not ours.
- This is a **prerelease**: never "Latest", never a published stable release.

## How to verify what you downloaded

```bash
sha256sum ManhwaLens-1.1.42-rc3-release.apk
# bab3d7e03d3be4624328144cb3d61c201c767aaf5f447db6cada161135cf70e5
```

Matching hash means the file is byte-identical to the one the build verified and signed.

## Previous candidate

`1.1.42-rc2` — versionCode `217442871`,
sha256 `b10682ca19e42b4374b6d62d636d7082967583eefd4bd5e548a96958ea8bb86d`,
[download](https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc2/ManhwaLens-1.1.42-rc2-release.apk).
It contains the name-protection and OCR-integrity work but **not** the separated diagnostics
codes. Superseded by rc3 on device (higher versionCode).

## Not claimed

No physical-device verification of the reported cases (`A HORNED RABBIT`, `LIZzy`/`DOROTHY`)
has been performed on this build yet, and no semantic translation-quality gain is claimed.
See [`PHASE-4.4-REPORT.md`](PHASE-4.4-REPORT.md) for the evidence that does exist.
