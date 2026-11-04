"""Guard acceptance oracles against false positives without an emulator or credentials."""

import unittest
from unittest.mock import patch

from android_acceptance import error, quiet, timeline_events


class AcceptanceOracleTest(unittest.TestCase):
    @patch("android_acceptance.get")
    def test_zero_errors_are_not_enough_when_other_telemetry_was_ingested(self, get):
        get.return_value = {"counts": {"errors": 0}, "firstSeenAt": "2026-11-03T00:00:00Z", "rejections": []}
        with self.assertRaisesRegex(AssertionError, "no telemetry"):
            quiet({}, seconds=1)

    @patch("android_acceptance.get")
    def test_rejected_ingest_cannot_pass_as_withheld_consent(self, get):
        get.return_value = {"counts": {"errors": 0}, "firstSeenAt": None, "rejections": [{"reason": "bad_key"}]}
        with self.assertRaisesRegex(AssertionError, "rejected ingest"):
            quiet({}, seconds=1)

    @patch("android_acceptance.get")
    def test_native_group_does_not_masquerade_as_java_crash(self, get):
        get.return_value = {"groups": [{"source": "native", "fingerprint": "native"}]}
        self.assertIsNone(error({}, "crash"))
        get.assert_called_once_with({}, "errors")

    def test_request_matching_includes_leading_events(self):
        leading = {"kind": "request", "request": {"traceId": "before-view"}}
        nested = {"kind": "request", "request": {"traceId": "inside-view"}}
        self.assertEqual([leading, nested], timeline_events({"leadingEvents": [leading], "timeline": [{"events": [nested]}]}))


if __name__ == "__main__":
    unittest.main()
