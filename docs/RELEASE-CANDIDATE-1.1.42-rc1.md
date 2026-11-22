# Release candidate 1.1.42-rc1 — official signed RELEASE build

Phase 4.3 deliverable: a build that can be installed on a physical phone for real-device
testing. It is an `assembleRelease` APK signed with the pinned production certificate.
No debug APK was built, no key was generated, nothing was published.

## Run and commit

| Item | Value |
| --- | --- |
| Commit under test | `7ec2dcf033d887d46c6bcd0fe32c77c460cd5a2b` |
| Workflow | `.github/workflows/release-candidate.yml` — "Official release candidate" |
| Run | [45032891506](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45032891506) (success, every step green) |
| Artifact | `ManhwaLens-1.1.42-rc1-release-apk` (61,215,679 bytes zipped) |
| Artifact id | `14758209038` |
| Test evidence artifact | `release-candidate-test-evidence` (id `14758577819`) |

Artifact download URL (needs an authenticated GitHub session; the Actions CDN blocks the
agent sandbox, so the numbers below were read from the run's own annotations):

```
https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45032891506/artifacts/14758209038
```

The job checks out the branch head, records `git rev-parse HEAD`, and only uploads what
that checkout produced — the APK therefore comes from exactly the commit above.

## Identity of the artefact

| Item | Value |
| --- | --- |
| package (applicationId) | `com.manhwalens.app` (unchanged) |
| versionName | `1.1.42-rc1` |
| versionCode | `217442870` (= last published `217442869` + 1) |
| signer certificate SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` |
| pinned production certificate | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` (match) |
| APK SHA-256 | `e50ffe9f59e1a83312277be09ce5b97010509ef443c494946ae0472f5031cfec` |
| APK size | 120,347,142 bytes |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |

### "Original vs generated" certificate

`signing/release-cert.sha256` pins the identity that has signed every release since
v1.1.10. The pre-1.1.10 certificate was lost and is recorded separately in
`signing/legacy-release-cert.sha256`:

| Identity | SHA-256 | status |
| --- | --- | --- |
| legacy / lost original (v1.1.1–v1.1.6) | `a1f2847fbb8f321aab4e4889f41e9d6970483185ced127978b29fa4aa54ee113` | irrecoverable, not used |
| current production signer | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` | used for this build |

This candidate was signed with the **current production signer** — no new key was
generated, no replacement identity was introduced.

## What actually executed

Executed in run 45032891506 (all steps `success`):

1. `.github/tests` regression suite — 26 tests (signing vault, keystore decoder, APK
   verifier, translation audit).
2. Version identity resolution: `-rc` versionName enforced, per-commit code computed,
   monotonicity proven against the newest published release (v1.1.41 → `217442869`).
3. Production keystore decoded from `signing/release-key.enc.json` inside the runner,
   its certificate compared to the pin with `keytool` **before** any release task ran.
4. `:app:compileReleaseKotlin`.
5. `:app:testReleaseUnitTest` — 59 suites, **266 tests, 0 failures, 0 errors, 0 skipped**.
6. `:app:compileDebugAndroidTestKotlin` (instrumented test sources still compile).
7. `assembleRelease` with the production signing config.
8. `apksigner verify --print-certs` + `aapt dump badging` through
   `.github/scripts/verify_release_apk.py`: signed, single signer, signer == pin,
   package unchanged, versionName/versionCode correct, `debuggable` absent.
9. APK SHA-256 computed, identity report + `SHA256SUMS.txt` written, signed APK uploaded.
10. Keystore deleted from the runner (`if: always()`).

Not executed in this phase: on-device installation and runtime testing (that is the
physical-device step this APK exists for), and emulator instrumented tests (they run in
the separate quality workflow, not here).

## R8 / ProGuard status

Unchanged project configuration: the release build type keeps `isMinifyEnabled = false`,
`isShrinkResources = false`, `isDebuggable = false`, with the proguard files declared but
effectively inert. R8 was **not** newly enabled in this phase; the resulting DEX is
unminified, which is also why the APK is ~120 MB.

## Guardrails that were added (and are enforced, not described)

* `app/build.gradle.kts`: every release task now requires the production keystore; a
  candidate build additionally refuses the `ALLOW_UNSIGNED_RELEASE` escape hatch via
  `CANDIDATE_RELEASE_BUILD=true`, so an unsigned or debug-signed "release" cannot be
  produced from this workflow.
* `CANDIDATE_VERSION_CODE_HOLD`: a candidate ships `last_published + 1` instead of the
  per-commit number, so testing a candidate never burns the code the eventual tagged
  release needs (`217442869 + 1` here).
* `.github/scripts/decode_release_keystore.py`: refuses a missing/malformed pin, refuses
  a certificate that is not the pinned one, refuses an undecryptable vault; the passphrase
  is scoped to that single step.
* `.github/scripts/verify_release_apk.py`: fails closed on unsigned APKs, several distinct
  signers, a foreign signer, a changed applicationId, a wrong versionName, a non-monotonic
  versionCode, or a debuggable APK.

## Compatibility risks (unresolved)

* Update-compatibility is verified only up to "same signing certificate as the installed
  official app". Whether the phone accepts the update also depends on its installed
  build's versionCode — the installed official build must be below `217442870`. If a newer
  internal build is installed, uninstall first (that loses app data).
* `versionCode 217442870` is deliberately just above the public release; the eventual
  tagged release of the same code will get a much larger per-commit code, so the candidate
  will be *older* than the final release for Play/update ordering.
* No physical-device validation has happened yet: capture/overlay/OCR behaviour on a real
  Xiaomi-class device remains unproven for this build.
* The APK is unminified with the full ML Kit set; installation size and cold start on
  low-RAM devices are not measured here.
* Emulator quality runs cannot stand in for real-photo OCR accuracy.
