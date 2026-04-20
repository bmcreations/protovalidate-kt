#!/bin/sh
# Run the PGV conformance tests against our Kotlin executor.
#
# Usage: ./run-conformance.sh [--verbose]
#
# Prerequisites:
#   cd runner && go build -o pgv-conformance-runner .
#   cd .. && ./gradlew :pgv-conformance:jar

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/pgv-conformance.jar"
RUNNER="$SCRIPT_DIR/runner/pgv-conformance-runner"

if [ ! -f "$JAR" ]; then
  echo "ERROR: Executor JAR not found at $JAR"
  echo "Run: ./gradlew :pgv-conformance:jar"
  exit 1
fi

if [ ! -f "$RUNNER" ]; then
  echo "ERROR: Runner not found at $RUNNER"
  echo "Run: cd runner && go build -o pgv-conformance-runner ."
  exit 1
fi

VERBOSE=""
if [ "$1" = "--verbose" ] || [ "$1" = "-v" ]; then
  VERBOSE="-v"
fi

exec "$RUNNER" -executor "java -Xmx512m -jar $JAR" $VERBOSE
