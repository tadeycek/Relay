#!/usr/bin/env python3
"""Run actual Android/JVM crypto+mailbox interop against a LOCAL instance of the real Worker.

Does not deploy, touch com.relay.app, export real keys, or satisfy unmodified-app UI acceptance.
Requires JDK21, Android SDK, Node24+, npm ci in server/cloudflare and an already booted emulator.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]

def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--node', default='node', help='Absolute path to Node 24+ recommended')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This experiment only targets disposable emulators')
    if not os.environ.get('JAVA_HOME'):
        parser.error('Set JAVA_HOME to JDK21 and put its bin on PATH')
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk'))
    adb = [sdk/'platform-tools/adb', '-s', args.serial]
    version = subprocess.check_output([args.node, '--version'], text=True)
    if int(version.lstrip('v').split('.')[0]) < 24:
        parser.error('Node 24+ required by the locked Wrangler version')
    # Refuse to replace an existing listener or an existing adb reverse mapping.
    with socket.socket() as s:
        if s.connect_ex(('127.0.0.1',8787)) == 0:
            parser.error('Port 8787 is in use; stop the prior local test Worker first')
    if 'tcp:8787' in subprocess.check_output([*adb,'reverse','--list'], text=True):
        parser.error('Existing adb reverse mapping uses 8787; remove it explicitly first')
    private = ROOT/'.local'/f'wp01-{time.time_ns()}'
    private.mkdir(parents=True, mode=0o700)
    os.chmod(private,0o700)
    gradle=[ROOT/'gradlew','-p',HERE,'--no-daemon',f'-Dorg.gradle.java.home={os.environ["JAVA_HOME"]}', '-PandroidProbe']
    run([*gradle,':jvm:test',':jvm:installDist',':android:assembleDebug',':android:assembleDebugAndroidTest','--rerun-tasks'],cwd=ROOT)
    client=HERE/'jvm/build/install/jvm/bin/jvm'
    run([client,'prepare',private])
    command=[args.node,'node_modules/wrangler/bin/wrangler.js','dev','--local','--port','8787','--var',
             'ALLOWED_PUBKEYS:'+ (private/'allowlist.txt').read_text(),'--persist-to',private/'worker-state']
    with (private/'worker.log').open('w') as log:
        proc=subprocess.Popen([str(x) for x in command],cwd=ROOT/'server/cloudflare',stdout=log,stderr=subprocess.STDOUT,
                              env={**os.environ,'WRANGLER_SEND_METRICS':'false'})
        try:
            deadline=time.monotonic()+60
            while True:
                if proc.poll() is not None: raise RuntimeError('Local Worker exited; inspect private worker.log')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:8787/v1/health',timeout=2) as response:
                        if json.load(response).get('ok'): break
                except OSError: pass
                if time.monotonic()>deadline: raise TimeoutError('Worker readiness')
                time.sleep(.25)
            run([*adb,'install','-r',HERE/'android/build/outputs/apk/debug/android-debug.apk'])
            run([*adb,'install','-r',HERE/'android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk'])
            run([*adb,'shell','run-as','relay.spike.android','mkdir','-p','files'])
            with (private/'input.json').open('rb') as f:
                run([*adb,'shell','run-as relay.spike.android sh -c "cat > files/input.json"'],stdin=f)
            run([*adb,'reverse','tcp:8787','tcp:8787'])
            run([client,'send',private])
            report=subprocess.check_output([str(x) for x in [*adb,'shell','am','instrument','-w','-e','localWorker','1',
                                            'relay.spike.android.test/androidx.test.runner.AndroidJUnitRunner']],text=True)
            print(report)
            (private/'instrumentation.txt').write_text(report)
            if 'OK (1 test)' not in report: raise RuntimeError('Instrumentation did not pass')
            with (private/'output.json').open('wb') as f:
                run([*adb,'exec-out','run-as','relay.spike.android','cat','files/output.json'],stdout=f)
            run([client,'verify',private])
            print('PASS local Worker/Android probe interop; private synthetic fixtures:',private)
        finally:
            subprocess.run([str(x) for x in [*adb,'reverse','--remove','tcp:8787']],check=False)
            proc.terminate()
            try: proc.wait(timeout=15)
            except subprocess.TimeoutExpired: proc.kill();proc.wait()

if __name__=='__main__': main()
