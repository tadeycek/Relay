#!/usr/bin/env python3
"""Live test of the delivery-server pilot (server/cloudflare) with the real app on two emulators.

Build with .local/mailbox.properties (url=<pilot>) and install the app and test APKs on both first,
like test-two-emulators.py. Such a build talks only to the delivery server: the harness checks that
the running transport is the mailbox (no Nostr relay is ever contacted). Then: QR pairing (its
messages travel through the mailbox too), text both ways, a photo (stored on the server, readable
only by the recipient), and a message sent while Bob is in airplane mode, received when he comes back
(store-and-forward and catch-up). Then a group (posts both ways, a group photo), typing indicators
(live signals, direct and in the group), and delivery while Bob's app isn't running: Android's
periodic background check is forced to run and must post the message notification. With --push,
periodic polling is paused instead: a real Firebase callback and the notification must arrive unaided.

The server admits its founders (ALLOWED_PUBKEYS) and whoever pairs in person with a member. The
emulator identities are written to .local/mailbox-emulator-pubkeys.txt. --founders says who is on the
deployed allowlist (both, or only Alice, so Bob has to join by pairing); --deploy-allowlist redeploys
the test pilot to match. With --repair the existing pairing is forgotten first, and --shower picks who
shows the QR (Bob showing tests a member vouching; Alice showing tests joining with her invite). Never run
against personal app data. Artifacts stay under .local/.
"""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--alice', default='emulator-5554')
parser.add_argument('--bob', default='emulator-5556')
parser.add_argument('--founders', default='both', choices=['both', 'alice'], help='Who is on the allowlist (founders).')
parser.add_argument('--deploy-allowlist', action='store_true', help='Redeploy the test pilot with --founders as its allowlist.')
parser.add_argument('--repair', action='store_true', help='Forget the existing Alice/Bob pairing and pair again.')
parser.add_argument('--shower', default='Bob', choices=['Alice', 'Bob'], help='Who shows the QR code; the other scans it.')
parser.add_argument('--push', action='store_true', help='Verify real Firebase wake-up delivery with periodic polling paused.')
parser.add_argument('--push-only', action='store_true', help='Run only pairing/registration and the controlled FCM delivery check.')
args = parser.parse_args()
if args.push_only:
    args.push = True
ADB = shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb')
PKG = 'com.relay.app'
ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / 'server/cloudflare'
(ROOT / '.local').mkdir(exist_ok=True)
peers = {'Alice': args.alice, 'Bob': args.bob}
assert len(set(peers.values())) == 2, 'Use two separate emulators'
assert all(serial.startswith('emulator-') for serial in peers.values()), 'This harness only targets emulators'
counter = 0
processes = []


def adb(role, *a, input=None, check=True, timeout=35):
    r = subprocess.run([ADB, '-s', peers[role], *a], input=input, capture_output=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr.decode(errors='replace') + r.stdout.decode(errors='replace'))
    return r.stdout


def state(role):
    try:
        return json.loads(adb(role, 'exec-out', 'run-as', PKG, 'cat', 'files/peer-test/result.json', check=False))
    except (ValueError, TypeError):
        return None


def wait(predicate, reason, seconds=100):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.5)
    raise AssertionError(reason)


def command(role, action, **values):
    global counter
    counter += 1
    identifier = f'{counter}-{action}-{uuid.uuid4().hex[:4]}'
    data = json.dumps({'id': identifier, 'action': action, **values}, ensure_ascii=False).encode()
    adb(role, 'shell', "run-as com.relay.app sh -c 'cat > files/peer-test/command.tmp && mv files/peer-test/command.tmp files/peer-test/command.json'", input=data)
    result = wait(lambda: (s if (s := state(role)) and s.get('id') == identifier else None), f'{role}: no response to {action}', 55)
    if not result.get('ok'):
        lines = [l.strip() for l in str(result.get('error', '')).splitlines() if 'PeerConversationTest' in l or 'Error' in l or 'Exception' in l]
        raise AssertionError(f'{role} {action} failed: ' + ' | '.join(lines[:3]))
    return result


def open_chat(role, peer):
    """Opening a conversation is pure UI navigation; a starved emulator sometimes misses the first tap."""
    try:
        command(role, 'chat', peer=peer)
    except AssertionError as error:
        print(f'NOTE: {role} retrying chat navigation ({error})', flush=True)
        command(role, 'chat', peer=peer)


