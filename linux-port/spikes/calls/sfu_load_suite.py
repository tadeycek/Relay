#!/usr/bin/env python3
"""Five-participant SFU load case with measured egress estimate.

Five independent Linux native participants (one JVM process each, reusing the
proven SfuProbe peer/ownership design via SfuLoadProbe) publish one generated
tone and subscribe to the other four: 20 directed streams. Numeric, non-
identifying evidence only. Credentials stay in .local/realtime.json and are
never printed. Raw SDP/session ids remain in .private.

This is provider-observed RTP byte accounting, not a billing-dashboard figure.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time
from live_sfu_media import (Native, Provider, RecoveryJournal, check_response,
                            cleanup_session, wall_clock_bound)

HERE = Path(__file__).resolve().parent
JDK = Path(os.environ.get('JAVA_HOME', '/home/tadej/tools/jdk-21.0.12.1+1'))
PARTICIPANTS = 5
OBSERVATION_SECONDS = 10.0
CASE_LIMIT = 150.0
CLK_TCK = os.sysconf('SC_CLK_TCK')


def validate_load(rows, participants):
    """Exact directed pair set, per-stream RTP, and decoded PCM energy."""
    expected = participants * (participants - 1)
    if len(rows) != expected:
        raise ValueError('directed stream count')
    pairs = {(r['participant'], r['publisher']) for r in rows}
    expected_pairs = {(i, j) for i in range(participants)
                      for j in range(participants) if i != j}
    if pairs != expected_pairs:
        raise ValueError('exact directed pairs')
    for r in rows:
        if r['participant'] == r['publisher']:
            raise ValueError('self subscription')
        if not r['connected'] or r['packets'] < 10 or r['bytes'] < 100:
            raise ValueError('inbound RTP assertion')
        if r['samples'] < 4800 or r['square_sum'] / max(1, r['samples']) < 500 ** 2:
            raise ValueError('PCM energy assertion')


def validate_window(rows, participants):
    """Each stream must carry fresh RTP during the measured observation window."""
    if len(rows) != participants * (participants - 1):
        raise ValueError('directed stream count')
    for r in rows:
        if r['window_packets'] < 10 or r['window_bytes'] < 100:
            raise ValueError('window RTP assertion')


def validate_cleanup(cleanup, participants):
    """All participant x participant track records must read back inactive."""
    total = participants * participants
    if len(cleanup) != participants:
        raise ValueError('cleanup session count')
    if sum(c.get('requested', 0) for c in cleanup) != total:
        raise ValueError('cleanup requested count')
    if sum(c.get('inactive_records', 0) for c in cleanup) != total:
        raise ValueError('cleanup inactive count')
    for c in cleanup:
        if not c.get('close_ok') or not c.get('readback_ok'):
            raise ValueError('cleanup readback')
        if c.get('disposition') != 'inactive':
            raise ValueError('cleanup disposition')


def egress_estimate(rows, participants, seconds):
    """Extrapolate measured per-stream payload bytes. ESTIMATE only."""
    if seconds <= 0:
        raise ValueError('observation window')
    total = sum(r['window_bytes'] for r in rows)
    packets = sum(r['window_packets'] for r in rows)
    streams = participants * (participants - 1)
    per_stream_min = total / streams / (seconds / 60.0)
    # 50 B/packet wire overhead: RTP 12 + SRTP auth/tag 10 + UDP 8 + IP 20.
    wire_total = total + packets * 50
    wire_per_stream_min = wire_total / streams / (seconds / 60.0)
    out = {
        'label': 'ESTIMATE',
        'observed_seconds': round(seconds, 3),
        'observed_total_payload_bytes': total,
        'observed_total_packets': packets,
        'observed_payload_kbps': round(total * 8 / seconds / 1000.0, 2),
        'observed_wire_kbps_estimate': round(wire_total * 8 / seconds / 1000.0, 2),
        'streams': streams,
        'per_stream_payload_bytes_minute': round(per_stream_min, 1),
        'per_stream_wire_bytes_minute_estimate': round(wire_per_stream_min, 1),
        'per_participant_payload_bytes_minute': round(total / participants / (seconds / 60.0), 1),
        'assumptions': [
            'Opus mono ~32 kbps, DTX off, continuous generated tone',
            'no video, no FEC/RTX counted in payload bytes',
            'wire overhead estimate adds 50 B per packet to provider-observed payload bytes',
            'N publishers x (N-1) subscribers, all streams continuously active',
            '17-participant figure is extrapolated from the 5-participant measurement, not measured',
        ],
        'estimate': {},
    }
    for n in (5, 17):
        s = n * (n - 1)
        bytes_min = per_stream_min * s
        wire_bytes_min = wire_per_stream_min * s
        hours_to_free = 1000 * 1000 * 1000 * 1000 / (wire_bytes_min * 60.0)
        out['estimate'][str(n)] = {
            'participants': n,
            'streams': s,
            'payload_bytes_minute': round(bytes_min, 1),
            'wire_bytes_minute_estimate': round(wire_bytes_min, 1),
            'wire_mb_hour_estimate': round(wire_bytes_min * 60 / 1e6, 1),
            'gb_hour_estimate': round(wire_bytes_min * 60 / 1e9, 4),
            'room_hours_until_1000gb_estimate': round(hours_to_free, 1),
            'cost_usd_per_room_hour_after_free': round(wire_bytes_min * 60 / 1e9 * 0.05, 4),
        }
    return out


def read_proc(pid):
    """Sample /proc/<pid>/{stat,status}; None if the process is gone."""
    try:
        raw = Path(f'/proc/{pid}/stat').read_text()
        rest = raw[raw.rfind(')') + 2:].split()
        ticks = int(rest[11]) + int(rest[12])
        status = {}
        for line in Path(f'/proc/{pid}/status').read_text().splitlines():
            if ':' in line:
                key, value = line.split(':', 1)
                status[key.strip()] = value.strip()
        return {
            'cpu_seconds': ticks / CLK_TCK,
            'rss_kb': int(status.get('VmRSS', '0 kB').split()[0]),
            'peak_rss_kb': int(status.get('VmHWM', '0 kB').split()[0]),
            'threads': int(status.get('Threads', '0')),
        }
    except (FileNotFoundError, ProcessLookupError, ValueError, IndexError):
        return None


class LoadNative(Native):
    """One participant per JVM. Parent methods reused; only log path differs."""

    def __init__(self, private, index):
        self.index = index
        self.created_at = time.monotonic()
        self.deadline = time.monotonic() + 240
        self.log = open(private / f'sfu-load-native-{index}.log', 'wb')
        cp = str(private / 'sfu-load-classes') + os.pathsep + str(HERE / 'build/install/relay-calls-spike/lib/*')
        self.p = subprocess.Popen([str(JDK / 'bin/java'), '-cp', cp, 'relay.spike.SfuLoadProbe'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log)
        assert self.p.stdin is not None and self.p.stdout is not None
        self.sel = selectors.DefaultSelector()
        self.sel.register(self.p.stdout, selectors.EVENT_READ)


def parse_metrics(raw):
    """Validate the load probe's numeric metrics object."""
    value = json.loads(raw)
    if type(value) is not dict or type(value.get('inbound')) is not list:
        raise ValueError('metrics schema')
    if type(value.get('frames_pushed')) is not int or value['frames_pushed'] < 0:
        raise ValueError('metrics schema')
    outbound = value.get('outbound', {})
    if type(outbound) is not dict:
        raise ValueError('metrics schema')
    for key in ('packetsSent', 'bytesSent', 'headerBytesSent', 'retransmittedPacketsSent'):
        if type(outbound.get(key)) is not int or outbound[key] < 0:
            raise ValueError('metrics schema')
    rows = []
    for row in value['inbound']:
        if type(row) is not dict:
            raise ValueError('metrics schema')
        for key in ('packets', 'bytes', 'samples', 'square_sum', 'packets_lost'):
            if type(row.get(key)) is not int or row[key] < 0 and key != 'packets_lost':
                raise ValueError('metrics schema')
        if type(row.get('connected')) is not bool or type(row.get('mid')) is not str:
            raise ValueError('metrics schema')
        if type(row.get('jitter')) not in (int, float) or not math.isfinite(row['jitter']):
            raise ValueError('metrics schema')
        rows.append(row)
    value['outbound'] = outbound
    value['inbound'] = rows
    return value


