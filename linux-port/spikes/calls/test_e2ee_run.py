"""Offline guards for the E2EE coordinator's pure helpers."""
import unittest

import e2ee_run
from e2ee_accept import CASES


class Config(unittest.TestCase):
    def test_match_shares_every_key_and_wrong_cases_do_not(self):
        c = e2ee_run.build_config('match')
        self.assertEqual((c['linux_key0'], c['linux_key1']), (c['android_key0'], c['android_key1']))
        self.assertNotEqual(c['linux_key0'], c['linux_key1'])
        for case in ('wrong_key_linux', 'wrong_key_android'):
            c = e2ee_run.build_config(case)
            self.assertNotEqual(c['linux_key0'], c['android_key0'], case)
            self.assertNotEqual(c['linux_key1'], c['android_key1'], case)

    def test_only_intended_flag_differs_from_match(self):
        base = e2ee_run.build_config('match')
        expect = {'plain_android': 'android_enc', 'plain_linux': 'linux_enc', 'tamper_linux_send': 'tamper_send',
                  'tamper_linux_receive': 'tamper_recv', 'replay': 'replay'}
        for case, flag in expect.items():
            c = e2ee_run.build_config(case)
            diff = {k for k in base if k not in ('salt',) and not k.endswith(('key0', 'key1')) and c[k] != base[k]}
            self.assertEqual({flag}, diff, case)

    def test_keys_are_fresh_per_call_and_sized(self):
        a, b = e2ee_run.build_config('match'), e2ee_run.build_config('match')
        self.assertNotEqual(a['linux_key0'], b['linux_key0'])
        self.assertEqual(16, len(bytes.fromhex(a['linux_key0'])))
        self.assertEqual(32, len(bytes.fromhex(e2ee_run.build_config('match', 256)['linux_key0'])))

    def test_every_case_has_a_config(self):
        for case in CASES:
            e2ee_run.build_config(case)

    def test_sanitize_rejects_strings_so_keys_cannot_leak_into_evidence(self):
        with self.assertRaises(AssertionError):
            e2ee_run.sanitize({'android': {'key': 'deadbeef'}})
        self.assertEqual({'a': {'b': 1, 'c': True}}, e2ee_run.sanitize({'a': {'b': 1, 'c': True}}))


if __name__ == '__main__':
    unittest.main()
