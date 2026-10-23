"""Offline acceptance guards for the WP02-AC5 E2EE interop spike (no device, no network)."""
import copy
import unittest

from e2ee_accept import CASES, tone_valid, validate_case


def side(valid=True, **extra):
    base = dict(connected=True, packets=400, bytes=20000, generated_frames=800,
                samples=60000 if valid else 60000, nonzero=59000,
                square_sum=60000 * (3000 ** 2) if valid else 60000 * (200 ** 2),
                tone_square=(60000 * (3000 ** 2) * 0.95) if valid else 60000 * (200 ** 2) * 0.02,
                e2ee_enabled=True, frames_ok=300 if valid else 0, frames_failed=0 if valid else 300,
                states={'OK': 1} if valid else {'DECRYPTIONFAILED': 1})
    base.update(extra)
    return base


def result(case):
    """Synthetic but schema-faithful result for what each case must look like."""
    exp = CASES[case]['expect']
    return {s: side(valid=(exp[s] == 'valid')) for s in ('android', 'linux')}


class KeyMismatchAssertion(unittest.TestCase):
    def test_positive_requires_both_receivers_valid(self):
        validate_case('match', result('match'))

    def test_matching_key_with_one_dead_receiver_is_rejected(self):
        for s in ('android', 'linux'):
            r = result('match')
            r[s] = side(valid=False)
            with self.assertRaises(AssertionError):
                validate_case('match', r)

    def test_low_rms_rejected_even_if_decrypt_counts_look_fine(self):
        r = result('match')
        r['linux']['square_sum'] = r['linux']['samples'] * 100 ** 2
        with self.assertRaises(AssertionError):
            validate_case('match', r)

    def test_loud_noise_without_the_tone_is_not_a_valid_tone(self):
        # Garbage Opus from undecrypted ciphertext can be LOUD: RMS alone must not accept it.
        r = result('match')
        r['android']['tone_square'] = r['android']['square_sum'] * 0.05
        with self.assertRaises(AssertionError):
            validate_case('match', r)

    def test_wrong_key_must_yield_no_valid_tone_on_both_receivers(self):
        for case in ('wrong_key_linux', 'wrong_key_android'):
            validate_case(case, result(case))
            for s in ('android', 'linux'):
                r = result(case)
                r[s] = side(valid=True)  # a receiver that decoded a good tone under a wrong key
                with self.assertRaises(AssertionError, msg=f'{case}/{s}'):
                    validate_case(case, r)

    def test_wrong_key_loud_noise_with_no_failures_recorded_is_still_rejected_as_valid_tone(self):
        r = result('wrong_key_linux')
        r['linux']['square_sum'] = r['linux']['samples'] * 4000 ** 2
        r['linux']['tone_square'] = r['linux']['square_sum'] * 0.9
        with self.assertRaises(AssertionError):
            validate_case('wrong_key_linux', r)

    def test_wrong_key_requires_decrypt_failure_evidence_not_just_silence(self):
        r = result('wrong_key_linux')
        r['linux']['frames_failed'] = 0
        r['linux']['states'] = {}
        with self.assertRaises(AssertionError):
            validate_case('wrong_key_linux', r)

    def test_plaintext_mismatch_must_be_detected_as_failures(self):
        for case in ('plain_android', 'plain_linux'):
            validate_case(case, result(case))
            r = result(case)
            for s in r:
                r[s]['frames_failed'] = 0
                r[s]['states'] = {'OK': 1}
            with self.assertRaises(AssertionError, msg=case):
                validate_case(case, r)

    def test_tamper_flows_are_one_directional(self):
        for case in ('tamper_linux_send', 'tamper_linux_receive'):
            validate_case(case, result(case))
            r = copy.deepcopy(result(case))
            for s in r:
                r[s] = side(valid=True)
            with self.assertRaises(AssertionError, msg=case):
                validate_case(case, r)

    def test_rotation_requires_valid_tone_after_rotation_window(self):
        r = result('rotate')
        r['linux']['phase2_square_sum'] = r['linux']['samples'] * 3000 ** 2 // 2
        r['linux']['phase2_samples'] = r['linux']['samples'] // 2
        r['android']['phase2_square_sum'] = r['android']['samples'] * 3000 ** 2 // 2
        r['android']['phase2_samples'] = r['android']['samples'] // 2
        validate_case('rotate', r)
        r['linux']['phase2_square_sum'] = 0
        with self.assertRaises(AssertionError):
            validate_case('rotate', r)

    def test_recorded_loud_garbage_from_undecrypted_ciphertext_is_not_a_tone(self):
        # Real measurement (plain_android run): Android decoded ciphertext as Opus -> RMS far above 500, no tone.
        import json, pathlib
        path = pathlib.Path(__file__).parent / 'evidence' / 'e2ee-plain_android-attempt1.json'
        if not path.exists():
            self.skipTest('recorded evidence not present')
        recorded = json.loads(path.read_text())['android']
        self.assertGreater((recorded['square_sum'] / recorded['samples']) ** .5, 500)
        self.assertFalse(tone_valid(recorded))

    def test_missing_or_extra_platform_rejected(self):
        with self.assertRaises(AssertionError):
            validate_case('match', {'linux': side()})

    def test_unknown_case_rejected(self):
        with self.assertRaises(AssertionError):
            validate_case('nope', result('match'))


if __name__ == '__main__':
    unittest.main()
