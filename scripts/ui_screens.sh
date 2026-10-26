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

shot() { sleep "${2:-3}"; dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$LOG"; }

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

set_system_locale() {
  adb root >/dev/null 2>&1; sleep 3
  adb shell setprop persist.sys.locale "$1"
  adb shell setprop ctl.restart zygote
  sleep 5; adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
  sleep 25
  dismiss_anr
  echo "system locale -> $1" >> "$LOG"
}

dump_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }

APK=$(ls app/build/outputs/apk/github/debug/*x86_64*.apk | head -1)
adb install -r -g "$APK" >> "$LOG" 2>&1

# skip the first-run welcome wizard (debug build is debuggable, so run-as works)
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><boolean name=\\\"welcome_completed\\\" value=\\\"true\\\" /><boolean name=\\\"asked_auto_update_preferences\\\" value=\\\"true\\\" /><boolean name=\\\"update_app\\\" value=\\\"false\\\" /></map>\" > shared_prefs/${PKG}_preferences.xml'" >> "$LOG" 2>&1

adb shell cmd uimode night no
set_system_locale ar-EG

# ---- Arabic, light ----
adb shell am start -W -n $PKG/$ACT >> "$LOG" 2>&1
shot 01_home_ar 15
dump_text 01_home_ar
# dismiss any startup permission dialog
tap_text "Allow" || tap_text "السماح" || true
shot 02_home_ar_after_dialogs 3

# invalid link -> validation error
tap_text "ألصق رابط الفيديو" && adb shell input text "not-a-link" && adb shell input keyevent 111
tap_text "تحليل الرابط"
shot 03_invalid_link_ar 2

# real TikTok link -> extraction
adb shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 20); do adb shell input keyevent 67; done
tap_text "not-a-link" || tap_text "ألصق رابط الفيديو" || true
adb shell input text "https://vt.tiktok.com/ZSg9fBwLS/"
adb shell input keyevent 111
tap_text "تحليل الرابط"
shot 04_analyzing_ar 4
shot 05_after_analyze_ar 40
dump_text 05_after_analyze_ar

adb shell input keyevent 4
sleep 2
tap_text "التنزيلات" && shot 06_downloads_ar 4
tap_text "المكتبة" && shot 07_library_ar 4
tap_text "الأدوات" && shot 08_tools_ar 4
tap_text "الرئيسية" && shot 09_home_ar_again 4

# ---- English, dark ----
adb shell am force-stop $PKG
set_system_locale en-US
adb shell cmd uimode night yes
adb shell am start -W -n $PKG/$ACT >> "$LOG" 2>&1
shot 10_home_en_dark 12
tap_text "Tools" && shot 11_tools_en_dark 4
tap_text "Home" && shot 12_home_en_dark_again 3

# launcher icon in the app drawer
adb shell cmd uimode night no
adb shell input keyevent 3
sleep 2
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
adb shell input swipe 540 1800 540 400 300
shot 13_app_drawer 3

adb logcat -d -t 400 '*:E' > "$OUT/logcat_errors.txt" 2>&1 || true
exit 0
