"""Mutation check of the E2EE acceptance rules: every mutant of e2ee_accept.py must make test_e2ee_accept fail."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
MUTANTS = {
    'accept_loud_noise_as_tone (drop tone-ratio check)': ("return r['tone_square'] / max(energy, 1) >= TONE_RATIO_MIN", 'return True'),
    'accept_low_rms (drop RMS check)': ("if energy / n <= RMS_MIN ** 2:\n        return False", 'pass'),
    'key_mismatch_not_rejected (invalid-side assertion removed)': ("assert not tone_valid(r), f'{case}: {side} decoded a valid tone but must be rejected'", 'pass'),
    'no_failure_evidence_required': ("assert has_failure_evidence(r), f'{case}: {side} shows no explicit crypto failure evidence'", 'pass'),
    'valid_side_not_required': ("assert tone_valid(r), f'{case}: {side} receiver must decode the expected tone'", 'pass'),
    'rotation_phase_not_checked': ("assert r['phase2_square_sum'] / r['phase2_samples'] > RMS_MIN ** 2, f'{case}: {side} silent after key rotation'", 'pass'),
    'sfu_wrong_key_tone_not_rejected': ("assert not tone_valid(view), f\"{r['participant']} decoded a valid tone under a wrong key\"", 'pass'),
    'sfu_failure_evidence_dropped': ("assert failure, f\"{r['participant']} shows no explicit crypto failure evidence\"", 'pass'),
    'sfu_rtp_flow_not_required': ("assert r['connected'] is True and r['packets'] >= 10 and r['bytes'] >= 100, 'inbound RTP assertion'", 'pass'),
    'sfu_wrong_tone_bin_for_publisher': ("tone = row['tone_square_440'] if row['publisher'] == 'linux' else row['tone_square_660']", "tone = row['tone_square_440']"),
    'sfu_match_valid_tone_not_required': ("assert tone_valid(view), f\"{r['participant']} must decode {r['publisher']} tone\"", 'pass'),
    'platform_set_not_checked': ("assert set(result) == {'android', 'linux'}, 'Both distinct platforms required'", 'pass'),
}


def main():
    source = (HERE / 'e2ee_accept.py').read_text()
    report = {}
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        for t in ('test_e2ee_accept.py', 'test_sfu_e2ee_accept.py'):
            shutil.copy(HERE / t, tmp)
        (tmp / 'evidence').mkdir()
        recorded = HERE / 'evidence/e2ee-plain_android-attempt1.json'
        if recorded.exists():
            shutil.copy(recorded, tmp / 'evidence')
        for name, (old, new) in MUTANTS.items():
            assert source.count(old) == 1, f'mutation anchor not unique/present: {name}'
            (tmp / 'e2ee_accept.py').write_text(source.replace(old, new))
            run = subprocess.run([sys.executable, '-B', '-m', 'unittest', 'test_e2ee_accept', 'test_sfu_e2ee_accept'], cwd=tmp, text=True, capture_output=True)
            report[name] = 'KILLED' if run.returncode != 0 else 'SURVIVED'
        (tmp / 'e2ee_accept.py').write_text(source)
        baseline = subprocess.run([sys.executable, '-B', '-m', 'unittest', 'test_e2ee_accept', 'test_sfu_e2ee_accept'], cwd=tmp, text=True, capture_output=True)
        report['_baseline_unmutated'] = 'PASS' if baseline.returncode == 0 else 'FAIL'
    print(json.dumps(report, indent=2))
    (HERE / 'evidence/e2ee-guard-mutations.json').write_text(json.dumps(report, indent=2) + '\n')
    survived = [k for k, v in report.items() if v in ('SURVIVED', 'FAIL')]
    raise SystemExit(1 if survived else 0)


if __name__ == '__main__':
    main()
