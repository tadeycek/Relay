"""Offline guards for the Android<->Linux live Cloudflare SFU interop (SfuAndroid.py).

No network, emulator or native WebRTC is used. Never prints credential values.
"""
import ast
import json
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import SfuAndroid as S  # noqa: E402

ANDROID_SRC = HERE / 'android/src/main'


def row(participant, publisher, packets=400, nbytes=20000, samples=300000, rms=8000, connected=True):
    return {'participant': participant, 'publisher': publisher, 'packets': packets, 'bytes': nbytes,
            'samples': samples, 'square_sum': int(rms * rms * samples), 'connected': connected}


GOOD = [row('android', 'linux'), row('linux', 'android')]


class ValidateCase(unittest.TestCase):
    def test_positive_requires_both_directed_streams(self):
        S.validate_case(GOOD, None)
        with self.assertRaises(ValueError): S.validate_case(GOOD[:1], None)
        with self.assertRaises(ValueError): S.validate_case([GOOD[0], GOOD[0]], None)

    def test_positive_rejects_low_energy_each_direction(self):
        for i in (0, 1):
            rows = [dict(r) for r in GOOD]; rows[i] = row(rows[i]['participant'], rows[i]['publisher'], rms=100)
            with self.assertRaisesRegex(ValueError, 'PCM energy assertion'): S.validate_case(rows, None)

    def test_positive_rejects_missing_rtp(self):
        rows = [dict(r) for r in GOOD]; rows[1]['packets'] = 3
        with self.assertRaisesRegex(ValueError, 'inbound RTP assertion'): S.validate_case(rows, None)

    def test_negative_accepts_silent_receiver_with_rtp_and_other_direction_passing(self):
        # android silent -> linux receives silence; android still hears linux's tone.
        S.validate_case([row('android', 'linux'), row('linux', 'android', rms=3)], 'android')
        S.validate_case([row('android', 'linux', rms=3), row('linux', 'android')], 'linux')

    def test_negative_rejects_when_silent_source_still_passes_energy(self):
        with self.assertRaisesRegex(ValueError, 'silent source not rejected'): S.validate_case(GOOD, 'android')
        with self.assertRaisesRegex(ValueError, 'silent source not rejected'): S.validate_case(GOOD, 'linux')

    def test_negative_requires_rtp_to_flow_for_silent_stream(self):
        # Zero packets would be a transport failure masquerading as a silence rejection.
        with self.assertRaisesRegex(ValueError, 'inbound RTP assertion'):
            S.validate_case([row('android', 'linux'), row('linux', 'android', packets=0, nbytes=0, samples=0, rms=0)], 'android')

    def test_negative_requires_other_direction_to_pass(self):
        with self.assertRaisesRegex(ValueError, 'PCM energy assertion'):
            S.validate_case([row('android', 'linux', rms=3), row('linux', 'android', rms=3)], 'android')

    def test_negative_rejects_disconnected_silent_receiver(self):
        with self.assertRaisesRegex(ValueError, 'inbound RTP assertion'):
            S.validate_case([row('android', 'linux'), row('linux', 'android', rms=3, connected=False)], 'android')

    def test_threshold_matches_live_sfu_media(self):
        import live_sfu_media as L
        # Same function object: thresholds cannot silently diverge.
        self.assertIs(S.validate_media, L.validate_media)
        rows = [row('android', 'linux', rms=501), row('linux', 'android', rms=499)]
        with self.assertRaisesRegex(ValueError, 'PCM energy assertion'): S.validate_case(rows, None)


class SilenceMutation(unittest.TestCase):
    """The negative assertion must bite: disabling the energy check makes the negative fail."""
    def test_disabled_energy_assertion_is_detected(self):
        original = S.validate_media
        S.validate_media = lambda rows, expected: None
        try:
            with self.assertRaisesRegex(ValueError, 'silent source not rejected'):
                S.validate_case([row('android', 'linux'), row('linux', 'android', rms=3)], 'android')
        finally:
            S.validate_media = original

    def test_inverted_negative_is_detected(self):
        # Silent source reported as loud on the silent direction => negative must fail.
        with self.assertRaisesRegex(ValueError, 'silent source not rejected'):
            S.validate_case([row('android', 'linux'), row('linux', 'android', rms=9000)], 'android')


