"""Keep collector ingest distinct from management/API and require real TLS trust."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import native_acceptance


class CollectorCompatibilityTest(unittest.TestCase):
    @patch('native_acceptance.subprocess.check_output', return_value='io.fuseone.fusescope:android:0.1.0-beta.3')
    @patch('native_acceptance.subprocess.run')
    def test_sdk_consumes_collector_url_not_backend_url(self, run, _report):
        native_acceptance.build({'endpoint': 'http://127.0.0.1:8080', 'ingestionEndpoint': 'https://127.0.0.1:4319', 'applicationId': 'app', 'key': 'test'}, '0.1.0-beta.3')
        self.assertIn('-Pfusescope.sample.endpoint=https://10.0.2.2:4319', run.call_args.args[0])

    def test_backend_spa_fallback_is_not_an_otlp_success(self):
        from acceptance_transport import backend_route_removed
        self.assertTrue(backend_route_removed(404, 'application/json', '{}'))
        self.assertTrue(backend_route_removed(200, 'text/html; charset=utf-8', '<!doctype html><html></html>'))
        self.assertFalse(backend_route_removed(200, 'application/json', '{}'))
        self.assertFalse(backend_route_removed(200, 'application/x-protobuf', ''))
        self.assertFalse(backend_route_removed(401, 'application/json', '{}'))

    def test_private_ca_is_scoped_to_build_and_restored_on_failure(self):
        from acceptance_transport import sample_tls
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config = root / 'sample/src/main/res/xml/network_security_config.xml'
            config.parent.mkdir(parents=True)
            original = '<network-security-config />'
            config.write_text(original)
            ca = root / 'ca.pem'
            ca.write_text('test certificate')
            resource = root / 'sample/src/main/res/raw/fusescope_acceptance_ca.pem'
            with self.assertRaisesRegex(RuntimeError, 'build failure'):
                with sample_tls({'caCertFile': str(ca)}, root):
                    self.assertIn('@raw/fusescope_acceptance_ca', config.read_text())
                    self.assertEqual(ca.read_bytes(), resource.read_bytes())
                    raise RuntimeError('build failure')
            self.assertEqual(original, config.read_text())
            self.assertFalse(resource.exists())


if __name__ == '__main__':
    unittest.main()
