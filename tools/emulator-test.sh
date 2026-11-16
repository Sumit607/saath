#!/usr/bin/env bash
# Runs inside the emulator job: installs the debug APK, starts a party with the test beat,
# then joins it from three headless browsers through adb port forwarding.
set -uo pipefail
PKG=com.saath.app.debug

fail() {
  echo "::error::$1"
  echo "----- logcat -----"
  adb logcat -d -t 400 | grep -iE "saath|AndroidRuntime|FATAL" | tail -120
  exit 1
}

adb install -r -g saath-debug.apk || fail "APK did not install"
adb logcat -c
adb shell am start -n "$PKG/com.saath.app.MainActivity" --ez autotest true || fail "App did not start"

for i in $(seq 1 30); do
  sleep 2
  adb forward tcp:8080 tcp:8080 >/dev/null 2>&1
  if curl -sf "http://127.0.0.1:8080/info?token=AUTOTEST" >/tmp/info.json; then break; fi
done
cat /tmp/info.json 2>/dev/null || fail "The app's party server never answered"
echo
adb shell pidof "$PKG" >/dev/null || fail "The app crashed after starting"

python tools/e2e/e2e_listeners.py --base http://127.0.0.1:8080 --token AUTOTEST || fail "Listener test against the app failed"

adb shell pidof "$PKG" >/dev/null || fail "The app crashed during the test"
echo "Emulator test passed"
