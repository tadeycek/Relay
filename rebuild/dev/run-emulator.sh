#!/usr/bin/env bash
# Install and launch this checkout on the dedicated local emulator.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source rebuild/dev/env.sh

apk=app/build/outputs/apk/debug/app-debug.apk
if [[ ! -f "$apk" ]]; then rebuild/dev/build.sh; fi
serial=emulator-5554
emulator_pid=""
if ! adb devices | awk '{print $1}' | grep -qx "$serial"; then
    mkdir -p .local
    # Use Mesa's software driver: the bundled renderers and NVK path crash on this host.
    QT_QPA_PLATFORM=xcb LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
        emulator -avd Relay_API_35 -port 5554 -memory 2048 -cores 2 \
        -gpu host -feature -Vulkan -no-audio -no-snapshot -no-boot-anim \
        > .local/emulator.log 2>&1 &
    emulator_pid=$!
fi

ready=false
for ((attempt = 0; attempt < 120; attempt++)); do
    if [[ -n "$emulator_pid" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo 'Emulator exited. Check .local/emulator.log.' >&2
        exit 1
    fi
    if [[ "$(timeout 5 adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        ready=true
        break
    fi
    sleep 2
done
if [[ "$ready" != true ]]; then
    echo 'Emulator did not finish booting. Check .local/emulator.log.' >&2
    exit 1
fi
if ! adb -s "$serial" emu avd name | tr -d '\r' | grep -qx Relay_API_35; then
    echo 'Port 5554 belongs to another emulator; close it and try again.' >&2
    exit 1
fi
adb -s "$serial" install -r "$apk"
adb -s "$serial" shell am start -W -n com.relay.app/.MainActivity
if [[ -n "$emulator_pid" ]]; then wait "$emulator_pid"; fi
