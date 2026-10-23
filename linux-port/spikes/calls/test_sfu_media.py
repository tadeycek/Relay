"""Pure numeric assertion guards; no credentials or live calls."""
import unittest
from live_sfu_media import validate_media, check_response, Provider, NoRedirect, LIMIT
from unittest.mock import patch
import os

class MediaGuards(unittest.TestCase):
    def row(self):
        return dict(participant=0, publisher=1, packets=30, bytes=2000,
                    samples=48000, square_sum=48000*1000*1000, connected=True)

    def test_energy_not_just_nonzero(self):
        row=self.row()
        row['square_sum']=48000
        with self.assertRaisesRegex(ValueError, 'PCM energy'):
            validate_media([row], 1)

    def test_good(self):
        validate_media([self.row()], 1)

    def test_rtp_required(self):
        row=self.row(); row['packets']=0
        with self.assertRaisesRegex(ValueError, 'RTP'):
            validate_media([row], 1)

    def test_count_and_duplicate(self):
        with self.assertRaises(ValueError): validate_media([self.row()], 6)
        with self.assertRaises(ValueError): validate_media([self.row(),self.row()], 2)

    def test_per_track_error(self):
        with self.assertRaisesRegex(ValueError, 'track error'):
            check_response({'tracks':[{'mid':'0','errorCode':'bad'}]})

    def test_opt_in_before_any_secret_or_network(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, 'live opt-in required'): Provider()

    def test_redirect_disabled(self):
        self.assertIsNone(NoRedirect().redirect_request(None,None,302,'',{},'https://invalid.example'))

    def test_response_bound(self):
        class Oversized:
            status=200
            def __enter__(self): return self
            def __exit__(self,*args): return False
            def read(self,n): return b'x'*(LIMIT+1)
        class Opener:
            def open(self,*args,**kwargs): return Oversized()
        provider=object.__new__(Provider)
        provider.base='https://invalid.example'
        provider.secret='noncredential-fixture'
        provider.opener=Opener()
        provider.deadline=float('inf')
        provider.calls=0
        with self.assertRaisesRegex(ValueError, 'provider response bound'):
            provider.call('GET','/never-sent')

if __name__=='__main__': unittest.main()
