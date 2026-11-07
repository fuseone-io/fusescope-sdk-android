"""Exercise a Maven Android consumer against the real FuseScope/collector test stack."""

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
import time
import traceback
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from acceptance_transport import emulator_endpoint, sample_runtime_properties, sample_sdk_properties, sample_tls, verify_sdk_origin
from native_acceptance import get
from native_smoke import PACKAGE, adb, launch, wait_for


def build(config: dict, version: str, directory: pathlib.Path) -> None:
    with sample_tls(config):
        _build(config, version, directory)


def _build(config: dict, version: str, directory: pathlib.Path) -> None:
    apks = directory / "apks"
    apks.mkdir(exist_ok=True)
    debug = pathlib.Path("sample/build/outputs/apk/debug/sample-debug.apk")
    shutil.copyfile(debug, apks / "native.apk")
    try:
        for name, scenario in config["scenarios"].items():
            endpoint = emulator_endpoint(scenario)
            properties = [
                *sample_sdk_properties(version), *sample_runtime_properties(), f"-Pfusescope.sample.endpoint={endpoint}",
                f"-Pfusescope.sample.mappingEndpoint={scenario['endpoint']}",
                f"-Pfusescope.sample.applicationId={scenario['applicationId']}", f"-Pfusescope.sample.key={scenario['key']}",
                f"-Pfusescope.sample.requireConsent={str(name == 'consent').lower()}",
                f"-Pfusescope.sample.sampleRate={0.0 if name == 'unsampled' else 1.0}",
                f"-Pfusescope.sample.propagate={str(name != 'consent').lower()}",
                f"-Pfusescope.sample.uploadMapping={str(name == 'full').lower()}",
            ]
            variant = "Release" if name == "full" else "Debug"
            environment = os.environ.copy()
            if name == "full":
                environment["FUSESCOPE_API_KEY"] = scenario["editorKey"]
            subprocess.run(["./gradlew", f":sample:assemble{variant}", *properties], env=environment, check=True)
            report = subprocess.check_output(
                ["./gradlew", ":sample:dependencies", "--configuration", variant.lower() + "RuntimeClasspath", *properties],
                text=True,
            )
            (directory / "evidence" / f"{name}-sdk-dependencies.txt").write_text(report)
            verify_sdk_origin(report, version, compose=True)
            apk = pathlib.Path(f"sample/build/outputs/apk/{variant.lower()}/sample-{variant.lower()}.apk")
            shutil.copyfile(apk, apks / f"{name}.apk")
            if name == "full":
                mapping = pathlib.Path("sample/build/outputs/mapping/release/mapping.txt")
                text = mapping.read_text()
                match = re.search(r"^# pg_map_id: (\S+)", text, re.MULTILINE)
                assert match, "actual R8 build must have its own map ID"
                (directory / "evidence" / "r8-build.json").write_text(json.dumps({
                    "mapId": match[1], "sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
                    "sdkVersion": version, "consumer": "source SDK/Compose and Gradle uploader" if os.environ.get("SDK_SOURCE") == "true" else "Maven Central SDK and Compose; source Gradle uploader",
                }, indent=2))
    finally:
        shutil.copyfile(apks / "native.apk", debug)


def error(config: dict, source: str):
    groups = [group for group in get(config, "errors")["groups"] if group["source"] == source]
    if not groups:
        return None
    assert len(groups) == 1, f"expected one {source} error group, found {len(groups)}"
    detail = get(config, "errors/" + groups[0]["fingerprint"])
    return detail if detail["latestEvents"] else None


def timeline_events(session: dict) -> list[dict]:
    return session["leadingEvents"] + [event for view in session["timeline"] for event in view["events"]]


def session_ready(config: dict, session_id: str, require_app_start: bool = False):
    session = get(config, "sessions/" + session_id)
    routes = {view["route"] for view in session["timeline"]}
    if require_app_start and (session["vitals"] < 1 or not any(event["kind"] == "vital" and event.get("vital", {}).get("name") == "app_start" for event in timeline_events(session))):
        return None
    if "orders/{id}" in routes and any("HomeFragment" in r for r in routes) and any("DetailsFragment" in r for r in routes) and session["requests"] >= 1:
        return session
    return None


