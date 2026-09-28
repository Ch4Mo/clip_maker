#!/usr/bin/env bash
# Smoke test run on an Android emulator by CI: installs the debug APK, walks the
# main flow (launch -> new project -> editor), then fires random UI events.
# Any crash is printed to the job log and fails the job.
set -u
PKG=com.clipmaker.app
OUT=smoke-output
mkdir -p "$OUT"

adb logcat -c
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

# Taps the centre of the first UI node whose text/content-desc matches $1.
tap_text() {
  local bounds="" try
  for try in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 \
      | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
    [ -n "$bounds" ] && break
    sleep 2
  done
  if [ -z "$bounds" ]; then echo "SMOKE: '$1' not found on screen"; return 1; fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "SMOKE: tapped '$1'"
}

step() {
  local name=$1
  sleep "${2:-4}"
  adb exec-out screencap -p > "$OUT/$name.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/$name.xml"
  echo "SMOKE: [$name] visible texts:"
  tr '>' '\n' < "$OUT/$name.xml" | grep -oE '(text|content-desc)="[^"]+"' | sort -u | head -40
  if ! adb shell pidof "$PKG" >/dev/null; then echo "SMOKE: app process is gone after step '$name'"; fi
}

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
step 01-home 8
tap_text "Nouveau projet" && step 02-new-project-dialog 3
tap_text "Créer" && step 03-editor 8

adb shell monkey -p "$PKG" --pct-syskeys 0 --throttle 150 -s 42 -v 1000 > "$OUT/monkey.txt" 2>&1
step 04-after-monkey 3

adb logcat -d > "$OUT/logcat.txt"
echo "================ CRASHES ================"
if grep -qE "FATAL EXCEPTION|ANR in $PKG|Fatal signal" "$OUT/logcat.txt"; then
  grep -E -A60 "FATAL EXCEPTION|ANR in $PKG|Fatal signal" "$OUT/logcat.txt" | head -300
  exit 1
fi
echo "No crash detected."
