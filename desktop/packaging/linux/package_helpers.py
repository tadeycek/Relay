#!/usr/bin/env python3
"""Byte-preserving staging checks for a Linux-only desktop distribution."""
import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path

TARGET = 'linux-x86-64/libnostr_sdk_ffi.so'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def is_nostr_native(name):
    return Path(name).name in ('libnostr_sdk_ffi.so', 'libnostr_sdk_ffi.dylib', 'nostr_sdk_ffi.dll')


def filter_nostr(source, target, removed_path):
    source, target, removed_path = map(Path, (source, target, removed_path))
    if source.resolve() == target.resolve():
        raise ValueError('refusing to modify source jar')
    with zipfile.ZipFile(source) as original:
        infos = original.infolist()
        native = [i.filename for i in infos if is_nostr_native(i.filename)]
        if native.count(TARGET) != 1 or len(native) < 1:
            raise ValueError(f'expected exactly one {TARGET}; found {native}')
        removed = sorted(x for x in native if x != TARGET)
        with zipfile.ZipFile(target, 'w') as result:
            result.comment = original.comment
            for info in infos:
                if info.filename not in removed:
                    result.writestr(info, original.read(info), compress_type=info.compress_type)
    with zipfile.ZipFile(target) as result:
        post = [n for n in result.namelist() if is_nostr_native(n)]
        if post != [TARGET]:
            raise ValueError(f'bad filtered native set: {post}')
    removed_path.write_text(''.join(x + '\n' for x in removed))
    print(json.dumps({'source_sha256': digest(source), 'filtered_sha256': digest(target),
                      'before_natives': len(native), 'after_natives': len(post),
                      'removed': removed, 'source_bytes': source.stat().st_size,
                      'filtered_bytes': target.stat().st_size}, indent=2))


def inventory(root, destination):
    root, destination = Path(root), Path(destination)
    records = []
    for jar in sorted(root.rglob('*.jar')):
        with zipfile.ZipFile(jar) as z:
            binaries = [i.filename for i in z.infolist() if i.filename.endswith(('.so', '.dylib', '.dll', '.jnilib'))]
            licences = [i.filename for i in z.infolist() if 'license' in i.filename.lower() or 'notice' in i.filename.lower()]
        records.append({'jar': str(jar.relative_to(root)), 'sha256': digest(jar),
                        'bytes': jar.stat().st_size, 'embedded_natives': binaries,
                        'embedded_license_entries': licences,
                        'license_conclusion': 'UNRESOLVED: verify exact POM and bundled native sources'})
    natives = [str(p.relative_to(root)) for p in sorted(root.rglob('*.so'))]
    runtime_legal = [str(p.relative_to(root)) for p in sorted(root.rglob('legal/**/*')) if p.is_file()]
    data = {'jars': records, 'standalone_so': natives, 'runtime_legal': runtime_legal,
            'jar_count': len(records), 'embedded_native_count': sum(len(r['embedded_natives']) for r in records),
            'standalone_so_count': len(natives), 'runtime_legal_count': len(runtime_legal)}
    destination.write_text(json.dumps(data, indent=2) + '\n')
    table = destination.with_name('archive-licences.md')
    rows = ['# Archive-derived licence inventory (development, NOT release clearance)', '',
            'Every application JAR below is read from the staged image; an embedded text path is evidence of a text, **not** a conclusion about native subcomponents.', '',
            '| Shipped JAR | SHA-256 | Embedded native count | Embedded licence/notice paths | Conclusion |',
            '|---|---|---:|---|---|']
    for record in records:
        rows.append(f"| `{record['jar']}` | `{record['sha256']}` | {len(record['embedded_natives'])} | "
                    f"{', '.join('`' + name + '`' for name in record['embedded_license_entries']) or 'none'} | "
                    'Unresolved exact component/native source obligations |')
    rows.extend(['', f"Standalone `.so`: {len(natives)} (exact paths in `archive-inventory.json`).",
                 f"OpenJDK legal files: {len(runtime_legal)} (exact paths in `archive-inventory.json`; original texts retained in app image).",
                 'AppImage type2 runtime: source commit fedfad51acc2860b2916a1eef32ba4a500ff31ef; LGPL libfuse and corresponding-source obligations NOT CLEARED.',
                 'Skiko/Skia and SQLite Multiple Ciphers native sources and notices NOT CLEARED.', ''])
    table.write_text('\n'.join(rows))
    print(json.dumps({k: v for k, v in data.items() if k.endswith('_count')}, indent=2))


def webrtc_notices(stage, destination):
    """Copy the licence texts bundled in the webrtc-java native jar, byte for byte, without editing them."""
    stage, destination = Path(stage), Path(destination)
    jars = sorted((stage / 'lib/app').glob('webrtc-java-*-linux-x86_64-*.jar'))
    if len(jars) != 1:
        raise ValueError(f'expected exactly one webrtc-java linux-x86_64 native jar, found {len(jars)}')
    prefix = 'META-INF/licenses/'
    count = 0
    with zipfile.ZipFile(jars[0]) as jar:
        for info in jar.infolist():
            if info.is_dir() or not info.filename.startswith(prefix):
                continue
            target = destination / info.filename[len(prefix):]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(jar.read(info))
            count += 1
    if count == 0:
        raise ValueError('native jar carries no META-INF/licenses entries')
    print(f'webrtc-notices: {count} licence files copied from {jars[0].name}')


def audit(root):
    root = Path(root)
    forbidden = (b'/home/tadej/', b'/.private/', b'RELAY_WORKER_DIR=', b'XDG_CONFIG_HOME=')
    scanned = 0
    for path in root.rglob('*'):
        if path.is_symlink():
            resolved = path.resolve()
            if not resolved.is_relative_to(root.resolve()):
                raise ValueError(f'escaping symlink: {path}')
        if not path.is_file():
            continue
        relative = str(path.relative_to(root)).encode()
        if any(token in relative for token in forbidden):
            raise ValueError(f'forbidden artifact path: {relative!r}')
        data = path.read_bytes()
        if any(token in data for token in forbidden):
            raise ValueError(f'forbidden personal path in {relative!r}')
        if path.suffix == '.jar':
            with zipfile.ZipFile(path) as jar:
                for info in jar.infolist():
                    name = info.filename.encode()
                    if any(token in name for token in forbidden):
                        raise ValueError(f'forbidden jar entry path: {relative!r}: {name!r}')
                    if info.file_size <= 8_000_000 and any(token in jar.read(info) for token in forbidden):
                        raise ValueError(f'forbidden jar entry content: {relative!r}: {name!r}')
        scanned += 1
    print(f'audit: {scanned} files, no forbidden paths or escaping symlinks')


if __name__ == '__main__':
    try:
        command, *args = sys.argv[1:]
        {'filter-nostr': filter_nostr, 'inventory': inventory, 'audit': audit, 'webrtc-notices': webrtc_notices}[command](*args)
    except (ValueError, KeyError, TypeError) as exc:
        sys.exit(str(exc))