class Framing(unittest.TestCase):
    def pair(self):
        a, b = socket.socketpair(); a.settimeout(3); b.settimeout(3); return a, b

    def test_roundtrip(self):
        a, b = self.pair()
        S.write_frame(a, 'hello\tworld'); self.assertEqual(S.read_frame(b), 'hello\tworld')

    def test_oversize_write_rejected(self):
        a, b = self.pair()
        with self.assertRaises(ValueError): S.write_frame(a, 'x' * (S.FRAME_LIMIT + 1))

    def test_oversize_or_zero_read_rejected_before_allocation(self):
        for n in (S.FRAME_LIMIT + 1, 0, 2 ** 31 - 1):
            a, b = self.pair(); a.sendall(struct.pack('>i', n))
            with self.assertRaises(ValueError): S.read_frame(b)

    def test_truncated_frame_rejected(self):
        a, b = self.pair(); a.sendall(struct.pack('>i', 10) + b'abc'); a.close()
        with self.assertRaises((ConnectionError, ValueError)): S.read_frame(b)


class Bridge(unittest.TestCase):
    def serve(self, handler):
        listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
        port = listener.getsockname()[1]
        def client():
            c = socket.create_connection(('127.0.0.1', port), timeout=5); c.settimeout(5)
            S.write_frame(c, S.HELLO)
            handler(c)
        t = threading.Thread(target=client, daemon=True); t.start()
        return listener, t

    def test_loopback_only_listener_and_command_roundtrip(self):
        def h(c):
            while True:
                cmd = S.read_frame(c)
                if cmd == 'QUIT': S.write_frame(c, 'OK'); return
                S.write_frame(c, 'echo:' + cmd)
        listener, t = self.serve(h)
        try:
            self.assertEqual(listener.getsockname()[0], '127.0.0.1')
            bridge = S.AndroidBridge(listener, accept_timeout=5)
            self.assertEqual(bridge.command('MID'), 'echo:MID')
            self.assertEqual(bridge.command('REMOTE', 'offer', 'v=0\r\na=b'), 'echo:REMOTE\toffer\tv=0\r\na=b')
            bridge.close()
        finally:
            listener.close(); t.join(5)

    def test_wrong_hello_rejected(self):
        listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
        port = listener.getsockname()[1]
        def bad():
            c = socket.create_connection(('127.0.0.1', port)); S.write_frame(c, 'something-else'); c.close()
        threading.Thread(target=bad, daemon=True).start()
        with self.assertRaises(ValueError): S.AndroidBridge(listener, accept_timeout=5)
        listener.close()

    def test_silent_client_times_out_via_wall_clock(self):
        stop = threading.Event()
        listener, t = self.serve(lambda c: stop.wait(5))
        try:
            bridge = S.AndroidBridge(listener, accept_timeout=5, command_timeout=.5)
            import time
            start = time.monotonic()
            with self.assertRaises(TimeoutError): bridge.command('METRICS')
            self.assertLess(time.monotonic() - start, 3)
        finally:
            stop.set(); listener.close(); t.join(6)

    def test_command_rejects_tab_or_newline_in_verb_and_args_oversize(self):
        listener, t = self.serve(lambda c: None)
        try:
            bridge = S.AndroidBridge(listener, accept_timeout=5)
            with self.assertRaises(ValueError): bridge.command('BAD\tVERB')
            with self.assertRaises(ValueError): bridge.command('REMOTE', 'x' * S.FRAME_LIMIT)
        finally:
            listener.close(); t.join(5)


class Cleanup(unittest.TestCase):
    NAMES = ['sfu_android', 'sfu_linux', 'android_quit', 'linux_close', 'force_stop', 'reverse_remove', 'listener_close']

    def steps(self, fail, log):
        def make(name):
            def f():
                log.append(name)
                if name == fail: raise RuntimeError(name)
            return name, f
        return [make(n) for n in self.NAMES]

    def check_all(self, run_cleanup):
        for fail in [None] + self.NAMES:
            log = []
            if fail is None:
                run_cleanup(self.steps(fail, log))
            else:
                with self.assertRaises(RuntimeError) as cm: run_cleanup(self.steps(fail, log))
                self.assertEqual(str(cm.exception), fail)
            self.assertEqual(log, self.NAMES, f'a later stage was skipped after failure at {fail}')

    def test_every_stage_runs_despite_failure(self):
        self.check_all(S.run_cleanup)

    def test_two_failures_are_both_retained(self):
        log = []
        steps = self.steps('sfu_linux', log)
        steps[4] = ('force_stop', lambda: (_ for _ in ()).throw(ValueError('second')))
        with self.assertRaises(RuntimeError) as cm: S.run_cleanup(steps)
        notes = ' '.join(getattr(cm.exception, '__notes__', []))
        self.assertIn('force_stop', notes)

    def test_cleanup_survives_python_optimize(self):
        # Compile the real source with -O (asserts stripped); side effects must not live in assert.
        src = (HERE / 'SfuAndroid.py').read_text()
        tree = ast.parse(src)
        for node in ast.walk(tree):
            if isinstance(node, ast.Assert):
                for sub in ast.walk(node):
                    self.assertNotIsInstance(sub, ast.Call, 'assert with call (side effect stripped by -O)')
        ns = {'__name__': 'sfu_android_optimized'}
        code = compile(src, 'SfuAndroid.py', 'exec', optimize=1, dont_inherit=True)
        sys.path.insert(0, str(HERE))
        exec(code, ns)
        self.check_all(ns['run_cleanup'])


