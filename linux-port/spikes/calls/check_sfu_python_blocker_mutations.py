#!/usr/bin/env python3
"""Offline mutation checks; all copies and output remain in disposable scratch."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent
SCRATCH=Path(os.environ.get('TMPDIR','/home/tadej/.hermes/cache/scratch'))
FILES=('live_sfu_media.py','sfu_media_suite.py','test_sfu_media.py','test_sfu_python_blockers.py',
       'check_sfu_media_mutations.py')


def main():
    sources={name:(HERE/name).read_text() for name in FILES}
    mutations=[
        ('http_wall_clock','live_sfu_media.py',
         'with wall_clock_bound(min(self.deadline,time.monotonic()+15)):',
         "with __import__('contextlib').nullcontext():",'Bounds.test_http_trickle_total_deadline'),
        ('native_partial_line','live_sfu_media.py',
         'with wall_clock_bound(min(self.deadline,time.monotonic()+35)):',
         "with __import__('contextlib').nullcontext():",'Bounds.test_partial_child_line_is_bounded_and_dead'),
        ('native_blocked_stdin','live_sfu_media.py',
         'with wall_clock_bound(min(self.deadline,time.monotonic()+35)):',
         "with __import__('contextlib').nullcontext():",'Bounds.test_blocked_child_stdin_is_bounded_and_dead'),
        ('native_timeout_kills','live_sfu_media.py',
         'except TimeoutError:\n            self.terminate()',
         'except TimeoutError:\n            pass','Bounds.test_partial_child_line_is_bounded_and_dead'),
        ('native_close_bound','live_sfu_media.py',
         'with wall_clock_bound(min(self.deadline,time.monotonic()+20)):',
         "with __import__('contextlib').nullcontext():",'Bounds.test_native_close_blocked_stdin_is_bounded_and_dead'),
        ('nested_timer_restore','live_sfu_media.py','if timer[0]:\n','if False:\n',
         'Bounds.test_nested_watchdog_normal_inner_restores_remaining_outer_timer'),
        ('journal_unique','live_sfu_media.py',
         "fd,name=tempfile.mkstemp(prefix='sfu-sessions-',suffix='.json',dir=private)",
         "name=str(private/'sfu-sessions.json'); fd=os.open(name,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)",
         'Recovery.test_unique_journals_preserve_prior_unresolved_allocations'),
        ('cleanup_force_close','live_sfu_media.py','if mids:\n','if False:\n',
         'Recovery.test_known_mid_closed_despite_reconcile_failure'),
        ('cleanup_exact_readback','live_sfu_media.py',
         "if any(by_mid.get(mid) not in ('closed','inactive') for mid in mids):",'if False:',
         'Recovery.test_cleanup_requires_exact_inactive_readback'),
        ('public_allowlist','sfu_media_suite.py','    public:dict={key:integer(report[key])',
         '    return report\n    public:dict={key:integer(report[key])',
         'PublicEvidence.test_export_reconstructs_nested_allowlist'),
        ('exact_directed_pairs','sfu_media_suite.py',
         'expected={(i,j) for i in range(3) for j in range(3) if i!=j}',
         "expected={(r['participant'],r['publisher']) for r in streams}",
         'PublicEvidence.test_suite_rejects_self_out_of_range_duplicate_and_noninteger_pairs'),
        ('journal_known_offer_mid','live_sfu_media.py',
         'sessions.append(sid); allocations[sid]=[mid]',
         'sessions.append(sid); allocations[sid]=[]',
         'Recovery.test_run_journals_known_offer_mid_when_publication_and_reconcile_fail'),
    ]
    with tempfile.TemporaryDirectory(prefix='sfu-python-mutations-',dir=SCRATCH) as directory:
        path=Path(directory)
        for name,source in sources.items(): (path/name).write_text(source)
        original=subprocess.run([sys.executable,'check_sfu_media_mutations.py'],cwd=path,
            capture_output=True,timeout=180)
        if original.returncode: raise ValueError('original guard mutations failed')
        results=json.loads(original.stdout)['mutations']
        for name,target,old,new,test in mutations:
            if sources[target].count(old)!=1: raise ValueError('mutation target count: '+name)
            (path/target).write_text(sources[target].replace(old,new))
            # Avoid stale same-size, same-second bytecode after restoring a mutant.
            env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')
            p=subprocess.run([sys.executable,'-B','-m','unittest','test_sfu_python_blockers.'+test],
                cwd=path,capture_output=True,timeout=10,env=env)
            killed=p.returncode!=0 and b'FAIL:' in p.stderr and b'AssertionError' in p.stderr and b'ERROR:' not in p.stderr
            results.append({'mutation':name,'killed':killed})
            (path/target).write_text(sources[target])
            for cached in (path/'__pycache__').glob('*.pyc'): cached.unlink()
    report={'mutations':results,'killed':sum(r['killed'] for r in results),'total':len(results)}
    print(json.dumps(report,sort_keys=True))
    return 0 if report['killed']==report['total'] else 1


if __name__=='__main__': raise SystemExit(main())
