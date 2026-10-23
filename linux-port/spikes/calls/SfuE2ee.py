#!/usr/bin/env python3
"""WP02-AC5 step D: the E2EE exchange (Android FrameCryptor <-> Linux AES-GCM transform) THROUGH the live Cloudflare SFU.

Reuses the SfuAndroid coordinator unchanged (it is NOT edited): this module swaps in E2EE variants of the
Linux child, Android bridge/activity and the assertion. The Python coordinator alone owns the SFU secret AND the
per-run test-only AES key (memory only; sent to the two peers over a private pipe / loopback bridge, never in argv,
logs or evidence). Opt-in: RELAY_SPIKE_LIVE=1. Honest UA and force-close/readback cleanup come from live_sfu_media.
"""
import argparse
import json
import os
import secrets
import selectors
import subprocess
import sys
import time
from pathlib import Path

if not __debug__:
    sys.exit('refusing to run with python -O: acceptance asserts would be stripped')

import SfuAndroid as S
from e2ee_accept import validate_sfu

HERE = S.HERE
STATE = {}


def new_state(case):
    material = secrets.token_hex(16)
    return dict(case=case, linux_key=secrets.token_hex(16) if case == 'wrong_key' else material, android_key=material,
                salt=secrets.token_hex(16))


class E2eeLinuxNative(S.LinuxNative):
    def __init__(self, private):
        self.deadline = time.monotonic() + 150
        self.log = open(private / f'sfu-e2ee-linux-{os.getpid()}-{int(time.time())}.log', 'wb')
        cp = str(HERE / 'build/install/relay-calls-spike/lib/*')
        self.p = subprocess.Popen([S.JAVA, '-cp', cp, 'relay.spike.SfuE2eeProbe'], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=self.log)
        self.sel = selectors.DefaultSelector(); self.sel.register(self.p.stdout, selectors.EVENT_READ)
        self.command('CONFIG', 0, '1', STATE['linux_key'], STATE['salt'], 'pbkdf2-100000', '16')


class E2eeBridge(S.AndroidBridge):
    def __init__(self, listener):
        super().__init__(listener)
        self.command('CONFIG', '1', STATE['android_key'], STATE['salt'], 'PBKDF2')


def launch_argv(port):
    return [S.ADB, '-s', S.SERIAL, 'shell', 'am', 'start', '-W', '-n', f'{S.PACKAGE}/.SfuE2eeActivity', '--ei', 'bridge_port', str(port)]


def install_patches():
    S.LinuxNative = E2eeLinuxNative
    S.AndroidBridge = E2eeBridge
    S.launch_argv = launch_argv
    S.validate_case = lambda rows, silent: validate_sfu(STATE['case'], rows)


def runner(case):
    def run(_silent, attempt):
        STATE.clear(); STATE.update(new_state(case))
        report = S.run_once(None, attempt)
        report.update(e2ee=True, e2ee_case=case, aes_bits=128, layout='livekit-style AES-GCM, PBKDF2-SHA256-100000, 1B clear header, IV12|ivlen|keyidx trailer')
        STATE.clear()
        return report
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=['match', 'wrong_key'], required=True)
    parser.add_argument('--out-dir', type=Path, default=HERE / 'evidence')
    args = parser.parse_args()
    install_patches()
    def write(case, attempt, report):
        (args.out_dir / f'e2ee-sfu-{args.case}-attempt{attempt}.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print(json.dumps({k: report.get(k) for k in ('case', 'e2ee_case', 'attempt', 'passed', 'error', 'stage', 'streams', 'cleanup', 'retryable')}, sort_keys=True))
    reports = S.attempt_case('none', runner(args.case), write)
    return 0 if reports[-1].get('passed') else 1


if __name__ == '__main__':
    raise SystemExit(main())
