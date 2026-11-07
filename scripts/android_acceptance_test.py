"""Guard acceptance oracles against false positives without an emulator or credentials."""

import unittest
from unittest.mock import patch

from android_acceptance import crash_dialog_button, app_process_id, sdk_report_ready, error, quiet, sampled_traceparent, session_ready, timeline_events


class AcceptanceOracleTest(unittest.TestCase):
    def test_only_the_android_system_crash_button_is_acknowledged(self):
        xml = '<hierarchy><node package="io.fuseone.fusescope.sample" resource-id="android:id/button1" bounds="[0,0][20,20]"/><node package="android" resource-id="android:id/button1" bounds="[100,200][300,400]"/></hierarchy>'
        self.assertEqual((200, 300), crash_dialog_button(xml))
        self.assertIsNone(crash_dialog_button('<hierarchy><node package="app" text="OK" bounds="[0,0][20,20]"/></hierarchy>'))

    @patch("android_acceptance.get")
    def test_sdk_identity_waits_for_the_collector_report(self, get):
        get.side_effect = [
            {"sdk": {"name": "", "version": ""}},
            {"sdk": {"name": "io.fuseone.fusescope:android", "version": "0.1.0-beta.3"}},
            {"sdk": {"name": "io.fuseone.fusescope:android", "version": "1.0.0"}},
        ]
        self.assertIsNone(sdk_report_ready({}, "1.0.0"))
        self.assertIsNone(sdk_report_ready({}, "1.0.0"))
        self.assertEqual("1.0.0", sdk_report_ready({}, "1.0.0")["sdk"]["version"])

    @patch("android_acceptance.adb")
    def test_process_detection_works_with_marshmallow_and_modern_ps(self, adb):
        for output in (
            "USER PID PPID VSIZE RSS WCHAN PC NAME\nu0_a66 123 1 1024 512 fffff 0000 io.fuseone.fusescope.sample",
            "USER PID PPID VSZ RSS WCHAN ADDR S NAME\nu0_a66 456 1 1024 512 0 0 S io.fuseone.fusescope.sample",
        ):
            adb.return_value = output
            self.assertIn(app_process_id(), ('123', '456'))
        adb.return_value = "USER PID PPID NAME\nu0_a66 123 1 other.application"
        self.assertIsNone(app_process_id())

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

    @patch("android_acceptance.time.sleep")
    @patch("android_acceptance.time.monotonic", side_effect=[0, 0, 2])
    @patch("android_acceptance.get")
    def test_consent_quiet_uses_ingested_errors_when_report_is_delayed(self, get, _clock, _sleep):
        status = {"counts": {"errors": 0}, "firstSeenAt": None, "rejections": []}
        get.side_effect = lambda _config, route: status if route == "status" else {"groups": [{"events": 1}]}
        quiet({}, expected_errors=1, seconds=1)

    @patch("android_acceptance.time.sleep")
    @patch("android_acceptance.time.monotonic", side_effect=[0, 0])
    @patch("android_acceptance.get")
    def test_negative_consent_cannot_pass_on_a_stale_empty_report(self, get, _clock, _sleep):
        status = {"counts": {"errors": 0}, "firstSeenAt": None, "rejections": []}
        get.side_effect = lambda _config, route: status if route == "status" else {"groups": [{"events": 1}]}
        with self.assertRaisesRegex(AssertionError, "telemetry escaped"):
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
