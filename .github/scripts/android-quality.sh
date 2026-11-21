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
# Gradle may uninstall the target and remove external files after tests; logcat
# captures the benchmark before teardown as well.
measurement=$(adb logcat -d -s ManhwaLensBenchmark:I '*:S' | grep '1000 utterances' | tail -1)
if [ -n "$measurement" ]; then
  printf '%s\n' "$measurement" > app/build/reports/overlay-evidence/cleanup-benchmark.txt
  echo "::notice::$measurement"
fi
adb logcat -d -s ManhwaLensFunnel:I '*:S' | tee app/build/reports/overlay-evidence/report25-funnel.txt
grep "ManhwaLensFunnel:" app/build/reports/overlay-evidence/report25-funnel.txt | while read -r line; do echo "::notice::$line"; done
# Keep all comparison arms in one annotation (GitHub caps annotations per step).
summary=$(grep -E 'perf33 mode=|moved_crop pass=' app/build/reports/overlay-evidence/report25-funnel.txt | sed 's/^.*ManhwaLensFunnel: //' | paste -sd ';' -)
[ -z "$summary" ] || echo "::notice::OCR comparison: $summary"
exit "$status"
