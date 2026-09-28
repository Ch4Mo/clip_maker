#!/usr/bin/env bash
# Smoke test run on an Android emulator by CI. Installs the debug APK, opens the fixture projects
# of .github/smoke (real video and music generated with ffmpeg), plays and exports them, then walks
# the "new project" flow and fires random UI events. Errors shown by the app and crashes are
# printed to the job log and fail the job.
set -u
PKG=com.clipmaker.app
OUT=smoke-output
FAILED=0
mkdir -p "$OUT"

adb logcat -c
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

# Media referenced by the fixtures, copied into the app's private storage.
adb push "$OUT/media/sample.mp4" "$OUT/media/song.m4a" /data/local/tmp/ >/dev/null
adb shell chmod 644 /data/local/tmp/sample.mp4 /data/local/tmp/song.m4a
adb shell run-as "$PKG" mkdir -p files/smoke
adb shell run-as "$PKG" cp /data/local/tmp/sample.mp4 /data/local/tmp/song.m4a files/smoke/
for f in .github/smoke/*.json; do
  id=$(basename "$f" .json)
  adb push "$f" /data/local/tmp/project.json >/dev/null
  adb shell chmod 644 /data/local/tmp/project.json
  adb shell run-as "$PKG" mkdir -p "files/projects/$id"
  adb shell run-as "$PKG" cp /data/local/tmp/project.json "files/projects/$id/project.json"
done

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml
}

# Taps the centre of the first UI node whose text/content-desc matches $1.
tap_text() {
  local bounds="" try
  for try in 1 2 3 4 5; do
    bounds=$(dump_ui | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 \
      | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
    [ -n "$bounds" ] && break
    sleep 2
  done
  if [ -z "$bounds" ]; then echo "SMOKE: '$1' not found on screen"; FAILED=1; return 1; fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "SMOKE: tapped '$1'"
}

# Waits, then saves a screenshot and UI dump and prints the visible texts. Error messages shown by
# the app (preview or export failures) fail the job.
step() {
  local name=$1
  sleep "${2:-4}"
  adb exec-out screencap -p > "$OUT/$name.png"
  dump_ui > "$OUT/$name.xml"
  echo "SMOKE: [$name] visible texts:"
  tr '>' '\n' < "$OUT/$name.xml" | grep -oE '(text|content-desc)="[^"]+"' | sort -u | head -50
  if grep -qE 'text="(Aperçu :|Échec de l)' "$OUT/$name.xml"; then
    echo "SMOKE: ERROR shown by the app at step '$name':"
    tr '>' '\n' < "$OUT/$name.xml" | grep -oE 'text="(Aperçu :|Échec de l)[^"]*"'
    FAILED=1
  fi
  if ! adb shell pidof "$PKG" >/dev/null; then echo "SMOKE: app process is gone after step '$name'"; FAILED=1; fi
}

launch() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
}

for f in .github/smoke/*.json; do
  id=$(basename "$f" .json)
  launch
  step "$id-01-home" 8
  tap_text "$id" || continue
  step "$id-02-editor" 8
  tap_text "Lecture" && step "$id-03-playing" 6
  tap_text "Exporter" && step "$id-04-export-screen" 3
  tap_text "Exporter la vidéo" && step "$id-05-exported" 60
done

launch
step 10-home 6
# The floating "Nouveau projet" button is not always exposed to uiautomator: fall back to its
# position (bottom-right, above the navigation bar).
before=$FAILED
if ! tap_text "Nouveau projet"; then
  FAILED=$before
  size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
  density=$(adb shell wm density | grep -oE '[0-9]+' | tail -1)
  w=${size%x*}; h=${size#*x}
  adb shell input tap $(( w - 96 * density / 160 )) $(( h - 92 * density / 160 ))
  echo "SMOKE: tapped bottom-right FAB position"
fi
step 11-new-project-dialog 3
tap_text "Créer" && step 12-editor 8

adb shell monkey -p "$PKG" --pct-syskeys 0 --throttle 150 -s 42 -v 1000 > "$OUT/monkey.txt" 2>&1
step 13-after-monkey 3

adb logcat -d > "$OUT/logcat.txt"
echo "================ APP ERRORS ================"
grep -E " E (ClipMaker|CompositionPlayer|Transformer|ExoPlayerImplInternal|AndroidRuntime) *:" -A40 "$OUT/logcat.txt" | head -400
echo "================ CRASHES ================"
if grep -qE "FATAL EXCEPTION|ANR in $PKG|Fatal signal" "$OUT/logcat.txt"; then
  grep -E -A60 "FATAL EXCEPTION|ANR in $PKG|Fatal signal" "$OUT/logcat.txt" | head -300
  FAILED=1
else
  echo "No crash detected."
fi
exit $FAILED
