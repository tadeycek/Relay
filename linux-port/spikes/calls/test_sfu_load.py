"""Offline acceptance guards for the five-participant load case."""
import os
import unittest

from sfu_load_suite import (PARTICIPANTS, egress_estimate, read_proc,
                            validate_cleanup, validate_load, validate_window)


def make_rows(n=PARTICIPANTS, packets=100, byte_count=10_000,
              window_packets=500, window_bytes=50_000,
              samples=48_000, square_sum=48_000 * 1_000 * 1_000):
    return [dict(participant=i, publisher=j, packets=packets, bytes=byte_count,
                 window_packets=window_packets, window_bytes=window_bytes,
                 samples=samples, square_sum=square_sum, jitter=0.001,
                 packets_lost=0, connected=True)
            for i in range(n) for j in range(n) if i != j]


def make_cleanup(n=PARTICIPANTS):
    return [dict(requested=n, inactive_records=n, close_ok=True,
                 readback_ok=True, disposition='inactive') for _ in range(n)]


class LoadGuards(unittest.TestCase):
    def test_valid_load_passes(self):
        rows = make_rows()
        validate_load(rows, PARTICIPANTS)
        validate_window(rows, PARTICIPANTS)
        self.assertEqual(len(rows), 20)

    def test_missing_stream_is_rejected(self):
        rows = make_rows()[:-1]
        with self.assertRaisesRegex(ValueError, 'directed stream count'):
            validate_load(rows, PARTICIPANTS)

    def test_duplicate_pair_is_rejected(self):
        rows = make_rows()
        rows[-1]['publisher'] = rows[0]['publisher']
        with self.assertRaisesRegex(ValueError, 'exact directed pairs'):
            validate_load(rows, PARTICIPANTS)

    def test_self_pair_is_rejected(self):
        rows = make_rows()
        rows[0]['publisher'] = rows[0]['participant']
        with self.assertRaisesRegex(ValueError, 'exact directed pairs'):
            validate_load(rows, PARTICIPANTS)

    def test_low_energy_is_rejected(self):
        rows = make_rows(square_sum=48_000)
        with self.assertRaisesRegex(ValueError, 'PCM energy assertion'):
            validate_load(rows, PARTICIPANTS)

    def test_stale_window_is_rejected(self):
        rows = make_rows(window_packets=0)
        with self.assertRaisesRegex(ValueError, 'window RTP assertion'):
            validate_window(rows, PARTICIPANTS)

    def test_cleanup_requires_all_inactive(self):
        validate_cleanup(make_cleanup(), PARTICIPANTS)
        cleanup = make_cleanup()
        cleanup[0]['inactive_records'] = PARTICIPANTS - 1
        with self.assertRaisesRegex(ValueError, 'cleanup inactive count'):
            validate_cleanup(cleanup, PARTICIPANTS)

    def test_unresolved_disposition_is_rejected(self):
        cleanup = make_cleanup()
        cleanup[2]['disposition'] = 'unresolved'
        with self.assertRaisesRegex(ValueError, 'cleanup disposition'):
            validate_cleanup(cleanup, PARTICIPANTS)

    def test_failed_readback_is_rejected(self):
        cleanup = make_cleanup()
        cleanup[4]['readback_ok'] = False
        with self.assertRaisesRegex(ValueError, 'cleanup readback'):
            validate_cleanup(cleanup, PARTICIPANTS)

    def test_cleanup_requested_count(self):
        cleanup = make_cleanup()
        cleanup[0]['requested'] = PARTICIPANTS - 1
        with self.assertRaisesRegex(ValueError, 'cleanup requested count'):
            validate_cleanup(cleanup, PARTICIPANTS)

    def test_egress_arithmetic(self):
        rows = make_rows(window_bytes=100_000)
        result = egress_estimate(rows, PARTICIPANTS, 60.0)
        assert result is not None
        self.assertEqual(result['observed_total_payload_bytes'], 2_000_000)
        self.assertEqual(result['per_stream_payload_bytes_minute'], 100_000.0)
        self.assertEqual(result['estimate']['5']['streams'], 20)
        self.assertEqual(result['estimate']['5']['payload_bytes_minute'], 2_000_000.0)
        self.assertEqual(result['estimate']['17']['streams'], 272)
        self.assertEqual(result['estimate']['17']['payload_bytes_minute'], 27_200_000.0)
        self.assertAlmostEqual(result['observed_payload_kbps'], 266.67, places=2)

    def test_zero_window_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'observation window'):
            egress_estimate(make_rows(), PARTICIPANTS, 0)

    def test_process_sample(self):
        sample = read_proc(os.getpid())
        self.assertIsNotNone(sample)
        self.assertGreaterEqual(sample['cpu_seconds'], 0)
        self.assertGreater(sample['rss_kb'], 0)
        self.assertGreaterEqual(sample['threads'], 1)


if __name__ == '__main__':
    unittest.main()
