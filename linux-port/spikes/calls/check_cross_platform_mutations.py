"""Real-device-run mutation checks. Requires own emulator-5556 and built probes."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

HERE = Path(__file__).resolve().parent
TURN = '--turn' in sys.argv
if TURN:
    sys.argv.remove('--turn')
OUTPUT = HERE / '.private' / ('turn-mutations' if TURN else 'local-mutations')


class NativeGeneratedAudio(unittest.TestCase):
    def run_probe(self, mode):
        OUTPUT.mkdir(mode=0o700, parents=True, exist_ok=True)
        path = OUTPUT / f'cross-platform-{mode}.json'
        command = [sys.executable, str(HERE / 'cross_platform.py'), '--output', str(path)]
        if TURN:
            command.append('--turn')
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
        metrics = json.loads((OUTPUT / f'cross-platform-silent-{sender}.json').read_text())
        # A networking/setup failure is NOT an acceptable mutation result: both sides must transport RTP.
        for side in ('android', 'linux'):
            if TURN:
                self.assertEqual(metrics[side]['local_candidate_type'], 'relay')
                self.assertEqual(metrics[side]['remote_candidate_type'], 'relay')
                self.assertEqual(metrics[side]['relay_protocol'], 'tls')
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
