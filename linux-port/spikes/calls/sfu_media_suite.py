#!/usr/bin/env python3
"""Reproduce positive media, three live silence mutations, and guard mutations."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
from live_sfu_media import validate_media

HERE=Path(__file__).resolve().parent
JDK=Path(os.environ.get('JAVA_HOME','/home/tadej/tools/jdk-21.0.12.1+1'))


def public_report(report):
    """Reconstruct the public numeric schema, never copy provider/native fields."""
    def integer(value,minimum=0):
        if type(value) is not int or value<minimum: raise ValueError('public integer schema')
        return value
    def number(value):
        if type(value) not in (int,float) or not math.isfinite(value) or value<0:
            raise ValueError('public numeric schema')
        return value
    def boolean(value):
        if type(value) is not bool: raise ValueError('public boolean schema')
        return value
    def choice(value,values):
        if value not in values: raise ValueError('public enum schema')
        return value
    public:dict={key:integer(report[key]) for key in ('participants','stream_count','api_calls')}
    public['silent_publisher']=choice(integer(report['silent_publisher'],-1),(-1,0,1,2))
    public['elapsed_seconds']=number(report['elapsed_seconds'])
    public['stage']=choice(report['stage'],('publish','subscribe','media'))
    public['error']=choice(report['error'],(None,'PCM energy assertion'))
    public['http_error_status']=None if report['http_error_status'] is None else integer(report['http_error_status'])
    for key in ('native_normal_exit','passed','synthetic_audio','physical_microphone','e2ee'):
        public[key]=boolean(report[key])
    streams=[]
    for row in report['streams']:
        item:dict={key:integer(row[key]) for key in ('participant','publisher','packets','bytes','samples','square_sum')}
        item['connected']=boolean(row['connected'])
        item['rms']=number(row['rms'])
        streams.append(item)
    expected={(i,j) for i in range(3) for j in range(3) if i!=j}
    if public['participants']!=3 or public['stream_count']!=6 or len(streams)!=6 or {
            (r['participant'],r['publisher']) for r in streams}!=expected:
        raise ValueError('exact directed pairs required')
    public['streams']=streams
    cleanup=[]
    for row in report['cleanup']:
        item={key:integer(row[key]) for key in ('requested','returned_records','inactive_records')}
        for key in ('close_ok','readback_ok'): item[key]=boolean(row[key])
        item['status_values']=[choice(s,('active','inactive','closed','unknown')) for s in row['status_values']]
        if 'disposition' in row: item['disposition']=choice(row['disposition'],('inactive','expired','unresolved'))
        cleanup.append(item)
    public['cleanup']=cleanup
    return public


def execute(args,expected=0):
    p=subprocess.run(args,cwd=HERE,capture_output=True,timeout=240)
    if p.returncode!=expected:
        (HERE/'.private/sfu-suite-failure.log').write_bytes(p.stdout+p.stderr)
        raise ValueError('suite subprocess failed; private logs retained')
    return p


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collect-existing',action='store_true',help='Validate and export earlier actual private run reports; no live requests')
    args=parser.parse_args()
    if not args.collect_existing and os.environ.get('RELAY_SPIKE_LIVE')!='1': raise ValueError('live opt-in required')
    os.umask(0o077)
    private=HERE/'.private'; private.mkdir(mode=0o700,exist_ok=True)
    evidence=HERE/'evidence'; evidence.mkdir(exist_ok=True)
    classes=private/'sfu-classes'; classes.mkdir(exist_ok=True)
    execute([str(JDK/'bin/javac'),'-cp',str(HERE/'build/install/relay-calls-spike/lib/*'),
        '-d',str(classes),'src/main/java/relay/spike/CallsProbe.java','src/main/java/relay/spike/SfuProbe.java'])
    unit=execute([sys.executable,'-m','unittest','test_sfu_media','test_sfu_python_blockers','test_sfu_native_cleanup','-v'])
    (evidence/'sfu-media-unit.txt').write_bytes(unit.stdout+unit.stderr)
    mutations=json.loads(execute([sys.executable,'check_sfu_python_blocker_mutations.py']).stdout)
    assert mutations['killed']==mutations['total']
    (evidence/'sfu-media-guard-mutations.json').write_text(json.dumps(mutations,indent=2)+'\n')
    reports=[]
    for silent in [-1,0,1,2]:
        name='sfu-live-positive' if silent<0 else f'sfu-live-silent-{silent}'
        destination=private/(name+'.json')
        if args.collect_existing:
            destination=private/('sfu-positive.json' if silent<0 else f'sfu-silent-{silent}.json')
        else:
            command=[sys.executable,'live_sfu_media.py','--output',str(destination)]
            if silent>=0: command+=['--silent-publisher',str(silent)]
            execute(command,0 if silent<0 else 1)
        report=public_report(json.loads(destination.read_text()))
        assert report['participants']==3 and report['stream_count']==6
        assert report['native_normal_exit'] and len(report['cleanup'])==3
        assert all(c['close_ok'] and c['readback_ok'] and c['inactive_records']==3 for c in report['cleanup'])
        if silent<0:
            assert report['passed']; validate_media(report['streams'],6)
        else:
            assert not report['passed'] and report['error']=='PCM energy assertion'
            affected=[r for r in report['streams'] if r['publisher']==silent]
            unaffected=[r for r in report['streams'] if r['publisher']!=silent]
            assert len(affected)==2 and len(unaffected)==4
            validate_media(unaffected,4)
            for row in affected:
                assert row['connected'] and row['packets']>=10 and row['bytes']>=100
                try: validate_media([row],1)
                except ValueError as e: assert str(e)=='PCM energy assertion'
                else: raise AssertionError('silent audio falsely accepted')
        # public_report has reconstructed every exported field, including nested rows.
        (evidence/(name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
        reports.append({'silent_publisher':silent,'elapsed_seconds':report['elapsed_seconds'],
            'streams':report['stream_count'],'expected_result_verified':True,
            'inactive_tracks':sum(c['inactive_records'] for c in report['cleanup'])})
    sources=['src/main/java/relay/spike/SfuProbe.java','live_sfu_media.py','test_sfu_media.py','test_sfu_python_blockers.py','test_sfu_native_cleanup.py',
        'check_sfu_media_mutations.py','check_sfu_python_blocker_mutations.py','sfu_media_suite.py']
    summary={'live_executed_this_invocation':not args.collect_existing,
        'runs':reports,'guard_mutations':mutations,'source_sha256_at_collection':{
        name:hashlib.sha256((HERE/name).read_bytes()).hexdigest() for name in sources}}
    (evidence/'sfu-media-suite.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,sort_keys=True))
    return 0

if __name__=='__main__':
    try: raise SystemExit(main())
    except Exception as e:
        print(json.dumps({'suite_failed':True,'type':type(e).__name__,'details':'suppressed'}))
        raise SystemExit(1)
