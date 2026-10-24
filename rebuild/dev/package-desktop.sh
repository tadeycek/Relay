#!/usr/bin/env bash
# Build development Linux installers from an already-created Compose app image.
# This never modifies Gradle's app image or cache. No release is published.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ASSETS="$ROOT/desktop/packaging/linux"
SCRATCH="${TMPDIR:-$HOME/.hermes/cache/scratch}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21.0.12.1+1}"
SOURCE="${1:-$ROOT/desktop/build/compose/binaries/main/app/relay}"
OUTPUT="${2:-$SCRATCH/relay-wp10-output}"
VERSION=0.1.0 # development version; not a release bump
TOOL_SHA=95cbe7cce9717fce90c484e34052ee7c7f1d7635b33c12525b4776826a7d29b6
RUNTIME_SHA=617aa77314d57c8b8e4fae9c3f0b35af7b30d5529cb69e98f64f92510ed98a46
TOOL_URL=https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
RUNTIME_URL=https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64
TOOLS="$SCRATCH/relay-wp10-tools"
mkdir -p "$OUTPUT" "$TOOLS"
[[ -x "$JAVA_HOME/bin/jpackage" && -x "$SOURCE/bin/relay" ]] || { echo 'missing jpackage or app image' >&2; exit 1; }
[[ $(uname -m) == x86_64 ]] || { echo 'Linux x86_64 required' >&2; exit 1; }
verify_file() { printf '%s  %s\n' "$2" "$1" | sha256sum -c - >/dev/null; }
fetch_pinned() {
    if [[ ! -f "$2" ]] || ! verify_file "$2" "$3"; then
        curl -fL --retry 2 --connect-timeout 10 --max-time 180 "$1" -o "$2.part"
        verify_file "$2.part" "$3" || { rm -f "$2.part"; echo "digest mismatch: $1" >&2; exit 1; }
        mv "$2.part" "$2"
    fi
}
fetch_pinned "$TOOL_URL" "$TOOLS/appimagetool-x86_64.AppImage" "$TOOL_SHA"
fetch_pinned "$RUNTIME_URL" "$TOOLS/runtime-x86_64" "$RUNTIME_SHA"
chmod +x "$TOOLS/appimagetool-x86_64.AppImage"
# A fresh staging directory prevents stale entries leaking into the archive.
rm -rf "$OUTPUT/stage" "$OUTPUT/AppDir"
mkdir -p "$OUTPUT/stage" "$OUTPUT/AppDir/usr"
cp -a "$SOURCE/." "$OUTPUT/stage/"
mapfile -t NOSTR < <(find "$OUTPUT/stage" -type f -name 'nostr-sdk-kmp-jvm-*.jar')
[[ ${#NOSTR[@]} == 1 ]] || { echo "expected one nostr jar, found ${#NOSTR[@]}" >&2; exit 1; }
python3 "$ASSETS/package_helpers.py" filter-nostr "${NOSTR[0]}" "$OUTPUT/nostr-filtered.jar" "$OUTPUT/nostr-removed-entries.txt" | tee "$OUTPUT/nostr-filter-report.json"
mv "$OUTPUT/nostr-filtered.jar" "${NOSTR[0]}"
cp "$ASSETS/relay.png" "$OUTPUT/stage/lib/relay.png"
python3 "$ASSETS/package_helpers.py" inventory "$OUTPUT/stage" "$OUTPUT/archive-inventory.json"
python3 "$ASSETS/package_helpers.py" audit "$OUTPUT/stage"
# jpackage accepts the staged app image directly. No dpkg install on the host.
"$JAVA_HOME/bin/jpackage" --type deb --app-image "$OUTPUT/stage" --dest "$OUTPUT" \
    --name relay --app-version "$VERSION" --vendor Relay \
    --description 'Relay desktop client (development package)' --linux-shortcut \
    --linux-menu-group Network --icon "$ASSETS/relay.png"
cp -a "$OUTPUT/stage/." "$OUTPUT/AppDir/usr/"
cp "$ASSETS/relay.png" "$OUTPUT/AppDir/relay.png"
cp "$ASSETS/relay.desktop" "$OUTPUT/AppDir/relay.desktop"
mkdir -p "$OUTPUT/AppDir/usr/share/doc/relay"
cp "$ASSETS/licenses/type2-runtime-LICENSE" "$OUTPUT/AppDir/usr/share/doc/relay/type2-runtime-LICENSE"
cp "$ASSETS/AppRun" "$OUTPUT/AppDir/AppRun"
chmod +x "$OUTPUT/AppDir/AppRun"
python3 "$ASSETS/package_helpers.py" audit "$OUTPUT/AppDir"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOLS/appimagetool-x86_64.AppImage" \
    --runtime-file "$TOOLS/runtime-x86_64" "$OUTPUT/AppDir" "$OUTPUT/Relay-$VERSION-x86_64.AppImage"
chmod +x "$OUTPUT/Relay-$VERSION-x86_64.AppImage"
(cd "$OUTPUT" && sha256sum ./*.deb ./*.AppImage > SHA256SUMS && sha256sum -c SHA256SUMS)
python3 "$ASSETS/verify_artifacts.py" "$OUTPUT"
"$JAVA_HOME/bin/jpackage" --version
printf 'Output: %s\n' "$OUTPUT"
