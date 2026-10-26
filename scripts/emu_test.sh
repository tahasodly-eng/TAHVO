#!/usr/bin/env bash
# Real conversions on an emulator: open the ring through the debug test hook, pick slices, verify the outputs.
set -x
PKG=com.taha.sahb
OUT=test_out; mkdir -p "$OUT"
LOG="$OUT/log.txt"; : > "$LOG"
log() { echo "$(date -u +%T) $*" >> "$LOG"; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; log "shot $1"; }
DIR=/sdcard/Android/data/$PKG/files

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local xy; xy=$(python3 - "$1" <<'PY'
import re, sys, html
x = open('/tmp/ui.xml', encoding='utf-8').read()
for n in re.findall(r'<node [^>]*>', x):
    t = html.unescape((re.search(r'text="([^"]*)"', n) or [0, ''])[1])
    if t == sys.argv[1]:
        a = list(map(int, re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n).groups()))
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' $xy"; return 0; fi
  log "NOT FOUND '$1'"; return 1
}

# wait until jobs.log has N lines
jobs_n() { adb shell "cat $DIR/jobs.log 2>/dev/null | wc -l" | tr -d '\r '; }
wait_jobs() { for _ in $(seq 1 90); do n=$(jobs_n); [ "${n:-0}" -ge "$1" ] && return 0; sleep 2; done; log "TIMEOUT waiting for job $1"; J=$(( $(jobs_n) - 1 )); }

# samples
ffmpeg -hide_banner -loglevel error -f lavfi -i "color=c=0x7C4DFF@0.6:s=1200x800,format=rgba" -frames:v 1 sample.png
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=s=1280x720:d=6:r=30 -f lavfi -i sine=d=6 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -metadata comment=private-note -metadata title=SecretTitle sample.mp4
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=f=440:d=6 sample.wav

APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r -g "$APK" >> "$LOG" 2>&1
sleep 20
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >> "$LOG" 2>&1
adb shell am start -W -n $PKG/.MainActivity >> "$LOG" 2>&1   # creates the app's files dir
sleep 4
adb shell mkdir -p $DIR
for f in sample.png sample.mp4 sample.wav; do adb push $f $DIR/ >> "$LOG" 2>&1; done
adb shell setprop persist.sys.locale ar-EG; adb shell setprop ctl.restart zygote
sleep 8; adb wait-for-device
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 25
adb shell am start -W -n $PKG/.MainActivity >> "$LOG" 2>&1
shot 00_home 4

ring() { adb shell am start -W -n $PKG/.RingActivity --es test_path "$DIR/$1" ${2:+--ez tools true} >> "$LOG" 2>&1; }
pick() { adb shell am start -f 0x20000000 -n $PKG/.RingActivity --ei pick $1 ${2:+--ez tools true} >> "$LOG" 2>&1; }
close() { adb shell input keyevent 4; sleep 1; adb shell am force-stop $PKG; sleep 1; }

J=0
# image: ring screenshot, JPG, PDF
ring sample.png; shot 01_ring_image 3
pick 0; J=$((J+1)); wait_jobs $J; shot 02_result_jpg 1; close
ring sample.png; pick 3; J=$((J+1)); wait_jobs $J; close
# image tools: ring screenshot, compress, crop 16:9
ring sample.png 1; shot 03_ring_image_tools 3
pick 0 1; J=$((J+1)); wait_jobs $J; close
ring sample.png 1; pick 2 1; sleep 2; shot 04_crop_dialog 1; tap_text "16:9"; J=$((J+1)); wait_jobs $J; close
# video: ring, MP4, M4A, frame
ring sample.mp4; shot 05_ring_video 3
pick 0; sleep 3; shot 06_progress_video 0; J=$((J+1)); wait_jobs $J; shot 07_result_video 1; close
ring sample.mp4; pick 1; J=$((J+1)); wait_jobs $J; close
ring sample.mp4; pick 2; J=$((J+1)); wait_jobs $J; close
# video tools: compress, trim 1-3s, mute, clean
ring sample.mp4 1; shot 08_ring_video_tools 3
pick 0 1; J=$((J+1)); wait_jobs $J; close
ring sample.mp4 1; pick 3 1
for _ in $(seq 1 40); do
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
grep -q 'android.widget.EditText' /tmp/ui.xml && break; sleep 1; done
python3 - <<'PY' > /tmp/fields.txt
import re
x=open('/tmp/ui.xml',encoding='utf-8').read()
for n in re.findall(r'<node [^>]*class="android.widget.EditText"[^>]*>', x):
    a=list(map(int,re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2)
PY
field() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  python3 - "$1" <<'PY'
import re, sys
x=open('/tmp/ui.xml',encoding='utf-8').read()
f=[n for n in re.findall(r'<node [^>]*>', x) if 'android.widget.EditText' in n]
n=f[int(sys.argv[1])]; a=list(map(int,re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2)
PY
}
# positions are re-read after each tap because the keyboard moves the dialog
adb shell input tap $(field 0); sleep 1; adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4; do adb shell input keyevent 67; done; adb shell input text 1
sleep 1; adb shell input tap $(field 1); sleep 1; adb shell input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4; do adb shell input keyevent 67; done; adb shell input text 3
shot 09_trim_dialog 1
cp /tmp/ui.xml "$OUT/09_trim_ui.xml"
tap_text "تنفيذ" || tap_text "Apply"; J=$((J+1)); wait_jobs $J; close
ring sample.mp4 1; pick 4 1; J=$((J+1)); wait_jobs $J; close
ring sample.mp4 1; pick 5 1; J=$((J+1)); wait_jobs $J; close
# audio: M4A
ring sample.wav; shot 10_ring_audio 3
pick 0; J=$((J+1)); wait_jobs $J; close

# image -> JPG again (the very first file access right after the emulator restart can be refused)
ring sample.png; pick 0; J=$((J+1)); wait_jobs $J; shot 11_result_jpg_retry 1; close

adb pull $DIR/jobs.log "$OUT/jobs.log" >> "$LOG" 2>&1
mkdir -p "$OUT/files"
for d in Pictures Movies Music Download; do adb pull /sdcard/$d/Sahb "$OUT/files/$d" >> "$LOG" 2>&1; done
{
  echo "== outputs"
  find "$OUT/files" -type f | while read -r f; do
    echo "--- $(basename "$f") ($(stat -c%s "$f") B)"
    case "$f" in
      *.pdf|*.zip) file "$f" ;;
      *) ffprobe -v error -show_entries format=duration:format_tags=comment,title:stream=codec_name,width,height -of compact "$f" ;;
    esac
  done
} > "$OUT/verify.txt" 2>&1
# don't ship the media files themselves in the results branch
find "$OUT/files" -type f \( -name '*.mp4' -o -name '*.m4a' \) -delete
adb logcat -d -v time > /tmp/full_logcat.txt 2>&1; grep -E 'Sahb|AndroidRuntime|FATAL|com.taha.sahb' /tmp/full_logcat.txt | tail -400 > "$OUT/logcat.txt"
exit 0
