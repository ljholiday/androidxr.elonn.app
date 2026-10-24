#!/usr/bin/env bash
set -euo pipefail

# JVM unit tests (app/src/test) -- pure-logic parsing (RuntimeModel.kt) exercised directly on
# the JVM via a real org.json implementation (testImplementation("org.json:json:...")), no
# device/emulator or Robolectric needed. World Dataset conformance remediation, Phase 4c:
# no test infrastructure existed in this repo at all before this.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

./gradlew testDebugUnitTest --console=plain
