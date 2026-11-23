#!/usr/bin/env bash
set -uo pipefail
chmod +x gradlew
# The default 256 KB logcat ring drops the instrumented funnel lines before the run ends, so only
# the last few were reaching the job summary and the 4/5-line rows were invisible in the report.
adb logcat -G 16M >/dev/null 2>&1 || true
status=0
./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee /tmp/quality-gradle.log || status=$?
adb logcat -d -b crash > /tmp/quality-crash.log || true
if [ "$status" -ne 0 ]; then
  # Actionable lines first: a bare "head -40" of "> Task" noise hid the real failure.
  grep -E 'FAILED|AssertionError|ComparisonFailure|expected:|Caused by:|What went wrong' /tmp/quality-gradle.log | head -25 | while IFS= read -r line; do
    echo "::error::${line//'%'/'%25'}"
  done
  grep -E 'tests? completed.*failed|Tests on .* failed' /tmp/quality-gradle.log | head -5 | while IFS= read -r line; do
    echo "::error::${line//'%'/'%25'}"
  done
  # Per-test failure detail from the JUnit XML when it exists.
  find app/build/outputs/androidTest-results -name '*.xml' -print0 2>/dev/null | while IFS= read -r -d '' f; do
    awk -v file="$f" '/<(failure|error)[ >]/{gsub(/\n/," "); print "::error file=" file "::" $0}' "$f" | head -12
  done
  head -40 /tmp/quality-crash.log | while IFS= read -r line; do
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
grep "ManhwaLensFunnel:" app/build/reports/overlay-evidence/report25-funnel.txt | while IFS= read -r line; do echo "::notice::$line"; done
exit "$status"
