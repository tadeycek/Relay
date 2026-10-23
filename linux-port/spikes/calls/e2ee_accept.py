"""Acceptance rules for the WP02-AC5 E2EE interop spike. Numbers/booleans only; never keys or frames."""

RMS_MIN = 500.0
TONE_RATIO_MIN = 0.5      # share of decoded energy at the expected generated tone
MIN_SAMPLES = 24000       # half a second of 48 kHz PCM
FAIL_STATES = ('DECRYPTIONFAILED', 'MISSINGKEY', 'ENCRYPTIONFAILED', 'INTERNALERROR')

# expect: what each RECEIVER must show.  detect: sides that must show explicit crypto-failure evidence.
CASES = {
    'match': dict(expect=dict(android='valid', linux='valid'), detect=()),
    'wrong_key_linux': dict(expect=dict(android='invalid', linux='invalid'), detect=('android', 'linux')),
    'wrong_key_android': dict(expect=dict(android='invalid', linux='invalid'), detect=('android', 'linux')),
    'plain_android': dict(expect=dict(android='invalid', linux='invalid'), detect=('linux',)),
    'plain_linux': dict(expect=dict(android='invalid', linux='invalid'), detect=('android',)),
    'tamper_linux_send': dict(expect=dict(android='invalid', linux='valid'), detect=('android',)),
    'tamper_linux_receive': dict(expect=dict(android='valid', linux='invalid'), detect=('linux',)),
    'rotate': dict(expect=dict(android='valid', linux='valid'), detect=(), phase2=True),
    'replay': dict(expect=dict(android='any', linux='valid'), detect=()),
    'dtx': dict(expect=dict(android='any', linux='any'), detect=()),
}


def tone_valid(r):
    """True only for a real decoded tone: enough samples, RMS above threshold AND energy at the generated frequency."""
    n = r['samples']
    if n < MIN_SAMPLES or r['connected'] is not True:
        return False
    energy = r['square_sum']
    if energy / n <= RMS_MIN ** 2:
        return False
    return r['tone_square'] / max(energy, 1) >= TONE_RATIO_MIN


def has_failure_evidence(r):
    return r.get('frames_failed', 0) > 0 or any(r.get('states', {}).get(s, 0) > 0 for s in FAIL_STATES)


def validate_case(case, result):
    assert case in CASES, f'unknown case {case}'
    assert set(result) == {'android', 'linux'}, 'Both distinct platforms required'
    spec = CASES[case]
    for side in ('android', 'linux'):
        r = result[side]
        want = spec['expect'][side]
        if want == 'valid':
            assert tone_valid(r), f'{case}: {side} receiver must decode the expected tone'
            assert r.get('frames_failed', 0) == 0 or r['frames_ok'] > 20 * r['frames_failed'], \
                f'{case}: {side} excessive decrypt failures on a matching key'
            if r.get('e2ee_enabled'):
                assert r['frames_ok'] > 0 or 'OK' in r.get('states', {}), f'{case}: {side} no successful decrypts recorded'
        elif want == 'invalid':
            assert not tone_valid(r), f'{case}: {side} decoded a valid tone but must be rejected'
        if side in spec['detect']:
            assert has_failure_evidence(r), f'{case}: {side} shows no explicit crypto failure evidence'
    if spec.get('phase2'):
        for side in ('android', 'linux'):
            r = result[side]
            assert r.get('phase2_samples', 0) >= MIN_SAMPLES // 4, f'{case}: {side} too little post-rotation PCM'
            assert r['phase2_square_sum'] / r['phase2_samples'] > RMS_MIN ** 2, f'{case}: {side} silent after key rotation'
    return True


def _sfu_view(row):
    """Adapter: SFU rows carry two tone bins; the publisher decides which one is the expected tone."""
    tone = row['tone_square_440'] if row['publisher'] == 'linux' else row['tone_square_660']
    return dict(connected=row['connected'], samples=row['samples'], square_sum=row['square_sum'], tone_square=tone)


def validate_sfu(case, rows):
    """E2EE through the SFU: 'match' -> both directed streams decode the publisher's tone; 'wrong_key' (Linux key wrong)
    -> RTP still flows on both streams but no valid tone, with explicit crypto-failure evidence on the receiver."""
    assert case in ('match', 'wrong_key'), f'unknown sfu case {case}'
    assert len(rows) == 2 and {(r['participant'], r['publisher']) for r in rows} == {('android', 'linux'), ('linux', 'android')}, \
        'directed stream count'
    for r in rows:
        assert r['connected'] is True and r['packets'] >= 10 and r['bytes'] >= 100, 'inbound RTP assertion'
        view = _sfu_view(r)
        failure = has_failure_evidence(r)
        if case == 'match':
            assert tone_valid(view), f"{r['participant']} must decode {r['publisher']} tone"
            assert not failure or r.get('frames_ok', 0) > 20 * r.get('frames_failed', 0), 'decrypt failures with matching key'
            if r['participant'] == 'android':
                assert r['states'].get('OK', 0) > 0, 'android FrameCryptor reported no OK state'
            else:
                assert r['frames_ok'] > 0, 'linux decrypted no frames'
        else:
            assert not tone_valid(view), f"{r['participant']} decoded a valid tone under a wrong key"
            assert failure, f"{r['participant']} shows no explicit crypto failure evidence"
    return True
