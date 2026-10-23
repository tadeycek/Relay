#!/usr/bin/env python3
"""Opt-in actual generated native audio through an existing Cloudflare SFU app.

Private stdin/stdout pipes carry SDP. Only numeric, non-identifying evidence is
exported. SFU terminates media encryption: this is NOT E2EE or microphone proof.
"""
import argparse
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

HERE=Path(__file__).resolve().parent
LIMIT=65536


@contextmanager
def wall_clock_bound(deadline):
    """Linux/main-thread watchdog, including blocking reads/writes and DNS/TLS.

    Preserve an enclosing watchdog's remaining time, never extend it. This probe
    owns SIGALRM while active; it must not be called from a worker thread.
    """
    started=time.monotonic()
    remaining=deadline-started
    if remaining<=0: raise TimeoutError('wall clock bound')
    previous=signal.getsignal(signal.SIGALRM)
    timer=signal.getitimer(signal.ITIMER_REAL)
    def expired(signum,frame): raise TimeoutError('wall clock bound')
    signal.signal(signal.SIGALRM,expired)
    signal.setitimer(signal.ITIMER_REAL,min(remaining,timer[0]) if timer[0] else remaining)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL,0)
        signal.signal(signal.SIGALRM,previous)
        if timer[0]:
            signal.setitimer(signal.ITIMER_REAL,max(.000001,timer[0]-(time.monotonic()-started)),timer[1])


def validate_media(rows, expected):
    if len(rows)!=expected or len({(r['participant'],r['publisher']) for r in rows})!=expected:
        raise ValueError('directed stream count')
    for r in rows:
        if not r['connected'] or r['packets']<10 or r['bytes']<100:
            raise ValueError('inbound RTP assertion')
        if r['samples']<4800 or r['square_sum']/max(1,r['samples'])<500**2:
            raise ValueError('PCM energy assertion')


def check_response(result):
    if result.get('errorCode') or result.get('errorDescription'):
        raise ValueError('provider request error')
    for track in result.get('tracks',[]):
        if track.get('errorCode') or track.get('errorDescription'):
            raise ValueError('provider track error')
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None


class Provider:
    def __init__(self):
        if os.environ.get('RELAY_SPIKE_LIVE')!='1': raise ValueError('live opt-in required')
        path=HERE.parents[2]/'.local/realtime.json'
        if path.stat().st_mode & 0o077: raise ValueError('credential permissions')
        data=json.loads(path.read_text())
        app=data['SFU_APP_ID']; secret=data['SFU_APP_SECRET']
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}',app) or not secret or '\n' in secret or '\r' in secret:
            raise ValueError('credential schema')
        self.base='https://rtc.live.cloudflare.com/v1/apps/'+app
        self.secret=secret
        self.opener=urllib.request.build_opener(NoRedirect())
        self.calls=0
        self.deadline=time.monotonic()+120
    def call(self,method,path,body=None):
        with wall_clock_bound(min(self.deadline,time.monotonic()+15)):
            return self._call(method,path,body)
    def _call(self,method,path,body=None):
        if time.monotonic()>=self.deadline: raise TimeoutError('provider total runtime bound')
        raw=None if body is None else json.dumps(body).encode()
        request=urllib.request.Request(self.base+path,data=raw,method=method,
            headers={'Authorization':'Bearer '+self.secret,'Content-Type':'application/json','User-Agent':'Relay-WP02-Probe/1.0'})
        try:
            with self.opener.open(request,timeout=15) as response:
                if response.status not in (200,201): raise ValueError('provider HTTP status')
                raw=response.read(LIMIT+1)
                if len(raw)>LIMIT: raise ValueError('provider response bound')
        except urllib.error.HTTPError as e:
            self.http_error_status=e.code
            # Do not read or export a potentially trickling sensitive error body.
            e.close()
            raise
        self.calls+=1
        result=json.loads(raw)
        if not isinstance(result,dict): raise ValueError('provider response schema')
        return result


