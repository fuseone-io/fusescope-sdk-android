"""Guard device acceptance against silently exercising the wrong API or transport."""
import os
import unittest
from unittest.mock import patch

import acceptance_transport


class OlderAndroidTest(unittest.TestCase):
    def test_old_device_builds_target_the_supported_floor(self):
        for api in ('23', '28', '35'):
            with patch.dict(os.environ, {'ANDROID_API': api}):
                self.assertIn('-Pfusescope.sample.minSdk=23', acceptance_transport.sample_runtime_properties())

    def test_unsupported_device_is_not_accepted_by_accident(self):
        with patch.dict(os.environ, {'ANDROID_API': '22'}):
            with self.assertRaises(ValueError):
                acceptance_transport.sample_runtime_properties()

    def test_probe_requires_expected_protocol_and_aead(self):
        from older_android import validate_probe
        validate_probe({'status': 405, 'protocol': 'TLSv1.2', 'cipher': 'TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256'}, 23)
        for probe in ({'error': 'SSLHandshakeException'}, {'status': 405, 'protocol': 'TLSv1.2', 'cipher': 'TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA'}):
            with self.assertRaises(AssertionError):
                validate_probe(probe, 23)
        with self.assertRaises(AssertionError):
            validate_probe({'status': 405, 'protocol': 'TLSv1.2', 'cipher': 'TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256'}, 35)


if __name__ == '__main__':
    unittest.main()
