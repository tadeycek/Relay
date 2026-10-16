#!/usr/bin/env bash
# Source this file for adb/emulator commands. RELAY_TOOLS preserves the original WSL layout.
if [[ -n "${RELAY_TOOLS:-}" ]]; then
    export JAVA_HOME="$RELAY_TOOLS/jdk17"
    export ANDROID_HOME="$RELAY_TOOLS/android-sdk"
elif [[ -d "$HOME/.local/share/relay-tools/jdk-17" ]]; then
    export JAVA_HOME="${RELAY_JAVA_HOME:-$HOME/.local/share/relay-tools/jdk-17}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
else
    export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/tools/android-sdk}"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
