"""Exercise a Maven Android consumer against the real FuseScope/collector test stack."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from native_acceptance import get
from native_smoke import PACKAGE, adb, launch, wait_for


def build(config: dict, version: str, directory: pathlib.Path) -> None:
    apks = directory / "apks"
    apks.mkdir(exist_ok=True)
    debug = pathlib.Path("sample/build/outputs/apk/debug/sample-debug.apk")
    shutil.copyfile(debug, apks / "native.apk")
    try:
        for name, scenario in config["scenarios"].items():
            endpoint = scenario["endpoint"].replace("localhost", "10.0.2.2").replace("127.0.0.1", "10.0.2.2")
            properties = [
                f"-Pfusescope.sample.sdkVersion={version}", f"-Pfusescope.sample.endpoint={endpoint}",
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
            assert f"io.fuseone.fusescope:android:{version}" in report
            assert f"io.fuseone.fusescope:android-compose:{version}" in report
            assert "project :sdk" not in report, "must consume published artifacts"
            apk = pathlib.Path(f"sample/build/outputs/apk/{variant.lower()}/sample-{variant.lower()}.apk")
            shutil.copyfile(apk, apks / f"{name}.apk")
            if name == "full":
                mapping = pathlib.Path("sample/build/outputs/mapping/release/mapping.txt")
                text = mapping.read_text()
                match = re.search(r"^# pg_map_id: (\S+)", text, re.MULTILINE)
                assert match, "actual R8 build must have its own map ID"
                (directory / "evidence" / "r8-build.json").write_text(json.dumps({
                    "mapId": match[1], "sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
                    "sdkVersion": version, "consumer": "Maven Central SDK and Compose; source Gradle uploader",
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


def session_ready(config: dict, session_id: str):
    session = get(config, "sessions/" + session_id)
    routes = {view["route"] for view in session["timeline"]}
    if "orders/{id}" in routes and any("HomeFragment" in r for r in routes) and any("DetailsFragment" in r for r in routes) and session["requests"] >= 1 and session["vitals"] >= 1:
        return session
    return None


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
        assert status["counts"]["errors"] == expected_errors, "telemetry escaped the consent/sampling gate"
        if expected_errors == 0:
            assert status["firstSeenAt"] is None, "no telemetry may be ingested before consent or for zero sampling"
        assert not status["rejections"], "invalid configuration/rejected ingest must not masquerade as collection being disabled"
        time.sleep(2)


def verify_full(config: dict, directory: pathlib.Path, calls: list, version: str) -> dict:
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
    session = wait_for(lambda: session_ready(config, session_id), "views/request/app-start did not reach the session timeline", 90)
    assert session["userId"] == hashlib.sha256(b"sample-user").hexdigest()
    requests = [item["request"] for item in timeline_events(session) if item["kind"] == "request"]
    assert any(item["traceId"] == traceparent.split("-")[1] and item["spanId"] == traceparent.split("-")[2] for item in requests)
    assert all("secret" not in item["url"] and "?q=" not in item["url"] for item in requests)
    adb("shell", "am", "force-stop", PACKAGE)
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
    assert anr["latestEvents"][0]["type"] == "ANR"
    assert anr["latestEvents"][0]["frames"]
    assert anr["latestEvents"][0]["symbolicated"] and anr["latestEvents"][0]["missingSourceMap"] is None
    pid = adb("shell", f"pidof {PACKAGE} || true")
    launch("crash")
    wait_for(lambda: adb("shell", f"pidof {PACKAGE} || true") != pid, "uncaught Java exception did not terminate the app")
    crashed = wait_for(lambda: error(config, "crash"), "uncaught Java crash did not reach FuseScope", 90)
    event = crashed["latestEvents"][0]
    assert event["symbolicated"] and event["missingSourceMap"] is None
    assert any("Checkout.pay" in (frame.get("original") or {}).get("function", "") for frame in event["frames"])
    assert event["sessionId"] == session_id and event["release"] == first["release"]
    assert get(config, "status")["sdk"]["version"] == version
    (directory / "evidence" / "java-crash.json").write_text(json.dumps(crashed, indent=2))
    (directory / "evidence" / "anr.json").write_text(json.dumps(anr, indent=2))
    (directory / "evidence" / "session.json").write_text(json.dumps(session, indent=2))
    return {"sessionContinuity": True, "views": [v["route"] for v in session["timeline"]],
            "traceparent": traceparent, "appStart": True, "hashedUser": True, "queryScrubbed": True, "traceparentMatchesRequestSpan": True,
            "javaCrashSymbolicated": True, "anrSymbolicated": True}


def verify_consent(config: dict, directory: pathlib.Path, calls: list) -> dict:
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
    return {"noIngestBeforeConsent": True, "noErrorsAfterWithdrawal": True,
            "resumedAfterGrant": True, "emptyHostListDoesNotPropagate": True, "observedErrors": resumed["events"]}


def verify_unsampled(config: dict, directory: pathlib.Path, calls: list) -> dict:
    install(directory, "unsampled")
    launch("error")
    launch("details")
    assert request(calls) is None
    launch("flush")
    quiet(config)
    return {"sampleRate": 0, "noIngest": True, "noTraceparent": True}


def verify(config: dict, directory: pathlib.Path, version: str) -> None:
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
    evidence = {"sdkVersion": version, "backend": "published image pinned by digest", "scenarios": {}}
    output = directory / "evidence" / "android-acceptance.json"
    try:
        for name, check in (("full", verify_full), ("consent", verify_consent), ("unsampled", verify_unsampled)):
            print(f"Real-backend Android acceptance: {name}", flush=True)
            args = (config["scenarios"][name], directory, calls)
            evidence["scenarios"][name] = check(*args, version) if name == "full" else check(*args)
            output.write_text(json.dumps(evidence, indent=2))
            print(f"Accepted: {name}", flush=True)
    except Exception:
        for name, scenario in config["scenarios"].items():
            for route in ("status", "sessions", "errors"):
                try:
                    data = get(scenario, route)
                    (directory / "evidence" / f"observed-{name}-{route}.json").write_text(json.dumps(data, indent=2))
                except Exception:
                    pass
        raise
    finally:
        server.shutdown()
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["build", "verify"])
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-version", required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.mode == "build":
        build(config, args.sdk_version, args.config.parent)
    else:
        verify(config, args.config.parent, args.sdk_version)


if __name__ == "__main__":
    main()
