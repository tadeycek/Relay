#!/usr/bin/env python3
"""Opt-in live Cloudflare SFU interop: one Android native peer + one Linux native peer.

The Python coordinator alone owns the SFU app secret and every provider call. Android
receives/sends SDP only through a bounded 127.0.0.1 adb-reverse bridge; the secret never
reaches Android, argv or logs. Synthetic generated tones; SFU terminates media encryption
(NOT E2EE, NOT microphone/listening proof). Only numeric evidence is exported.
"""
import argparse
import base64
import json
import os
import re
import selectors
import socket
import struct
import subprocess
import time
import urllib.error
from pathlib import Path

import live_sfu_media as L
from live_sfu_media import (HERE, Provider, RecoveryJournal, cleanup_session, check_response,  # noqa: F401
                            validate_media, wall_clock_bound, Native)

SERIAL = 'emulator-5556'
AVD = 'localbeat_wp11_16k'
PACKAGE = 'org.relay.callsprobe'
HELLO = 'relay-sfu-android-v1'
FRAME_LIMIT = 65536
MAX_ATTEMPTS = 2
PLATFORMS = ('android', 'linux')
ASSERTION_LABELS = ('PCM energy assertion', 'inbound RTP assertion', 'directed stream count',
                    'silent source not rejected')
ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))) / 'platform-tools/adb')
JAVA = '/home/tadej/tools/jdk-21.0.12.1+1/bin/java'
APK = HERE / 'android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk'


# ---------------------------------------------------------------- assertions
def validate_case(rows, silent):
    """Positive: both directed streams pass validate_media. Negative: the stream published by
    the silent platform must still have RTP flowing yet be rejected on PCM energy, while the
    other direction passes the full positive assertion."""
    if silent is None:
        validate_media(rows, 2)
        return
    if silent not in PLATFORMS: raise ValueError('silent platform')
    if len(rows) != 2 or {(r['participant'], r['publisher']) for r in rows} != {('android', 'linux'), ('linux', 'android')}:
        raise ValueError('directed stream count')
    silent_row = next(r for r in rows if r['publisher'] == silent)
    other_row = next(r for r in rows if r['publisher'] != silent)
    # RTP must flow on the silent stream (otherwise it is a transport failure, not silence).
    if not silent_row['connected'] or silent_row['packets'] < 10 or silent_row['bytes'] < 100:
        raise ValueError('inbound RTP assertion')
    validate_media([other_row], 1)
    try:
        validate_media([silent_row], 1)
    except ValueError as e:
        if str(e) == 'PCM energy assertion': return
        raise
    raise ValueError('silent source not rejected')


# ---------------------------------------------------------------- bridge
def _recv_exact(sock, n):
    parts = []
    while n:
        chunk = sock.recv(min(n, 65536))
        if not chunk: raise ConnectionError('bridge closed')
        parts.append(chunk); n -= len(chunk)
    return b''.join(parts)


def write_frame(sock, text):
    raw = text.encode()
    if len(raw) > FRAME_LIMIT: raise ValueError('frame bound')
    sock.sendall(struct.pack('>i', len(raw)) + raw)


def read_frame(sock):
    n = struct.unpack('>i', _recv_exact(sock, 4))[0]
    if n < 1 or n > FRAME_LIMIT: raise ValueError('frame bound')
    return _recv_exact(sock, n).decode()


class AndroidBridge:
    """Python-side server on 127.0.0.1; Android connects through `adb reverse`."""
    def __init__(self, listener, accept_timeout=30, command_timeout=40):
        self.command_timeout = command_timeout
        listener.settimeout(accept_timeout)
        with wall_clock_bound(time.monotonic() + accept_timeout + 1):
            self.sock, _ = listener.accept()
        self.sock.settimeout(command_timeout)
        try:
            with wall_clock_bound(time.monotonic() + command_timeout):
                if read_frame(self.sock) != HELLO: raise ValueError('wrong endpoint')
        except BaseException:
            self.sock.close(); raise
        self.closed = False

    def command(self, op, *args):
        line = '\t'.join((op, *args))
        if '\t' in op or '\n' in op or len(line.encode()) > FRAME_LIMIT: raise ValueError('command bound')
        with wall_clock_bound(time.monotonic() + self.command_timeout):
            write_frame(self.sock, line)
            return read_frame(self.sock)

    def remote(self, description):
        self.command('REMOTE', description['type'], description['sdp'])

    def close(self):
        if self.closed: return
        self.closed = True
        try:
            try: self.command('QUIT')
            except OSError: pass
        finally:
            self.sock.close()


class LinuxNative(Native):
    """The existing SfuProbe child with a unique private log and the installed class path."""
    def __init__(self, private):
        self.deadline = time.monotonic() + 150
        self.log = open(private / f'sfu-android-linux-{os.getpid()}-{int(time.time())}.log', 'wb')
        cp = str(HERE / 'build/install/relay-calls-spike/lib/*')
        self.p = subprocess.Popen([JAVA, '-cp', cp, 'relay.spike.SfuProbe'], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=self.log)
        self.sel = selectors.DefaultSelector(); self.sel.register(self.p.stdout, selectors.EVENT_READ)

    def cmd(self, op, *args):
        return self.command(op, 0, *args)


