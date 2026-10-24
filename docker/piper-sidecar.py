#!/usr/bin/env python3
"""T411：Piper 本地 TTS sidecar（127.0.0.1:5002）。

- GET /health：模型文件存在即 200 {"status":"ok","model":...}；否则 503。
- POST /synthesize {"text","voice","rate"} → 调 piper CLI，返回 audio/wav。
只服务单词本身；不读释义/例句。超时由调用方（Java 2s）负责，这里 10s 兜底。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PIPER_BIN = os.environ.get("PIPER_BIN", "piper")
PIPER_MODEL = os.environ.get("PIPER_MODEL", "/app/piper/en_US-amy-medium.onnx")
PIPER_PORT = int(os.environ.get("PIPER_PORT", "5002"))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            if os.path.isfile(PIPER_MODEL):
                self._json(200, {"status": "ok", "model": PIPER_MODEL})
            else:
                self._json(503, {"status": "down", "model": PIPER_MODEL})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/synthesize":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
            text = str(payload.get("text", "")).strip()
            if not text or len(text) > 64:
                self._json(400, {"error": "text is required (<=64 chars)"})
                return
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as output:
                output_path = output.name
            try:
                subprocess.run(
                    [PIPER_BIN, "--model", PIPER_MODEL, "--output_file", output_path],
                    input=text.encode("utf-8"), check=True, timeout=10,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
                with open(output_path, "rb") as audio:
                    data = audio.read()
            finally:
                os.unlink(output_path)
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as exc:  # 引擎缺失/模型缺失都按不可用返回，触发浏览器回落
            self._json(503, {"error": str(exc)})

    def _json(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[piper-sidecar] " + fmt % args + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PIPER_PORT), Handler).serve_forever()
