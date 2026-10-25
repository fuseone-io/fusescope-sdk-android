"""Stand-in for the FuseScope RUM gateway, for local smoke tests. Never forwards anything.

    uv run --with opentelemetry-proto==1.39.1 scripts/stub_gateway.py --port 4399 --out build/stub

Writes every OTLP request as JSON lines to <out>/traces.jsonl and <out>/logs.jsonl, mapping uploads to
<out>/mappings/, and calls to /api/* (with their traceparent header) to <out>/api.jsonl.
"""

import argparse
import gzip
import json
import pathlib
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from google.protobuf.json_format import MessageToDict
from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import ExportLogsServiceRequest
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

KEY_HEADER = "FuseScope-RUM-Key"
MAPPINGS = re.compile(r"^/api/v1/rum/applications/([^/]+)/mappings$")


def make_handler(out: pathlib.Path):
    def append(name: str, record: dict) -> None:
        with (out / name).open("a") as f:
            f.write(json.dumps(record) + "\n")

    class Handler(BaseHTTPRequestHandler):
        def body(self) -> bytes:
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                raw = b""
                while size := int(self.rfile.readline().split(b";")[0], 16):
                    raw += self.rfile.read(size)
                    self.rfile.readline()
                self.rfile.readline()
            else:
                raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            return gzip.decompress(raw) if self.headers.get("Content-Encoding") == "gzip" else raw

        def reply(self, status: int, payload: bytes = b"{}") -> None:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self) -> None:
            path = self.path.split("?")[0]
            if path in ("/rum/v1/traces", "/rum/v1/logs"):
                message = ExportTraceServiceRequest() if path.endswith("traces") else ExportLogsServiceRequest()
                data = self.body()
                if "json" in self.headers.get("Content-Type", ""):
                    decoded = json.loads(data)
                else:
                    message.ParseFromString(data)
                    decoded = MessageToDict(message)
                record = {"key": self.headers.get(KEY_HEADER), "bytes": len(data), "body": decoded}
                append(path.rsplit("/", 1)[1] + ".jsonl", record)
                return self.reply(200)
            match = MAPPINGS.match(path)
            if match:
                target = out / "mappings" / match.group(1)
                target.mkdir(parents=True, exist_ok=True)
                (target / "request.txt").write_text(f"{self.path}\nSigNoz-Api-Key: {self.headers.get('SigNoz-Api-Key')}\n")
                (target / "body.multipart").write_bytes(self.body())
                return self.reply(201)
            if path.startswith("/api/"):
                return self.do_GET()
            self.reply(404)

        def do_GET(self) -> None:
            if self.path.startswith("/api/"):
                append("api.jsonl", {"path": self.path, "traceparent": self.headers.get("traceparent")})
                return self.reply(200, b'{"pong":true}')
            self.reply(404)

        def log_message(self, fmt: str, *args) -> None:
            print(fmt % args, flush=True)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=4399)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("build/stub"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    print(f"stub gateway on http://{args.host}:{args.port}, writing to {args.out}", flush=True)
    ThreadingHTTPServer((args.host, args.port), make_handler(args.out)).serve_forever()


if __name__ == "__main__":
    main()