def app_start(config: dict, session_id: str):
    store = config["clickhouse"]
    params = urllib.parse.urlencode({"param_application": config["applicationId"], "param_session": session_id})
    query = """SELECT attributes_string['fusescope.rum.vital.name'] AS name,
        attributes_number['fusescope.rum.vital.value'] AS value,
        attributes_string['fusescope.rum.vital.unit'] AS unit,
        attributes_string['fusescope.rum.vital.rating'] AS rating,
        attributes_string['session.id'] AS sessionId,
        resources_string['service.version'] AS release
        FROM signoz_logs.distributed_logs_v2
        WHERE resources_string['fusescope.rum.application.id'] = {application:String}
          AND attributes_string['session.id'] = {session:String}
          AND attributes_string['event.name'] = 'rum.vital'
          AND attributes_string['fusescope.rum.vital.name'] = 'app_start'
        LIMIT 20 FORMAT JSON"""
    authorization = base64.b64encode(f"{store['user']}:{store['password']}".encode()).decode()
    request = urllib.request.Request(store["endpoint"] + "?" + params, data=query.encode(), headers={"Authorization": "Basic " + authorization})
    with urllib.request.urlopen(request, timeout=15) as response:
        rows = json.load(response)["data"]
    return rows if rows else None


def install(directory: pathlib.Path, name: str) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    adb("install", "-r", str(directory / "apks" / f"{name}.apk"))
    adb("shell", "pm", "clear", PACKAGE)


def sampled_traceparent(header: str | None) -> bool:
    match = re.fullmatch(r"00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})", header or "")
    return bool(match and int(match[1], 16) and int(match[2], 16) and int(match[3], 16) & 1)


def request(calls: list) -> str | None:
    before = len(calls)
    launch("request")
    wait_for(lambda: len(calls) > before, "sample HTTP request never reached the API", 30)
    return calls[-1]


def quiet(config: dict, expected_errors: int = 0, seconds: int = 30) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        status = get(config, "status")
        # Collector setup reports arrive asynchronously; query actual ingested data too.
        errors = sum(group["events"] for group in get(config, "errors").get("groups", []))
        assert errors == expected_errors, "telemetry escaped the consent/sampling gate"
        assert status["counts"]["errors"] <= expected_errors, "telemetry escaped the consent/sampling gate"
        if expected_errors == 0:
            assert not get(config, "sessions").get("sessions", []), "no telemetry may be ingested before consent or for zero sampling"
            assert status["firstSeenAt"] is None, "no telemetry may be ingested before consent or for zero sampling"
        assert not status["rejections"], "invalid configuration/rejected ingest must not masquerade as collection being disabled"
        time.sleep(2)


def crash_dialog_button(xml):
    for node in ET.fromstring(xml).iter("node"):
        if node.get("package") != "android" or node.get("resource-id") != "android:id/button1":
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def acknowledge_crash_dialog(directory):
    point = None
    try:
        adb("shell", "uiautomator", "dump", "/sdcard/fusescope-crash.xml", timeout=15)
        xml = adb("shell", "cat", "/sdcard/fusescope-crash.xml", timeout=5)
        (directory / "evidence" / "java-crash-dialog.xml").write_text(xml)
        point = crash_dialog_button(xml)
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError, ET.ParseError):
        pass
    if point:
        x, y = point
        adb("shell", "input", "tap", str(x), str(y))
    else:
        # Android 6 can hold UI automation while its crash dialog is open.
        # The caller has already required the real backend crash; Enter is the
        # normal user acknowledgement, and process termination is still checked.
        time.sleep(2)
        adb("shell", "input", "keyevent", "KEYCODE_ENTER")


def sdk_report_ready(config, version):
    status = get(config, "status")
    sdk = status.get("sdk", {})
    return status if sdk.get("name") == "io.fuseone.fusescope:android" and sdk.get("version") == version else None


def app_process_id():
    # Android 6 does not consistently ship pidof; ps is available on every tested API.
    rows = [line.split() for line in adb("shell", "ps").splitlines() if line.strip()]
    if not rows or "PID" not in rows[0]:
        raise AssertionError("device ps output has no PID column")
    pid_column = rows[0].index("PID")
    return next((row[pid_column] for row in rows[1:] if row[-1] == PACKAGE), None)


