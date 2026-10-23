"""Offline guards and numeric acceptance; these fixtures are not provider evidence."""
import importlib.util
import json
from pathlib import Path
import unittest

class TurnAcceptance(unittest.TestCase):
    def load_runner(self):
        path = Path(__file__).with_name('live_turn.py')
        self.assertTrue(path.is_file(), 'Missing guarded live TURN runner')
        spec = importlib.util.spec_from_file_location('live_turn', path)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_opt_in_required_before_file_or_network(self):
        module = self.load_runner()
        with self.assertRaisesRegex(ValueError, 'opt-in'):
            module.configuration({})

    def test_acceptance_rejects_host_candidate(self):
        module = self.load_runner()
        text = self.good_text().replace('local=relay', 'local=host')
        with self.assertRaisesRegex(ValueError, 'selected candidate'):
            module.validate(text)

    def test_acceptance_rejects_missing_selected_pair(self):
        module = self.load_runner()
        text = '\n'.join(l for l in self.good_text().splitlines() if 'selected_pair side=b' not in l)
        with self.assertRaises(ValueError):
            module.validate(text)

    def test_acceptance_rejects_non_tls_relay(self):
        module = self.load_runner()
        with self.assertRaises(ValueError):
            module.validate(self.good_text().replace('relay_protocol=tls', 'relay_protocol=udp'))

    def test_acceptance_rejects_empty_receiver_pcm(self):
        module = self.load_runner()
        with self.assertRaises(ValueError):
            module.validate(self.good_text().replace('b_nonzero_bytes=1', 'b_nonzero_bytes=0'))

    def test_acceptance_rejects_missing_cleanup(self):
        module = self.load_runner()
        with self.assertRaises(ValueError):
            module.validate(self.good_text().replace('cleanup=COMPLETE', ''))

    def test_acceptance_rejects_unexercised_pair(self):
        module = self.load_runner()
        with self.assertRaises(ValueError):
            module.validate(self.good_text().replace('sent=1', 'sent=0'))

    def test_acceptance_keeps_physical_and_firewall_gates_open(self):
        module = self.load_runner()
        result = module.validate(self.good_text())
        self.assertFalse(result['physical_audio'])
        self.assertFalse(result['independent_udp_block_test'])
        self.assertEqual(len(result['selected_pairs']), 2)

    def test_provider_without_tls443_rejected(self):
        module = self.load_runner()
        with self.assertRaises(ValueError):
            module.select_credentials({'iceServers': [{'urls': ['turn:turn.cloudflare.com:3478'],
                                                       'username': 'unit-only', 'credential': 'unit-only'}]})

    def test_provider_dict_or_list_ice_servers_supported(self):
        module = self.load_runner()
        fixture = {'urls': [module.URL], 'username': 'unit-only', 'credential': 'unit-only'}
        for value in (fixture, [fixture]):
            self.assertEqual(module.select_credentials({'iceServers': value}), ('unit-only', 'unit-only'))

    @staticmethod
    def good_text():
        # Numeric fixture exercises parsing only. Never written to evidence or counted as live output.
        return '''cleanup=COMPLETE
selected_pair side=a local=relay remote=relay relay_protocol=tls sent=1 received=1
selected_pair side=b local=relay remote=relay relay_protocol=tls sent=1 received=1
decoded_audio a_pcm_bytes=2 a_nonzero_bytes=1 b_pcm_bytes=2 b_nonzero_bytes=1
data_messages=2 result=PASS mode=forced-turn-tls443'''

if __name__ == '__main__':
    unittest.main()
