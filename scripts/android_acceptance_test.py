"""Guard acceptance oracles against false positives without an emulator or credentials."""

import unittest
from unittest.mock import patch

from android_acceptance import error, quiet, sampled_traceparent, session_ready, timeline_events


class AcceptanceOracleTest(unittest.TestCase):
    def test_sampling_bit_is_independent_of_random_trace_id_flag(self):
        prefix = "00-" + "a" * 32 + "-" + "b" * 16 + "-"
        for flags in ("01", "03"):
            self.assertTrue(sampled_traceparent(prefix + flags))
        for header in (None, "malformed", prefix + "00", prefix + "02", "00-" + "0" * 32 + "-" + "b" * 16 + "-03"):
            self.assertFalse(sampled_traceparent(header))

    @patch("android_acceptance.get")
    def test_strict_timeline_acceptance_requires_app_start_not_just_web_vitals(self, get):
        session = {"timeline": [{"route": route, "events": []} for route in ("HomeFragment", "DetailsFragment", "orders/{id}")], "requests": 1, "vitals": 1, "leadingEvents": [{"kind": "vital", "vital": {"name": "lcp"}}]}
        get.return_value = session
        self.assertIsNone(session_ready({}, "session", require_app_start=True))
        session["leadingEvents"][0]["vital"]["name"] = "app_start"
        self.assertEqual(session, session_ready({}, "session", require_app_start=True))
        session["vitals"] = 0
        self.assertIsNone(session_ready({}, "session", require_app_start=True))

    @patch("android_acceptance.get")
    def test_session_views_are_checked_separately_from_app_vital_storage(self, get):
        session = {"timeline": [{"route": route} for route in ("HomeFragment", "DetailsFragment", "orders/{id}")], "requests": 1, "vitals": 0}
        get.return_value = session
        self.assertEqual(session, session_ready({}, "session"))
        session["requests"] = 0
        self.assertIsNone(session_ready({}, "session"))

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
