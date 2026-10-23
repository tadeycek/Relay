"""Local Android/native Linux proof (never a provider/hardware test)."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time

HERE = Path(__file__).resolve().parent
PACKAGE = 'org.relay.callsprobe'


def validate(result):
    assert set(result) == {'android', 'linux'}, 'Both distinct platforms required'
    for side in ('android', 'linux'):
        r = result[side]
        assert r['connected'] is True, f'{side}: native peer not connected'
        for key in ('packets', 'bytes', 'nonzero', 'generated_frames'):
            assert r[key] > 0, f'{side}: missing {key}'
        assert r['samples'] >= 24000, f'{side}: less than half a second decoded PCM'
        assert r['square_sum'] / r['samples'] > 500**2, f'{side}: decoded energy below tone threshold'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--silent', choices=['android', 'linux'], help='Negative mutation: source sends zero PCM')
    parser.add_argument('--skip-install', action='store_true', help='Reuse the APK installed by the immediately preceding run')
    parser.add_argument('--output', type=Path, help='Sanitized JSON metrics only')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    java = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'tools/jdk-21.0.12.1+1'))) / 'bin/java'
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5556']

    def device(*cmd, check=True):
        return subprocess.run([*adb, *cmd], check=check, text=True, capture_output=True, timeout=30).stdout.strip()

    assert device('get-state') == 'device', 'Own emulator-5556 required; never falls back to another device'
    assert 'localbeat_wp11_16k' in device('emu', 'avd', 'name'), 'Unexpected AVD on emulator-5556'
    apk = HERE / 'android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk'
    jars = HERE / 'build/install/relay-calls-spike/lib/*'
    assert apk.is_file() and jars.parent.is_dir(), 'Build Android assembleDebug and desktop installDist first'
    private = HERE / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    os.chmod(private, 0o700)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    assert f'tcp:{port} ' not in device('reverse', '--list'), 'Refusing to overwrite a reverse mapping'
    mode = args.silent or 'positive'
    log_path = private / f'cross-platform-{mode}.log'
    process = None
    mapped = False
    try:
        if not args.skip_install:
            device('install', '-r', str(apk))
        assert device('shell', 'pm', 'path', PACKAGE).startswith('package:'), 'APK installation readback failed'
        device('shell', 'am', 'force-stop', PACKAGE)
        with log_path.open('w', encoding='utf-8') as native_log:
            command = [str(java), '-cp', str(jars), 'relay.spike.CrossPlatformProbe', str(port)]
            if args.silent == 'linux':
                command.append('--silent-linux')
            process = subprocess.Popen(command, stdout=native_log, stderr=subprocess.STDOUT)
            end = time.monotonic() + 30
            while 'BRIDGE_READY' not in log_path.read_text():
                assert process.poll() is None, f'Linux native peer exited; private diagnostic: {log_path}'
                assert time.monotonic() < end, 'Linux bridge startup timeout'
                time.sleep(.1)
            device('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}')
            mapped = True
            assert f'tcp:{port} tcp:{port}' in device('reverse', '--list'), 'Reverse mapping readback failed'
            device('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity',
                   '--ez', 'cross_platform', 'true', '--ei', 'bridge_port', str(port),
                   '--ez', 'silent_android', str(args.silent == 'android').lower())
            process.wait(timeout=90)
        # Read only our process and tag. Never save unrelated platform/app logs.
        pid = device('shell', 'pidof', PACKAGE)
        assert re.fullmatch(r'\d+', pid), 'Android probe process vanished'
        # Native Android disposal finishes after the host acknowledgement; wait for the exact marker.
        end = time.monotonic() + 15
        while True:
            android_log = device('logcat', '-d', f'--pid={pid}', '-s', 'RelayCallsProbe:I', '*:S')
            if 'android_cross_platform_cleanup=COMPLETE' in android_log or 'android_cross_platform=FAIL' in android_log:
                break
            assert time.monotonic() < end, 'Android native cleanup timeout'
            time.sleep(.1)
        for line in android_log.splitlines():
            if 'android_receive=' in line or 'android_cross_platform' in line:
                print(line)
        text = log_path.read_text()
        assert process.returncode == 0, f'Linux native peer failed; private diagnostic: {log_path}'
        assert 'cross_platform_cleanup=COMPLETE' in text, 'Linux native cleanup not verified'
        assert 'android_cross_platform_cleanup=COMPLETE' in android_log, 'Android cleanup not verified'
        assert 'android_cross_platform=FAIL' not in android_log, 'Android native worker failed'
        match = re.search(r'^CROSS_PLATFORM_RESULT (\{.*\})$', text, re.M)
        assert match, 'Missing actual cross-platform native metrics'
        result = json.loads(match[1])
        print(json.dumps(result, sort_keys=True))
        for line in text.splitlines():
            if line.startswith('SIGNAL_SUMMARY '):
                print(line)
        if args.output:
            args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
        validate(result)
        print('cross_platform=PASS synthetic_audio=true microphone_capture=false listening_test=false provider_test=false')
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        device('shell', 'am', 'force-stop', PACKAGE, check=False)
        if mapped:
            device('reverse', '--remove', f'tcp:{port}')
            assert f'tcp:{port} tcp:{port}' not in device('reverse', '--list'), 'Owned reverse mapping cleanup failed'


if __name__ == '__main__':
    main()
