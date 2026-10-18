#!/usr/bin/env bash
set -euo pipefail

# The emulator action shuts the device down when this script returns.
# Collect screenshots before teardown, including when a later test fails.
collect_screenshots() {
  adb pull /sdcard/Android/data/com.hbhaq.metricme/files/screenshots ui-screenshots || true
}
trap collect_screenshots EXIT
bash gradlew :app:connectedDebugAndroidTest --console=plain
