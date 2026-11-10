#!/bin/sh
# Run the PGV conformance tests against the Wire executor (validators generated with target=wire).
#
# Usage: ./run-conformance.sh [--verbose]
#
# Prerequisites:
#   (cd ../pgv-conformance/runner && go build -o pgv-conformance-runner .)
#   ./gradlew :pgv-conformance-wire:executorJar
#
# Cases listed in expected-failures.txt may fail; any other failure, or a listed
# case that passes, makes the script exit non-zero.
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

EXPECTED="$SCRIPT_DIR/expected-failures.txt"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

# The runner exits non-zero on any failure; the expected-failures check below decides.
"$RUNNER" -executor "java -Xmx512m -jar $JAR" -v > "$OUT" 2>&1 || true

if [ -n "$VERBOSE" ]; then
  cat "$OUT"
else
  grep -E '^(FAIL|ERROR): |^Results: ' "$OUT" || true
fi

grep -q '^Results: ' "$OUT" || { echo "ERROR: runner produced no results"; cat "$OUT"; exit 1; }

actual="$(grep -E '^(FAIL|ERROR): ' "$OUT" | sed -E 's/^(FAIL|ERROR): //; s/: .*//' | sort)"
expected="$(grep -v '^#' "$EXPECTED" | grep -v '^$' | sort)"

if [ "$actual" != "$expected" ]; then
  echo
  echo "Failures differ from $EXPECTED:"
  printf '%s\n' "$expected" > "$OUT.expected"
  printf '%s\n' "$actual" > "$OUT.actual"
  /usr/bin/diff "$OUT.expected" "$OUT.actual" | sed -n 's/^< /  now passing (remove from list): /p; s/^> /  unexpected failure: /p'
  rm -f "$OUT.expected" "$OUT.actual"
  exit 1
fi
echo "All failures are in expected-failures.txt ($(printf '%s\n' "$expected" | grep -c .) cases)."