class Native:
    def __init__(self,private):
        self.deadline=time.monotonic()+120
        self.log=open(private/'sfu-native.log','wb')
        cp=str(private/'sfu-classes')+os.pathsep+str(HERE/'build/install/relay-calls-spike/lib/*')
        self.p=subprocess.Popen(['/home/tadej/tools/jdk-21.0.12.1+1/bin/java','-cp',cp,'relay.spike.SfuProbe'],
             stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log)
        assert self.p.stdin is not None and self.p.stdout is not None
        self.sel=selectors.DefaultSelector(); self.sel.register(self.p.stdout,selectors.EVENT_READ)
    def command(self,op,i,*args):
        try:
            with wall_clock_bound(min(self.deadline,time.monotonic()+35)):
                return self._command(op,i,*args)
        except TimeoutError:
            self.terminate()
            raise
    def terminate(self):
        if self.p.poll() is None: self.p.kill()
        self.p.wait(timeout=2)
    def _command(self,op,i,*args):
        assert self.p.stdin is not None and self.p.stdout is not None
        if time.monotonic()>=self.deadline: raise TimeoutError('native total runtime bound')
        line='\t'.join([op,str(i),*args])+'\n'
        if len(line)>100000: raise ValueError('native request bound')
        encoded=line.encode()
        if len(encoded)>100000: raise ValueError('native request bound')
        self.p.stdin.write(encoded); self.p.stdin.flush()
        deadline=min(self.deadline,time.monotonic()+35)
        received=0
        while time.monotonic()<deadline:
            if not self.sel.select(max(0,deadline-time.monotonic())): break
            line=self.p.stdout.readline(LIMIT*2)
            received+=len(line)
            if received>LIMIT*2: raise ValueError('native response bound')
            if not line: raise ValueError('native exited')
            if line.startswith(b'SFU_REPLY '): return base64.b64decode(line.split(b' ',1)[1],validate=False).decode()
            self.log.write(line)
        raise TimeoutError('native bounded response')
    def remote(self,i,description):
        self.command('REMOTE',i,description['type'],base64.b64encode(description['sdp'].encode()).decode())
    def close(self):
        try:
            with wall_clock_bound(min(self.deadline,time.monotonic()+20)):
                self._close()
        except BaseException:
            self.terminate()
            raise
        finally:
            # The child is dead on failure, so buffered close cannot block.
            if self.p.stdin is not None:
                try: self.p.stdin.close()
                except OSError: pass
            if self.p.stdout is not None: self.p.stdout.close()
            self.log.close(); self.sel.close()
    def _close(self):
        assert self.p.stdin is not None
        if self.p.poll() is None:
            try: self.p.stdin.write(b'QUIT\n'); self.p.stdin.flush()
            except BrokenPipeError: pass
        assert self.p.stdout is not None
        out=self.p.stdout.read(LIMIT*2+1)
        if len(out)>LIMIT*2: raise ValueError('native response bound')
        self.p.wait(timeout=2)
        self.log.write(out)
        if self.p.returncode!=0 or b'SFU_DISPOSED' not in out: raise ValueError('native disposal failed')


class RecoveryJournal:
    """A private, unique run journal; never read or replace previous runs."""
    def __init__(self,private):
        fd,name=tempfile.mkstemp(prefix='sfu-sessions-',suffix='.json',dir=private)
        os.close(fd)
        self.path=Path(name)
        self.sessions={}
        self._save()
    def _save(self):
        fd,name=tempfile.mkstemp(prefix='.sfu-journal-',dir=self.path.parent)
        try:
            with os.fdopen(fd,'w') as stream:
                json.dump({'sessions':list(self.sessions.values())},stream)
                stream.flush(); os.fsync(stream.fileno())
            os.replace(name,self.path)
        finally:
            if os.path.exists(name): os.unlink(name)
    def record(self,sid,mids,disposition,cleanup=None):
        self.sessions[sid]={'session_id':sid,'mids':list(dict.fromkeys(mids)),
                            'disposition':disposition}
        if cleanup is not None: self.sessions[sid]['cleanup']=cleanup
        self._save()


def cleanup_session(provider,sid,known_mids,journal=None):
    mids=list(dict.fromkeys(known_mids))
    try:
        before=check_response(provider.call('GET',f'/sessions/{sid}'))
        mids=list(dict.fromkeys(mids+[t['mid'] for t in before.get('tracks',[]) if 'mid' in t]))
    except Exception:
        pass # Reconciliation must never prevent force-closing known allocations.
    item={'requested':len(mids),'readback_ok':False,'close_ok':False,'disposition':'unresolved'}
    try:
        if mids:
            closed=check_response(provider.call('PUT',f'/sessions/{sid}/tracks/close',
                {'tracks':[{'mid':m} for m in mids],'force':True}))
            if len(closed.get('tracks',[]))!=len(mids): raise ValueError('close result count')
        item['close_ok']=True
    except Exception:
        pass
    try:
        info=check_response(provider.call('GET',f'/sessions/{sid}'))
        records=info.get('tracks',[])
        states=[t.get('status','unknown') for t in records]
        by_mid={t.get('mid'):t.get('status') for t in records}
        item.update(returned_records=len(states),inactive_records=sum(s in ('closed','inactive') for s in states),
            status_values=sorted(set(s for s in states if s in ('active','inactive','closed','unknown'))))
        if any(by_mid.get(mid) not in ('closed','inactive') for mid in mids):
            raise ValueError('close readback active or missing')
        if any(s not in ('closed','inactive') for s in states): raise ValueError('close readback active')
        item['readback_ok']=True
        item['disposition']='inactive'
    except urllib.error.HTTPError as e:
        item['readback_http_status']=e.code
        if e.code==410:
            item.update(readback_ok=True,expired=True,disposition='expired')
        e.close()
    except Exception:
        pass
    if journal is not None: journal.record(sid,mids,item['disposition'],item)
    return item


