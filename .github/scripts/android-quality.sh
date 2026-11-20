#!/usr/bin/env bash
set -uo pipefail
chmod +x gradlew
status=0
./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee /tmp/quality-gradle.log || status=$?
adb logcat -d -b crash > /tmp/quality-crash.log || true
if [ "$status" -ne 0 ]; then
  head -70 /tmp/quality-crash.log | while IFS= read -r line; do
    echo "::error::${line//'%'/'%25'}"
  done
fi
mkdir -p app/build/reports/overlay-evidence
adb pull /sdcard/Android/data/com.manhwalens.app/files/overlay-evidence/. app/build/reports/overlay-evidence/ || true
exit "$status"
