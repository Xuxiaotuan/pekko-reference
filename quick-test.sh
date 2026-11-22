#!/bin/sh
# Compatibility entry; canonical implementation is support/scripts/test.sh.
set -eu
exec "$(dirname "$0")/support/scripts/test.sh" "$@"