def verify_full(config: dict, directory: pathlib.Path, calls: list, version: str, require_app_start: bool = False) -> dict:
    install(directory, "full")
    launch()
    time.sleep(2)
    launch("details")
    time.sleep(2)
    launch("compose")
    time.sleep(3)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    launch("error")
    traceparent = request(calls)
    assert sampled_traceparent(traceparent), f"sampled request did not propagate a valid traceparent: {traceparent!r}"
    launch("flush")
    handled = wait_for(lambda: error(config, "manual"), "handled error did not reach real FuseScope", 90)
    first = handled["latestEvents"][0]
    assert "secret" not in first["message"] and "token=" not in first["stacktrace"]
    session_id = first["sessionId"]
    session = wait_for(lambda: session_ready(config, session_id, require_app_start), "views/request did not reach the session timeline", 90)
    (directory / "evidence" / "session.json").write_text(json.dumps(session, indent=2))
    starts = wait_for(lambda: app_start(config, session_id), "actual app_start vital was not stored by the collector", 90)
    assert all(row["name"] == "app_start" and row["unit"] == "ms" and row["value"] >= 0 for row in starts)
    assert all(row["rating"] in ("good", "needs-improvement", "poor") and row["release"] == first["release"] for row in starts)
    (directory / "evidence" / "app-start.json").write_text(json.dumps(starts, indent=2))
    app_starts = [event["vital"] for event in timeline_events(session) if event["kind"] == "vital" and event.get("vital", {}).get("name") == "app_start"]
    if require_app_start:
        assert app_starts and session["vitals"] >= len(app_starts)
        assert all(any(vital["value"] == stored["value"] and vital["rating"] == stored["rating"] for stored in starts) for vital in app_starts)
    with (directory / ".env.local").open("a") as environment:
        environment.write(f"FULL_SESSION_ID={session_id}\n")
    assert session["userId"] == hashlib.sha256(b"sample-user").hexdigest()
    requests = [item["request"] for item in timeline_events(session) if item["kind"] == "request"]
    assert any(item["traceId"] == traceparent.split("-")[1] and item["spanId"] == traceparent.split("-")[2] for item in requests)
    assert all("secret" not in item["url"] and "?q=" not in item["url"] for item in requests)
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    launch("error")
    launch("flush")

    def resumed():
        detail = error(config, "manual")
        return detail if detail and detail["events"] >= 2 and detail["latestEvents"][0]["id"] != first["id"] else None

    continued = wait_for(resumed, "relaunch error did not arrive", 90)
    assert continued["latestEvents"][0]["sessionId"] == session_id, "relaunch must continue the persisted session"
    launch("anr")
    launch("flush")
    anr = wait_for(lambda: error(config, "anr"), "real blocked-main-thread ANR did not reach FuseScope", 90)
    (directory / "evidence" / "anr.json").write_text(json.dumps(anr, indent=2))
    assert anr["latestEvents"][0]["type"] == "ANR"
    assert anr["latestEvents"][0]["frames"]
    assert anr["latestEvents"][0]["symbolicated"] and anr["latestEvents"][0]["missingSourceMap"] is None
    # Old Android can keep its system ANR dialog over the activity even after
    # the app's main thread resumes. Start a fresh process before the Java crash;
    # persisted session continuity is still required below.
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    pid = app_process_id()
    assert pid is not None, "sample was not running before the Java crash"
    launch("crash")
    crashed = wait_for(lambda: error(config, "crash"), "uncaught Java crash did not reach FuseScope", 90)
    if os.environ.get("ANDROID_API") == "23" and app_process_id() == pid:
        # Marshmallow can hold the crashed process while the system's OK dialog
        # awaits a user. The real backend crash above must exist before closing it.
        acknowledge_crash_dialog(directory)
    wait_for(lambda: app_process_id() != pid, "uncaught Java exception did not terminate the app")
    (directory / "evidence" / "java-crash.json").write_text(json.dumps(crashed, indent=2))
    event = crashed["latestEvents"][0]
    assert event["symbolicated"] and event["missingSourceMap"] is None
    assert any("Checkout.pay" in (frame.get("original") or {}).get("function", "") for frame in event["frames"])
    assert event["sessionId"] == session_id and event["release"] == first["release"]
    report = wait_for(lambda: sdk_report_ready(config, version), "collector SDK identity report did not arrive", 120)
    (directory / "evidence" / "sdk-status.json").write_text(json.dumps(report, indent=2))
    (directory / "evidence" / "java-crash.json").write_text(json.dumps(crashed, indent=2))
    (directory / "evidence" / "anr.json").write_text(json.dumps(anr, indent=2))
    (directory / "evidence" / "session.json").write_text(json.dumps(session, indent=2))
    return {"sessionContinuity": True, "views": [v["route"] for v in session["timeline"]],
            "traceparent": traceparent, "appStartStored": True, "appStartInSessionTimeline": bool(app_starts), "hashedUser": True, "queryScrubbed": True, "traceparentMatchesRequestSpan": True,
            "javaCrashSymbolicated": True, "anrSymbolicated": True}


