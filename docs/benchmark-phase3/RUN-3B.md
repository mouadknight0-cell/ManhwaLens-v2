# Phase 3B · local phone execution

**Not run on a model-ready device.** The paired APKs are in the [CI artifact](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44886766542/artifacts/14700312747). Download while signed in, then unzip. Alternative build: from the repository with Android SDK 35, JDK 17 and the baseline commit available, `bash tools/phase3b/build.sh`; both APKs go in `phase3b-apks/`. Do not mix pairs from different builds (debug signing keys can differ).

1. Connect an Android 8+ phone with USB debugging. Check `adb devices -l`; use just one authorized device. `adb shell pm list packages com.manhwalens.phase3b` should be empty for a new installation. If these benchmark packages already exist, use the existing matched pair or stop. Do not remove the reader or clear any package data.
2. Install **both** APKs, without replacement flags:

   ```sh
   adb install phase3b-host.apk
   adb install phase3b-test.apk
   adb shell pm list instrumentation
   ```

   The new target is `com.manhwalens.phase3b`, not `com.manhwalens.app`. It has the baseline code in a separate sandbox. Its launcher looks like the regular reader; you don't need to open either reader to run the test. No media projection, overlay permission, or private pages needed. Model availability is **package-scoped**: installation in the regular reader does not establish availability here. The runner checks the isolated host and stops before calling the production readiness probe if its Arabic model isn't installed. It cannot copy a private model from the normal reader. If not already provisioned for this host, stop; do not enable networking as part of the benchmark.
3. Disable Wi-Fi and cellular data (airplane mode, USB debugging still connected). Run:

   ```sh
   adb shell am instrument -w -r -e class 'benchmark.phase3b.Benchmark#cleanEnglish' com.manhwalens.phase3b.test/androidx.test.runner.AndroidJUnitRunner
   adb exec-out run-as com.manhwalens.phase3b cat files/phase3_results.json > phase3_results.json
   python3 docs/benchmark-phase3/export_clean.py phase3_results.json phase3-phone-export
   ```

4. Check timestamps, `already_installed`, `ready`, any `run_error`, and **both arm statuses in every row**, even on JUnit success. A missing-model run should fail the test and yield 40 `NOT_RUN` rows. Copy the JSON before rerunning: each invocation replaces only the harness result. The exporter refuses an existing destination and writes a separate CSV and observation ledger there. No references, scene descriptions or scores are passed into MT. No results go to a server.

## Reading the evidence

- `app` invokes the unchanged `translateBlock` with a fixed region at confidence 1, previous English only within a scene; context arms and independent crops have null previous. Its `final_ar` is the **block** output, not evidence of on-screen display. A deterministic local answer has no native raw output. For hybrids, `raw_ar` covers only the native suffix.
- `native_only` separately runs production preparation and `translateText` on the full line, even if `app` used a glossary, local rule or rejected the line. This control is **not** the selected reader route. `restored_ar` is a diagnostic restoration of raw output, not approval by validation. Use `final_ar` and the status for acceptance. Null/absent fields mean unavailable, not an empty native translation.
- A test-only delegating translator records the real SDK argument and task, returning that same task to the caller. Late/pending tasks are flagged rather than treated as successful outputs. Production timeouts remain in force. The client's translation cache is cleared per item and per arm; the SDK client and model stay warm after the readiness probe. No warm-cache pass. Compare paired IDs as in REPORT.md. Do not grade missing, rejected or unexecuted native outputs as fluent or inaccurate Arabic.
