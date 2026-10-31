#!/usr/bin/env python3
"""T411：Piper 本地 TTS sidecar（127.0.0.1:5002）。

- GET /health：模型文件存在即 200 {"status":"ok","model":...}；否则 503。
- POST /synthesize {"text","voice","rate"} → 调 piper CLI，返回 audio/wav。
  按 voice 在 PIPER_MODEL_DIR 找 <voice>.onnx；找不到就 503，让前端回落浏览器
  （T427：英音 en_GB-alan-medium / 美音 en_US-amy-medium）。
只服务单词本身；不读释义/例句。超时由调用方（Java 2s）负责，这里 10s 兜底。
"""
from __future__ import annotations

import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PIPER_BIN = os.environ.get("PIPER_BIN", "piper")
PIPER_MODEL = os.environ.get("PIPER_MODEL", "/app/piper/en_US-amy-medium.onnx")
PIPER_MODEL_DIR = os.environ.get("PIPER_MODEL_DIR", "/app/piper")
PIPER_DEFAULT_VOICE = os.environ.get("PIPER_DEFAULT_VOICE", "en_US-amy-medium")
PIPER_PORT = int(os.environ.get("PIPER_PORT", "5002"))

# T438：不同版本的 piper CLI 参数名不一样（旧版 --length_scale，新版可能 --length-scale）。
# 启动时探一次 --help，按实际支持的写法传语速，避免某个版本直接 503。
try:
    _HELP = subprocess.run([PIPER_BIN, "--help"], capture_output=True, text=True, timeout=10).stdout
except Exception:  # 引擎未装好时按不支持处理，等真调用再回落
    _HELP = ""
LENGTH_SCALE_FLAG = (
    "--length_scale" if "--length_scale" in _HELP
    else "--length-scale" if "--length-scale" in _HELP
    else None
)


def model_for(voice: str) -> str | None:
    """T427：一个 voice 一个模型文件；默认音色仍尊重 PIPER_MODEL 覆盖。"""
    if voice == PIPER_DEFAULT_VOICE and os.path.isfile(PIPER_MODEL):
        return PIPER_MODEL
    candidate = os.path.join(PIPER_MODEL_DIR, voice + ".onnx")
    return candidate if os.path.isfile(candidate) else None


def piper_available() -> bool:
    """piper 可执行是否真的在（dev 走 venv 的绝对路径，容器走 PATH 里的 piper）。"""
    if os.path.sep in PIPER_BIN:
        return os.path.isfile(PIPER_BIN) and os.access(PIPER_BIN, os.X_OK)
    return shutil.which(PIPER_BIN) is not None


TAIL_FADE_SECONDS = 0.008


def soften_tail(wav: bytes) -> bytes:
    """PCM WAV 结尾做 8ms 线性淡出（T410 的「尾音杂音」在 2026-10-31 的兜底）。

    Piper 默认会在句尾留约 80ms 静音，此时这里是空操作；只有引擎/参数把波形硬切断
    （最后 8ms 还有明显振幅）时才淡出，避免播放器在末尾补一声「咔哒」。
    只处理 16-bit 单声道 PCM（piper 的输出格式），其它格式原样返回。
    """
    if len(wav) < 44 or wav[:4] != b"RIFF" or wav[8:12] != b"WAVE":
        return wav
    channels = struct.unpack_from("<H", wav, 22)[0]
    sample_rate = struct.unpack_from("<I", wav, 24)[0]
    bits = struct.unpack_from("<H", wav, 34)[0]
    data_offset = wav.find(b"data")
    if channels != 1 or bits != 16 or sample_rate <= 0 or data_offset < 0:
        return wav
    payload = bytearray(wav[data_offset + 8:])
    count = len(payload) // 2
    if count == 0:
        return wav
    samples = list(struct.unpack_from("<%dh" % count, payload, 0))
    window = min(count, max(1, int(sample_rate * TAIL_FADE_SECONDS)))
    if max(abs(value) for value in samples[-window:]) <= 2:
        return wav  # 已经是静音尾巴
    for index in range(window):
        factor = 1.0 - (index + 1) / window
        position = count - window + index
        samples[position] = int(samples[position] * factor)
    struct.pack_into("<%dh" % count, payload, 0, *samples)
    return wav[:data_offset + 8] + bytes(payload)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            if not piper_available():
                self._json(503, {"status": "down", "reason": f"piper not found: {PIPER_BIN}"})
            elif os.path.isfile(PIPER_MODEL):
                self._json(200, {"status": "ok", "model": PIPER_MODEL, "piper": PIPER_BIN})
            else:
                self._json(503, {"status": "down", "reason": f"model missing: {PIPER_MODEL}"})
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
            voice = str(payload.get("voice") or PIPER_DEFAULT_VOICE)
            model = model_for(voice)
            if model is None:
                self._json(503, {"error": f"voice model missing: {voice}"})
                return
            # T438：rate<1 表示放慢；piper 用 length_scale（越大越慢）
            try:
                rate = float(payload.get("rate") or 1.0)
            except (TypeError, ValueError):
                rate = 1.0
            rate = min(2.0, max(0.5, rate))
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as output:
                output_path = output.name
            command = [PIPER_BIN, "--model", model, "--output_file", output_path]
            if LENGTH_SCALE_FLAG:
                command += [LENGTH_SCALE_FLAG, f"{1.0 / rate:.3f}"]
            try:
                subprocess.run(
                    command,
                    input=text.encode("utf-8"), check=True, timeout=10,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
                with open(output_path, "rb") as audio:
                    data = audio.read()
            finally:
                os.unlink(output_path)
            data = soften_tail(data)
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
