#!/usr/bin/env bash
# Drives a debug build of TAHVO on an emulator and captures screenshots.
# Runs on the CI host inside android-emulator-runner (adb is available).
set -x
PKG=com.tahatube.app
ACT=com.deniscerri.ytdl.MainActivity
OUT=ui_out
mkdir -p "$OUT"
LOG="$OUT/ui_log.txt"
: > "$LOG"

log() { echo "$(date -u +%T) $*" >> "$LOG"; }
shot() { sleep "${2:-3}"; dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; log "shot $1"; }

# tap the centre of the first UI element whose text or content-desc contains $1
tap_text() {
  dismiss_anr
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'EOF' > /tmp/tap.txt
import re, sys, html
needle = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = html.unescape(re.search(r'text="([^"]*)"', n).group(1)) if 'text="' in n else ''
    d = html.unescape(re.search(r'content-desc="([^"]*)"', n).group(1)) if 'content-desc="' in n else ''
    if needle in t or needle in d:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
EOF
  if [ -s /tmp/tap.txt ]; then
    adb shell input tap $(cat /tmp/tap.txt)
    echo "tap '$1' at $(cat /tmp/tap.txt)" >> "$LOG"
    return 0
  fi
  echo "NOT FOUND: '$1'" >> "$LOG"
  return 1
}

