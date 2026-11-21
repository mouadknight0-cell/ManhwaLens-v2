#!/usr/bin/env bash
# Only modify package coordinates in a disposable build tree.
set -eu
cd "$(git rev-parse --show-toplevel)"
base=a0db02b822ce93edf3aa6b6d9758ebdc7db2bb2a
out=${1:-$PWD/phase3b-apks}; mkdir -p "$out"; out=$(cd "$out" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git archive "$base" | tar -x -C "$tmp"
echo 'e05c2d8f5f9f20c63680b429cb6f06621fe371eb31d08a133568bc85c0139956  docs/benchmark-phase3/dataset.tsv' | sha256sum -c -
rm -rf "$tmp/app/src/androidTest"; mkdir -p "$tmp/app/src/androidTest/"{assets,java}
cp docs/benchmark-phase3/dataset.tsv "$tmp/app/src/androidTest/assets/"
cp tools/phase3b/Benchmark.kt "$tmp/app/src/androidTest/java/"
cd "$tmp"
sed -i 's/applicationId = "com.manhwalens.app"/applicationId = "com.manhwalens.phase3b"/' app/build.gradle.kts
sed -i 's/com.manhwalens.app.fileprovider/com.manhwalens.phase3b.fileprovider/' app/src/main/AndroidManifest.xml
unset MANHWALENS_KEYSTORE_FILE
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk "$out/phase3b-host.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$out/phase3b-test.apk"