def run(silent=-1,output=None):
    os.umask(0o077)
    start=time.monotonic()
    provider=Provider() # Opt-in and credentials checked before native or provider activity.
    private=HERE/'.private'; private.mkdir(mode=0o700,exist_ok=True)
    journal=RecoveryJournal(private)
    native=Native(private)
    sessions=[]; allocations={}; mapping={}; rows=[]; cleanup=[]; error=None
    stage='publish'
    http_error_status=None
    try:
        for i in range(3):
            native.command('NEW',i,'1' if i==silent else '0')
            offer=native.command('OFFER',i)
            mid=native.command('MID',i)
            created=check_response(provider.call('POST','/sessions/new'))
            sid=created.get('sessionId','')
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}',sid): raise ValueError('session schema')
            # The publication MID is already known even if POST response/GET fails.
            sessions.append(sid); allocations[sid]=[mid]
            # Private cleanup journal survives a later signalling failure.
            journal.record(sid,allocations[sid],'unresolved')
            result=provider.call('POST',f'/sessions/{sid}/tracks/new',{
                'sessionDescription':{'type':'offer','sdp':offer},
                'tracks':[{'location':'local','mid':mid,'trackName':f'tone-{i}'}]})
            allocations[sid].extend(t['mid'] for t in result.get('tracks',[]) if 'mid' in t and not t.get('errorCode'))
            journal.record(sid,allocations[sid],'unresolved')
            check_response(result)
            if len(result.get('tracks',[]))!=1: raise ValueError('publication count')
            native.remote(i,result['sessionDescription'])
            native.command('CONNECTED',i)
            check_response(provider.call('GET',f'/sessions/{sid}'))
        stage='subscribe'
        for i,sid in enumerate(sessions):
            publishers=[j for j in range(3) if j!=i]
            result=provider.call('POST',f'/sessions/{sid}/tracks/new',{'tracks':[
                {'location':'remote','sessionId':sessions[j],'trackName':f'tone-{j}'} for j in publishers]})
            allocations[sid].extend(t['mid'] for t in result.get('tracks',[]) if 'mid' in t and not t.get('errorCode'))
            journal.record(sid,allocations[sid],'unresolved')
            check_response(result)
            tracks=result.get('tracks',[])
            if len(tracks)!=2: raise ValueError('subscription count')
            for t in tracks:
                j=next((j for j in publishers if t.get('trackName')==f'tone-{j}'),None)
                if j is None: raise ValueError('subscription mapping')
                mapping[i,t['mid']]=j
            native.remote(i,result['sessionDescription'])
            answer=native.command('ANSWER',i)
            check_response(provider.call('PUT',f'/sessions/{sid}/renegotiate',{'sessionDescription':{'type':'answer','sdp':answer}}))
            native.command('CONNECTED',i)
            check_response(provider.call('GET',f'/sessions/{sid}'))
        stage='media'
        time.sleep(8)
        for i in range(3):
            for row in json.loads(native.command('METRICS',i)):
                row['publisher']=mapping[i,row.pop('mid')]; row['participant']=i
                row['rms']=round((row['square_sum']/max(1,row['samples']))**0.5,2)
                rows.append(row)
        validate_media(rows,6)
    except Exception as e:
        error=type(e).__name__
        if isinstance(e,urllib.error.HTTPError): http_error_status=e.code
        # Only our own fixed numeric assertion labels may be public.
        if isinstance(e,ValueError) and str(e) in ('PCM energy assertion','inbound RTP assertion','directed stream count'):
            error=str(e)
    finally:
        # Subscribers first, then sources; force close works without SDP on failure.
        provider.deadline=time.monotonic()+90
        for sid in reversed(sessions):
            cleanup.append(cleanup_session(provider,sid,allocations[sid],journal))
        try: native.close(); native_exit=True
        except Exception: native_exit=False
    report={'participants':len(sessions),'streams':rows,'stream_count':len(rows),'silent_publisher':silent,
        'error':error,'http_error_status':http_error_status,'stage':stage,'cleanup':cleanup,'native_normal_exit':native_exit,
        'api_calls':provider.calls,'elapsed_seconds':round(time.monotonic()-start,2),
        'synthetic_audio':True,'physical_microphone':False,'e2ee':False}
    report['passed']=error is None and native_exit and len(cleanup)==3 and all(c['close_ok'] and c['readback_ok'] for c in cleanup)
    if output: Path(output).write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,sort_keys=True))
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--silent-publisher',type=int,choices=range(3),default=-1)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    try: return 0 if run(args.silent_publisher,args.output)['passed'] else 1
    except Exception as e:
        print(json.dumps({'passed':False,'error':type(e).__name__,'details':'suppressed'})); return 2

if __name__=='__main__': raise SystemExit(main())
