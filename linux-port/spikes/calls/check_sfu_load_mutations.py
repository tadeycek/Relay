#!/usr/bin/env python3
"""Prove each five-participant load guard kills a deliberately weakened copy."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SCRATCH = Path(os.environ.get('TMPDIR', '/home/tadej/.hermes/cache/scratch'))
FILES = ('sfu_load_suite.py', 'live_sfu_media.py', 'test_sfu_load.py')

MUTATIONS = [
    ('stream_count', 'sfu_load_suite.py', 'if len(rows) != expected:',
     'if False:', 'LoadGuards.test_missing_stream_is_rejected'),
    ('pair_set', 'sfu_load_suite.py', 'if pairs != expected_pairs:',
     'if False:', 'LoadGuards.test_duplicate_pair_is_rejected'),
    ('energy', 'sfu_load_suite.py',
     "if r['samples'] < 4800 or r['square_sum'] / max(1, r['samples']) < 500 ** 2:",
     'if False:', 'LoadGuards.test_low_energy_is_rejected'),
    ('window_throughput', 'sfu_load_suite.py',
     "if r['window_packets'] < 10 or r['window_bytes'] < 100:",
     'if False:', 'LoadGuards.test_stale_window_is_rejected'),
    ('inactive_count', 'sfu_load_suite.py',
     "if sum(c.get('inactive_records', 0) for c in cleanup) != total:",
     'if False:', 'LoadGuards.test_cleanup_requires_all_inactive'),
    ('disposition', 'sfu_load_suite.py', "if c.get('disposition') != 'inactive':",
     'if False:', 'LoadGuards.test_unresolved_disposition_is_rejected'),
    ('egress_arithmetic', 'sfu_load_suite.py',
     'streams = participants * (participants - 1)',
     'streams = participants', 'LoadGuards.test_egress_arithmetic'),
    ('zero_window', 'sfu_load_suite.py',
     "if seconds <= 0:\n        raise ValueError('observation window')",
     "if seconds <= 0:\n        seconds = 1.0", 'LoadGuards.test_zero_window_is_rejected'),
]


def main():
    sources = {name: (HERE / name).read_text() for name in FILES}
    results = []
    with tempfile.TemporaryDirectory(prefix='sfu-load-mutations-', dir=SCRATCH) as directory:
        path = Path(directory)
        for name, source in sources.items():
            (path / name).write_text(source)
        for name, target, old, new, test in MUTATIONS:
            if sources[target].count(old) != 1:
                raise ValueError('mutation target count: ' + name)
            (path / target).write_text(sources[target].replace(old, new))
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
            p = subprocess.run([sys.executable, '-B', '-m', 'unittest', 'test_sfu_load.' + test],
                               cwd=path, capture_output=True, timeout=20, env=env)
            killed = (p.returncode != 0 and b'FAIL:' in p.stderr
                      and b'AssertionError' in p.stderr and b'ERROR:' not in p.stderr)
            results.append({'mutation': name, 'killed': killed})
            (path / target).write_text(sources[target])
            for cached in (path / '__pycache__').glob('*.pyc'):
                cached.unlink()
    report = {'mutations': results, 'killed': sum(r['killed'] for r in results),
              'total': len(results)}
    print(json.dumps(report, sort_keys=True))
    return 0 if report['killed'] == report['total'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
