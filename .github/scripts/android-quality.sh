#!/usr/bin/env bash
set -uo pipefail
chmod +x gradlew
status=0
./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee /tmp/quality-gradle.log || status=$?
adb logcat -d -b crash > /tmp/quality-crash.log || true
if [ "$status" -ne 0 ]; then
  grep -E '^e:|^FAILURE:|^\* What went wrong:|^Execution failed|^> ' /tmp/quality-gradle.log | head -40 | while IFS= read -r line; do
    echo "::error::${line//'%'/'%25'}"
  done
  head -70 /tmp/quality-crash.log | while IFS= read -r line; do
    echo "::error::${line//'%'/'%25'}"
  done
fi
mkdir -p app/build/reports/overlay-evidence
adb pull /sdcard/Android/data/com.manhwalens.app/files/overlay-evidence/. app/build/reports/overlay-evidence/ || true
if [ -f app/build/reports/overlay-evidence/cleanup-benchmark.txt ]; then
  echo "::notice::$(cat app/build/reports/overlay-evidence/cleanup-benchmark.txt)"
fi
exit "$status"
