import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("live_sfu", Path(__file__).with_name("live_sfu.py"))
assert spec is not None and spec.loader is not None
live_sfu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live_sfu)


class LiveGuards(unittest.TestCase):
    def test_opt_in_required_before_network(self):
        with patch.object(live_sfu.urllib.request, "build_opener", side_effect=AssertionError("Network used")):
            with self.assertRaises(ValueError):
                live_sfu.smoke({})

    def test_credentials_required_before_network(self):
        with patch.object(live_sfu.urllib.request, "build_opener", side_effect=AssertionError("Network used")):
            with self.assertRaises(ValueError):
                live_sfu.smoke({"RELAY_SPIKE_LIVE": "1"})

    def test_app_id_path_injection_rejected(self):
        with self.assertRaises(ValueError):
            live_sfu.configuration({"RELAY_SPIKE_LIVE": "1", "RELAY_SFU_APP_ID": "../invalid", "RELAY_SFU_APP_SECRET": "noncredential"})

    def test_header_injection_rejected(self):
        with self.assertRaises(ValueError):
            live_sfu.configuration({"RELAY_SPIKE_LIVE": "1", "RELAY_SFU_APP_ID": "test", "RELAY_SFU_APP_SECRET": "noncredential\r\ninvalid"})

    @unittest.skipUnless(live_sfu.os.environ.get("RELAY_SPIKE_LIVE") == "1", "Requires explicit opt-in and real SFU credentials")
    def test_live_sfu_session_create_readback(self):
        live_sfu.smoke(live_sfu.os.environ)


if __name__ == "__main__":
    unittest.main()
