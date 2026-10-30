#!/usr/bin/env bash
set -euo pipefail
mkdir -p session-device-results
session_app_apk=app/build/outputs/apk/debug/app-debug.apk
session_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
session_aapt="$ANDROID_HOME/build-tools/34.0.0/aapt"
session_app_id=$("$session_aapt" dump badging "$session_app_apk" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
session_test_id=$("$session_aapt" dump badging "$session_test_apk" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
test -n "$session_app_id"
test -n "$session_test_id"
adb install -r "$session_app_apk"
adb install -r "$session_test_apk"
session_run_case() {
  local session_case="$1"
  adb shell am instrument -w -e class "com.opc.baize.subscription.PersistentSessionDeviceTest#$session_case" \
    "$session_test_id/androidx.test.runner.AndroidJUnitRunner" | tee "session-device-results/$session_case.log"
  grep -q 'OK (1 test)' "session-device-results/$session_case.log"
}
session_run_case seedEncryptedVerifiedSession
adb shell am force-stop "$session_app_id"
session_run_case restoresAfterForceStopAndClearPreventsResurrection
session_run_case rejectsTamperedCiphertextAndKeepsExpiredRefreshState