# close "X isn't responding" system dialogs that the emulator shows while it settles
dismiss_anr() {
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/anr.xml >/dev/null 2>&1
    if grep -q "isn&apos;t responding\|isn't responding\|لا يستجيب" /tmp/anr.xml 2>/dev/null; then
      b=$(python3 -c "
import re,html
x=open('/tmp/anr.xml',encoding='utf-8').read()
for n in re.findall(r'<node [^>]*>',x):
    t=html.unescape((re.search(r'text=\"([^\"]*)\"',n) or [None,''])[1])
    if t in ('Wait','انتظار'):
        a=list(map(int,re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n).groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
")
      [ -n "$b" ] && adb shell input tap $b || adb shell input keyevent 4
      echo "dismissed ANR dialog" >> "$LOG"; sleep 2
    else
      return 0
    fi
  done
}

set_app_locale() {
  adb shell cmd locale set-app-locales $PKG --locales "$1" >> "$LOG" 2>&1
  log "app locale requested: $1 -> now: $(adb shell cmd locale get-app-locales $PKG 2>&1 | tr -d '\r')"
}

set_system_locale() {
  adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
  adb shell setprop persist.sys.locale "$1"
  adb shell setprop ctl.restart zygote
  sleep 8; adb wait-for-device
  for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 3; done
  sleep 20
  log "system locale now: $(adb shell getprop persist.sys.locale | tr -d '\r')"
  dismiss_anr
}

# tap by text, otherwise at fixed coordinates (1080x2400 screen)
tap_or() { tap_text "$1" || { adb shell input tap $2 $3; log "tap fallback '$1' at $2 $3"; }; }

dump_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }

APK=$(ls app/build/outputs/apk/github/debug/*x86_64*.apk | head -1)
adb install -r -g "$APK" >> "$LOG" 2>&1

# skip the first-run welcome wizard (debug build is debuggable, so run-as works)
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><boolean name=\\\"welcome_completed\\\" value=\\\"true\\\" /><boolean name=\\\"asked_auto_update_preferences\\\" value=\\\"true\\\" /><boolean name=\\\"update_app\\\" value=\\\"false\\\" /></map>\" > shared_prefs/${PKG}_preferences.xml'" >> "$LOG" 2>&1

adb shell cmd uimode night no
sleep 30
# the stock launcher keeps raising "isn't responding" on CI emulators and steals the screen; turn it off
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
for l in com.google.android.apps.nexuslauncher com.android.launcher3; do adb shell pm disable-user --user 0 $l >> "$LOG" 2>&1; done
dismiss_anr
adb shell cmd locale set-app-locales $PKG --locales "" >> "$LOG" 2>&1
adb shell am force-stop $PKG
set_system_locale ar-EG

# ---- Arabic, light ----
adb shell am start -W -n $PKG/$ACT >> "$LOG" 2>&1
shot 01_home_ar 15
log "activity config: $(adb shell dumpsys activity activities | grep -m1 -o 'mLastReportedConfiguration={[^}]*' | grep -o 'ld[a-z]*\|[a-z][a-z]-r[A-Z][A-Z]\|ar[^ ]*' | tr '\n' ' ')"
dump_text 01_home_ar
# dismiss any startup permission dialog
tap_text "Allow" || tap_text "السماح" || true
shot 02_home_ar_after_dialogs 3

# invalid link -> validation error
tap_or "ألصق رابط الفيديو" 540 606; adb shell input text "not-a-link"; adb shell input keyevent 111
tap_or "تحليل الرابط" 540 930
shot 03_invalid_link_ar 2

# ---- TAHVO browser: page with two videos -> pick -> download ----
mkdir -p site
if command -v ffmpeg >/dev/null; then
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=s=640x360:d=4:r=25 -f lavfi -i sine=d=4 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest site/clip_one.mp4
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i smptebars=s=640x360:d=3:r=25 -f lavfi -i sine=f=660:d=3 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest site/clip_two.mp4
fi
cat > site/index.html <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Sample Videos Page</title></head>
<body style="font-family:sans-serif;padding:12px"><h2>Two videos</h2>
<video controls preload="metadata" width="100%" src="clip_one.mp4"></video>
<p>Second:</p>
<video controls preload="metadata" width="100%"><source src="clip_two.mp4" type="video/mp4"></video>
</body></html>
HTML
: > "$OUT/server_requests.txt"
python3 scripts/test_page_server.py site "$OUT/server_requests.txt" &
SERVER=$!
adb reverse tcp:8765 tcp:8765 >> "$LOG" 2>&1
sleep 2
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/$ACT >> "$LOG" 2>&1
sleep 10
shot 20_home_browser_button_ar 2
tap_or "فتح الصفحة في متصفح TAHVO" 540 1010
shot 21_browser_start_ar 4
adb shell input text "http://127.0.0.1:8765/index.html"
adb shell input keyevent 66
shot 22_browser_page_ar 10
dump_text 22_browser_page_ar
tap_text "الفيديوهات" || tap_text "تنزيل"
shot 23_browser_sheet_ar 3
dump_text 23_browser_sheet_ar
tap_text "تحديد الكل"
shot 24_browser_selected_ar 2
tap_text "تنزيل المحدد"
shot 25_browser_queued_ar 1
sleep 45
adb shell "find /sdcard -iname '*.mp4' -newer /sdcard/ui.xml 2>/dev/null; find /sdcard/Download /sdcard/Movies -iname '*.mp4' 2>/dev/null" | sort -u > "$OUT/downloaded_files.txt"
adb shell "find /sdcard -iname '*.mp4' -exec ls -l {} \\; 2>/dev/null" >> "$OUT/downloaded_files.txt" 2>&1
# "Analyze this page" hands the same page to yt-dlp, which should list both videos
tap_text "الفيديوهات" || tap_text "تنزيل"
sleep 2
tap_text "تحليل هذه الصفحة"
shot 26_analyze_page_ar 40
dump_text 26_analyze_page_ar
kill $SERVER 2>/dev/null

adb shell input keyevent 4
sleep 2
tap_or "التنزيلات" 675 2232; shot 06_downloads_ar 4
tap_or "المكتبة" 405 2232; shot 07_library_ar 4
tap_or "الأدوات" 135 2232; shot 08_tools_ar 4
tap_or "الرئيسية" 945 2232; shot 09_home_ar_again 4

# ---- English, dark ----
adb shell am force-stop $PKG
adb shell am force-stop $PKG
set_system_locale en-US
adb shell cmd uimode night yes
adb shell am start -W -n $PKG/$ACT >> "$LOG" 2>&1
shot 10_home_en_dark 12
tap_text "Tools" && shot 11_tools_en_dark 4
tap_text "Home" && shot 12_home_en_dark_again 3

# launcher icon in the app drawer
adb shell cmd uimode night no

adb logcat -d -t 400 '*:E' > "$OUT/logcat_errors.txt" 2>&1 || true
exit 0
