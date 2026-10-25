#!/usr/bin/env python3
"""Independent assertions over built development artifacts (no install)."""
import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def check(output):
    output = Path(output)
    debs, images = list(output.glob('*.deb')), list(output.glob('*.AppImage'))
    assert len(debs) == len(images) == 1, 'exactly one of each artifact required'
    manifest = dict(line.split(maxsplit=1)[::-1] for line in (output / 'SHA256SUMS').read_text().splitlines())
    for artifact in debs + images:
        assert manifest['./' + artifact.name] == hashlib.sha256(artifact.read_bytes()).hexdigest()
    info = subprocess.check_output(['dpkg-deb', '--field', str(debs[0])], text=True)
    for expected in ('Package: relay\n', 'Version: 0.1.0\n', 'Architecture: amd64\n'):
        assert expected in info, expected
    contents = subprocess.check_output(['dpkg-deb', '--contents', str(debs[0])], text=True)
    for expected in ('./opt/relay/bin/relay', './opt/relay/lib/relay.png', './opt/relay/lib/relay-relay.desktop'):
        assert expected in contents, expected
    with tempfile.TemporaryDirectory() as temp:
        subprocess.run(['dpkg-deb', '-x', str(debs[0]), temp], check=True)
        root = Path(temp) / 'opt/relay'
        jars = sorted(root.rglob('*.jar'))
        nostr = [j for j in jars if j.name.startswith('nostr-sdk-kmp-jvm-')]
        assert len(nostr) == 1
        with zipfile.ZipFile(nostr[0]) as jar:
            natives = [n for n in jar.namelist() if Path(n).name in ('libnostr_sdk_ffi.so', 'libnostr_sdk_ffi.dylib', 'nostr_sdk_ffi.dll')]
            assert natives == ['linux-x86-64/libnostr_sdk_ffi.so'], natives
        # webrtc-java native: jar present, its licence texts shipped verbatim, jlink modules cover the call/media/DB/dbus code
        wjar = [j for j in jars if j.name.startswith('webrtc-java-') and '-linux-x86_64-' in j.name]
        assert len(wjar) == 1, wjar
        with zipfile.ZipFile(wjar[0]) as jar:
            texts = [n for n in jar.namelist() if n.startswith('META-INF/licenses/') and not n.endswith('/')]
            assert texts, 'native jar has no licence texts'
            for name in texts:
                shipped = root / 'lib/licenses/webrtc-java-native' / name[len('META-INF/licenses/'):]
                assert shipped.read_bytes() == jar.read(name), name
        modules = (root / 'lib/runtime/release').read_text()
        for module in ('java.sql', 'java.transaction.xa', 'jdk.crypto.ec', 'jdk.security.auth', 'java.desktop'):
            assert module in modules.split('MODULES="')[1].split('"')[0].split(), module
        inv = json.loads((output / 'archive-inventory.json').read_text())
        assert len(jars) == inv['jar_count']
        assert len(list(root.rglob('*.so'))) == inv['standalone_so_count']
        assert len(list(root.rglob('legal/**/*'))) >= inv['runtime_legal_count']
        assert all(jar.name in {Path(x['jar']).name for x in inv['jars']} for jar in jars)
        subprocess.run([sys.executable, str(Path(__file__).with_name('package_helpers.py')), 'audit', str(root)], check=True)
    print('verified: SHA256SUMS, deb metadata and icon/desktop entry, extraction, nostr sole target native, archive counts and paths')


if __name__ == '__main__':
    check(sys.argv[1])
