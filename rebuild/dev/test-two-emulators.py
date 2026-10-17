#!/usr/bin/env python3
"""Opt-in live test of two fresh test installs on dedicated Android emulators.

Build/install the app and test APK on both first. This sets fixture names Alice/Bob and
uses Calculator code 4826. It sends fixture messages/photos to real public relays and
media storage; never run it against personal app data. Artifacts stay under .local/.
"""
from pathlib import Path
import json
import subprocess
import time
import uuid
import argparse
import os
import shutil

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--alice',default='emulator-5554')
parser.add_argument('--bob',default='emulator-5556')
parser.add_argument('--resume',action='store_true',help='Reuse already verified Alice/Bob fixture contacts after a media-only code fix.')
args=parser.parse_args()
ADB=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))/'platform-tools/adb')
PKG='com.relay.app'
ROOT=Path(__file__).resolve().parents[2]
(ROOT/'.local').mkdir(exist_ok=True)
peers={'Alice':args.alice,'Bob':args.bob}
assert len(set(peers.values()))==2,'Use two separate emulators'
assert all(serial.startswith('emulator-') for serial in peers.values()),'This harness only targets emulators'
counter=0
processes=[]

def adb(role,*args,input=None,check=True):
    r=subprocess.run([ADB,'-s',peers[role],*args],input=input,capture_output=True,timeout=35)
    if check and r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')+r.stdout.decode(errors='replace'))
    return r.stdout

def state(role):
    try:return json.loads(adb(role,'exec-out','run-as',PKG,'cat','files/peer-test/result.json',check=False))
    except (ValueError,TypeError):return None

def wait(predicate,reason,seconds=100):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        result=predicate()
        if result:return result
        time.sleep(.5)
    raise AssertionError(reason)

def command(role,action,**values):
    global counter
    counter+=1
    identifier=f'{counter}-{action}'
    data=json.dumps({'id':identifier,'action':action,**values},ensure_ascii=False).encode()
    adb(role,'shell',"run-as com.relay.app sh -c 'cat > files/peer-test/command.tmp && mv files/peer-test/command.tmp files/peer-test/command.json'",input=data)
    result=wait(lambda:(s if (s:=state(role)) and s.get('id')==identifier else None),f'{role}: no response to {action}',55)
    assert result.get('ok'),result
    return result

def has_message(result,body,sent):
    return any(m['body']==body and m['sent']==sent for c in result['contacts'] for m in c['messages'])

