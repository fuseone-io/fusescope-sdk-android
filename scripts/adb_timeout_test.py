"""A stuck device command must fail instead of consuming the entire acceptance job."""
import unittest
from unittest.mock import patch

from native_smoke import adb


class AdbTimeoutTest(unittest.TestCase):
    @patch('native_smoke.subprocess.check_output', return_value='Success\n')
    def test_device_commands_have_a_default_deadline(self, output):
        self.assertEqual('Success', adb('install', '-r', 'sample.apk'))
        self.assertEqual(60, output.call_args.kwargs['timeout'])

    @patch('native_smoke.subprocess.check_output', return_value='dumped')
    def test_ui_diagnostics_can_use_a_shorter_deadline(self, output):
        adb('shell', 'uiautomator', 'dump', timeout=15)
        self.assertEqual(15, output.call_args.kwargs['timeout'])


if __name__ == '__main__':
    unittest.main()
