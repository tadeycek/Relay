"""WP02-AC5: Android FrameCryptor <-> Linux webrtc-java AES-GCM interop over the local ADB-reverse loopback bridge.

The coordinator owns the test-only keys (generated per run, in memory, handed to the Linux probe on stdin; the Linux
probe forwards the Android half over the loopback bridge). Keys, SDP and frames never reach disk, argv or stdout.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time

from e2ee_accept import CASES, validate_case

HERE = Path(__file__).resolve().parent
PACKAGE = 'org.relay.callsprobe'
KDF_LINUX = os.environ.get('E2EE_KDF', 'pbkdf2-100000')   # layout discovery result, see E2EE-INTEROP-EVIDENCE.md
KDF_ANDROID = os.environ.get('E2EE_ANDROID_KDF', 'PBKDF2')
DERIVED_LEN = os.environ.get('E2EE_DERIVED_LEN', '16')


def build_config(case, bits=128, scan=False):
    """Pure function: per-case Linux/Android flags and in-memory keys. No I/O so it is unit-testable."""
    assert case in CASES, case
    material = bits // 8
    k0, k1, wrong0, wrong1 = (secrets.token_hex(material) for _ in range(4))
    cfg = dict(linux_enc='1', android_enc='1', rotate='0', tamper_send='0', tamper_recv='0', replay='0', silent='0',
               dtx='0', scan='1' if scan else '0', duration_ms='8000', kdf=KDF_LINUX, android_kdf=KDF_ANDROID,
               derived_len=DERIVED_LEN, android_derived_len=DERIVED_LEN, salt=secrets.token_hex(16),
               linux_key0=k0, linux_key1=k1, android_key0=k0, android_key1=k1)
    if case == 'wrong_key_linux':
        cfg.update(linux_key0=wrong0, linux_key1=wrong1)
    elif case == 'wrong_key_android':
        cfg.update(android_key0=wrong0, android_key1=wrong1)
    elif case == 'plain_android':
        cfg.update(android_enc='0')
    elif case == 'plain_linux':
        cfg.update(linux_enc='0')
    elif case == 'tamper_linux_send':
        cfg.update(tamper_send='1')
    elif case == 'tamper_linux_receive':
        cfg.update(tamper_recv='1')
    elif case == 'rotate':
        cfg.update(rotate='1', duration_ms='10000')
    elif case == 'replay':
        cfg.update(replay='1')
    elif case == 'dtx':
        cfg.update(silent='1', dtx='1')
    return cfg


def sanitize(result):
    """Evidence may contain numbers/booleans/state-name counts only."""
    def walk(v):
        if isinstance(v, dict):
            return {str(k): walk(x) for k, x in v.items()}
        assert isinstance(v, (int, float, bool)), f'non-numeric evidence value rejected: {type(v).__name__}'
        return v
    return walk(result)


def run_case(case, attempt, output_dir, skip_install=False, bits=128, scan=False):
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    java = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'tools/jdk-21.0.12.1+1'))) / 'bin/java'
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5556']

    def device(*cmd, check=True):
        return subprocess.run([*adb, *cmd], check=check, text=True, capture_output=True, timeout=60).stdout.strip()

    assert device('get-state') == 'device', 'Own emulator-5556 required'
    assert 'localbeat_wp11_16k' in device('emu', 'avd', 'name'), 'Unexpected AVD on emulator-5556'
    apk = HERE / 'android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk'
    jars = HERE / 'build/install/relay-calls-spike/lib/*'
    assert apk.is_file() and jars.parent.is_dir(), 'Build Android assembleDebug and desktop installDist first'
    private = HERE / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    assert f'tcp:{port} ' not in device('reverse', '--list'), 'Refusing to overwrite a reverse mapping'
    cfg = build_config(case, bits, scan)
    log_path = private / f'e2ee-{case}.log'
    process = None
    mapped = False
    try:
        if not skip_install:
            device('install', '-r', str(apk))
        assert device('shell', 'pm', 'path', PACKAGE).startswith('package:'), 'APK installation readback failed'
        device('shell', 'am', 'force-stop', PACKAGE)
        device('logcat', '-c')
        with log_path.open('w', encoding='utf-8') as native_log:
            process = subprocess.Popen([str(java), '-cp', str(jars), 'relay.spike.E2eeProbe', str(port)],
                                       stdin=subprocess.PIPE, stdout=native_log, stderr=subprocess.STDOUT)
            process.stdin.write(''.join(f'{k}={v}\n' for k, v in cfg.items()).encode() + b'end\n')
            process.stdin.close()
            cfg = None
            end = time.monotonic() + 30
            while 'BRIDGE_READY' not in log_path.read_text():
                assert process.poll() is None, f'Linux peer exited early; private diagnostic: {log_path}'
                assert time.monotonic() < end, 'Linux bridge startup timeout'
                time.sleep(.1)
            device('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}')
            mapped = True
            device('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.E2eeActivity', '--ei', 'bridge_port', str(port))
            process.wait(timeout=120)
        pid = device('shell', 'pidof', PACKAGE)
        assert re.fullmatch(r'\d+', pid), 'Android probe process vanished'
        end = time.monotonic() + 20
        while True:
            android_log = device('logcat', '-d', f'--pid={pid}', '-s', 'RelayCallsProbe:I', '*:S')
            if 'android_cross_platform_cleanup=COMPLETE' in android_log or 'e2ee_android=FAIL' in android_log:
                break
            assert time.monotonic() < end, 'Android native cleanup timeout'
            time.sleep(.1)
        assert 'e2ee_android=FAIL' not in android_log, 'Android native worker failed'
        text = log_path.read_text()
        assert process.returncode == 0, f'Linux native peer failed; private diagnostic: {log_path}'
        assert 'e2ee_cleanup=COMPLETE' in text, 'Linux native cleanup not verified'
        assert 'android_cross_platform_cleanup=COMPLETE' in android_log, 'Android cleanup not verified'
        scan_lines = [line for line in text.splitlines() if line.startswith('SCAN')]  # sizes/booleans/scheme names only
        for line in scan_lines:
            print(line)
        match = re.search(r'^E2EE_RESULT (\{.*\})$', text, re.M)
        assert match, 'Missing E2EE metrics'
        result = json.loads(match[1])
        assert result.get('linux_transform_failure') is False, 'Linux transform raised'
        result = sanitize(result)
        result_for_validation = {'android': result['android'], 'linux': result['linux']}
        record = dict(case=case, attempt=attempt, aes_bits=bits, completed_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), **result)
        outcome = 'PASS'
        try:
            validate_case(case, result_for_validation)
        except AssertionError as failure:
            outcome = 'FAIL'
            record['assertion'] = str(failure)
        record['acceptance'] = outcome
        if scan:
            (output_dir / f'e2ee-layout-scan-{bits}.json').write_text(json.dumps(dict(kdf_android=KDF_ANDROID, aes_material_bits=bits, lines=scan_lines), indent=2) + '\n')
        output = output_dir / f'e2ee-{case}{"-aes256material" if bits == 256 else ""}-attempt{attempt}.json'
        output.write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
        print(json.dumps(record, sort_keys=True))
        print(f'e2ee_case={case} attempt={attempt} acceptance={outcome} synthetic_audio=true microphone_capture=false provider_test=false')
        return outcome == 'PASS'
    finally:
        try:
            try:
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            finally:
                try:
                    device('shell', 'am', 'force-stop', PACKAGE, check=False)
                finally:
                    if mapped:
                        try:
                            device('reverse', '--remove', f'tcp:{port}')
                        finally:
                            if f'tcp:{port} tcp:{port}' in device('reverse', '--list'):
                                raise RuntimeError('Owned reverse mapping cleanup failed')
        finally:
            pass


def main():
    if not __debug__:
        raise SystemExit('refusing to run with python -O: acceptance asserts would be stripped')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', choices=sorted(CASES) + ['scan'])
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--bits', type=int, choices=[128, 256], default=128)
    parser.add_argument('--skip-install', action='store_true')
    parser.add_argument('--output-dir', type=Path, default=HERE / 'evidence')
    args = parser.parse_args()
    scan = args.case == 'scan'
    ok = run_case('match' if scan else args.case, args.attempt, args.output_dir, args.skip_install, args.bits, scan)
    raise SystemExit(0 if ok else 1)


if __name__ == '__main__':
    main()
