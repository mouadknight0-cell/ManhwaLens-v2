#!/usr/bin/env bash
# USB-debuggable Android 14 device. Launch the reader and start live mode first.
# Use wireless adb on battery for drain tests; a charging trace is not a discharge measurement.
set -euo pipefail
out=${1:?usage: profile-reader.sh NEW_OUTPUT_DIRECTORY [seconds]}; seconds=${2:-600}
[[ $seconds =~ ^[0-9]+$ ]] && ((seconds >= 60)) || exit 2
mkdir "$out" # never overwrite earlier evidence
pkg=com.manhwalens.app
adb shell pidof "$pkg" >/dev/null
adb shell getprop ro.build.fingerprint >"$out/fingerprint.txt"
snapshot(){
    adb shell dumpsys battery >"$out/battery-$1.txt"
    adb shell dumpsys thermalservice >"$out/thermal-$1.txt"
    adb shell dumpsys meminfo "$pkg" >"$out/memory-$1.txt"
}
snapshot start
adb shell dumpsys gfxinfo "$pkg" reset >/dev/null
remote=/data/misc/perfetto-traces/mlens-$$.pftrace
# Capture a bounded scheduling/FrameTimeline trace during the first minute.
adb shell perfetto --txt -c - -o "$remote" >"$out/perfetto.log" 2>&1 <<'CONFIG' &
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_switch" ftrace_events: "power/cpu_frequency" atrace_categories: "gfx" atrace_categories: "dalvik" atrace_apps: "com.manhwalens.app" } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: 60000
CONFIG
pid=$!
start=$SECONDS
while ((SECONDS-start < seconds)); do
    { echo "elapsed=$((SECONDS-start))"; adb shell dumpsys meminfo "$pkg"; } >>"$out/memory-series.txt"
    sleep 10
done
snapshot end
wait "$pid"; adb pull "$remote" "$out/reader.pftrace"; adb shell rm "$remote"
adb shell dumpsys gfxinfo "$pkg" framestats >"$out/framestats.txt"
