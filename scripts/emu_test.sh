#!/usr/bin/env bash
# Real conversions on an emulator through the UI flow (ring -> settings sheet -> run -> result), Arabic and English.
set -x
PKG=com.taha.sahb
OUT=test_out; mkdir -p "$OUT"
LOG="$OUT/log.txt"; : > "$LOG"
log() { echo "$(date -u +%T) $*" >> "$LOG"; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; log "shot $1"; }
DIR=/sdcard/Android/data/$PKG/files

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
# tap the first node whose text equals $1 (waits up to 10 s for it to appear)
tap_text() {
  for _ in $(seq 1 10); do
    dump
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
    sleep 1
  done
  log "NOT FOUND '$1'"; return 1
}
# centre of the Nth EditText currently on screen
field_xy() { dump; python3 - "$1" <<'PY'
import re, sys
x=open('/tmp/ui.xml',encoding='utf-8').read()
f=[n for n in re.findall(r'<node [^>]*>', x) if 'android.widget.EditText' in n]
n=f[int(sys.argv[1])]; a=list(map(int,re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2)
PY
}
set_field() { adb shell input tap $(field_xy $1); sleep 1; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; sleep 1; }

jobs_n() { adb shell "grep -cE '^(OK|FAIL|CANCEL)' $DIR/jobs.log 2>/dev/null" | tr -d '\r '; }
NEXT=0
expect_job() { NEXT=$(( $(jobs_n) + 1 )); }
wait_job() { for _ in $(seq 1 90); do [ "$(jobs_n)" -ge "$NEXT" ] && return 0; sleep 2; done; log "TIMEOUT"; }

PRO=false
ring() { adb shell am start -W -n $PKG/.RingActivity --es test_path "'$1'" ${2:+--ez tools true} --ez pro $PRO >> "$LOG" 2>&1; sleep 2; }
# pick slice N; "auto" skips the sheet for options-free actions (still records the job)
pick() { adb shell am start -f 0x20000000 -n $PKG/.RingActivity --ei pick $1 ${2:+--ez tools true} ${3:+--ez auto true} >> "$LOG" 2>&1; sleep 2; }
close() { adb shell am force-stop $PKG; sleep 1; }

set_locale() {
  adb shell setprop persist.sys.locale "$1"; adb shell setprop ctl.restart zygote
  sleep 8; adb wait-for-device
  for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
  sleep 25; log "locale $1"
}

# ---------------------------------------------------------------- samples
ffmpeg -hide_banner -loglevel error -f lavfi -i "color=c=0x2DD4BF:s=1200x800,format=rgba,drawbox=x=100:y=100:w=400:h=300:color=0x0B1220@1:t=fill" -frames:v 1 sample.png
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=s=1280x720:d=6:r=30 -f lavfi -i sine=d=6 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -metadata comment=private-note -metadata title=SecretTitle sample.mp4
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=f=440:d=6 sample.wav
# 4-minute low-res clip for the free 3-minute limit
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=0x115E59:s=320x240:d=240:r=5 -f lavfi -i sine=d=240 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest long.mp4
python3 - <<'PY'
# minimal valid 3-page PDF with text
def pdf(path, pages):
    objs = []
    objs.append("<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{3+i*2} 0 R" for i in range(pages))
    objs.append(f"<< /Type /Pages /Kids [{kids}] /Count {pages} >>")
    for i in range(pages):
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents {4+i*2} 0 R /Resources << /Font << /F1 {3+pages*2} 0 R >> >> >>")
        s = f"BT /F1 48 Tf 100 700 Td (Page {i+1}) Tj ET"
        objs.append(f"<< /Length {len(s)} >>\nstream\n{s}\nendstream")
    objs.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    out = "%PDF-1.4\n"; offs = []
    for n, o in enumerate(objs, 1):
        offs.append(len(out.encode())); out += f"{n} 0 obj\n{o}\nendobj\n"
    x = len(out.encode())
    out += f"xref\n0 {len(objs)+1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offs)
    out += f"trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n"
    open(path, "w").write(out)
pdf("doc.pdf", 3); pdf("doc2.pdf", 2)
PY

APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r -g "$APK" >> "$LOG" 2>&1
sleep 15
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >> "$LOG" 2>&1
adb shell am start -W -n $PKG/.MainActivity >> "$LOG" 2>&1; sleep 3
adb shell mkdir -p $DIR
cp sample.png sample2.png
for f in sample.png sample2.png sample.mp4 sample.wav doc.pdf doc2.pdf long.mp4; do adb push $f $DIR/ >> "$LOG" 2>&1; done
# touch every file once from the app side (first access after a push can be refused right after boot)
ring $DIR/sample.png; close

set_locale ar-EG
ring $DIR/sample.png; close   # first app access after the restart
IMG=$DIR/sample.png; VID=$DIR/sample.mp4; AUD=$DIR/sample.wav; PDF=$DIR/doc.pdf

# ---------------- Arabic screens — FREE mode (locks, paywall, free limits)
PRO=false
adb shell am start -W -n $PKG/.MainActivity --ez pro false >> "$LOG" 2>&1; shot 01_home_ar 4
ring $IMG; shot 02_ring_image_ar 2
pick 0; shot 03_sheet_jpg_ar 2
expect_job; tap_text "بدء التحويل"; wait_job; shot 04_result_ar 2; close
ring $IMG 1; shot 05_ring_image_tools_ar 2
pick 1 1; shot 13_paywall_tool_ar 2; close                                         # resize is Pro
ring $IMG 1; expect_job; pick 0 1 auto; wait_job; close                            # compress image is free
ring $VID; shot 06_ring_video_ar 2; close
ring $VID; expect_job; pick 0 "" auto; wait_job; close                              # MP4 on a short clip is free
ring $DIR/long.mp4; pick 0 "" auto; shot 14_paywall_long_ar 4; close                # longer than 3 minutes
ring "$IMG|$DIR/sample2.png"; shot 15_ring_multi_locked_ar 2; pick 0 "" auto; shot 16_paywall_multi_ar 2; close
ring $AUD; shot 07_single_audio_ar 2; close
ring $PDF; shot 08_ring_pdf_ar 2; close
adb shell grep -c PAYWALL $DIR/jobs.log > "$OUT/paywall_count.txt" 2>&1

# ---------------- Pro mode: every tool must work
PRO=true
ring $IMG 1; shot 17_ring_image_tools_pro_ar 2; close

# ---------------- functional runs
ring $IMG; expect_job; pick 3 "" auto; wait_job; close                              # image -> PDF
ring $IMG 1; expect_job; pick 0 1 auto; wait_job; close                            # compress
ring $IMG 1; pick 2 1; tap_text "16:9"; expect_job; tap_text "قص الإطار"; wait_job; close
ring $IMG 1; pick 3 1; set_field 0 "TahaTest"; shot 09_sheet_watermark_ar 1; expect_job; tap_text "إضافة العلامة"; wait_job; close
ring $VID; expect_job; pick 0 "" auto; wait_job; close                              # MP4
ring $VID; expect_job; pick 1 "" auto; wait_job; close                              # M4A
ring $VID; expect_job; pick 2 "" auto; wait_job; close                              # snapshot
ring $VID 1; pick 3 1; set_field 0 1; set_field 1 3; shot 10_sheet_trim_ar 1; expect_job; tap_text "اقتطاع المقطع"; wait_job; close
ring $VID 1; expect_job; pick 4 1 auto; wait_job; close                            # mute
ring $VID 1; expect_job; pick 5 1 auto; wait_job; close                            # clean
ring $AUD; expect_job; tap_text "M4A"; tap_text "بدء التحويل"; wait_job; close     # single-option card
ring $PDF; expect_job; pick 0 "" auto; wait_job; close                              # PDF -> JPG pages
ring $PDF 1; expect_job; pick 0 1 auto; wait_job; close                            # PDF compress
ring $PDF 1; pick 1 1; set_field 0 2; set_field 1 3; expect_job; tap_text "حفظ الصفحات"; wait_job; close
ring $PDF 1; pick 2 1; set_field 0 "TahaTest"; expect_job; tap_text "إضافة العلامة"; wait_job; close
ring "$PDF|$DIR/doc2.pdf"; shot 11_ring_pdf_merge_ar 2; expect_job; pick 2 "" auto; wait_job; close   # merge
# cancel: start a video job and cancel it
ring $VID 1; pick 1 1; tap_text "200%"; expect_job; tap_text "تغيير الحجم"; sleep 2; shot 12_progress_ar 0; tap_text "إلغاء"; wait_job; close

# ---------------- English screens
set_locale en-US
adb shell am start -W -n $PKG/.MainActivity >> "$LOG" 2>&1; shot 20_home_en 4
PRO=false
ring $IMG; shot 21_ring_image_en 2
pick 0; shot 22_sheet_jpg_en 2; close
ring $IMG 1; pick 2 1; shot 26_paywall_en 2; close
ring $VID 1; shot 23_ring_video_tools_en 2; close
ring $PDF; shot 24_ring_pdf_en 2; close
# fallback language: French device -> English UI
set_locale fr-FR
adb shell am start -W -n $PKG/.MainActivity >> "$LOG" 2>&1; shot 25_home_fr_fallback 4

# ---------------- collect
adb pull $DIR/jobs.log "$OUT/jobs.log" >> "$LOG" 2>&1
mkdir -p "$OUT/files"
for d in Pictures Movies Music Download; do adb pull /sdcard/$d/Sahb "$OUT/files/$d" >> "$LOG" 2>&1; done
{
  find "$OUT/files" -type f | sort | while read -r f; do
    echo "--- $(basename "$f") ($(stat -c%s "$f") B)"
    case "$f" in
      *.pdf) python3 -c "import re,sys;d=open(sys.argv[1],'rb').read();print('pages:',len(re.findall(rb'/Type\s*/Page[^s]',d)))" "$f" ;;
      *.zip) file "$f" ;;
      *) ffprobe -v error -show_entries format=duration:format_tags=comment,title:stream=codec_name,width,height -of compact "$f" ;;
    esac
  done
} > "$OUT/verify.txt" 2>&1
find "$OUT/files" -type f \( -name '*.mp4' -o -name '*.m4a' \) -delete
adb logcat -d -v time > /tmp/full_logcat.txt 2>&1; grep -E 'Sahb|AndroidRuntime|FATAL' /tmp/full_logcat.txt | tail -300 > "$OUT/logcat.txt"
exit 0
