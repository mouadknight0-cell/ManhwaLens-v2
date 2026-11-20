#!/usr/bin/env bash
set -uo pipefail
chmod +x gradlew
status=0
adb logcat -v brief -s PhraseTiming:I '*:S' >/tmp/phrase-timing.log &
timing_pid=$!
trap 'kill "$timing_pid" 2>/dev/null || true' EXIT
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
# Gradle may uninstall the target and remove external files after tests; logcat
# captures the benchmark before teardown as well.
measurement=$(adb logcat -d -s ManhwaLensBenchmark:I '*:S' | grep '1000 utterances' | tail -1)
if [ -n "$measurement" ]; then
  printf '%s\n' "$measurement" > app/build/reports/overlay-evidence/cleanup-benchmark.txt
  echo "::notice::$measurement"
fi
adb logcat -d -s ManhwaLensFunnel:I '*:S' | tee app/build/reports/overlay-evidence/report25-funnel.txt
grep "ManhwaLensFunnel:" app/build/reports/overlay-evidence/report25-funnel.txt | while read -r line; do echo "::notice::$line"; done
grep "startup_ms=" /tmp/phrase-timing.log | tail -2 | while IFS= read -r line; do echo "::notice::$line"; done
exit "$status"
