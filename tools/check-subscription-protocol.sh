#!/usr/bin/env bash
set -euo pipefail
# Host-only checks. Production uses Android's org.json; this does not vendor another library.
# Clone https://github.com/stleary/JSON-java and provide its source directory.
# CI's testDebugUnitTest uses the repository's existing org.json test dependency instead.
: "${JSON_JAVA_SOURCE:?Set JSON_JAVA_SOURCE to a JSON-java checkout src/main/java directory}"
probe_root="$(cd "$(dirname "$0")/.." && pwd)"
probe_classes="$(mktemp -d)"
trap 'rm -rf "$probe_classes"' EXIT
java com.sun.tools.javac.Main -d "$probe_classes" \
  "$JSON_JAVA_SOURCE"/org/json/*.java \
  "$probe_root"/app/src/debug/java/com/opc/baize/subscription/SubscriptionProtocol.java \
  "$probe_root"/app/src/debug/java/com/opc/baize/subscription/IdentityVerifier.java \
  "$probe_root"/app/src/debug/java/com/opc/baize/subscription/ProbeDiagnostics.java \
  "$probe_root"/app/src/debug/java/com/opc/baize/subscription/LoopbackReceiver.java \
  "$probe_root"/app/src/testDebug/java/com/opc/baize/subscription/LoopbackChecks.java \
  "$probe_root"/app/src/testDebug/java/com/opc/baize/subscription/ProtocolChecks.java
java -cp "$probe_classes" com.opc.baize.subscription.ProtocolChecks
java -cp "$probe_classes" com.opc.baize.subscription.LoopbackChecks
