"""Offline blocker regressions: only loopback HTTP and disposable children."""
import base64
import contextlib
import http.server
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.request

import live_sfu_media as live
import sfu_media_suite as suite

SCRATCH=Path(os.environ.get('TMPDIR','/home/tadej/.hermes/cache/scratch'))


class Bounds(unittest.TestCase):
    def test_http_trickle_total_deadline(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                self.send_response(200); self.send_header('Content-Length','100'); self.end_headers()
                try:
                    for _ in range(100):
                        self.wfile.write(b' '); self.wfile.flush(); time.sleep(.02)
                except (BrokenPipeError,ConnectionResetError): pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        server.daemon_threads=True
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        provider=object.__new__(live.Provider)
        provider.base=f'http://127.0.0.1:{server.server_port}'
        provider.secret='offline-fixture'; provider.calls=0
        provider.opener=urllib.request.build_opener(live.NoRedirect())
        provider.deadline=time.monotonic()+.2
        start=time.monotonic()
        try:
            with self.assertRaises(Exception) as caught: provider.call('GET','/')
            self.assertIsInstance(caught.exception,TimeoutError)
            self.assertLess(time.monotonic()-start,.8)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def native_timeout(self,script,argument=''):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            real_popen=subprocess.Popen
            def child(*args,**kwargs):
                return real_popen([sys.executable,'-u','-c',script],**kwargs)
            with patch.object(live.subprocess,'Popen',side_effect=child):
                native=live.Native(Path(directory))
            native.deadline=time.monotonic()+.2
            start=time.monotonic()
            try:
                with self.assertRaises(Exception) as caught: native.command('TEST',0,argument)
                self.assertIsInstance(caught.exception,TimeoutError)
                self.assertLess(time.monotonic()-start,.8)
                self.assertIsNotNone(native.p.poll(), 'timeout must reap actual child')
            finally:
                if native.p.poll() is None: native.p.kill(); native.p.wait()
                for stream in (native.p.stdin,native.p.stdout,native.log):
                    with contextlib.suppress(BrokenPipeError): stream.close()
                native.sel.close()

    def test_partial_child_line_is_bounded_and_dead(self):
        self.native_timeout("import sys,time; sys.stdout.write('SFU_REPLY partial'); sys.stdout.flush(); time.sleep(2)")

    def test_blocked_child_stdin_is_bounded_and_dead(self):
        self.native_timeout('import time; time.sleep(2)','x'*99000)

    def test_nested_watchdog_restores_outer_deadline_and_handler(self):
        self.assertTrue(hasattr(live,'wall_clock_bound'))
        original=signal.getsignal(signal.SIGALRM)
        start=time.monotonic()
        with self.assertRaises(TimeoutError):
            with live.wall_clock_bound(start+.2):
                with live.wall_clock_bound(start+2): time.sleep(1)
        self.assertLess(time.monotonic()-start,.8)
        self.assertEqual(signal.getsignal(signal.SIGALRM),original)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL),(0.0,0.0))

    def test_native_close_blocked_stdin_is_bounded_and_dead(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            real_popen=subprocess.Popen
            def child(*args,**kwargs):
                return real_popen([sys.executable,'-u','-c','import time; time.sleep(2)'],**kwargs)
            with patch.object(live.subprocess,'Popen',side_effect=child): native=live.Native(Path(directory))
            # Fill the actual pipe without blocking, then leave buffered pending bytes.
            os.set_blocking(native.p.stdin.fileno(),False)
            try:
                while True: os.write(native.p.stdin.fileno(),b'x'*4096)
            except BlockingIOError: pass
            os.set_blocking(native.p.stdin.fileno(),True)
            native.deadline=time.monotonic()+.2
            start=time.monotonic()
            try:
                with self.assertRaises(Exception) as caught: native.close()
                self.assertIsInstance(caught.exception,TimeoutError)
                self.assertLess(time.monotonic()-start,.8)
                self.assertIsNotNone(native.p.poll())
            finally:
                if native.p.poll() is None: native.p.kill(); native.p.wait()

    def test_nested_watchdog_normal_inner_restores_remaining_outer_timer(self):
        start=time.monotonic()
        with self.assertRaises(TimeoutError):
            with live.wall_clock_bound(start+.2):
                with live.wall_clock_bound(start+1): time.sleep(.02)
                time.sleep(.4)
        self.assertLess(time.monotonic()-start,.8)

    def test_native_protocol_normal_close_still_works(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            real_popen=subprocess.Popen
            script="import sys,base64\nfor line in sys.stdin:\n if line.strip()=='QUIT':\n  print('SFU_DISPOSED',flush=True); break\n print('SFU_REPLY '+base64.b64encode(b'ok').decode(),flush=True)"
            def child(*args,**kwargs): return real_popen([sys.executable,'-u','-c',script],**kwargs)
            with patch.object(live.subprocess,'Popen',side_effect=child): native=live.Native(Path(directory))
            try:
                self.assertEqual(native.command('TEST',0),'ok')
                native.close()
                self.assertEqual(native.p.returncode,0)
            finally:
                if native.p.poll() is None: native.p.kill(); native.p.wait()


class PublicEvidence(unittest.TestCase):
    def report(self):
        streams=[dict(participant=i,publisher=j,packets=30,bytes=2000,samples=48000,
            square_sum=48000000000,connected=True,rms=1000.0,secret='DO-NOT-EXPORT')
            for i in range(3) for j in range(3) if i!=j]
        return dict(participants=3,streams=streams,stream_count=6,silent_publisher=-1,
            error=None,http_error_status=None,stage='media',cleanup=[dict(requested=3,
                returned_records=3,inactive_records=3,readback_ok=True,close_ok=True,
                status_values=['inactive'],secret='DO-NOT-EXPORT') for _ in range(3)],
            native_normal_exit=True,api_calls=24,elapsed_seconds=15.2,synthetic_audio=True,
            physical_microphone=False,e2ee=False,passed=True,secret='DO-NOT-EXPORT')

    def test_export_reconstructs_nested_allowlist(self):
        self.assertTrue(hasattr(suite,'public_report'))
        report=self.report()
        public=suite.public_report(report)
        self.assertNotIn('DO-NOT-EXPORT',json.dumps(public))
        self.assertEqual(public['stream_count'],6)
        self.assertEqual(public['streams'][0]['packets'],30)
        report['streams'][0]['packets']='DO-NOT-EXPORT'
        with self.assertRaises(ValueError): suite.public_report(report)

    def test_suite_rejects_self_out_of_range_duplicate_and_noninteger_pairs(self):
        self.assertTrue(hasattr(suite,'public_report'))
        for pair in ((0,0),(0,3),(True,1),(0,2)):
            with self.subTest(pair=pair):
                report=self.report()
                report['streams'][0].update(participant=pair[0],publisher=pair[1])
                with self.assertRaises(ValueError): suite.public_report(report)

    def test_export_rejects_nonfinite_or_untyped_values(self):
        self.assertTrue(hasattr(suite,'public_report'))
        for key,value in (('elapsed_seconds',float('nan')),('participants',True),
                          ('stage','DO-NOT-EXPORT'),('error','DO-NOT-EXPORT')):
            report=self.report(); report[key]=value
            with self.subTest(key=key):
                with self.assertRaises(ValueError): suite.public_report(report)


class Recovery(unittest.TestCase):
    def test_run_journals_known_offer_mid_when_publication_and_reconcile_fail(self):
        class Provider:
            def __init__(self): self.calls=0; self.history=[]
            def call(self,method,path,body=None):
                self.calls+=1; self.history.append((method,path,body))
                if path=='/sessions/new': return {'sessionId':'offline-fixture'}
                if path.endswith('/tracks/new'): raise TimeoutError()
                if path.endswith('/tracks/close'): return {'tracks':[{'mid':'0'}]}
                if len(self.history)==3: raise TimeoutError()
                return {'tracks':[{'mid':'0','status':'inactive'}]}
        class Native:
            def __init__(self,private): self.closed=False
            def command(self,op,*args): return '0' if op=='MID' else 'offline-offer'
            def close(self): self.closed=True
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            for _ in range(2):
                provider=Provider()
                with patch.object(live,'HERE',Path(directory)), patch.object(live,'Provider',return_value=provider), \
                        patch.object(live,'Native',Native), contextlib.redirect_stdout(io.StringIO()):
                    # run sets umask; do not leak that state to the unittest caller.
                    mask=os.umask(0o077)
                    try: report=live.run()
                    finally: os.umask(mask)
                closed=[entry for entry in provider.history if entry[0]=='PUT']
                self.assertEqual(len(closed),1)
                self.assertEqual(closed[0][2],{'tracks':[{'mid':'0'}],'force':True})
                self.assertTrue(report['cleanup'][0]['readback_ok'])
                self.assertTrue(report['native_normal_exit'])
            journals=list((Path(directory)/'.private').glob('sfu-sessions-*.json'))
            self.assertEqual(len(journals),2)
            for path in journals:
                record=json.loads(path.read_text())['sessions'][0]
                self.assertEqual(record['mids'],['0'])
                self.assertEqual(record['disposition'],'inactive')

    def test_unique_journals_preserve_prior_unresolved_allocations(self):
        self.assertTrue(hasattr(live,'RecoveryJournal'))
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            private=Path(directory)
            old=private/'sfu-sessions.json'; old.write_text('["older-unresolved"]')
            first=live.RecoveryJournal(private)
            first.record('fixture-session',['0'], 'unresolved')
            snapshot=first.path.read_bytes()
            second=live.RecoveryJournal(private)
            second.record('another-fixture',['1'],'inactive')
            self.assertNotEqual(first.path,second.path)
            self.assertEqual(first.path.read_bytes(),snapshot)
            self.assertEqual(old.read_text(),'["older-unresolved"]')
            self.assertEqual(first.path.stat().st_mode & 0o777,0o600)
            for disposition in ('inactive','expired','unresolved'):
                second.record('another-fixture',['1'],disposition)
                record=json.loads(second.path.read_text())['sessions'][0]
                self.assertEqual(record['disposition'],disposition)
                self.assertEqual(record['mids'],['1'])

    def cleanup_provider(self,readback):
        class Provider:
            def __init__(self): self.calls=[]
            def call(self,method,path,body=None):
                self.calls.append((method,path,body))
                if len(self.calls)==1: raise TimeoutError('offline reconcile failure')
                if method=='PUT': return {'tracks':[{'mid':'0'}]}
                if isinstance(readback,Exception): raise readback
                return readback
        return Provider()

    def test_known_mid_closed_despite_reconcile_failure(self):
        self.assertTrue(hasattr(live,'cleanup_session'))
        provider=self.cleanup_provider({'tracks':[{'mid':'0','status':'inactive'}]})
        result=live.cleanup_session(provider,'fixture',['0'])
        self.assertEqual(provider.calls[1][0],'PUT')
        self.assertEqual(provider.calls[1][2],{'tracks':[{'mid':'0'}],'force':True})
        self.assertTrue(result['close_ok'] and result['readback_ok'])
        self.assertEqual(result['disposition'],'inactive')

    def test_cleanup_requires_exact_inactive_readback(self):
        self.assertTrue(hasattr(live,'cleanup_session'))
        for readback in ({'tracks':[]},{'tracks':[{'mid':'0','status':'active'}]},
                         {'tracks':[{'mid':'other','status':'inactive'}]},TimeoutError()):
            with self.subTest(readback=readback):
                provider=self.cleanup_provider(readback)
                result=live.cleanup_session(provider,'fixture',['0'])
                self.assertEqual(provider.calls[1][0],'PUT')
                self.assertFalse(result['readback_ok'])
                self.assertEqual(result['disposition'],'unresolved')

    def test_expired_disposition_requires_410(self):
        self.assertTrue(hasattr(live,'cleanup_session'))
        import urllib.error
        for status in (404,410):
            provider=self.cleanup_provider(urllib.error.HTTPError('offline',status,'fixture',{},None))
            result=live.cleanup_session(provider,'fixture',['0'])
            self.assertEqual(result['disposition'],'expired' if status==410 else 'unresolved')


if __name__=='__main__': unittest.main()