def has_message(result, body, sent):
    return any(m['body'] == body and m['sent'] == sent for c in result['contacts'] for m in c['messages'])


def group_message(result, group, body, sent=False, image=False):
    return any(g['name'] == group and any(m['body'] == body and m['sent'] == sent and (not image or (m['type'] == 'IMAGE' and m['mediaHash']))
                                          for m in g['messages']) for g in result['groups'])


def relay_notifications(role):
    out = adb(role, 'shell', 'dumpsys', 'notification', '--noredact').decode(errors='replace')
    return len(re.findall(r'NotificationRecord\(.*pkg=com\.relay\.app', out))


def push_log_count(role, message):
    output = adb(role, 'logcat', '-d', '-v', 'brief', '-s', 'MailboxPush:I').decode(errors='replace')
    return output.count(message)


def run_background_check(role):
    """Forces WorkManager's periodic PollWorker (a JobScheduler job) to run now."""
    jobs = adb(role, 'shell', 'dumpsys', 'jobscheduler', 'com.relay.app').decode(errors='replace')
    ids = set(re.findall(r'JOB #u\d+a\d+/(\d+): \S+ com\.relay\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService', jobs))
    assert ids, f'{role}: no scheduled background job found'
    for job in ids:
        adb(role, 'shell', 'cmd', 'jobscheduler', 'run', '-f', 'com.relay.app', job, check=False)
    return ids


def send_text(role, text):
    """Sends through the UI. A starved emulator can draw the new bubble after the harness's 5 s check;
    delivery is what this test is about, so the sender's own database decides whether it was sent."""
    try:
        command(role, 'send', text=text)
    except AssertionError:
        if not has_message(command(role, 'status'), text, True):
            raise
        print(f'NOTE: {role}\'s screen was slow to show the sent message; it is stored as sent', flush=True)


def deploy_allowlist(extra):
    pilot = subprocess.run(['node', 'tools/pilot.ts', 'allowlist'], cwd=SERVER, capture_output=True, text=True, check=True).stdout.strip()
    allow = ','.join([pilot, *extra])
    r = subprocess.run(['npx', 'wrangler', 'deploy', '--var', f'ALLOWED_PUBKEYS:{allow}', '--var', 'EXPOSE_METRICS:1'],
                       cwd=SERVER, capture_output=True, text=True, env={**os.environ, 'WRANGLER_SEND_METRICS': 'false'})
    if r.returncode:
        raise RuntimeError('deploy failed:\n' + r.stdout[-2000:] + r.stderr[-2000:])


def check_background_delivery(token):
    open_chat('Alice', 'Bob')
    if args.push:
        command('Bob', 'pause_poll')
    command('Bob', 'stop')
    wait(lambda: not adb('Bob', 'shell', 'pidof', 'com.relay.app', check=False).strip(), "Bob's app did not stop", 30)
    before = relay_notifications('Bob')
    wake_before = push_log_count('Bob', 'wake-up push received') if args.push else 0
    background = f'Delivered while Bob\'s app was closed {token}'
    started = time.monotonic()
    send_text('Alice', background)
    if args.push:
        wait(lambda: push_log_count('Bob', 'wake-up push received') > wake_before,
             "Bob received no Firebase wake-up with his app closed", 120)
        wait(lambda: relay_notifications('Bob') > before, "Bob's Firebase wake-up posted no notification", 90)
        print(f"PASS: with Bob's app closed and periodic polling paused, real FCM woke it and notified ({time.monotonic() - started:.1f}s after send; no forced job)", flush=True)
    else:
        time.sleep(5)
        run_background_check('Bob')
        wait(lambda: relay_notifications('Bob') > before, "Bob's background check posted no notification", 90)
        print("PASS: with Bob's app closed, Android's periodic background check fetched the message from the server and notified", flush=True)


