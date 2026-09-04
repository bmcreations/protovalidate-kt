#!/bin/sh
# Run the protovalidate conformance tests against our Kotlin executor.
#
# Usage: ./run-conformance.sh [--verbose] [--suite REGEX] [--case REGEX]
#
# Prerequisites:
#   go install github.com/bufbuild/protovalidate/tools/protovalidate-conformance@$(cat conformance/HARNESS_VERSION)
#   ./gradlew :conformance:jar --no-configure-on-demand

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/conformance.jar"
HARNESS="$(go env GOPATH)/bin/protovalidate-conformance"
# Pinned in one place so a local run and CI exercise the same corpus.
HARNESS_VERSION="$(cat "$SCRIPT_DIR/HARNESS_VERSION")"

if [ ! -f "$JAR" ]; then
  echo "ERROR: Executor JAR not found at $JAR"
  echo "Run: ./gradlew :conformance:jar --no-configure-on-demand"
  exit 1
fi

if [ ! -f "$HARNESS" ]; then
  echo "ERROR: Conformance harness not found at $HARNESS"
  echo "Run: go install github.com/bufbuild/protovalidate/tools/protovalidate-conformance@$HARNESS_VERSION"
  exit 1
fi

exec "$HARNESS" "$@" -- java -Xmx512m -jar "$JAR"
