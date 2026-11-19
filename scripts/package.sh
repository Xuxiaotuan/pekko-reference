#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec sbt "$@" 'pekko-server/package' 'pekko-server/Universal/stage' 'pekko-server/Docker/stage'