try:
    for role in peers:
        assert adb(role,'shell','getprop','sys.boot_completed').strip()==b'1',f'{role} emulator is not booted'
    for role,serial in peers.items():
        adb(role,'shell','run-as',PKG,'rm','-f','files/peer-test/result.json','files/peer-test/command.json',check=False)
        output=(ROOT/'.local'/f'peer-{role.lower()}-instrument.log').open('w')
        process=subprocess.Popen([ADB,'-s',serial,'shell','am','instrument','-w','-e','class',
            'com.relay.app.calculator.PeerConversationTest','-e','peerRole',role,
            'com.relay.app.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=subprocess.STDOUT)
        processes.append((role,process,output))
    initial={role:wait(lambda role=role:state(role),f'{role} did not start',100) for role in peers}
    assert initial['Alice']['pubkey']!=initial['Bob']['pubkey'],'Must use two independently generated identities'
    with (ROOT/'.local/fresh-install-test-ids.txt').open('a') as output:
        for role,value in initial.items():output.write(value['installation']+'\n')
    print('PASS: two separate Android installs and independent cryptographic identities',flush=True)
    for role in peers:
        wait(lambda role=role:command(role,'status')['transport']=='ONLINE',f'{role} could not reach the default public relays',90)
    print('PASS: both devices connected to the configured public Nostr relays',flush=True)
    if args.resume:
        for role,peer in [('Alice','Bob'),('Bob','Alice')]:
            assert any(c['name']==peer and c['verified'] and c['hasKey'] for c in initial[role]['contacts']),f'{role} has no verified fixture contact'
        print('PASS: reusing the previously verified QR-paired fixture contacts',flush=True)
    else:
        command('Bob','show_qr')
        screenshot=adb('Bob','exec-out','screencap','-p')
        (ROOT/'.local/peer-bob-qr.png').write_bytes(screenshot)
        adb('Alice','shell',"run-as com.relay.app sh -c 'cat > files/peer-test/peer-qr.png'",input=screenshot)
        command('Alice','scan_image')
        print('PASS: production QR decoder read Bob\'s actual displayed 30-minute invitation; confirmation shown',flush=True)
        command('Alice','confirm_add',peer='Bob')
        wait(lambda:command('Bob','status').get('incomingPair')=='Alice','Bob never received Alice\'s pairing request',100)
        command('Bob','accept_incoming',peer='Alice')
        for role in peers:
            wait(lambda role=role:((v:=command(role,'status')).get('outgoingPair') is None and v.get('incomingPair') is None and any(c['verified'] and c['hasKey'] for c in v['contacts'])),f'{role} was not mutually verified',100)
        print('PASS: scan, explicit approval on both devices, and mutual verified contact creation over the internet',flush=True)
    token=uuid.uuid4().hex[:8]
    first=f'Hi Bob! Privacy test {token} 🌱'
    second=f'Hi Alice! Reply received {token} ✅'
    command('Alice','chat',peer='Bob')
    started=time.monotonic()
    command('Alice','send',text=first)
    wait(lambda:has_message(command('Bob','status'),first,False),'Bob did not receive/decrypt Alice\'s message',100)
    print(f'PASS: Alice → Bob text/emoji delivery ({time.monotonic()-started:.1f}s including UI automation)',flush=True)
    command('Bob','chat',peer='Alice')
    started=time.monotonic()
    command('Bob','send',text=second)
    wait(lambda:has_message(command('Alice','status'),second,False),'Alice did not receive/decrypt Bob\'s reply',100)
    print(f'PASS: Bob → Alice text/emoji reply ({time.monotonic()-started:.1f}s including UI automation)',flush=True)
    for role,recipient in [('Alice','Bob'),('Bob','Alice')]:
        fixture=(ROOT/'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png').read_bytes()
        adb(role,'shell',"run-as com.relay.app sh -c 'cat > files/peer-test/fixture-photo.png'",input=fixture)
        caption=f'Photo from {role} {token}'
        command(role,'photo',text=caption)
        wait(lambda recipient=recipient,caption=caption:any(m['body']==caption and not m['sent'] and m['type']=='IMAGE' and m['mediaHash'] for c in command(recipient,'status')['contacts'] for m in c['messages']),f'{recipient} did not download/decrypt the photo',150)
        sent=command(role,'status')
        received=command(recipient,'status')
        sender_hash=next(m['mediaHash'] for c in sent['contacts'] for m in c['messages'] if m['body']==caption and m['sent'])
        receiver_hash=next(m['mediaHash'] for c in received['contacts'] for m in c['messages'] if m['body']==caption and not m['sent'])
        assert sender_hash and sender_hash==receiver_hash,'Decrypted photo bytes must match the sent compressed photo'
        print(f'PASS: {role} → {recipient} gallery selection, session return, encrypted upload/download and matching photo hash',flush=True)
    for role,recipient in [('Alice','Bob'),('Bob','Alice')]:
        source=command(role,'set_profile')['selfPhotoHash']
        assert source,'Profile picker must produce a shared photo'
        wait(lambda recipient=recipient,role=role,source=source:any(c['name']==role and c.get('photoHash')==source for c in command(recipient,'status')['contacts']),f'{recipient} did not receive the encrypted profile photo',100)
        print(f'PASS: {role} → {recipient} own profile photo selected in the gallery, encrypted transfer and identical received photo',flush=True)
    command('Alice','remove_profile')
    wait(lambda:any(c['name']=='Alice' and not c.get('photoHash') for c in command('Bob','status')['contacts']),'Profile removal did not reach Bob',100)
    print('PASS: profile-photo removal propagates to the approved contact',flush=True)
    final={role:command(role,'status') for role in peers}
    for role in peers:
        (ROOT/'.local'/f'peer-{role.lower()}-conversation.png').write_bytes(adb(role,'exec-out','screencap','-p'))
    (ROOT/'.local/peer-conversation-result.json').write_text(json.dumps(final,indent=2,ensure_ascii=False))
    print('PASS: contact verification and both directions of the conversation checked in each app\'s encrypted database and UI',flush=True)
finally:
    for role,process,output in processes:
        if process.poll() is None:
            try:command(role,'stop')
            except Exception:pass
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.terminate()
        output.close()