def snapshot(natives, mapping, participants):
    """Cumulative per-stream metrics keyed by (participant, publisher)."""
    by_key = {}
    participants_meta = []
    for i in range(participants):
        native = natives[i]
        value = parse_metrics(native.command('METRICS', 0))
        participants_meta.append({
            'participant': i,
            'frames_pushed': value['frames_pushed'],
            'outbound': value['outbound'],
        })
        for row in value['inbound']:
            key = (i, mapping.get((i, row['mid'])))
            if key[1] is None:
                raise ValueError('metrics mapping')
            if key in by_key:
                raise ValueError('duplicate stream metrics')
            by_key[key] = row
    return by_key, participants_meta


def build_rows(before, after, window_seconds):
    rows = []
    for key, now in after.items():
        prior = before.get(key)
        if prior is None:
            raise ValueError('stream disappeared during window')
        rows.append({
            'participant': key[0],
            'publisher': key[1],
            'packets': now['packets'],
            'bytes': now['bytes'],
            'window_packets': now['packets'] - prior['packets'],
            'window_bytes': now['bytes'] - prior['bytes'],
            'samples': now['samples'],
            'square_sum': now['square_sum'],
            'jitter': round(float(now['jitter']), 6),
            'packets_lost': now['packets_lost'],
            'connected': now['connected'],
        })
    rows.sort(key=lambda r: (r['participant'], r['publisher']))
    return rows


