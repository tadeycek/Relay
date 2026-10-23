"""Real-device-run mutation checks. Requires own emulator-5556 and built probes."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

HERE = Path(__file__).resolve().parent


class NativeGeneratedAudio(unittest.TestCase):
    def run_probe(self, mode):
        path = HERE / 'evidence' / f'cross-platform-{mode}.json'
        command = [sys.executable, str(HERE / 'cross_platform.py'), '--output', str(path)]
        if mode != 'positive':
            command += ['--skip-install', '--silent', mode.removeprefix('silent-')]
        result = subprocess.run(command, text=True, capture_output=True, timeout=120,
                                env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        print(result.stdout, end='')
        return result

    def test_positive_both_native_platforms(self):
        result = self.run_probe('positive')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('cross_platform=PASS', result.stdout)

    def check_silence(self, sender, receiver):
        result = self.run_probe('silent-' + sender)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(f'AssertionError: {receiver}: decoded energy below tone threshold', result.stderr)
        metrics = json.loads((HERE / 'evidence' / f'cross-platform-silent-{sender}.json').read_text())
        # A networking/setup failure is NOT an acceptable mutation result: both sides must transport RTP.
        for side in ('android', 'linux'):
            self.assertTrue(metrics[side]['connected'])
            self.assertGreater(metrics[side]['packets'], 0)
            self.assertGreater(metrics[side]['samples'], 24000)
        self.assertNotIn('cross_platform=PASS', result.stdout)
        print(f'mutation=PASS silent_sender={sender} correctly_rejected_receiver={receiver}')

    def test_silent_android_rejected_by_linux_decoded_pcm(self):
        self.check_silence('android', 'linux')

    def test_silent_linux_rejected_by_android_decoded_pcm(self):
        self.check_silence('linux', 'android')


if __name__ == '__main__':
    unittest.main(verbosity=2)
