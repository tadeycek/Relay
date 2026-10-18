#!/usr/bin/env bash
# Build using the repo's Gradle wrapper and a user-local Android toolchain.
# Usage: rebuild/dev/build.sh <gradle args...>; defaults to assembleDebug.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source rebuild/dev/env.sh
if [[ $# == 0 ]]; then set -- assembleDebug; fi
exec ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.java.home="$JAVA_HOME" "$@"