def attempt_is_retryable(error, http_status=None):
    if error is None:
        return False
    if http_status in (500, 502, 503, 504):
        return True
    text = str(error)
    return text in ('SFU connection', 'bounded SFU ICE gathering', 'wall clock bound',
                    'native bounded response', 'provider total runtime bound',
                    'full media not reached', 'native exited') or 'timeout' in text.lower()


def run_case(attempt):
    start = time.monotonic()
    os.umask(0o077)
    private = HERE / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    provider = Provider()
    provider.deadline = time.monotonic() + 300
    journal = RecoveryJournal(private)
    natives = []
    sessions = []
    allocations = {}
    mapping = {}
    cleanup = []
    rows = []
    error = None
    stage = 'publish'
    http_status = None
    connected = []
    time_to_full = None
    window_seconds = None
    before = {}
    after = {}
    proc_before = {}
    proc_after = {}
    participants_meta = []
    try:
        with wall_clock_bound(time.monotonic() + CASE_LIMIT):
            for i in range(PARTICIPANTS):
                native = LoadNative(private, i)
                natives.append(native)
                native.command('NEW', 0, '0')
                offer = native.command('OFFER', 0)
                mid = native.command('MID', 0)
                created = check_response(provider.call('POST', '/sessions/new'))
                sid = created.get('sessionId', '')
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', sid):
                    raise ValueError('session schema')
                sessions.append(sid)
                allocations[sid] = [mid]
                journal.record(sid, allocations[sid], 'unresolved')
                result = provider.call('POST', f'/sessions/{sid}/tracks/new', {
                    'sessionDescription': {'type': 'offer', 'sdp': offer},
                    'tracks': [{'location': 'local', 'mid': mid, 'trackName': f'tone-{i}'}]})
                allocations[sid].extend(t['mid'] for t in result.get('tracks', [])
                                        if 'mid' in t and not t.get('errorCode'))
                journal.record(sid, allocations[sid], 'unresolved')
                check_response(result)
                if len(result.get('tracks', [])) != 1:
                    raise ValueError('publication count')
                native.remote(0, result['sessionDescription'])
                native.command('CONNECTED', 0)
                connected.append({'participant': i,
                                  'connected_seconds': round(time.monotonic() - start, 2),
                                  'connect_seconds': round(time.monotonic() - native.created_at, 2)})
                check_response(provider.call('GET', f'/sessions/{sid}'))
            stage = 'subscribe'
            for i, sid in enumerate(sessions):
                publishers = [j for j in range(PARTICIPANTS) if j != i]
                result = provider.call('POST', f'/sessions/{sid}/tracks/new', {'tracks': [
                    {'location': 'remote', 'sessionId': sessions[j], 'trackName': f'tone-{j}'}
                    for j in publishers]})
                allocations[sid].extend(t['mid'] for t in result.get('tracks', [])
                                        if 'mid' in t and not t.get('errorCode'))
                journal.record(sid, allocations[sid], 'unresolved')
                check_response(result)
                tracks = result.get('tracks', [])
                if len(tracks) != PARTICIPANTS - 1:
                    raise ValueError('subscription count')
                for track in tracks:
                    j = next((j for j in publishers if track.get('trackName') == f'tone-{j}'), None)
                    if j is None:
                        raise ValueError('subscription mapping')
                    mapping[(i, track['mid'])] = j
                native = natives[i]
                native.remote(0, result['sessionDescription'])
                answer = native.command('ANSWER', 0)
                check_response(provider.call('PUT', f'/sessions/{sid}/renegotiate',
                                             {'sessionDescription': {'type': 'answer', 'sdp': answer}}))
                native.command('CONNECTED', 0)
                check_response(provider.call('GET', f'/sessions/{sid}'))
            stage = 'media'
            deadline = time.monotonic() + 40
            full_snapshot = None
            full_meta = []
            while time.monotonic() < deadline:
                snap, meta = snapshot(natives, mapping, PARTICIPANTS)
                if all(r['packets'] >= 10 and r['samples'] >= 4800 for r in snap.values()):
                    full_snapshot = snap
                    full_meta = meta
                    time_to_full = round(time.monotonic() - start, 2)
                    break
                time.sleep(1)
            if full_snapshot is None:
                raise TimeoutError('full media not reached')
            before = full_snapshot
            participants_meta = full_meta
            window_start = time.monotonic()
            proc_before = {n.index: read_proc(n.p.pid) for n in natives}
            time.sleep(OBSERVATION_SECONDS)
            after, participants_meta = snapshot(natives, mapping, PARTICIPANTS)
            window_seconds = time.monotonic() - window_start
            proc_after = {n.index: read_proc(n.p.pid) for n in natives}
            rows = build_rows(before, after, window_seconds)
            validate_load(rows, PARTICIPANTS)
            validate_window(rows, PARTICIPANTS)
    except Exception as e:
        error = type(e).__name__
        if isinstance(e, TimeoutError):
            error = 'TimeoutError'
        if hasattr(e, 'code'):
            http_status = getattr(e, 'code', None)
        elif isinstance(e, ValueError):
            error = str(e)
        else:
            error = type(e).__name__
        # Keep the fixed numeric assertion labels public; suppress everything else.
        if isinstance(e, ValueError) and str(e) in (
                'PCM energy assertion', 'inbound RTP assertion', 'directed stream count',
                'exact directed pairs', 'window RTP assertion', 'metrics schema',
                'metrics mapping', 'stream disappeared during window',
                'subscription count', 'publication count', 'subscription mapping'):
            error = str(e)
    finally:
        provider.deadline = time.monotonic() + 120
        for sid in reversed(sessions):
            cleanup.append(cleanup_session(provider, sid, allocations.get(sid, []), journal))
        native_exits = []
        for native in natives:
            try:
                native.close()
                native_exits.append(True)
            except Exception:
                native_exits.append(False)

    # Per-participant accounting from the cumulative snapshots and /proc.
    per_participant = []
    for meta in participants_meta:
        i = meta['participant']
        received = [r for r in rows if r['participant'] == i]
        before_i = proc_before.get(i) or {}
        after_i = proc_after.get(i) or {}
        cpu_total = after_i.get('cpu_seconds')
        cpu_window = None
        if cpu_total is not None and before_i.get('cpu_seconds') is not None:
            cpu_window = round(cpu_total - before_i['cpu_seconds'], 3)
        per_participant.append({
            'participant': i,
            'frames_pushed': meta['frames_pushed'],
            'packets_sent': meta['outbound']['packetsSent'],
            'bytes_sent': meta['outbound']['bytesSent'],
            'header_bytes_sent': meta['outbound']['headerBytesSent'],
            'packets_received': sum(r['packets'] for r in received),
            'bytes_received': sum(r['bytes'] for r in received),
            'cpu_seconds_total': round(cpu_total, 3) if cpu_total is not None else None,
            'cpu_seconds_window': cpu_window,
            'peak_rss_kb': after_i.get('peak_rss_kb'),
            'rss_kb_window_end': after_i.get('rss_kb'),
            'threads': after_i.get('threads'),
        })

    passed = (error is None and len(rows) == PARTICIPANTS * (PARTICIPANTS - 1)
              and all(native_exits) and len(cleanup) == PARTICIPANTS)
    if passed:
        try:
            validate_load(rows, PARTICIPANTS)
            validate_window(rows, PARTICIPANTS)
            validate_cleanup(cleanup, PARTICIPANTS)
        except ValueError as e:
            passed = False
            error = str(e)

    egress = None
    if rows and window_seconds:
        egress = egress_estimate(rows, PARTICIPANTS, window_seconds)

    report = {
        'attempt': attempt,
        'participants': PARTICIPANTS,
        'stream_count': len(rows),
        'stage': stage,
        'error': error,
        'retryable': attempt_is_retryable(error, http_status),
        'http_error_status': http_status,
        'passed': passed,
        'native_normal_exit': all(native_exits),
        'timings': {
            'connected': connected,
            'time_to_full_media_seconds': time_to_full,
            'observation_window_seconds': round(window_seconds, 3) if window_seconds else None,
        },
        'per_participant': per_participant,
        'streams': rows,
        'cleanup': {
            'sessions': len(cleanup),
            'requested': sum(c.get('requested', 0) for c in cleanup),
            'inactive_records': sum(c.get('inactive_records', 0) for c in cleanup),
            'close_ok': sum(bool(c.get('close_ok')) for c in cleanup),
            'readback_ok': sum(bool(c.get('readback_ok')) for c in cleanup),
            'dispositions': [c.get('disposition', 'unresolved') for c in cleanup],
        },
        'resources': {
            'total_threads': sum(p.get('threads') or 0 for p in per_participant),
            'total_peak_rss_kb': sum(p.get('peak_rss_kb') or 0 for p in per_participant),
            'processes_sampled': sum(1 for p in per_participant if p.get('peak_rss_kb')),
        },
        'egress': egress,
        'elapsed_seconds': round(time.monotonic() - start, 2),
        'synthetic_audio': True,
        'physical_microphone': False,
        'e2ee': False,
    }
    return report


