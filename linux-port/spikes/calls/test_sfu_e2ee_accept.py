"""Offline guards for the E2EE-through-SFU assertion (no network, no device)."""
import unittest

from e2ee_accept import validate_sfu


def row(participant, publisher, valid=True, failure=False, **extra):
    n = 60000
    r = dict(participant=participant, publisher=publisher, connected=True, packets=380, bytes=30000, samples=n,
             square_sum=n * (8000 ** 2) if valid else n * (9000 ** 2),
             tone_square_440=0, tone_square_660=0, e2ee_enabled=True,
             frames_ok=0 if failure else 380, frames_failed=380 if failure else 0, states={})
    tone = int(n * (8000 ** 2) * .98) if valid else int(n * (9000 ** 2) * .01)
    r['tone_square_440' if publisher == 'linux' else 'tone_square_660'] = tone
    if participant == 'android':
        r['states'] = {'DECRYPTIONFAILED': 1} if failure else {'OK': 1}
        r['frames_ok'] = r['frames_failed'] = 0
    r.update(extra)
    return r


def good():
    return [row('android', 'linux'), row('linux', 'android')]


def wrong():
    return [row('android', 'linux', valid=False, failure=True), row('linux', 'android', valid=False, failure=True)]


class SfuE2ee(unittest.TestCase):
    def test_match_passes(self):
        validate_sfu('match', good())

    def test_match_rejects_each_dead_stream(self):
        for i in (0, 1):
            rows = good(); rows[i] = wrong()[i]
            with self.assertRaises(AssertionError):
                validate_sfu('match', rows)

    def test_tone_is_measured_at_the_publishers_frequency(self):
        rows = good()
        rows[0]['tone_square_440'], rows[0]['tone_square_660'] = 0, rows[0]['tone_square_440'] or 1  # wrong bin
        rows[0]['tone_square_660'] = int(rows[0]['square_sum'] * .98); rows[0]['tone_square_440'] = 0
        with self.assertRaises(AssertionError):
            validate_sfu('match', rows)

    def test_wrong_key_must_flow_rtp_but_decode_no_tone_with_failure_evidence(self):
        validate_sfu('wrong_key', wrong())
        for i in (0, 1):
            rows = wrong(); rows[i] = good()[i]
            with self.assertRaises(AssertionError):
                validate_sfu('wrong_key', rows)
        rows = wrong(); rows[0]['packets'] = 0     # transport failure is not a crypto rejection
        with self.assertRaises(AssertionError):
            validate_sfu('wrong_key', rows)
        rows = wrong(); rows[1]['frames_failed'] = 0; rows[1]['states'] = {}
        with self.assertRaises(AssertionError):
            validate_sfu('wrong_key', rows)
        rows = wrong(); rows[0]['frames_failed'] = 0; rows[0]['states'] = {}
        with self.assertRaises(AssertionError):
            validate_sfu('wrong_key', rows)

    def test_wrong_key_valid_tone_is_rejected_even_when_failure_counters_exist(self):
        for i in (0, 1):
            rows = wrong()
            rows[i] = row(rows[i]['participant'], rows[i]['publisher'], valid=True, failure=True)
            with self.assertRaises(AssertionError):
                validate_sfu('wrong_key', rows)

    def test_stream_set_must_be_exactly_the_two_directions(self):
        with self.assertRaises(AssertionError):
            validate_sfu('match', good()[:1])
        with self.assertRaises(AssertionError):
            validate_sfu('match', [good()[0], good()[0]])


if __name__ == '__main__':
    unittest.main()
