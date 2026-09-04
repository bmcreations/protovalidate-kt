#!/bin/sh
# Fail if a message in the vendored PGV harness protos is never the subject of a
# conformance case.
#
# The oneof `required` bug shipped because nothing tied the proto corpus to the
# case list: `EnumInsideOneOf` and `StringInOneOf` had sat uncovered since the
# port, and upstream does not cover them either, so importing more cases would
# not have found them.
#
# "Subject" means the message under test — `{"name", &cases.Foo{...}, N}` —
# not merely a type constructed inside some other case's payload.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROTO_DIR="$SCRIPT_DIR/src/main/proto/tests/harness/cases"
CASES_GO="$SCRIPT_DIR/runner/cases.go"

# Helper types that exist to be embedded in other messages, so they are never a
# case subject on their own.
HELPERS="complextestmsg embed testmsg testoneofmsg"

# protoc-gen-go capitalises after digits (MessageWith3dInside -> MessageWith3DInside),
# so compare case-insensitively.
covered="$(grep -oE '\{"[^"]+", &cases\.[A-Za-z0-9_]+\{' "$CASES_GO" \
  | sed -E 's/.*&cases\.([A-Za-z0-9_]+)\{/\1/' | tr 'A-Z' 'a-z' | sort -u)"

missing=""
for m in $(grep -rhoE '^message ([A-Za-z0-9_]+)' "$PROTO_DIR" --include='*.proto' \
             | awk '{print $2}' | sort -u); do
  lm="$(echo "$m" | tr 'A-Z' 'a-z')"
  case " $HELPERS " in *" $lm "*) continue ;; esac
  echo "$covered" | grep -qx "$lm" || missing="$missing $m"
done

if [ -n "$missing" ]; then
  echo "ERROR: harness messages with no conformance case of their own:"
  for m in $missing; do echo "  $m"; done
  echo
  echo "Add a case to pgv-conformance/runner/cases.go, or add the message to"
  echo "HELPERS in this script if it only exists to be embedded elsewhere."
  exit 1
fi

echo "PGV coverage check: every harness message has a case."
