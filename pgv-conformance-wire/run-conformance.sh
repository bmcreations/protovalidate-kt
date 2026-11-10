#!/bin/sh
# Run the PGV conformance tests against the Wire executor (validators generated with target=wire).
#
# Usage: ./run-conformance.sh [--verbose]
#
# Prerequisites:
#   (cd ../pgv-conformance/runner && go build -o pgv-conformance-runner .)
#   ./gradlew :pgv-conformance-wire:executorJar
#
# Set PGV_NO_VALIDATOR_LOG=<file> to record every case whose message has no
# generated validator; those cases pass without checking anything.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/pgv-conformance-wire.jar"
RUNNER="$SCRIPT_DIR/../pgv-conformance/runner/pgv-conformance-runner"

if [ ! -f "$JAR" ]; then
  echo "ERROR: Executor JAR not found at $JAR"
  echo "Run: ./gradlew :pgv-conformance-wire:executorJar"
  exit 1
fi

if [ ! -f "$RUNNER" ]; then
  echo "ERROR: Runner not found at $RUNNER"
  echo "Run: (cd pgv-conformance/runner && go build -o pgv-conformance-runner .)"
  exit 1
fi

VERBOSE=""
if [ "$1" = "--verbose" ] || [ "$1" = "-v" ]; then
  VERBOSE="-v"
fi

exec "$RUNNER" -executor "java -Xmx512m -jar $JAR" $VERBOSE
