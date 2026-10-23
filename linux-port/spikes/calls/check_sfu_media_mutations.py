#!/usr/bin/env python3
"""Kill focused guard mutations using real unit assertions, in ignored copies."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent

def main():
    source=(HERE/'live_sfu_media.py').read_text()
    mutations={
        'energy':("if r['samples']<4800 or r['square_sum']/max(1,r['samples'])<500**2:", 'if False:'),
        'rtp':("if not r['connected'] or r['packets']<10 or r['bytes']<100:", 'if False:'),
        'count':("if len(rows)!=expected or len({(r['participant'],r['publisher']) for r in rows})!=expected:", 'if False:'),
        'track_error':("if track.get('errorCode') or track.get('errorDescription'):", 'if False:'),
        'opt_in':("if os.environ.get('RELAY_SPIKE_LIVE')!='1': raise ValueError('live opt-in required')", "if os.environ.get('RELAY_SPIKE_LIVE')!='1': return"),
        'redirect':('def redirect_request(self,*args,**kwargs): return None', 'def redirect_request(self,*args,**kwargs): return object()'),
        'response_bound':("if len(raw)>LIMIT: raise ValueError('provider response bound')", 'if False: pass'),
    }
    results=[]
    (HERE/'.private').mkdir(mode=0o700,exist_ok=True)
    for name,(old,new) in mutations.items():
        if source.count(old)!=1: raise ValueError('mutation target count')
        with tempfile.TemporaryDirectory(prefix='sfu-mutation-',dir=HERE/'.private') as directory:
            path=Path(directory)
            (path/'live_sfu_media.py').write_text(source.replace(old,new))
            (path/'test_sfu_media.py').write_text((HERE/'test_sfu_media.py').read_text())
            p=subprocess.run([sys.executable,'-m','unittest','test_sfu_media'],cwd=path,capture_output=True,timeout=20)
            killed=p.returncode!=0 and b'AssertionError' in p.stderr and b'FAIL:' in p.stderr and b'ERROR:' not in p.stderr
            results.append({'mutation':name,'killed':killed})
    print(json.dumps({'mutations':results,'killed':sum(r['killed'] for r in results),'total':len(results)}))
    return 0 if all(r['killed'] for r in results) else 1

if __name__=='__main__': raise SystemExit(main())
