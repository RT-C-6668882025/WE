#!/usr/bin/env bash
set -euo pipefail
# Reuses the exact production HTTP bridge with synthetic Responses, no OAuth credentials.
: "${WE_BRIDGE_TEST_JARS:?Set to a directory containing json.jar and nanohttpd.jar}"
bridge_root="$(cd "$(dirname "$0")/.." && pwd)"
bridge_classes="$(mktemp -d)"
trap 'rm -rf "$bridge_classes"' EXIT
bridge_cp="$WE_BRIDGE_TEST_JARS/json.jar:$WE_BRIDGE_TEST_JARS/nanohttpd.jar"
java com.sun.tools.javac.Main -cp "$bridge_cp" -d "$bridge_classes" \
  "$bridge_root/app/src/main/java/com/opc/baize/subscription/SubscriptionProtocol.java" \
  "$bridge_root/app/src/main/java/com/opc/baize/subscription/AgentSubscriptionBridge.java" \
  "$bridge_root/app/src/testDebug/java/com/opc/baize/subscription/AgentBridgeChecks.java" \
  "$bridge_root/app/src/testDebug/java/com/opc/baize/subscription/AgentBridgeFixture.java"
java -cp "$bridge_classes:$bridge_cp" com.opc.baize.subscription.AgentBridgeChecks
cd "$bridge_root/tools/agent-engine"
WE_BRIDGE_TEST_CLASSPATH="$bridge_classes:$bridge_cp" npm test
