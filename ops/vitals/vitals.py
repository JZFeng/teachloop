#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TeachLoop prod 生命体征看板（V1，只读）。

只回答「现在活没活、还剩多少油」这几个生死问题：

  1. 应用活着？           公网 https://teachloop.org/healthz?deep=1（含 DB / PDF 字体）
  2. 公网入口通吗？        同一条请求，证明 Cloudflare Tunnel → nginx → app 整条链
  3. 四个容器在吗？        SSH 只读探针（prod-probe.sh 强制命令）读 docker ps / RestartCount
  4. 磁盘 / 内存 / CPU     同一探针读 df / free / /proc/stat
  5. 今日 5xx / 请求数     同一探针读 /app/logs/requests-*.jsonl
  6. 采集本身失联没？      SSH 探针多久没成功过一次

明确不做（V2）：错误与异常统计、历史曲线与留存、日志搜索、告警分级、多主机。
页面：/vitals/ （HTTP Basic），数据：/vitals/api，自检：/vitals/healthz。

这是独立服务，不 import 产品代码、不连数据库、不写 prod 任何东西。
"""

from __future__ import annotations

import argparse
import base64
import email.utils
import hmac
import json
import os
import random
import smtplib
import string
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PAGE_FILE = os.path.join(HERE, "page.html")

DEFAULTS = {
    "VITALS_PORT": "8099",
    "VITALS_USER": "teachloop",
    "VITALS_PASS": "",
    "PROD_SSH": "jzfeng@192.168.68.166",
    "PROD_SSH_KEY": "",
    "PROD_PROBE_CMD": "",       # 空 = 依赖 prod 侧 authorized_keys 的 command= 强制命令
    "PROD_URL": "https://teachloop.org",
    "SSH_INTERVAL": "5",
    "PUBLIC_INTERVAL": "10",
    "ALERT_ENABLED": "0",
    "ALERT_COOLDOWN": "1800",
    "SMTP_HOST": "smtp.163.com",
    "SMTP_PORT": "465",
    "SMTP_USER": "",
    "SMTP_PASS": "",
    "MAIL_FROM": "",
    "MAIL_TO": "",
    "DISK_WARN": "90",
    "MEM_WARN": "90",
    "HISTORY_POINTS": "60",
}

LOCK = threading.Lock()
STATE = {
    "started": time.time(),
    "public": None,      # 最近一次公网探针结果
    "public_at": 0.0,
    "facts": None,       # 最近一次 SSH 探针解析结果
    "facts_at": 0.0,
    "facts_ms": 0,
    "facts_err": "",
    "history": deque(maxlen=60),
    "active": {},        # 当前告警条件
    "alert_log": deque(maxlen=40),
    "mail_ok": None,
    "mail_err": "",
}


# ---------------------------------------------------------------- 配置

def load_config(path: str | None = None) -> dict:
    """环境变量 > config.env > 默认值。"""
    cfg = dict(DEFAULTS)
    path = path or os.environ.get("VITALS_CONFIG") or os.path.join(HERE, "config.env")
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, _, v = line.partition("=")
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    for k in DEFAULTS:
        if k in os.environ:
            cfg[k] = os.environ[k]
    return cfg


def num(cfg: dict, key: str, default: float) -> float:
    try:
        return float(cfg.get(key) or default)
    except (TypeError, ValueError):
        return default


# ---------------------------------------------------------------- 探针

def http_get(url: str, timeout: float = 10.0) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "teachloop-vitals/1"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read(8192).decode("utf-8", "replace")
            return {"ok": 200 <= resp.status < 300, "code": resp.status,
                    "ms": int((time.time() - t0) * 1000), "body": body}
    except urllib.error.HTTPError as exc:
        body = exc.read(8192).decode("utf-8", "replace")
        return {"ok": False, "code": exc.code,
                "ms": int((time.time() - t0) * 1000), "body": body}
    except Exception as exc:  # 网络 / DNS / TLS 一律当「不通」
        return {"ok": False, "code": 0,
                "ms": int((time.time() - t0) * 1000), "body": str(exc)[:200]}


def ssh_args(cfg: dict) -> list[str]:
    args = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=6",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "LogLevel=ERROR"]
    if cfg.get("PROD_SSH_KEY"):
        args += ["-i", cfg["PROD_SSH_KEY"]]
    args.append(cfg["PROD_SSH"])
    if cfg.get("PROD_PROBE_CMD"):
        args.append(cfg["PROD_PROBE_CMD"])
    return args


def parse_facts(text: str) -> dict:
    """解析 prod-probe.sh 的 KEY=VALUE，容错优先：坏行跳过，不抛异常。"""
    facts: dict = {"containers": [], "restarts": {}, "host": "", "cpu": None}
    for line in text.splitlines():
        key, sep, val = line.partition("=")
        if not sep:
            continue
        key, val = key.strip(), val.strip()
        if key == "CONTAINER":
            parts = val.split("|")
            if len(parts) >= 3:
                name, svc, status = parts[0], parts[1], "|".join(parts[2:])
                facts["containers"].append({
                    "name": name, "svc": svc, "status": status,
                    "up": status.startswith("Up"),
                    "healthy": "(healthy)" in status,
                    "health": "(unhealthy)" in status,
                })
        elif key == "RESTART":
            name, _, count = val.partition("|")
            facts["restarts"][name.lstrip("/")] = count or "0"
        elif key == "MEM":
            bits = val.split("|")
            if len(bits) >= 3:
                facts["mem"] = {"total": bits[0], "used": bits[1], "avail": bits[2]}
        elif key == "DISK":
            bits = val.split("|")
            if len(bits) >= 4:
                facts["disk"] = {"total": bits[0], "used": bits[1],
                                 "avail": bits[2], "pct": bits[3]}
        elif key == "LOAD":
            facts["load"] = val
        elif key == "UPTIME":
            try:
                facts["uptime_s"] = int(float(val))
            except ValueError:
                pass
        elif key in ("HOST", "REQS", "S5XX", "ERROR", "DAY"):
            facts[key.lower()] = val
        elif key == "CPU":
            try:
                facts["cpu"] = round(int(val) / 10.0, 1)
            except ValueError:
                pass
    for c in facts["containers"]:
        c["restarts"] = facts["restarts"].get(c["name"], "0")
    return facts


def poll_public(cfg: dict) -> None:
    url = cfg["PROD_URL"].rstrip("/") + "/healthz?deep=1"
    res = http_get(url)
    deep = {}
    try:
        deep = json.loads(res["body"])
    except Exception:
        deep = {}
    res["deep"] = deep
    res["at"] = time.time()
    with LOCK:
        STATE["public"] = res
        STATE["public_at"] = res["at"]


def poll_prod(cfg: dict) -> None:
    t0 = time.time()
    try:
        proc = subprocess.run(ssh_args(cfg), capture_output=True, text=True, timeout=25)
        out, err, code = proc.stdout, proc.stderr, proc.returncode
    except subprocess.TimeoutExpired:
        out, err, code = "", "ssh 超时（25s）", 255
    except Exception as exc:
        out, err, code = "", f"{type(exc).__name__}: {exc}", 255

    facts = parse_facts(out) if code == 0 and "HOST=" in out else None
    with LOCK:
        if facts is None:
            STATE["facts_err"] = (err or "").strip().splitlines()[-1][:200] if (err or "").strip() else f"ssh exit {code}"
        else:
            facts["at"] = time.time()
            STATE["facts"] = facts
            STATE["facts_at"] = facts["at"]
            STATE["facts_err"] = ""
            STATE["facts_ms"] = int((time.time() - t0) * 1000)
            mem = facts.get("mem") or {}
            try:
                mem_pct = round(int(mem.get("used", 0)) * 100 / max(int(mem.get("total", 1)), 1))
            except (TypeError, ValueError):
                mem_pct = 0
            try:
                disk_pct = int(str((facts.get("disk") or {}).get("pct", "0")).rstrip("%"))
            except ValueError:
                disk_pct = 0
            STATE["history"].append({"t": time.time(), "cpu": facts.get("cpu") or 0,
                                     "mem": mem_pct, "disk": disk_pct})


# ---------------------------------------------------------------- 告警

def conditions(cfg: dict) -> list[dict]:
    """返回当前「不健康」的条件列表；空列表 = 全绿。"""
    out: list[dict] = []
    now = time.time()
    with LOCK:
        public = dict(STATE["public"] or {})
        public_at = STATE["public_at"]
        facts = dict(STATE["facts"] or {})
        facts_at = STATE["facts_at"]
        facts_err = STATE["facts_err"]

    if public and not public.get("ok"):
        deep = public.get("deep") or {}
        detail = f"HTTP {public.get('code')}"
        if deep:
            detail += f"，db={deep.get('db')} pdfFont={deep.get('pdfFont')}"
        elif public.get("body"):
            detail += f"（{' '.join(str(public['body']).split())[:80]}）"
        out.append({"id": "public", "level": "down", "title": "公网入口不通",
                    "detail": detail})

    ssh_interval = num(cfg, "SSH_INTERVAL", 5)
    if now - facts_at > max(ssh_interval * 3, 20):
        out.append({"id": "prod_ssh", "level": "down", "title": "采集失联（SSH 探针）",
                    "detail": facts_err or "尚未成功采集过一次"})

    for c in facts.get("containers", []):
        if not c["up"]:
            out.append({"id": f"container:{c['svc']}", "level": "down",
                        "title": f"容器 {c['svc']} 不在运行", "detail": c["status"]})
        elif c["svc"] in ("app", "db") and c["health"]:
            out.append({"id": f"health:{c['svc']}", "level": "warn",
                        "title": f"容器 {c['svc']} unhealthy", "detail": c["status"]})

    disk = facts.get("disk") or {}
    try:
        disk_pct = int(str(disk.get("pct", "0")).rstrip("%"))
    except ValueError:
        disk_pct = 0
    if disk and disk_pct >= num(cfg, "DISK_WARN", 90):
        out.append({"id": "disk", "level": "warn", "title": "磁盘快满了",
                    "detail": f"已用 {disk.get('pct')}（{disk.get('used')}/{disk.get('total')}，"
                              f"剩 {disk.get('avail')}）"})

    mem = facts.get("mem") or {}
    try:
        mem_pct = int(int(mem.get("used", 0)) * 100 / max(int(mem.get("total", 1)), 1))
    except (TypeError, ValueError):
        mem_pct = 0
    if mem and mem_pct >= num(cfg, "MEM_WARN", 90):
        out.append({"id": "mem", "level": "warn", "title": "内存快满了",
                    "detail": f"已用 {mem_pct}%（{mem.get('used')}/{mem.get('total')} MB）"})
    return out


def mail(cfg: dict, subject: str, body: str) -> bool:
    if cfg.get("ALERT_ENABLED") != "1":
        with LOCK:
            STATE["mail_ok"] = None
            STATE["mail_err"] = "ALERT_ENABLED=0（告警未开启）"
        return False
    to = cfg.get("MAIL_TO", "")
    frm = cfg.get("MAIL_FROM") or cfg.get("SMTP_USER", "")
    if not (to and frm and cfg.get("SMTP_USER") and cfg.get("SMTP_PASS")):
        with LOCK:
            STATE["mail_ok"] = False
            STATE["mail_err"] = "SMTP 未配置完整（SMTP_USER / SMTP_PASS / MAIL_TO）"
        return False
    from email.message import EmailMessage
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = frm
    msg["To"] = to
    msg["Date"] = email.utils.formatdate(localtime=True)
    msg.set_content(body)
    try:
        port = int(num(cfg, "SMTP_PORT", 465))
        with smtplib.SMTP_SSL(cfg["SMTP_HOST"], port, timeout=20) as smtp:
            smtp.login(cfg["SMTP_USER"], cfg["SMTP_PASS"])
            smtp.send_message(msg)
        with LOCK:
            STATE["mail_ok"] = True
            STATE["mail_err"] = ""
        return True
    except Exception as exc:
        with LOCK:
            STATE["mail_ok"] = False
            STATE["mail_err"] = f"{type(exc).__name__}: {exc}"[:200]
        print(f"[vitals] 邮件发送失败：{exc}", flush=True)
        return False


def alert_text(kind: str, cond: dict, since: float) -> tuple[str, str]:
    mins = int(max(time.time() - since, 0) // 60)
    if kind == "down":
        subject = f"[TeachLoop] 🔴 {cond['title']}"
        body = (f"环境：prod（teachloop.org / 192.168.68.166）\n"
                f"时间：{time.strftime('%Y-%m-%d %H:%M:%S')}\n"
                f"问题：{cond['title']}\n"
                f"详情：{cond['detail']}\n\n"
                f"看板：监控机的 /vitals/ 页面\n"
                f"排查：ssh jzfeng@192.168.68.166 'cd ~/teachloop && docker compose -f docker-compose.prod.yml ps'\n")
    elif kind == "repeat":
        subject = f"[TeachLoop] 🔴 仍然异常：{cond['title']}（已 {mins} 分钟）"
        body = (f"问题仍未恢复：{cond['title']}\n详情：{cond['detail']}\n"
                f"已持续 {mins} 分钟。\n")
    else:
        subject = f"[TeachLoop] 🟢 已恢复：{cond['title']}"
        body = (f"环境：prod\n时间：{time.strftime('%Y-%m-%d %H:%M:%S')}\n"
                f"恢复项：{cond['title']}\n持续时间：{mins} 分钟\n")
    return subject, body


def apply_alerts(cfg: dict) -> None:
    conds = conditions(cfg)
    now = time.time()
    cooldown = num(cfg, "ALERT_COOLDOWN", 1800)
    sends: list[tuple[str, str]] = []

    with LOCK:
        active = {c["id"]: c for c in conds}
        prev = STATE["active"]
        for cid, cond in active.items():
            old = prev.get(cid)
            if old is None:
                prev[cid] = {"since": now, "last": now, "cond": cond}
                sends.append(alert_text("down", cond, now))
                STATE["alert_log"].append({"at": now, "kind": "down", "title": cond["title"]})
            else:
                old["cond"] = cond
                if now - old["last"] >= cooldown:
                    old["last"] = now
                    sends.append(alert_text("repeat", cond, old["since"]))
                    STATE["alert_log"].append({"at": now, "kind": "repeat", "title": cond["title"]})
        for cid in list(prev):
            if cid not in active:
                old = prev.pop(cid)
                if old.get("last") is not None:
                    sends.append(alert_text("up", old["cond"], old["since"]))
                    STATE["alert_log"].append({"at": now, "kind": "up",
                                               "title": old["cond"]["title"]})

    for subject, body in sends:
        threading.Thread(target=mail, args=(cfg, subject, body), daemon=True).start()


# ---------------------------------------------------------------- 快照

def snapshot(cfg: dict) -> dict:
    now = time.time()
    with LOCK:
        public = dict(STATE["public"] or {})
        public_at = STATE["public_at"]
        facts = dict(STATE["facts"] or {})
        facts_at = STATE["facts_at"]
        facts_ms = STATE["facts_ms"]
        facts_err = STATE["facts_err"]
        history = list(STATE["history"])
        active = [c["cond"] for c in STATE["active"].values()]
        log = list(STATE["alert_log"])[-6:]
        mail_ok, mail_err = STATE["mail_ok"], STATE["mail_err"]
        started = STATE["started"]

    mem = facts.get("mem") or {}
    try:
        mem_pct = round(int(mem.get("used", 0)) * 100 / max(int(mem.get("total", 1)), 1))
    except (TypeError, ValueError):
        mem_pct = 0
    disk = facts.get("disk") or {}
    try:
        disk_pct = int(str(disk.get("pct", "0")).rstrip("%"))
    except ValueError:
        disk_pct = 0

    containers = []
    for c in facts.get("containers", []):
        containers.append({
            "name": c["name"], "svc": c["svc"], "status": c["status"],
            "up": c["up"], "healthy": c["healthy"], "health": c["health"],
            "restarts": c.get("restarts", "0"),
        })

    # 探针「新鲜度」：超过 3 个采集周期没成功，就当看不见 prod
    fresh = (now - facts_at) <= max(num(cfg, "SSH_INTERVAL", 5) * 3, 20) if facts_at else False
    public_fresh = (now - public_at) <= max(num(cfg, "PUBLIC_INTERVAL", 10) * 3, 40) if public_at else False

    if not fresh or (public_fresh and not public.get("ok")):
        overall = "down"
    elif any(c["health"] for c in containers) or disk_pct >= num(cfg, "DISK_WARN", 90) \
            or mem_pct >= num(cfg, "MEM_WARN", 90) or not public_fresh:
        overall = "warn"
    else:
        overall = "ok"

    return {
        "ts": now,
        "overall": overall,
        "uptime_s": int(now - started),
        "public": {
            "ok": bool(public.get("ok")), "code": public.get("code"),
            "ms": public.get("ms"), "at": public_at or None,
            "age_s": round(now - public_at) if public_at else None,
            "deep": public.get("deep") or {},
        },
        "probe": {
            "fresh": fresh, "age_s": round(now - facts_at) if facts_at else None,
            "ms": facts_ms, "err": facts_err,
        },
        "host": {
            "hostname": facts.get("host", ""),
            "cpu": facts.get("cpu"), "load": facts.get("load"),
            "mem": {"used": mem.get("used"), "total": mem.get("total"),
                    "avail": mem.get("avail"), "pct": mem_pct},
            "disk": {"used": disk.get("used"), "total": disk.get("total"),
                     "avail": disk.get("avail"), "pct": disk_pct},
            "uptime_s": facts.get("uptime_s"),
        },
        "containers": containers,
        "http": {"requests_today": facts.get("reqs"), "s5xx": facts.get("s5xx"),
                 "day": facts.get("day", "")},
        "alerts": {"active": active, "log": log, "mail_ok": mail_ok, "mail_err": mail_err},
        "history": history,
    }


# ---------------------------------------------------------------- HTTP

def render_page(cfg: dict) -> bytes:
    with open(PAGE_FILE, "r", encoding="utf-8") as fh:
        return fh.read().encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "teachloop-vitals/1"
    sys_version = ""

    def log_message(self, fmt, *args):  # 只记错误，正常请求不刷日志
        if str(args[1] if len(args) > 1 else "") not in ("200", "304"):
            sys.stderr.write("[vitals] %s %s\n" % (self.address_string(), fmt % args))

    def authorized(self, cfg: dict) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        user, _, pwd = raw.partition(":")
        return (hmac.compare_digest(user, cfg["VITALS_USER"])
                and hmac.compare_digest(pwd, cfg["VITALS_PASS"]))

    def send_bytes(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        cfg = self.server.cfg
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path == "/vitals/healthz":
            self.send_bytes(200, b"ok\n", "text/plain; charset=utf-8")
            return
        if path == "/":
            self.send_response(302)
            self.send_header("Location", "/vitals/")
            self.end_headers()
            return
        if not self.authorized(cfg):
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="teachloop vitals"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path in ("/vitals", "/vitals/index.html"):
            try:
                self.send_bytes(200, render_page(cfg), "text/html; charset=utf-8")
            except OSError as exc:
                self.send_bytes(500, f"page.html 读不到：{exc}".encode(), "text/plain; charset=utf-8")
        elif path == "/vitals/api":
            body = json.dumps(snapshot(cfg), ensure_ascii=False).encode("utf-8")
            self.send_bytes(200, body, "application/json; charset=utf-8")
        else:
            self.send_bytes(404, b"not found\n", "text/plain; charset=utf-8")


def collector(cfg: dict) -> None:
    ssh_every = num(cfg, "SSH_INTERVAL", 5)
    pub_every = num(cfg, "PUBLIC_INTERVAL", 10)
    last_ssh = last_pub = last_alert = 0.0
    while True:
        now = time.time()
        if now - last_ssh >= ssh_every:
            last_ssh = now
            try:
                poll_prod(cfg)
            except Exception as exc:
                print(f"[vitals] 采集异常：{exc}", flush=True)
        if now - last_pub >= pub_every:
            last_pub = now
            try:
                poll_public(cfg)
            except Exception as exc:
                print(f"[vitals] 公网探针异常：{exc}", flush=True)
        if now - last_alert >= 2:
            last_alert = now
            try:
                apply_alerts(cfg)
            except Exception as exc:
                print(f"[vitals] 告警异常：{exc}", flush=True)
        time.sleep(0.5)


# ---------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser(description="TeachLoop prod 生命体征看板")
    ap.add_argument("--once", action="store_true", help="采集一次并打印 JSON，不启动 HTTP")
    ap.add_argument("--test-mail", action="store_true", help="发一封测试邮件后退出")
    ap.add_argument("--check", action="store_true", help="只校验配置与 prod 探针连通性")
    args = ap.parse_args()

    cfg = load_config()
    if not cfg.get("VITALS_PASS"):
        cfg["VITALS_PASS"] = "".join(random.choice(string.ascii_letters + string.digits) for _ in range(16))
        print(f"[vitals] VITALS_PASS 为空，本次使用随机口令：{cfg['VITALS_PASS']}", flush=True)

    if args.check:
        print(f"PROD_URL   = {cfg['PROD_URL']}")
        print(f"PROD_SSH   = {cfg['PROD_SSH']}")
        print(f"ALERT      = {'on' if cfg['ALERT_ENABLED'] == '1' else 'off'} → {cfg.get('MAIL_TO') or '(未设收件人)'}")
        pub = http_get(cfg["PROD_URL"].rstrip("/") + "/healthz?deep=1")
        print(f"公网探针   = {pub['code']} {pub['ms']}ms {pub['body'][:120]}")
        poll_prod(cfg)
        with LOCK:
            err = STATE["facts_err"]
            facts = STATE["facts"]
        if facts:
            print(f"SSH 探针   = ok {STATE['facts_ms']}ms，容器 {len(facts['containers'])} 个")
        else:
            print(f"SSH 探针   = 失败：{err}")
            return 1
        return 0

    if args.test_mail:
        if cfg.get("ALERT_ENABLED") != "1":
            print("ALERT_ENABLED 不是 1，邮件不会发。先把它改成 1、填好 SMTP_USER/SMTP_PASS/MAIL_TO/MAIL_FROM 再试。")
            return 2
        subject = "[TeachLoop] vitals 测试邮件"
        body = f"如果你看到这封，告警通道是通的。\n时间：{time.strftime('%Y-%m-%d %H:%M:%S')}\n"
        ok = mail(cfg, subject, body)
        with LOCK:
            print(("已发送 → " if ok else "发送失败：") + (cfg.get("MAIL_TO") or "") + " " + STATE["mail_err"])
        return 0 if ok else 1

    if args.once:
        poll_public(cfg)
        poll_prod(cfg)
        print(json.dumps(snapshot(cfg), ensure_ascii=False, indent=2))
        return 0

    threading.Thread(target=collector, args=(cfg,), daemon=True).start()
    port = int(num(cfg, "VITALS_PORT", 8099))
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    httpd.daemon_threads = True
    httpd.cfg = cfg
    print(f"[vitals] 监听 :{port}，看板 /vitals/ ，用户 {cfg['VITALS_USER']}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
