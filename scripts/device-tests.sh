#!/usr/bin/env bash
set -euo pipefail

test_status=0
bash gradlew :app:connectedDebugAndroidTest --console=plain || test_status=$?

# Gradle removes the tested app and its private files after instrumentation.
# Reinstall the verified APK and capture a fresh, real first-launch screen.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.hbhaq.metricme/.MainActivity
sleep 3
mkdir -p ui-screenshots
adb exec-out screencap -p > ui-screenshots/today.png
exit "$test_status"
