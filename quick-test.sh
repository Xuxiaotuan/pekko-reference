#!/bin/sh
# Compatibility entry; canonical implementation is scripts/test.sh.
set -eu
exec "$(dirname "$0")/scripts/test.sh" "$@"
