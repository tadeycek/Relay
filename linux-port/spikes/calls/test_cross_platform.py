"""Acceptance gate: transport, decoded PCM and energy on each *different* platform."""
import unittest
from cross_platform import validate

class ReceiveAcceptance(unittest.TestCase):
    def good(self):
        return {side: dict(packets=100, bytes=1000, samples=48000, nonzero=47000,
                           square_sum=48000 * 1000**2, generated_frames=500,
                           connected=True) for side in ('android', 'linux')}

    def test_both_platforms_receive(self):
        validate(self.good())

    def test_each_platform_must_have_actual_rtp_and_pcm(self):
        for side in ('android', 'linux'):
            for field in ('packets', 'bytes', 'samples', 'nonzero', 'generated_frames', 'square_sum'):
                with self.subTest(side=side, field=field):
                    value = self.good()
                    value[side][field] = 0
                    with self.assertRaises(AssertionError):
                        validate(value)

    def test_comfort_noise_is_not_generated_tone(self):
        for side in ('android', 'linux'):
            value = self.good()
            value[side]['square_sum'] = value[side]['samples'] * 10**2
            with self.assertRaises(AssertionError):
                validate(value)

    def test_disconnected_or_missing_platform_fails(self):
        value = self.good()
        value['android']['connected'] = False
        with self.assertRaises(AssertionError):
            validate(value)
        with self.assertRaises(AssertionError):
            validate({'linux': self.good()['linux']})

class TurnAcceptance(ReceiveAcceptance):
    def good(self):
        value = super().good()
        for r in value.values():
            r.update(local_candidate_type='relay', remote_candidate_type='relay', relay_protocol='tls')
        return value

    def test_turn_rejects_each_direct_or_non_tls_endpoint(self):
        for side in ('android', 'linux'):
            for key, bad in [('local_candidate_type', 'host'), ('remote_candidate_type', 'srflx'), ('relay_protocol', 'tcp'), ('relay_protocol', '')]:
                with self.subTest(side=side, key=key, bad=bad):
                    value = self.good()
                    value[side][key] = bad
                    with self.assertRaises(AssertionError):
                        validate(value, turn=True)

    def test_turn_positive(self):
        validate(self.good(), turn=True)

if __name__ == '__main__':
    unittest.main()
