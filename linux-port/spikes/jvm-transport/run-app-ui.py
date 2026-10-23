#!/usr/bin/env python3
"""Actual Relay UI↔JVM interop through local real Worker. Disposable emulator only.

Installs a 1.15 debug build with no live mailbox URL and a test-only runner. Does not deploy.
Refuses an existing app unless --reset-owned-fixture and our fixture marker proves ownership.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
PACKAGE='com.relay.app'
MARKER=b'relay-wp01-disposable-ui-fixture-v1'


def run(args, **kw):
    return subprocess.run([str(x) for x in args],check=True,**kw)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--node',default='node')
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--reset-owned-fixture',action='store_true')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):parser.error('Disposable emulators only')
    if not os.environ.get('JAVA_HOME'):parser.error('JAVA_HOME must point to JDK21')
    sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))
    adb=[str(sdk/'platform-tools/adb'),'-s',args.serial]
    installed=subprocess.check_output([*adb,'shell','pm','list','packages',PACKAGE],text=True)
    if 'package:'+PACKAGE in installed:
        marker=subprocess.run([*adb,'exec-out','run-as',PACKAGE,'cat','files/jvm-ui-owned'],capture_output=True)
        if not args.reset_owned_fixture or marker.returncode or marker.stdout!=MARKER:
            parser.error('Refusing installed Relay app; only --reset-owned-fixture with exact harness ownership marker permits reset')
        result=subprocess.check_output([*adb,'shell','pm','clear',PACKAGE],text=True)
        if 'Success' not in result:raise RuntimeError('Owned fixture clear failed')
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1',8787))==0:parser.error('Port 8787 already in use')
    major=int(subprocess.check_output([args.node,'--version'],text=True).lstrip('v').split('.')[0])
    if major<24:parser.error('Node24+ required')
    gradle=[ROOT/'gradlew','--no-daemon',f'-Dorg.gradle.java.home={os.environ["JAVA_HOME"]}']
    if not args.skip_build:
        run([*gradle,'-I',HERE/'ui-local.init.gradle',':app:assembleDebug',':app:assembleDebugAndroidTest'],cwd=ROOT)
        run([*gradle,'-p',HERE,':jvm:test',':jvm:installDist','--rerun-tasks'],cwd=ROOT)
    private=ROOT/'.local'/f'wp01-ui-{time.time_ns()}'
    private.mkdir(parents=True,mode=0o700);os.chmod(private,0o700)
    client=HERE/'jvm/build/install/jvm/bin/jvm'
    run([client,'prepare-ui',private])
    run([*adb,'install','-r',ROOT/'app/build/outputs/apk/debug/app-debug.apk'])
    run([*adb,'install','-r',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'])
    run([*adb,'shell','run-as',PACKAGE,'mkdir','-p','files/jvm-ui'])
    run([*adb,'shell',f'run-as {PACKAGE} sh -c "cat > files/jvm-ui-owned"'],input=MARKER)
    with (private/'ui-input.json').open('rb') as f:
        run([*adb,'shell',f'run-as {PACKAGE} sh -c "cat > files/jvm-ui/input.json"'],stdin=f)
    # Package replacement can wake a previously scheduled app job; force a fresh instrumentation process.
    run([*adb,'shell','am','force-stop',PACKAGE])
    worker=None
    with (private/'instrumentation.txt').open('w') as output, (private/'worker.txt').open('w') as log:
        instrument=subprocess.Popen([*adb,'shell','am','instrument','-w','-e','class',
            'com.relay.app.calculator.JvmAppInteropTest','-e','jvmAppInterop','1',
            'com.relay.app.test/com.relay.app.calculator.JvmInteropRunner'],stdout=output,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+150
            while True:
                ready=subprocess.run([*adb,'exec-out','run-as',PACKAGE,'cat','files/jvm-ui/ready.json'],capture_output=True)
                try: android=json.loads(ready.stdout);break
                except ValueError:pass
                if instrument.poll() is not None:raise RuntimeError('Instrumentation exited before ready; inspect private instrumentation.txt')
                if time.monotonic()>deadline:raise TimeoutError('App fixture readiness')
                time.sleep(.25)
            (private/'ui-ready.json').write_text(json.dumps(android))
            peer=json.loads((private/'ui-input.json').read_text())
            allow=peer['jvm_pub']+','+android['android_pub']
            worker=subprocess.Popen([args.node,'node_modules/wrangler/bin/wrangler.js','dev','--local','--port','8787',
                '--var','ALLOWED_PUBKEYS:'+allow,'--persist-to',str(private/'worker-state')],cwd=ROOT/'server/cloudflare',
                stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'WRANGLER_SEND_METRICS':'false'})
            deadline=time.monotonic()+60
            while True:
                if worker.poll() is not None:raise RuntimeError('Worker exited')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:8787/v1/health',timeout=2) as response:
                        if json.load(response).get('ok'):break
                except OSError:pass
                if time.monotonic()>deadline:raise TimeoutError('Worker readiness')
                time.sleep(.25)
            run([*adb,'shell','run-as',PACKAGE,'touch','files/jvm-ui/worker-ready'])
            run([client,'send-ui',private])
            run([client,'verify-ui',private])
            run([*adb,'shell','run-as',PACKAGE,'touch','files/jvm-ui/host-verified'])
            instrument.wait(timeout=45);output.flush()
            report=(private/'instrumentation.txt').read_text()
            print(report)
            if 'OK (1 test)' not in report:raise RuntimeError('App UI test failed')
            for name in ['chat.png','passed.json','ui-sent.json']:
                with (private/name).open('wb') as f:
                    run([*adb,'exec-out','run-as',PACKAGE,'cat','files/jvm-ui/'+name],stdout=f)
            print('PASS actual app UI and production transport/outbox interop; fixture evidence:',private)
        finally:
            if instrument.poll() is None:
                instrument.terminate()
                try:instrument.wait(timeout=5)
                except subprocess.TimeoutExpired:instrument.kill();instrument.wait()
            subprocess.run([*adb,'shell','am','force-stop',PACKAGE],check=False)
            if worker is not None:
                worker.terminate()
                try:worker.wait(timeout=15)
                except subprocess.TimeoutExpired:worker.kill();worker.wait()


if __name__=='__main__':main()
