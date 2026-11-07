"""Exercise actual Android native exit recovery against the local OTLP stub.

Requires an API 31+ emulator, the sample APK and opentelemetry-proto==1.39.1.
"""

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.fuseone.fusescope.sample"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def adb(*args: str, timeout: int = 60) -> str:
    return subprocess.check_output(["adb", *args], text=True, timeout=timeout).strip()


def launch(action: str | None = None) -> None:
    args = ["shell", "am", "start", "-W", "--activity-single-top", "-n", ACTIVITY]
    if action:
        args += ["--es", "action", action]
    print(adb(*args), flush=True)


def records(path: pathlib.Path) -> list[dict]:
    result = []
    if not path.exists():
        return result
    for line in path.read_text().splitlines():
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue
        for resource in request["body"].get("resourceLogs", []):
            for scope in resource.get("scopeLogs", []):
                for log in scope.get("logRecords", []):
                    attrs = {a["key"]: a["value"].get("stringValue") for a in log.get("attributes", [])}
                    result.append({"log": log, "attrs": attrs})
    return result


def wait_for(check, message: str, seconds: int = 60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(1)
    raise AssertionError(message)


def wait_native_ack(exit_id: str) -> None:
    namespace = hashlib.sha256(PACKAGE.encode()).hexdigest()
    filename = f"shared_prefs/fusescope-native-{namespace}.xml"

    def acknowledged() -> bool:
        xml = adb("shell", "run-as", PACKAGE, "cat", filename)
        reported = ET.fromstring(xml).find("set[@name='reported']")
        return reported is not None and any(item.text == exit_id for item in reported)

    # Receipt at the gateway precedes the export callback and durable acknowledgement.
    wait_for(acknowledged, "SDK did not persist native export acknowledgement")


def main() -> None:
    adb("install", "-r", "sample/build/outputs/apk/debug/sample-debug.apk")
    adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    with tempfile.TemporaryDirectory(prefix="fusescope-native-") as directory:
        output = pathlib.Path(directory)
        stub = subprocess.Popen(
            [sys.executable, "scripts/stub_gateway.py", "--host", "0.0.0.0", "--out", directory],
        )
        try:
            time.sleep(1)
            launch("error")
            launch("flush")
            logs = output / "logs.jsonl"
            before = wait_for(
                lambda: next((r for r in records(logs) if r["attrs"].get("fusescope.rum.error.source") == "manual"), None),
                "sample did not export the initial session",
            )
            original_pid = adb("shell", f"pidof {PACKAGE} || true")
            launch("native")
            wait_for(
                lambda: adb("shell", f"pidof {PACKAGE} || true") != original_pid,
                "sample process did not exit after SIGABRT",
            )
            time.sleep(3)
            launch()
            native = wait_for(
                lambda: next((r for r in records(logs) if r["attrs"].get("fusescope.rum.error.source") == "native"), None),
                "native exit was not recovered",
            )
            attrs = native["attrs"]
            assert attrs["exception.type"] == "SIGABRT", native
            assert "#00 pc " in attrs["exception.stacktrace"], native
            assert attrs["session.id"] == before["attrs"]["session.id"], native
            assert "user.id" not in attrs, native
            assert not native["log"].get("traceId"), native
            exit_id = attrs["fusescope.rum.native.exit_id"]
            wait_native_ack(exit_id)
            adb("shell", "am", "force-stop", PACKAGE)
            launch()
            time.sleep(8)
            matching = [r for r in records(logs) if r["attrs"].get("fusescope.rum.native.exit_id") == exit_id]
            assert len(matching) == 1, matching
            print("Native SIGABRT recovered with frames, original session and no duplicate")
        except Exception:
            print(adb("logcat", "-d", "-t", "300"), file=sys.stderr)
            print(adb("shell", "dumpsys", "activity", "exit-info", PACKAGE), file=sys.stderr)
            raise
        finally:
            stub.terminate()
            stub.wait(timeout=10)


if __name__ == "__main__":
    main()
