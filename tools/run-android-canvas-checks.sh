#!/usr/bin/env bash
set -euo pipefail
# Capture shared test media while the emulator is alive, including failed test runs.
trap 'mkdir -p app/build/reports/ui-checks; adb pull /sdcard/Pictures/WE-CI app/build/reports/ui-checks >/dev/null 2>&1 || true' EXIT
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.opc.baize.AppUiDeviceTest,com.opc.baize.agent.NativeAgentDeviceTest,com.opc.baize.mcp.McpDeviceTest,com.opc.baize.paper.DrawingDeviceTest,com.opc.baize.mcp.McpWebViewDeviceTest,com.opc.baize.mcp.McpPublicRelayDeviceTest --no-daemon
bash tools/check-persistent-session-device.sh
