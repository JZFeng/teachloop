#!/usr/bin/env python3
"""2026-10-31 / task-1：以管理员路径建词表 `words_with_images`，把 113 个词放进去。

**走的是哪条路（以及为什么不写 SQL）**

`AdminCli` 只有 `--create-admin` / `--create-user` / `--set-password`，没有建词表的命令；
词表又是**按用户**的容器（`VocabularyListEndpoint`：`AC-69.1` 只引用词条、`AC-69.9` 他人词单一律 404，
连管理员也没有「给别人建词表」的接口）。所以这里走的是任务书里给的备选路：
**用业务接口 `POST /api/vocab/lists` + `POST /api/vocab/lists/{id}/words` 建**，账号用
`teacher@teachloop.local`（qa 里唯一能密码登录、且名下已有这 113 个词的账号）。

为什么不用 `admin@teachloop.local`：qa 里它 `password_login_enabled=false`（只能走微信），
用它建出来的词表也只有管理员自己看得见，验收账号（teacher）看不到。

用法：

    python3 scripts/vocab-list-import.py --base http://127.0.0.1:8011
    python3 scripts/vocab-list-import.py --base http://127.0.0.1:8010 \\
        --email teacher@teachloop.local --password teachloop123

幂等：词表已存在就复用（按名字找），词已在表里就计入 `duplicated`，不报错、不重建。
退出码：0 = 词表里 113 个词齐了；1 = 登录失败 / 接口报错 / 数量对不上。
"""
from __future__ import annotations

import argparse
import csv
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CSV = ROOT / "docs/reports/2026-10-31-word-selection-100/qa-113.csv"

# 与 VocabularyListEndpoint.MAX_ADD_PER_REQUEST 一致：一次最多 200 个词形。
MAX_ADD_PER_REQUEST = 200


class Client:
    """最小 HTTP 客户端：cookie + CSRF（qa 开了 `teachloop.security.csrf=true`，与生产对齐）。"""

    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def _request(self, method: str, path: str, payload: dict | None = None) -> tuple[int, dict | str]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.csrf:
            request.add_header("X-XSRF-TOKEN", self.csrf)
        try:
            with self.opener.open(request, timeout=30) as response:
                body = response.read().decode("utf-8", "replace")
                status = response.status
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            status = error.code
        self._remember_csrf()
        try:
            return status, json.loads(body) if body else {}
        except json.JSONDecodeError:
            return status, body

    def _remember_csrf(self) -> None:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                self.csrf = cookie.value

    def _refresh_csrf(self) -> None:
        """重新取一次 XSRF-TOKEN：登录成功会换 session，绑在旧 session 上的 token 随即作废。"""
        self._request("GET", "/api/csrf")
        self._remember_csrf()

    def _send(self, method: str, path: str, payload: dict | None) -> tuple[int, object]:
        status, body = self._request(method, path, payload)
        if status == 403:
            # 与前端 src/api/client.ts 同一个兜底：403 响应会回写新 token，刷新后重试一次。
            self._refresh_csrf()
            status, body = self._request(method, path, payload)
        return status, body

    def call(self, method: str, path: str, payload: dict | None = None) -> dict:
        """写请求 / 返回单个对象的读请求；返回 JSON 对象（dict）。"""
        status, body = self._send(method, path, payload)
        if status >= 400 or not isinstance(body, dict):
            raise SystemExit(f"{method} {path} 失败：HTTP {status} {body}")
        return body

    def call_list(self, method: str, path: str, payload: dict | None = None) -> list:
        """读请求；返回 JSON 数组（如 `GET /api/vocab/lists`）。"""
        status, body = self._send(method, path, payload)
        if status >= 400 or not isinstance(body, list):
            raise SystemExit(f"{method} {path} 失败：HTTP {status} {body}")
        return body


def load_forms(csv_path: Path) -> list[str]:
    with csv_path.open(newline="", encoding="utf-8") as handle:
        forms = [row["word"].strip().lower() for row in csv.DictReader(handle) if row["word"].strip()]
    # 保序去重：接口按顺序加入，词表顺序 = 清单顺序。
    seen: set[str] = set()
    return [form for form in forms if not (form in seen or seen.add(form))]


def main() -> int:
    parser = argparse.ArgumentParser(description="建词表 words_with_images 并放入 113 个词")
    parser.add_argument("--base", default="http://127.0.0.1:8011", help="后端地址（qa 8011 / dev 8010）")
    parser.add_argument("--email", default="teacher@teachloop.local", help="登录账号")
    parser.add_argument("--password", default="teachloop123", help="登录密码")
    parser.add_argument("--name", default="words_with_images", help="词表名")
    parser.add_argument("--description",
                        default="113 张定稿配图（176px WebP）覆盖的词，用于验收 review-back / word-details 的图片位",
                        help="词表描述")
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV, help="词形来源")
    parser.add_argument("--language", default="en", help="词条语言")
    args = parser.parse_args()

    forms = load_forms(args.csv)
    client = Client(args.base)
    # qa/prod 开了 CSRF：先 GET /api/csrf 拿 XSRF-TOKEN，再带 X-XSRF-TOKEN 写请求。
    client.call("GET", "/api/csrf")
    me = client.call("POST", "/api/auth/login", {"email": args.email, "password": args.password})
    print(f"登录：{me.get('email')}（{me.get('role')}）")

    existing = next((item for item in client.call_list("GET", "/api/vocab/lists")
                     if item.get("name") == args.name), None)
    if existing:
        list_id = existing["id"]
        print(f"词表已存在，复用：{args.name} ({list_id})")
    else:
        created = client.call("POST", "/api/vocab/lists",
                              {"name": args.name, "description": args.description})
        list_id = created["id"]
        print(f"已建词表：{args.name} ({list_id})")

    added = duplicated = 0
    for start in range(0, len(forms), MAX_ADD_PER_REQUEST):
        chunk = forms[start:start + MAX_ADD_PER_REQUEST]
        result = client.call("POST", f"/api/vocab/lists/{list_id}/words",
                             {"forms": chunk, "language": args.language})
        added += result.get("added", 0)
        duplicated += result.get("duplicated", 0)
        print(f"  批次 {start // MAX_ADD_PER_REQUEST + 1}：+{result.get('added', 0)} 词，"
              f"{result.get('duplicated', 0)} 已在表里")

    detail = client.call("GET", f"/api/vocab/lists/{list_id}")
    total = detail.get("wordCount") or detail.get("total")
    print(f"词表 {args.name}：新增 {added} / 重复 {duplicated} / 现在 {total} 词")
    print(f"验收入口：{args.base.replace(':8011', ':5174').replace(':8010', ':5173')}"
          f"/vocab/lists/{list_id}")
    if isinstance(total, int) and total < len(forms):
        print(f"数量对不上：期望至少 {len(forms)} 词，实际 {total}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