class SecretAndPolicy(unittest.TestCase):
    def test_android_sources_have_no_provider_or_secret_path(self):
        text = ''
        for p in ANDROID_SRC.rglob('*'):
            if p.is_file() and (p.name.startswith('SfuAndroid') or p.name == 'AndroidManifest.xml'): text += p.read_text()
        self.assertIn('SfuAndroidRun', text)
        for needle in ('rtc.live.cloudflare', 'Authorization', 'Bearer', 'SFU_APP_SECRET', 'realtime.json', 'permission.RECORD_AUDIO',
                       'HttpURLConnection', 'URLConnection', 'okhttp'):
            self.assertNotIn(needle, text, needle)
        cred = HERE.parents[2] / '.local/realtime.json'
        if cred.exists():
            data = json.loads(cred.read_text())
            for k in ('SFU_APP_SECRET', 'SFU_APP_ID', 'TURN_API_TOKEN'):
                if data.get(k): self.assertNotIn(data[k], text)

    def test_android_sfu_native_policy(self):
        text = (ANDROID_SRC / 'java/org/relay/callsprobe/SfuAndroidRun.java').read_text()
        self.assertIn('setAudioRecordEnabled(false)', text)
        self.assertIn('setAudioBufferCallback', text)
        self.assertIn('10)', text)  # 10 ms pacing
        self.assertIn('127.0.0.1', text)
        self.assertNotIn('AudioRecord(', text)
        manifest = (ANDROID_SRC / 'AndroidManifest.xml').read_text()
        self.assertIn('ACCESS_NETWORK_STATE', manifest)
        self.assertIn('SfuAndroidActivity', manifest)
        self.assertNotIn('RECORD_AUDIO', manifest)

    def test_android_activity_has_recreation_guard(self):
        text = (ANDROID_SRC / 'java/org/relay/callsprobe/SfuAndroidActivity.java').read_text()
        self.assertIn('compareAndSet(false', text)

    def test_launch_and_adb_argv_carry_only_port(self):
        argv = S.launch_argv(12345)
        joined = ' '.join(argv)
        self.assertIn('bridge_port 12345', joined)
        self.assertIn('emulator-5556', argv)
        self.assertNotIn('SFU', joined)

    def test_python_policy(self):
        src = (HERE / 'SfuAndroid.py').read_text()
        self.assertIn('Relay-WP02-Probe/1.0', src + (HERE / 'live_sfu_media.py').read_text())
        self.assertNotIn('urlopen(', src)
        self.assertNotIn('shell=True', src)
        self.assertNotIn('emulator-5554', src)
        # Provider is the redirect-refusing, size/time-bounded one.
        import live_sfu_media as L
        self.assertIs(S.Provider, L.Provider)
        self.assertEqual(L.NoRedirect().redirect_request(None, None, 302, '', {}, 'x'), None)

    def test_max_two_attempts(self):
        self.assertEqual(S.MAX_ATTEMPTS, 2)
        calls = []
        def fake(case, attempt):
            calls.append(attempt); return {'passed': False, 'error': 'TimeoutError', 'retryable': True}
        reports = S.attempt_case('none', fake, write=lambda *a: None)
        self.assertEqual(calls, [1, 2]); self.assertEqual(len(reports), 2)

    def test_no_retry_after_assertion_failure_or_pass(self):
        calls = []
        def fake(case, attempt):
            calls.append(attempt); return {'passed': False, 'error': 'PCM energy assertion', 'retryable': False}
        S.attempt_case('none', fake, write=lambda *a: None); self.assertEqual(calls, [1])
        calls.clear()
        S.attempt_case('none', lambda c, a: (calls.append(a), {'passed': True, 'retryable': False})[1], write=lambda *a: None)
        self.assertEqual(calls, [1])

    def test_unique_private_journal(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            a = S.RecoveryJournal(Path(d)); b = S.RecoveryJournal(Path(d))
            self.assertNotEqual(a.path, b.path)

    def test_owned_emulator_only(self):
        self.assertEqual(S.SERIAL, 'emulator-5556'); self.assertEqual(S.AVD, 'localbeat_wp11_16k')


if __name__ == '__main__':
    unittest.main()