def verify_consent(config: dict, directory: pathlib.Path, calls: list, cycles: int = 1) -> dict:
    install(directory, "consent")
    launch("error")
    assert request(calls) is None
    launch("flush")
    quiet(config)
    launch("consent")
    launch("error")
    assert request(calls) is None, "explicit empty host list must disable propagation even after consent"
    launch("flush")
    granted = wait_for(lambda: error(config, "manual"), "grant did not start collection", 90)
    assert granted["events"] == 1
    # Revocation flushes previously collected data; wait before exercising the denied state.
    launch("revoke")
    time.sleep(12)
    launch("error")
    assert request(calls) is None
    launch("flush")
    quiet(config, expected_errors=1)
    launch("consent")
    launch("error")
    launch("flush")
    resumed = wait_for(lambda: (detail if (detail := error(config, "manual")) and detail["events"] == 2 else None), "second grant did not resume collection", 90)
    assert len({e["sessionId"] for e in resumed["latestEvents"]}) == 1
    observed_errors = 2
    for _cycle in range(1, cycles):
        launch("revoke")
        time.sleep(12)
        launch("error")
        launch("flush")
        quiet(config, expected_errors=observed_errors)
        launch("consent")
        launch("error")
        launch("flush")
        observed_errors += 1
        wait_for(lambda: (detail if (detail := error(config, "manual")) and detail["events"] == observed_errors else None), "consent stress cycle did not resume collection", 90)
    return {"noIngestBeforeConsent": True, "noErrorsAfterWithdrawal": True,
            "resumedAfterGrant": True, "emptyHostListDoesNotPropagate": True, "observedErrors": observed_errors, "consentCycles": cycles}


def verify_unsampled(config: dict, directory: pathlib.Path, calls: list) -> dict:
    install(directory, "unsampled")
    launch("error")
    launch("details")
    assert request(calls) is None
    launch("flush")
    quiet(config)
    xml = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/io.fuseone.fusescope.android.xml")
    persisted = ET.fromstring(xml).findtext("string[@name='session']", "").split("|")
    assert len(persisted) == 6 and persisted[-1] == "false", "confirm the SDK initialized and persisted an unsampled session"
    return {"sampleRate": 0, "noIngest": True, "noTraceparent": True, "unsampledDecisionPersisted": True}


def verify(config: dict, directory: pathlib.Path, version: str, require_app_start: bool = False, scenarios: str = "all") -> None:
    calls = []

    class API(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append(self.headers.get("traceparent"))
            self.send_response(200)
            self.send_header("Content-Length", "13")
            self.end_headers()
            self.wfile.write(b'{"pong":true}')

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("0.0.0.0", 4399), API)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    evidence = {"sdkOrigin": "source" if os.environ.get("SDK_SOURCE") == "true" else "Maven Central", "sdkVersion": version, "backend": os.environ.get("BACKEND_IMAGE", "see versions.txt"), "requiresAppStartTimeline": require_app_start, "scenarios": {}}
    output = directory / "evidence" / "android-acceptance.json"
    failures = []
    try:
        for name, check in (("full", verify_full), ("consent", verify_consent), ("unsampled", verify_unsampled)):
            if scenarios != "all" and name != scenarios:
                continue
            print(f"Real-backend Android acceptance: {name}", flush=True)
            args = (config["scenarios"][name], directory, calls)
            try:
                if name == "full":
                    evidence["scenarios"][name] = check(*args, version, require_app_start)
                elif name == "consent":
                    evidence["scenarios"][name] = check(*args, cycles=int(os.environ.get("CONSENT_CYCLES", "1")))
                else:
                    evidence["scenarios"][name] = check(*args)
                print(f"Accepted: {name}", flush=True)
            except Exception as failure:
                failures.append(name)
                evidence["scenarios"][name] = {"passed": False, "error": str(failure)}
                print(f"Failed: {name}: {failure}", flush=True)
                print(traceback.format_exc(), flush=True)
                for route in ("status", "sessions", "errors"):
                    try:
                        data = get(config["scenarios"][name], route)
                        (directory / "evidence" / f"observed-{name}-{route}.json").write_text(json.dumps(data, indent=2))
                        if route == "errors":
                            for group in data.get("groups", [])[:10]:
                                detail = get(config["scenarios"][name], "errors/" + group["fingerprint"], group["source"])
                                (directory / "evidence" / f"observed-{name}-{group['source']}-detail.json").write_text(json.dumps(detail, indent=2))
                    except Exception:
                        pass
            output.write_text(json.dumps(evidence, indent=2))
        assert not failures, f"Android acceptance failed: {', '.join(failures)}"
    finally:
        server.shutdown()
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["build", "verify"])
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-version", required=True)
    parser.add_argument("--require-app-start-timeline", action="store_true")
    parser.add_argument("--scenarios", choices=["all", "consent"], default="all")
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.mode == "build":
        build(config, args.sdk_version, args.config.parent)
    else:
        verify(config, args.config.parent, args.sdk_version, args.require_app_start_timeline, args.scenarios)


if __name__ == "__main__":
    main()
