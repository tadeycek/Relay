"""WP18 native packaging guards; fixtures never touch the Gradle cache."""
import hashlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOL = Path(__file__).with_name('package_helpers.py')


class MacPackagingTests(unittest.TestCase):
    def run_tool(self, *args):
        return subprocess.run([sys.executable, str(TOOL), *map(str, args)], capture_output=True, text=True)

    def test_filter_preserves_only_darwin_arm64_and_original_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'nostr-sdk-kmp-jvm-0.44.8.jar'
            app = root / 'relay.app' / 'Contents' / 'app'
            app.mkdir(parents=True)
            with zipfile.ZipFile(source, 'w') as jar:
                jar.writestr('com/example/Example.class', b'class')
                jar.writestr('darwin-aarch64/libnostr_sdk_ffi.dylib', b'mac')
                jar.writestr('linux-x86-64/libnostr_sdk_ffi.so', b'linux')
            original = source.read_bytes()
            target = app / source.name
            target.write_bytes(original)
            result = self.run_tool('filter-nostr', source, target)
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(target) as jar:
                self.assertEqual(set(jar.namelist()), {'com/example/Example.class', 'darwin-aarch64/libnostr_sdk_ffi.dylib'})
            self.assertEqual(source.read_bytes(), original)
            self.assertNotEqual(hashlib.sha256(target.read_bytes()).digest(), hashlib.sha256(original).digest())
            self.assertNotEqual(self.run_tool('filter-nostr', target, target).returncode, 0)

    def test_filter_rejects_absent_or_duplicate_native(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'src.jar'
            for entries in [[], ['darwin-aarch64/libnostr_sdk_ffi.dylib'] * 2]:
                with zipfile.ZipFile(source, 'w') as jar:
                    for name in entries:
                        jar.writestr(name, b'mac')
                self.assertNotEqual(self.run_tool('filter-nostr', source, root / 'dst.jar').returncode, 0)

    def test_audit_rejects_foreign_nostr_native_in_secondary_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp) / 'relay.app' / 'Contents' / 'app'
            app.mkdir(parents=True)
            with zipfile.ZipFile(app / 'nostr-sdk-kmp-jvm-0.44.8.jar', 'w') as jar:
                jar.writestr('darwin-aarch64/libnostr_sdk_ffi.dylib', b'mac')
            webrtc = app / 'webrtc-java-0.20.0-macos-aarch64-xyz.jar'
            with zipfile.ZipFile(webrtc, 'w') as jar:
                jar.writestr('libwebrtc-java-macos-aarch64.dylib', b'mac')
            spec = importlib.util.spec_from_file_location('mac_package_helpers', TOOL)
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
            helper.WEBRTC_HASH = hashlib.sha256(webrtc.read_bytes()).hexdigest()
            with zipfile.ZipFile(app / 'unrelated-component.jar', 'w') as jar:
                jar.writestr('linux-x86-64/libnostr_sdk_ffi.so', b'foreign')
            with self.assertRaisesRegex(ValueError, 'bad native set'):
                helper.audit_app(app.parent.parent)

    def test_codesign_classifier_accepts_verified_ad_hoc(self):
        from signing_helpers import classify_codesign
        self.assertEqual(classify_codesign(0, 'Signature=adhoc\n', 0), 'ad-hoc signature (codesign -dv and verify succeeded)')

    def test_codesign_classifier_accepts_explicit_unsigned(self):
        from signing_helpers import classify_codesign
        self.assertEqual(classify_codesign(1, 'code object is not signed at all\n', 1), 'unsigned app bundle (codesign -dv reported not signed)')

    def test_codesign_classifier_rejects_unclassified_and_failed_verification(self):
        from signing_helpers import classify_codesign
        for dv, output, verify in [(0, 'Signature=other\n', 0), (0, 'Signature=adhoc\n', 1),
                                   (1, 'not signed at all\n', 0)]:
            with self.subTest(dv=dv, output=output, verify=verify):
                with self.assertRaises(ValueError):
                    classify_codesign(dv, output, verify)

    def test_codesign_cli_retains_status_on_unclassified_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'codesign-dv.txt').write_text('Signature=unexpected\n')
            (root / 'signing-status.txt').write_text('codesign -dv exit=0\ncodesign --verify exit=0\n')
            result = subprocess.run([sys.executable, str(TOOL.with_name('signing_helpers.py')), str(root), '0', '0'],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('observed=unclassified', (root / 'signing-status.txt').read_text())

    def test_audit_rejects_wrong_webrtc_classifier_and_foreign_nostr_native(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp) / 'relay.app' / 'Contents' / 'app'
            app.mkdir(parents=True)
            with zipfile.ZipFile(app / 'nostr-sdk-kmp-jvm-0.44.8.jar', 'w') as jar:
                jar.writestr('darwin-aarch64/libnostr_sdk_ffi.dylib', b'mac')
            with zipfile.ZipFile(app / 'webrtc-java-0.20.0-macos-aarch64-xyz.jar', 'w') as jar:
                jar.writestr('libwebrtc-java-macos-aarch64.dylib', b'mac')
            # Patch only the fixture's digest expectation, retaining production classifier checks.
            spec = importlib.util.spec_from_file_location('mac_package_helpers', TOOL)
            assert spec is not None and spec.loader is not None
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
            setattr(helper, 'WEBRTC_HASH', hashlib.sha256((app / 'webrtc-java-0.20.0-macos-aarch64-xyz.jar').read_bytes()).hexdigest())
            helper.audit_app(app.parent.parent)
            with zipfile.ZipFile(app / 'nostr-sdk-kmp-jvm-0.44.8.jar', 'a') as jar:
                jar.writestr('linux-x86-64/libnostr_sdk_ffi.so', b'linux')
            with self.assertRaisesRegex(ValueError, 'bad native set'):
                helper.audit_app(app.parent.parent)
            with zipfile.ZipFile(app / 'nostr-sdk-kmp-jvm-0.44.8.jar', 'w') as jar:
                jar.writestr('darwin-aarch64/libnostr_sdk_ffi.dylib', b'mac')
            with zipfile.ZipFile(app / 'webrtc-java-0.20.0-linux-x86_64-xyz.jar', 'w') as jar:
                jar.writestr('libwebrtc-java-linux-x86_64.so', b'linux')
            with self.assertRaisesRegex(ValueError, 'expected exactly one webrtc native jar'):
                helper.audit_app(app.parent.parent)
            # The classifier-less API jar shares the version prefix but carries no native; it must
            # never be mistaken for a second classifier (the macOS DMG audit failed on exactly that).
            (app / 'webrtc-java-0.20.0-linux-x86_64-xyz.jar').unlink()
            with zipfile.ZipFile(app / 'webrtc-java-0.20.0-c733343250561533a49e1cd7316842b3.jar', 'w') as jar:
                jar.writestr('dev/onvoid/webrtc/PeerConnection.class', b'class')
            helper.audit_app(app.parent.parent)


if __name__ == '__main__':
    unittest.main()
