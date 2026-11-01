"""Exercise actual Android native exit recovery against the local OTLP stub.

Requires an API 31+ emulator, the sample APK and opentelemetry-proto==1.39.1.
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import time

PACKAGE = "io.fuseone.fusescope.sample"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True).strip()


def launch(action: str | None = None) -> None:
    args = ["shell", "am", "start", "-n", ACTIVITY]
    if action:
        args += ["--es", "action", action]
    adb(*args)


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


def main() -> None:
    adb("install", "-r", "sample/build/outputs/apk/debug/sample-debug.apk")
    adb("shell", "pm", "clear", PACKAGE)
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
