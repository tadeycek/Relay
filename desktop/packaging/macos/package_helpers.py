#!/usr/bin/env python3
"""Fail-closed macOS native staging audit; never writes to dependency caches."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

NOSTR_TARGET = 'darwin-aarch64/libnostr_sdk_ffi.dylib'
NOSTR_HASH = '5bcaf5d29e84ad3f144ed509345f2b79ecdc689b6fbc722ba591cae1e1d27217'
WEBRTC_HASH = 'c9c2c1e1202b191b8e07bac48db31aef6cefdb4248a3dd58a3b04dfeaa2cfb09'
WEBRTC_NATIVE = 'libwebrtc-java-macos-aarch64.dylib'
# SHA-256 of the native payload itself, taken from the audited Maven Central jar. Packaging may
# normalise the surrounding jar (entry order/compression), so integrity is pinned to the dylib.
WEBRTC_NATIVE_HASH = '89af8e2196692ac2544a2b910e3c76594d4952acab373353c85f9c6c8a3b6618'
LICENSES = {
    'META-INF/licenses/webrtc/LICENSE.md': '3637ae45fdfb8e1d4eb30ca9e5f107c99e8a39b6c5b1e9068e853e4c308bd24b',
    'META-INF/licenses/webrtc/PATENTS': '01462e2068d1a04c2274f3389773014c14ed9bc3446b28303543bd3e3c064145',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def native(name):
    return Path(name).name in ('libnostr_sdk_ffi.so', 'libnostr_sdk_ffi.dylib', 'nostr_sdk_ffi.dll')


def entries(jar):
    with zipfile.ZipFile(jar) as archive:
        return archive.infolist()


def filter_nostr(source, target):
    source, target = Path(source), Path(target)
    if source.resolve() == target.resolve() or not target.is_file():
        raise ValueError('source must differ from existing staged target jar')
    with zipfile.ZipFile(source) as original:
        infos = original.infolist()
        natives = [i.filename for i in infos if native(i.filename)]
        if natives.count(NOSTR_TARGET) != 1 or len(natives) != len(set(natives)):
            raise ValueError(f'expected one unique {NOSTR_TARGET}; found {natives}')
        # Write a new file beside the staged jar; the original dependency/cache jar is read-only.
        temp = target.with_name(target.name + '.filtered')
        try:
            with zipfile.ZipFile(temp, 'w') as out:
                out.comment = original.comment
                for info in infos:
                    if not native(info.filename) or info.filename == NOSTR_TARGET:
                        out.writestr(info, original.read(info), compress_type=info.compress_type)
            with zipfile.ZipFile(temp) as check:
                if [i.filename for i in check.infolist() if native(i.filename)] != [NOSTR_TARGET]:
                    raise ValueError('filtered native inventory mismatch')
            temp.replace(target)
        finally:
            temp.unlink(missing_ok=True)
    print(json.dumps({'original_sha256': digest(source.read_bytes()), 'staged_sha256': digest(target.read_bytes()),
                      'before_natives': len(natives), 'after_natives': 1, 'removed': sorted(set(natives) - {NOSTR_TARGET})}))


def one(root, prefix):
    found = [p for p in root.rglob('*.jar') if p.name.startswith(prefix)]
    if len(found) != 1:
        raise ValueError(f'expected one {prefix} jar under {root}, found {len(found)}')
    return found[0]


NATIVE_SUFFIXES = ('.so', '.dylib', '.dll')


def audit_app(app):
    app = Path(app)
    nostr = one(app, 'nostr-sdk-kmp-jvm-')
    # The API jar and the classifier jar share the version prefix, so classify by content, not by
    # name: exactly one bundled jar may carry a webrtc native, and it must be the Apple Silicon one.
    carriers = []
    for candidate in app.rglob('*.jar'):
        if 'webrtc-java-0.20.0' not in candidate.name:
            continue
        if any(i.filename.endswith(NATIVE_SUFFIXES) for i in entries(candidate)):
            carriers.append(candidate)
    if len(carriers) != 1:
        raise ValueError(f'expected exactly one webrtc native jar, found {[p.name for p in carriers]}')
    webrtc = carriers[0]
    if 'macos-aarch64' not in webrtc.name:
        raise ValueError(f'webrtc native jar is not the Apple Silicon classifier: {webrtc.name}')
    names = []
    for bundled_jar in app.rglob('*.jar'):
        with zipfile.ZipFile(bundled_jar) as jar:
            names.extend(i.filename for i in jar.infolist() if native(i.filename))
    with zipfile.ZipFile(webrtc) as jar:
        webrtc_names = [i.filename for i in jar.infolist() if i.filename.endswith(('.so', '.dylib', '.dll'))]
    if names != [NOSTR_TARGET] or webrtc_names != [WEBRTC_NATIVE]:
        raise ValueError(f'bad native set: nostr={names}, webrtc={webrtc_names}')
    with zipfile.ZipFile(webrtc) as jar:
        native_hash = digest(jar.read(WEBRTC_NATIVE))
    if native_hash != WEBRTC_NATIVE_HASH:
        raise ValueError('staged webrtc native differs from the audited Maven Central artifact: '
                         f'native_sha256={native_hash} jar_sha256={digest(webrtc.read_bytes())}')
    print(json.dumps({'nostr_native': names, 'nostr_staged_sha256': digest(nostr.read_bytes()),
                      'webrtc_native': webrtc_names, 'webrtc_sha256': digest(webrtc.read_bytes()),
                      'webrtc_native_sha256': native_hash}))


def audit_upstream(nostr, webrtc):
    nostr, webrtc = Path(nostr), Path(webrtc)
    if digest(nostr.read_bytes()) != NOSTR_HASH or digest(webrtc.read_bytes()) != WEBRTC_HASH:
        raise ValueError('upstream dependency checksum mismatch')
    with zipfile.ZipFile(nostr) as archive:
        natives = [i.filename for i in archive.infolist() if native(i.filename)]
        if natives.count(NOSTR_TARGET) != 1:
            raise ValueError('upstream nostr target missing or repeated')
    with zipfile.ZipFile(webrtc) as archive:
        names = [i.filename for i in archive.infolist() if i.filename.endswith(('.so', '.dylib', '.dll'))]
        if names != [WEBRTC_NATIVE] or any(digest(archive.read(p)) != h for p, h in LICENSES.items()):
            raise ValueError('upstream webrtc native or licence hash mismatch')
    print(json.dumps({'nostr_sha256': NOSTR_HASH, 'nostr_native_count': len(natives),
                      'webrtc_sha256': WEBRTC_HASH, 'webrtc_native': names, 'license_texts': LICENSES}))


if __name__ == '__main__':
    try:
        command, *args = sys.argv[1:]
        {'filter-nostr': filter_nostr, 'audit-app': audit_app, 'audit-upstream': audit_upstream}[command](*args)
    except (ValueError, KeyError, IndexError, TypeError, FileNotFoundError, zipfile.BadZipFile) as exc:
        sys.exit(str(exc))
