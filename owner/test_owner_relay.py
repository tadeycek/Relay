import copy
from pathlib import Path
import secrets
import tempfile
import unittest
from uuid import uuid4
from owner_app import OwnerDatabase
from owner_relay import PROTOCOL, process_report, state_body

class RelayRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.db = OwnerDatabase(Path(self.temp.name) / 'owner.sqlite3')
        self.sender = 'a' * 64
        self.report = {'protocol': PROTOCOL, 'type': 'report', 'device_id': str(uuid4()),
            'device_token': secrets.token_urlsafe(32), 'events': [
                {'seq': 1, 'kind': 'enrolled', 'failures': 0},
                {'seq': 2, 'kind': 'failed', 'failures': 1},
                {'seq': 3, 'kind': 'failed', 'failures': 2},
                {'seq': 4, 'kind': 'locked', 'failures': 3}],
            'diagnostics': {'revision': 0, 'enabled': False}}

    def test_report_binding_and_targeted_recovery(self):
        state = process_report(self.db, self.sender, self.report)
        self.assertEqual(state['acknowledged_sequence'], 4)
        self.assertEqual(state['unlock_through_sequence'], 0)
        self.db.clear_lockout(self.report['device_id'])
        pending = self.db.pending_relay_recoveries()
        self.assertEqual(pending[0]['pubkey'], self.sender)
        self.assertEqual(state_body(self.db, self.report['device_id'])['unlock_through_sequence'], 4)
        ack = copy.deepcopy(self.report)
        ack['events'] = [{'seq': 5, 'kind': 'recovered', 'failures': 0}]
        process_report(self.db, self.sender, ack)
        self.assertEqual(self.db.pending_relay_recoveries(), [])

    def test_wrong_sender_cannot_replace_existing_installation(self):
        process_report(self.db, self.sender, self.report)
        with self.assertRaises(PermissionError): process_report(self.db, 'b' * 64, self.report)
        wrong = copy.deepcopy(self.report)
        wrong['device_token'] = secrets.token_urlsafe(32)
        with self.assertRaises(PermissionError): process_report(self.db, self.sender, wrong)
        self.assertEqual(self.db.state(self.report['device_id'])['last_sequence'], 4)

    def test_replayed_opt_in_cannot_undo_revocation_and_no_ip_is_inferred(self):
        report = copy.deepcopy(self.report)
        report['diagnostics'] = {'revision': 1, 'enabled': True, 'report': {
            'app_version': '1.0-preview', 'manufacturer': 'Test', 'model': 'Emulator',
            'android_version': '15', 'android_api': 35, 'network': 'wifi', 'route': 'tor',
            'permissions': {'camera': True, 'location': False, 'notifications': True}}}
        process_report(self.db, self.sender, report)
        self.assertEqual(self.db.diagnostic(report['device_id'])['peer_address'], 'Not observed (encrypted relay)')
        off = copy.deepcopy(report)
        off['diagnostics'] = {'revision': 2, 'enabled': False}
        process_report(self.db, self.sender, off)
        process_report(self.db, self.sender, report)
        self.assertIsNone(self.db.diagnostic(report['device_id']))
        self.assertEqual(self.db.relay_revision(report['device_id']), 2)

    def test_extra_fields_and_unconsented_diagnostic_fields_are_rejected(self):
        extra = dict(self.report, contacts=['forbidden'])
        with self.assertRaises(ValueError): process_report(self.db, self.sender, extra)
        extra = copy.deepcopy(self.report)
        extra['diagnostics']['report'] = {'secret': 'forbidden'}
        with self.assertRaises(ValueError): process_report(self.db, self.sender, extra)

if __name__ == '__main__': unittest.main()