# ---------------------------------------------------------------- adb
def adb(*args, check=True, timeout=30):
    return subprocess.run([ADB, '-s', SERIAL, *args], check=check, text=True, capture_output=True,
                          timeout=timeout).stdout.strip()


def launch_argv(port):
    return [ADB, '-s', SERIAL, 'shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.SfuAndroidActivity',
            '--ei', 'bridge_port', str(port)]


# ---------------------------------------------------------------- cleanup
def run_cleanup(steps):
    """Run every step in order; one failure never skips later ones (no asserts: -O safe)."""
    errors = []
    for name, step in steps:
        try:
            step()
        except BaseException as e:  # noqa: BLE001 - re-raised below
            errors.append((name, e))
    if errors:
        first = errors[0][1]
        for name, e in errors:
            if e is not first and hasattr(first, 'add_note'):
                first.add_note(f'cleanup stage {name} also failed: {type(e).__name__}')
        if hasattr(first, 'add_note'): first.add_note(f'cleanup stage {errors[0][0]} failed')
        raise first


class Peer:
    """Uniform command surface over the Linux SfuProbe child and the Android bridge."""
    def __init__(self, native, name):
        self.p, self.name = native, name

    def cmd(self, op, *args):
        return self.p.cmd(op, *args) if self.name == 'linux' else self.p.command(op, *args)

    def remote(self, d):
        sdp = base64.b64encode(d['sdp'].encode()).decode() if self.name == 'linux' else d['sdp']
        self.cmd('REMOTE', d['type'], sdp)


