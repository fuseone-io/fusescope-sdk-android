"""Build a Maven consumer and verify native recovery against a real FuseScope."""

import argparse
import json
import pathlib
import subprocess
import time
import urllib.parse
import urllib.request

from native_smoke import PACKAGE, adb, launch, wait_for


def get(config: dict, route: str, source: str | None = None) -> dict:
    params = {"start": int(time.time() * 1000) - 3600000, "end": int(time.time() * 1000)}
    if source:
        params["source"] = source
    url = f"{config['endpoint']}/api/v1/rum/applications/{config['applicationId']}/{route}"
    request = urllib.request.Request(url + "?" + urllib.parse.urlencode(params), headers={"Authorization": f"Bearer {config['token']}"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)["data"]


def event(config: dict, source: str):
    groups = get(config, "errors", source).get("groups", [])
    if not groups:
        return None
    assert len(groups) == 1, "expected one error group for this source"
    group = groups[0]
    detail = get(config, "errors/" + group["fingerprint"], source)
    events = detail.get("latestEvents", [])
    if not events:
        return None
    return {"group": group, "event": events[0]}


def build(config: dict, version: str) -> None:
    endpoint = config["endpoint"].replace("localhost", "10.0.2.2").replace("127.0.0.1", "10.0.2.2")
    properties = [f"-Pfusescope.sample.sdkVersion={version}", f"-Pfusescope.sample.endpoint={endpoint}", f"-Pfusescope.sample.applicationId={config['applicationId']}", f"-Pfusescope.sample.key={config['key']}"]
    subprocess.run(["./gradlew", ":sample:assembleDebug", *properties], check=True)
    report = subprocess.check_output(["./gradlew", ":sample:dependencies", "--configuration", "debugRuntimeClasspath", *properties], text=True)
    assert f"io.fuseone.fusescope:android:{version}" in report
    assert "project :sdk" not in report, "acceptance must consume the published SDK"


def verify(config: dict, version: str, output: pathlib.Path) -> None:
    adb("install", "-r", "sample/build/outputs/apk/debug/sample-debug.apk")
    adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    launch("error")
    launch("flush")
    before = wait_for(lambda: event(config, "manual"), "initial session did not reach FuseScope", 90)
    pid = adb("shell", f"pidof {PACKAGE} || true")
    crash_time = time.time()
    launch("native")
    wait_for(lambda: adb("shell", f"pidof {PACKAGE} || true") != pid, "SIGABRT did not terminate the sample")
    time.sleep(3)
    launch()
    recovered = wait_for(lambda: event(config, "native"), "native crash did not reach FuseScope", 90)
    crash = recovered["event"]
    assert crash["type"] == "SIGABRT" and crash["source"] == "native"
    assert crash["frames"] and any("pc 0x" in frame["function"] for frame in crash["frames"])
    assert crash["sessionId"] == before["event"]["sessionId"]
    assert crash["release"] == before["event"]["release"]
    assert crash["missingSourceMap"] is None and crash["symbolicated"] is False
    assert recovered["group"]["events"] == 1
    status = get(config, "status")
    assert status["sdk"]["version"] == version, "gateway did not observe the published SDK version"
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    time.sleep(10)
    repeated = event(config, "native")
    assert repeated["group"]["events"] == 1 and repeated["event"]["id"] == crash["id"]
    output.mkdir(parents=True, exist_ok=True)
    (output / "native-event.json").write_text(json.dumps({"sdkVersion": version, "crashTriggeredAt": crash_time, **recovered}, indent=2))
    print("Published SDK: native SIGABRT grouped with frames, original session/release and no duplicate in FuseScope")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["build", "verify"])
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-version", required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.mode == "build":
        build(config, args.sdk_version)
    else:
        verify(config, args.sdk_version, args.config.parent / "evidence")


if __name__ == "__main__":
    main()
