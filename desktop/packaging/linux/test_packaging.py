import hashlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOL = Path(__file__).with_name('package_helpers.py')


class PackagingTests(unittest.TestCase):
    def test_filter_nostr_copy_preserves_linux_native_and_other_entries(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            original = root / 'source.jar'
            target = root / 'staged.jar'
            with zipfile.ZipFile(original, 'w') as jar:
                jar.writestr('foo.class', b'bytecode')
                jar.writestr('linux-x86-64/libnostr_sdk_ffi.so', b'linux')
                jar.writestr('darwin-aarch64/libnostr_sdk_ffi.dylib', b'mac')
                jar.writestr('win32-x86-64/nostr_sdk_ffi.dll', b'win')
            before = hashlib.sha256(original.read_bytes()).hexdigest()
            subprocess.run([sys.executable, str(TOOL), 'filter-nostr', str(original), str(target), str(root / 'removed.txt')], check=True)
            with zipfile.ZipFile(target) as jar:
                self.assertEqual(set(jar.namelist()), {'foo.class', 'linux-x86-64/libnostr_sdk_ffi.so'})
            self.assertEqual(original.read_bytes()[:2], b'PK')
            self.assertEqual(hashlib.sha256(original.read_bytes()).hexdigest(), before)
            self.assertEqual((root / 'removed.txt').read_text().splitlines(), ['darwin-aarch64/libnostr_sdk_ffi.dylib', 'win32-x86-64/nostr_sdk_ffi.dll'])

    def test_filter_rejects_missing_or_duplicate_target(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            src = root / 'source.jar'
            for names in [('darwin-x86-64/libnostr_sdk_ffi.dylib',), ('linux-x86-64/libnostr_sdk_ffi.so',) * 2]:
                with zipfile.ZipFile(src, 'w') as jar:
                    for name in names:
                        jar.writestr(name, b'native')
                run = subprocess.run([sys.executable, str(TOOL), 'filter-nostr', str(src), str(root / 'out.jar'), str(root / 'removed')], capture_output=True)
                self.assertNotEqual(run.returncode, 0)

    def test_archive_audit_rejects_secret_paths_and_mismatched_checksums(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / 'secret.txt').write_text('/home/tadej/.private/token')
            run = subprocess.run([sys.executable, str(TOOL), 'audit', str(root)], capture_output=True)
            self.assertNotEqual(run.returncode, 0)

    def test_webrtc_native_notices_are_copied_byte_for_byte_from_the_native_jar(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            app = root / 'stage/lib/app'
            app.mkdir(parents=True)
            texts = {'META-INF/licenses/webrtc/LICENSE.md': b'bsd text\r\n', 'META-INF/licenses/webrtc/PATENTS': b'patents',
                     'META-INF/licenses/libva/COPYING': b'mit'}
            with zipfile.ZipFile(app / 'webrtc-java-0.20.0-linux-x86_64-abc.jar', 'w') as jar:
                jar.writestr('libwebrtc-java-linux-x86_64.so', b'elf')
                for name, data in texts.items():
                    jar.writestr(name, data)
            dest = root / 'stage/lib/licenses/webrtc-java-native'
            subprocess.run([sys.executable, str(TOOL), 'webrtc-notices', str(root / 'stage'), str(dest)], check=True)
            for name, data in texts.items():
                self.assertEqual((dest / name.removeprefix('META-INF/licenses/')).read_bytes(), data)

    def test_webrtc_notices_fail_when_native_jar_is_missing_or_has_no_licence_text(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            app = root / 'stage/lib/app'
            app.mkdir(parents=True)
            run = subprocess.run([sys.executable, str(TOOL), 'webrtc-notices', str(root / 'stage'), str(root / 'out')], capture_output=True)
            self.assertNotEqual(run.returncode, 0)
            with zipfile.ZipFile(app / 'webrtc-java-0.20.0-linux-x86_64-abc.jar', 'w') as jar:
                jar.writestr('libwebrtc-java-linux-x86_64.so', b'elf')
            run = subprocess.run([sys.executable, str(TOOL), 'webrtc-notices', str(root / 'stage'), str(root / 'out')], capture_output=True)
            self.assertNotEqual(run.returncode, 0)


if __name__ == '__main__':
    unittest.main()