def execute(args, expected=0, timeout=300):
    p = subprocess.run(args, cwd=HERE, capture_output=True, timeout=timeout)
    if p.returncode != expected:
        (HERE / '.private/sfu-load-suite-failure.log').write_bytes(p.stdout + p.stderr)
        raise ValueError('load suite subprocess failed; private log retained')
    return p


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collect-existing', action='store_true',
                        help='Revalidate earlier numeric reports; no live requests')
    args = parser.parse_args()
    if not args.collect_existing and os.environ.get('RELAY_SPIKE_LIVE') != '1':
        raise ValueError('live opt-in required')
    os.umask(0o077)
    private = HERE / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    evidence = HERE / 'evidence'
    evidence.mkdir(exist_ok=True)

    if not args.collect_existing:
        classes = private / 'sfu-load-classes'
        classes.mkdir(exist_ok=True)
        execute([str(JDK / 'bin/javac'), '-cp', str(HERE / 'build/install/relay-calls-spike/lib/*'),
                 '-d', str(classes), 'src/main/java/relay/spike/CallsProbe.java',
                 'src/main/java/relay/spike/SfuLoadProbe.java'])
    unit = execute([sys.executable, '-m', 'unittest', 'test_sfu_load', '-v'])
    (evidence / 'sfu-load-unit.txt').write_bytes(unit.stdout + unit.stderr)
    mutations = json.loads(execute([sys.executable, 'check_sfu_load_mutations.py']).stdout)
    assert mutations['killed'] == mutations['total'], 'guard mutation survived'
    (evidence / 'sfu-load-guard-mutations.json').write_text(json.dumps(mutations, indent=2) + '\n')

    attempts = []
    if args.collect_existing:
        for path in sorted(evidence.glob('sfu-load-attempt*.json')):
            attempts.append(json.loads(path.read_text()))
    else:
        for attempt in (1, 2):
            report = run_case(attempt)
            attempts.append(report)
            (evidence / f'sfu-load-attempt{attempt}.json').write_text(json.dumps(report, indent=2) + '\n')
            if report['passed'] or not report['retryable']:
                break
            time.sleep(2)
    final = next((a for a in attempts if a['passed']), None)
    summary = {
        'live_executed_this_invocation': not args.collect_existing,
        'participants': PARTICIPANTS,
        'attempts': [{
            'attempt': a['attempt'], 'passed': a['passed'], 'stage': a['stage'],
            'error': a['error'], 'retryable': a['retryable'],
            'stream_count': a['stream_count'],
            'time_to_full_media_seconds': a['timings']['time_to_full_media_seconds'],
            'elapsed_seconds': a['elapsed_seconds'],
        } for a in attempts],
        'final_passed': bool(final),
        'egress': final['egress'] if final else None,
        'guard_mutations': mutations,
        'unit_tests': 'evidence/sfu-load-unit.txt',
    }
    (evidence / 'sfu-load-suite.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))
    return 0 if final else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as e:
        print(json.dumps({'suite_failed': True, 'type': type(e).__name__, 'details': 'suppressed'}))
        raise SystemExit(1)
