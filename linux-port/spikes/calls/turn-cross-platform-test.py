"""Offline negatives for provider response normalization and secret-safe failure."""
import io
import json
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

MODULE = runpy.run_path(str(Path(__file__).with_name('turn-cross-platform.py')))
GENERATE = MODULE['generate']
URL = MODULE['URL']


class Credentials(unittest.TestCase):
    def test_redirects_are_rejected_before_secret_forwarding(self):
        handler = MODULE.get('NoRedirect')
        self.assertTrue(callable(handler), 'Provider redirect rejection required')
        with self.assertRaises(ValueError):
            handler().redirect_request(None, None, 302, 'redirect', {}, 'https://untrusted.invalid')

    def test_revocation_is_available(self):
        self.assertTrue(callable(MODULE.get('revoke')), 'Explicit ephemeral credential revocation required')
        from unittest.mock import MagicMock
        for status in (204, 200):
            response = MagicMock()
            response.__enter__.return_value.status = status
            with patch('pathlib.Path.read_text', return_value=json.dumps({'TURN_KEY_ID': 'fixture-id', 'TURN_API_TOKEN': 'private-token'})), patch('urllib.request.OpenerDirector.open', return_value=response) as opened:
                if status == 204:
                    self.assertEqual(MODULE['revoke']('ephemeral/user'), 204)
                    self.assertTrue(opened.call_args.args[0].full_url.endswith('/credentials/ephemeral%2Fuser/revoke'))
                else:
                    with self.assertRaisesRegex(RuntimeError, '^TURN revocation not confirmed'):
                        MODULE['revoke']('ephemeral/user')

    def call(self, servers):
        response = io.BytesIO(json.dumps({'iceServers': servers}).encode())
        with patch('pathlib.Path.read_text', return_value=json.dumps({'TURN_KEY_ID': 'fixture-id', 'TURN_API_TOKEN': 'private-token'})), patch('urllib.request.OpenerDirector.open', return_value=response) as opened:
            result = GENERATE()
            request = opened.call_args.args[0]
            self.assertEqual(json.loads(request.data), {'ttl': 600})
            self.assertEqual(request.method, 'POST')
            self.assertEqual(opened.call_args.kwargs['timeout'], 20)
            return result

    def test_dict_and_list_ice_servers_are_supported(self):
        server = dict(urls=['stun:unselected.invalid', URL], username='ephemeral-user', credential='ephemeral-password')
        for servers in (server, [server]):
            result, expiry = self.call(servers)
            self.assertEqual(result, (URL, 'ephemeral-user', 'ephemeral-password'))
            self.assertGreater(expiry, 0)

    def test_other_urls_and_injected_newline_are_rejected(self):
        for server in (dict(urls=['turn:turn.cloudflare.com:3478'], username='x', credential='y'), dict(urls=[URL], username='x\nprivate-value', credential='y')):
            with self.assertRaisesRegex(RuntimeError, '^TURN credential generation failed') as raised:
                self.call(server)
            self.assertNotIn('private-value', str(raised.exception))

    def test_http_error_never_exposes_token_or_provider_body(self):
        error = HTTPError('https://private.invalid', 403, 'private-token', {}, io.BytesIO(b'private-token'))
        with patch('pathlib.Path.read_text', return_value=json.dumps({'TURN_KEY_ID': 'fixture-id', 'TURN_API_TOKEN': 'private-token'})), patch('urllib.request.OpenerDirector.open', side_effect=error):
            with self.assertRaises(RuntimeError) as raised:
                GENERATE()
            self.assertNotIn('private-token', str(raised.exception))
            self.assertIn('http_status=403', str(raised.exception))


if __name__ == '__main__':
    unittest.main(verbosity=2)