# ---------------------------------------------------------------- one run
def run_once(silent, attempt):
    os.umask(0o077)
    start = time.monotonic()
    case = silent or 'none'
    provider = Provider()  # opt-in + credential schema checked before any device/native activity
    private = HERE / '.private'; private.mkdir(mode=0o700, exist_ok=True)
    journal = RecoveryJournal(private)
    if adb('get-state') != 'device': raise RuntimeError('own emulator required')
    if AVD not in adb('emu', 'avd', 'name'): raise RuntimeError('unexpected AVD')
    if not APK.is_file(): raise RuntimeError('build android assembleDebug first')
    listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1)
    port = listener.getsockname()[1]
    if f'tcp:{port} ' in adb('reverse', '--list'): raise RuntimeError('refusing to overwrite reverse mapping')
    state = {'mapped': False, 'native': None, 'bridge': None}
    sessions = {}; allocations = {}; mapping = {}; rows = []; cleanup = {}
    error = None; stage = 'setup'; http_status = None; android_cleanup = False
    native_exit = False; reverse_removed = None; peers = {}
    marks = {}

    def mark(name): marks[name] = round(time.monotonic() - start, 2)

    try:
        adb('install', '-r', str(APK), timeout=120)
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('logcat', '-c')
        state['native'] = peers['linux'] = LinuxNative(private)
        adb('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}'); state['mapped'] = True
        if f'tcp:{port} tcp:{port}' not in adb('reverse', '--list'): raise RuntimeError('reverse readback')
        subprocess.run(launch_argv(port), check=True, capture_output=True, timeout=30)
        stage = 'bridge'
        state['bridge'] = peers['android'] = AndroidBridge(listener)
        mark('bridge')
        provider.deadline = time.monotonic() + 150
        P = {n: Peer(peers[n], n) for n in PLATFORMS}
        stage = 'publish'
        for name in PLATFORMS:
            p = P[name]
            p.cmd('NEW', '1' if name == silent else '0')
            offer = p.cmd('OFFER'); mid = p.cmd('MID')
            created = check_response(provider.call('POST', '/sessions/new'))
            sid = created.get('sessionId', '')
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', sid): raise ValueError('session schema')
            sessions[name] = sid; allocations[sid] = [mid]
            journal.record(sid, allocations[sid], 'unresolved')
            result = provider.call('POST', f'/sessions/{sid}/tracks/new', {
                'sessionDescription': {'type': 'offer', 'sdp': offer},
                'tracks': [{'location': 'local', 'mid': mid, 'trackName': f'tone-{name}'}]})
            allocations[sid].extend(t['mid'] for t in result.get('tracks', []) if 'mid' in t and not t.get('errorCode'))
            journal.record(sid, allocations[sid], 'unresolved')
            check_response(result)
            if len(result.get('tracks', [])) != 1: raise ValueError('publication count')
            p.remote(result['sessionDescription'])
            p.cmd('CONNECTED')
            check_response(provider.call('GET', f'/sessions/{sid}'))
            mark(f'published_{name}')
        stage = 'subscribe'
        for name in PLATFORMS:
            other = 'linux' if name == 'android' else 'android'
            sid = sessions[name]; p = P[name]
            result = provider.call('POST', f'/sessions/{sid}/tracks/new', {'tracks': [
                {'location': 'remote', 'sessionId': sessions[other], 'trackName': f'tone-{other}'}]})
            allocations[sid].extend(t['mid'] for t in result.get('tracks', []) if 'mid' in t and not t.get('errorCode'))
            journal.record(sid, allocations[sid], 'unresolved')
            check_response(result)
            tracks = result.get('tracks', [])
            if len(tracks) != 1 or tracks[0].get('trackName') != f'tone-{other}': raise ValueError('subscription mapping')
            mapping[name, tracks[0]['mid']] = other
            p.remote(result['sessionDescription'])
            answer = p.cmd('ANSWER')
            check_response(provider.call('PUT', f'/sessions/{sid}/renegotiate', {'sessionDescription': {'type': 'answer', 'sdp': answer}}))
            p.cmd('CONNECTED')
            check_response(provider.call('GET', f'/sessions/{sid}'))
            mark(f'subscribed_{name}')
        stage = 'media'
        time.sleep(8)
        for name in PLATFORMS:
            reported = json.loads(P[name].cmd('METRICS'))
            for r in reported:
                r['publisher'] = mapping[name, r.pop('mid')]; r['participant'] = name
                r['rms'] = round((r['square_sum'] / max(1, r['samples'])) ** 0.5, 2)
                rows.append(r)
        stage = 'assert'
        validate_case(rows, silent)
    except BaseException as e:  # noqa: BLE001
        error = type(e).__name__
        if isinstance(e, urllib.error.HTTPError): http_status = e.code
        if isinstance(e, ValueError) and str(e) in ASSERTION_LABELS: error = str(e)
    finally:
        provider.deadline = time.monotonic() + 90
        def sfu_close(name):
            def f():
                sid = sessions.get(name)
                if sid: cleanup[name] = cleanup_session(provider, sid, allocations[sid], journal)
            return f
        def android_quit():
            if state['bridge'] is not None: state['bridge'].close()
        def android_marker():
            nonlocal android_cleanup
            end = time.monotonic() + 15
            while time.monotonic() < end:
                log = adb('logcat', '-d', '-s', 'RelayCallsProbe:I', '*:S', check=False)
                if 'sfu_android_cleanup=COMPLETE' in log: android_cleanup = True; return
                time.sleep(.2)
        def linux_close():
            nonlocal native_exit
            if state['native'] is not None:
                state['native'].close(); native_exit = True
        def reverse_remove():
            nonlocal reverse_removed
            if state['mapped']:
                adb('reverse', '--remove', f'tcp:{port}')
                reverse_removed = f'tcp:{port} tcp:{port}' not in adb('reverse', '--list')
        try:
            run_cleanup([('sfu_android', sfu_close('android')), ('sfu_linux', sfu_close('linux')),
                         ('android_quit', android_quit), ('android_marker', android_marker),
                         ('linux_close', linux_close),
                         ('force_stop', lambda: adb('shell', 'am', 'force-stop', PACKAGE, check=False)),
                         ('reverse_remove', reverse_remove), ('listener_close', listener.close)])
        except BaseException as e:  # noqa: BLE001
            error = error or f'cleanup:{type(e).__name__}'
    report = {'case': case, 'attempt': attempt, 'silent_publisher': silent, 'streams': rows, 'stream_count': len(rows),
              'error': error, 'http_error_status': http_status, 'stage': stage, 'cleanup': cleanup,
              'native_normal_exit': native_exit, 'android_cleanup_marker': android_cleanup,
              'reverse_removed': reverse_removed, 'api_calls': provider.calls, 'milestones_seconds': marks,
              'elapsed_seconds': round(time.monotonic() - start, 2), 'synthetic_audio': True,
              'physical_microphone': False, 'e2ee': False, 'local_ice_only': True}
    report['passed'] = (error is None and native_exit and android_cleanup and reverse_removed is True
                        and len(cleanup) == 2 and all(c['close_ok'] and c['readback_ok'] for c in cleanup.values()))
    # Only transport/timeout class failures are retryable; assertion outcomes are not.
    report['retryable'] = (not report['passed']) and error not in ASSERTION_LABELS and stage != 'assert'
    return report


def attempt_case(silent_name, runner, write):
    reports = []
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            report = runner(None if silent_name == 'none' else silent_name, attempt)
        except Exception as e:  # noqa: BLE001
            report = {'case': silent_name, 'attempt': attempt, 'passed': False, 'error': type(e).__name__,
                      'retryable': False, 'details': 'suppressed'}
        report['stamp_unix'] = int(time.time())
        reports.append(report); write(silent_name, attempt, report)
        if report.get('passed') or not report.get('retryable'): break
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=['none', 'android', 'linux'], required=True,
                        help="'android'/'linux' = that platform publishes real silence")
    parser.add_argument('--out-dir', type=Path, default=HERE / 'evidence')
    args = parser.parse_args()
    def write(case, attempt, report):
        (args.out_dir / f'sfu-android-{case}-attempt{attempt}.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print(json.dumps(report, sort_keys=True))
    reports = attempt_case(args.case, run_once, write)
    return 0 if reports[-1].get('passed') else 1


if __name__ == '__main__':
    raise SystemExit(main())