try:
    for role in peers:
        assert adb(role, 'shell', 'getprop', 'sys.boot_completed').strip() == b'1', f'{role} emulator is not booted'
        adb(role, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable', check=False)
    registered = {role: push_log_count(role, 'push token registration: HTTP 200') for role in peers} if args.push else {}
    for role, serial in peers.items():
        adb(role, 'shell', 'run-as', PKG, 'rm', '-f', 'files/peer-test/result.json', 'files/peer-test/command.json', check=False)
        output = (ROOT / '.local' / f'mailbox-{role.lower()}-instrument.log').open('w')
        process = subprocess.Popen([ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
                                    'com.relay.app.calculator.PeerConversationTest', '-e', 'peerRole', role,
                                    'com.relay.app.test/androidx.test.runner.AndroidJUnitRunner'], stdout=output, stderr=subprocess.STDOUT)
        processes.append((role, process, output))
    initial = {role: wait(lambda role=role: state(role), f'{role} did not start', 120) for role in peers}
    keys = {role: initial[role]['pubkey'] for role in peers}
    assert keys['Alice'] != keys['Bob'], 'Must use two independently generated identities'
    (ROOT / '.local/mailbox-emulator-pubkeys.txt').write_text('\n'.join(keys.values()) + '\n')
    print(f'PASS: two installs with independent identities (Alice {keys["Alice"][:8]}…, Bob {keys["Bob"][:8]}…)', flush=True)
    founders = ['Alice'] if args.founders == 'alice' else list(peers)
    if args.deploy_allowlist:
        deploy_allowlist([keys[role] for role in founders])
        print(f'PASS: test pilot redeployed with {" and ".join(founders)} as founders', flush=True)
        # An app refused before its key was added waits out a back-off; reconnecting retries now.
        for role in peers:
            command(role, 'reconnect')

    for role in peers:
        assert initial[role]['route'] == 'MailboxTransport', f"{role} runs {initial[role]['route']}, not the delivery server"
    for role in founders:
        wait(lambda role=role: command(role, 'status')['transport'] == 'ONLINE', f'{role} could not reach the delivery server', 90)
    print(f'PASS: {" and ".join(founders)} online through the delivery server only (no Nostr transport exists)', flush=True)

    # A peer that was wiped has a new key; drop its stale contact so names stay unambiguous. With
    # --repair every contact of that name goes, so the two pair again.
    for role, peer in [('Alice', 'Bob'), ('Bob', 'Alice')]:
        initial[role] = command(role, 'forget_stale', peer=peer, keep='-' if args.repair else keys[peer])
    paired = all(any(c['name'] == peer and c['verified'] and c['hasKey'] for c in initial[role]['contacts'])
                 for role, peer in [('Alice', 'Bob'), ('Bob', 'Alice')])
    if paired:
        print('PASS: reusing the already verified Alice/Bob contacts', flush=True)
    else:
        shower = args.shower
        scanner = 'Alice' if shower == 'Bob' else 'Bob'
        command(shower, 'show_qr')
        time.sleep(3)  # the shown code is registered as an invite in the background
        screenshot = adb(shower, 'exec-out', 'screencap', '-p')
        (ROOT / '.local' / f'mailbox-{shower.lower()}-qr.png').write_bytes(screenshot)
        adb(scanner, 'shell', "run-as com.relay.app sh -c 'cat > files/peer-test/peer-qr.png'", input=screenshot)
        command(scanner, 'scan_image')
        command(scanner, 'confirm_add', peer=shower)
        wait(lambda: command(shower, 'status').get('incomingPair') == scanner, f"{shower} never received {scanner}'s pairing request", 100)
        command(shower, 'accept_incoming', peer=scanner)
        for role in peers:
            wait(lambda role=role: ((v := command(role, 'status')).get('outgoingPair') is None and v.get('incomingPair') is None
                                    and any(c['verified'] and c['hasKey'] for c in v['contacts'])), f'{role} was not mutually verified', 100)
        joined = [role for role in peers if role not in founders]
        how = f' ({", ".join(joined)} joined the server by this pairing: ' + ('vouched for by the scanner)' if scanner in founders else 'with the shower\'s invite)') if joined else ''
        print(f'PASS: QR pairing and mutual verification, all through the delivery server{how}', flush=True)
        for role in peers:
            wait(lambda role=role: command(role, 'status')['transport'] == 'ONLINE', f'{role} is not online after pairing', 60)

    if args.push:
        for role in peers:
            wait(lambda role=role: push_log_count(role, 'push token registration: HTTP 200') > registered[role],
                 f'{role}: Firebase token was not registered', 150)
        print('PASS: both Firebase device tokens registered with the delivery server', flush=True)

    if args.push_only:
        check_background_delivery(uuid.uuid4().hex[:8])
        raise SystemExit(0)

    token = uuid.uuid4().hex[:8]
    for sender, recipient, text in [('Alice', 'Bob', f'Mailbox hello {token} 📬'), ('Bob', 'Alice', f'Mailbox reply {token} ✅')]:
        open_chat(sender, recipient)
        started = time.monotonic()
        send_text(sender, text)
        wait(lambda r=recipient, t=text: has_message(command(r, 'status'), t, False), f'{recipient} did not receive {text!r}', 60)
        print(f'PASS: {sender} → {recipient} text through the delivery server ({time.monotonic() - started:.1f}s including UI automation)', flush=True)

    fixture = (ROOT / 'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png').read_bytes()
    adb('Alice', 'shell', "run-as com.relay.app sh -c 'cat > files/peer-test/fixture-photo.png'", input=fixture)
    caption = f'Mailbox photo {token}'
    open_chat('Alice', 'Bob')
    command('Alice', 'photo', text=caption)
    wait(lambda: any(m['body'] == caption and not m['sent'] and m['type'] == 'IMAGE' and m['mediaHash']
                     for c in command('Bob', 'status')['contacts'] for m in c['messages']), 'Bob did not get the photo', 150)
    print('PASS: Alice → Bob photo, encrypted and stored on the delivery server for Bob only', flush=True)

    adb('Bob', 'shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    time.sleep(5)
    offline = f'Sent while Bob was offline {token}'
    open_chat('Alice', 'Bob')
    send_text('Alice', offline)
    time.sleep(10)
    assert not has_message(command('Bob', 'status'), offline, False), 'Bob received it while in airplane mode?'
    started = time.monotonic()
    adb('Bob', 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    wait(lambda: has_message(command('Bob', 'status'), offline, False), 'Bob did not catch up after reconnecting', 120)
    print(f'PASS: message held by the server while Bob was offline, delivered on reconnect ({time.monotonic() - started:.1f}s after the network returned)', flush=True)

    group = f'Pilot {token}'
    command('Alice', 'create_group', name=group, peer='Bob')
    wait(lambda: any(g['name'] == group for g in command('Bob', 'status')['groups']), 'Bob never got the new group', 100)
    print('PASS: group created; its roster reached Bob through the delivery server', flush=True)
    for sender, recipient, text in [('Alice', 'Bob', f'Group hello {token}'), ('Bob', 'Alice', f'Group reply {token}')]:
        command(sender, 'group_send', group=group, text=text)
        wait(lambda r=recipient, t=text: group_message(command(r, 'status'), group, t), f'{recipient} did not get the group post {text!r}', 60)
        print(f'PASS: {sender} → group → {recipient} post', flush=True)
    caption = f'Group photo {token}'
    command('Alice', 'group_photo', group=group, text=caption)
    wait(lambda: group_message(command('Bob', 'status'), group, caption, image=True), 'Bob did not get the group photo', 150)
    print('PASS: group photo stored on the delivery server for the members only, downloaded and decrypted by Bob', flush=True)

    command('Alice', 'typing', peer='Bob')
    wait(lambda: 'Alice' in command('Bob', 'status')['typing'], 'Bob never saw Alice typing (direct)', 20)
    command('Alice', 'typing', group=group)
    wait(lambda: 'Alice' in command('Bob', 'status')['typing'], 'Bob never saw Alice typing (group)', 20)
    print('PASS: typing indicators passed live through the server, direct and in the group (never stored)', flush=True)

    final = {role: command(role, 'status') for role in peers}
    for role in peers:
        (ROOT / '.local' / f'mailbox-{role.lower()}-conversation.png').write_bytes(adb(role, 'exec-out', 'screencap', '-p'))
    (ROOT / '.local/mailbox-peer-result.json').write_text(json.dumps(final, indent=2, ensure_ascii=False))
    print('PASS: delivery-server conversation checked in both apps\' encrypted databases', flush=True)

    check_background_delivery(token)
finally:
    for role in peers:
        try:
            adb(role, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable', check=False)
        except Exception:
            pass
    for role, process, output in processes:
        if process.poll() is None:
            try:
                command(role, 'stop')
            except Exception:
                pass
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.terminate()
        output.close()
    if args.push:
        adb('Bob', 'shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', check=False)
