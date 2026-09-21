#!/usr/bin/env bash
# Dev build helper for the WSL Fedora environment used during the rebuild.
#
# Why this exists:
#  - gradle.properties pins org.gradle.java.home=/opt/android-studio/jbr, which only exists on the
#    original author's machine. We override it on the command line instead of editing the file.
#  - ./gradlew has CRLF line endings and cannot run under Linux, so a standalone Gradle 8.14 is used.
#
# Expected layout (created by the rebuild setup, all under $HOME/tools):
#   jdk17/            Temurin JDK 17
#   android-sdk/      cmdline-tools, platform-tools, platforms;android-36, build-tools
#   gradle-8.14/      Gradle distribution
#
# Usage: rebuild/dev/build.sh <gradle args...>   e.g.  rebuild/dev/build.sh :app:testDebugUnitTest
set -euo pipefail
TOOLS="${RELAY_TOOLS:-$HOME/tools}"
export JAVA_HOME="$TOOLS/jdk17"
export ANDROID_HOME="$TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$TOOLS/gradle-8.14/bin:$PATH"
cd "$(dirname "$0")/../.."
exec gradle --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" "$@"
