#!/usr/bin/env python3
"""011 / T568：今日页与背单词的本地基准（5000 词 + 500 条复习日志）。

做四件事：
1. 用真实接口给学生灌 5000 个词（`POST /api/vocab/words/paste`，每批 500）；
2. 用 psql 直接写 500 条 `vocab_review_logs`（复习历史不该靠接口刷，接口会写 SRS 排期）；
3. 顺序打三组请求各 N 次，报 p50/p95/p99：
   - `GET /api/student/today`（今日页唯一数据源，AC-90.7）
   - `GET /api/vocab/review/queue?limit=100`（今日复习队列）
   - `GET /api/vocab/words?page=1&size=50`（我的词库列表）
4. 若目标库开了 `pg_stat_statements`，打印按 `mean_exec_time` 排前的几条（服务端真实耗时）。

用法（本机隔离实例，不动 dev/qa/prod）：

    python3 scripts/bench-vocab.py \
        --base http://127.0.0.1:8021 \
        --db-container teachloop-011-bench \
        --words 5000 --logs 500 --samples 30

只看结果、不想再灌数据时加 `--skip-seed`。退出码固定 0（基准不是断言），
「< 50ms」的判定写在输出最后一行，报告里照抄。
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http.cookies import SimpleCookie

DEFAULT_EMAIL = "student@teachloop.local"
DEFAULT_PASSWORD = "teachloop123"


def http(method: str, url: str, body: dict | None = None,
         cookie: str | None = None, headers: dict | None = None) -> tuple[int, str, dict]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if cookie:
        request.add_header("Cookie", cookie)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(), dict(error.headers)


def login(base: str, email: str, password: str) -> str:
    status, body, headers = http("POST", f"{base}/api/auth/login",
                                 {"email": email, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败：{status} {body[:200]}")
    raw = headers.get("Set-Cookie", "")
    cookie = SimpleCookie()
    cookie.load(raw)
    jar = "; ".join(f"{key}={morsel.value}" for key, morsel in cookie.items())
    if not jar:
        raise SystemExit(f"登录响应里没有 cookie：{raw}")
    return jar


def seed_words(base: str, cookie: str, count: int, batch: int = 500) -> tuple[int, float]:
    started = time.perf_counter()
    created = 0
    for offset in range(0, count, batch):
        words = "\n".join(f"bench{index:05d}" for index in range(offset, min(offset + batch, count)))
        status, body, _ = http("POST", f"{base}/api/vocab/words/paste",
                               {"text": words, "language": "en"}, cookie)
        if status != 200:
            raise SystemExit(f"灌词失败（offset={offset}）：{status} {body[:300]}")
        payload = json.loads(body)
        created += int(payload.get("counts", {}).get("created", 0))
    return created, time.perf_counter() - started


def seed_logs(container: str, database: str, user_id: str, count: int) -> None:
    sql = (
        "insert into vocab_review_logs (user_id, word_id, rating, reviewed_at) "
        f"select '{user_id}'::uuid, gen_random_uuid(), 2, "
        f"now() - (g || ' minutes')::interval from generate_series(1, {count}) g;"
    )
    subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "teachloop", "-d", database,
         "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True, capture_output=True, text=True,
    )


def percentile(values: list[float], ratio: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, int(round(ratio * (len(ordered) - 1)))))
    return ordered[index]


def measure(base: str, cookie: str, path: str, samples: int, warmup: int = 3,
            headers: dict | None = None) -> dict:
    for _ in range(warmup):
        status, body, _ = http("GET", f"{base}{path}", cookie=cookie, headers=headers)
        if status != 200:
            raise SystemExit(f"预热失败 {path}：{status} {body[:200]}")
    latencies: list[float] = []
    for _ in range(samples):
        started = time.perf_counter()
        status, body, _ = http("GET", f"{base}{path}", cookie=cookie, headers=headers)
        elapsed = (time.perf_counter() - started) * 1000
        if status != 200:
            raise SystemExit(f"采样失败 {path}：{status} {body[:200]}")
        latencies.append(elapsed)
    return {
        "path": path,
        "p50": statistics.median(latencies),
        "p95": percentile(latencies, 0.95),
        "p99": percentile(latencies, 0.99),
        "max": max(latencies),
        "samples": len(latencies),
    }


def pg_stat_statements(container: str, database: str) -> list[str]:
    sql = (
        "select round(mean_exec_time::numeric, 2) || ' ms | calls=' || calls || ' | ' "
        "|| left(regexp_replace(query, '\\s+', ' ', 'g'), 110) "
        "from pg_stat_statements where query ilike '%vocab_%' or query ilike '%offering%' "
        "order by mean_exec_time desc limit 8"
    )
    completed = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "teachloop", "-d", database,
         "-t", "-A", "-c", sql],
        capture_output=True, text=True,
    )
    if completed.returncode != 0:
        return [f"（pg_stat_statements 不可用：{completed.stderr.strip().splitlines()[:1]}）"]
    return [line for line in completed.stdout.splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8021")
    parser.add_argument("--db-container", default="teachloop-011-bench")
    parser.add_argument("--db-name", default="teachloop")
    parser.add_argument("--email", default=DEFAULT_EMAIL)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    parser.add_argument("--words", type=int, default=5000)
    parser.add_argument("--logs", type=int, default=500)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--skip-seed", action="store_true")
    args = parser.parse_args()

    cookie = login(args.base, args.email, args.password)
    status, body, _ = http("GET", f"{args.base}/api/auth/me", cookie=cookie)
    if status != 200:
        raise SystemExit(f"/api/auth/me 失败：{status}")
    user_id = json.loads(body)["id"]
    print(f"账号 {args.email}（{user_id}）")

    if not args.skip_seed:
        created, seconds = seed_words(args.base, cookie, args.words)
        print(f"灌词：created={created} / 目标 {args.words}，耗时 {seconds:.1f}s")
        seed_logs(args.db_container, args.db_name, user_id, args.logs)
        print(f"复习日志：插入 {args.logs} 条")

    targets = [
        ("today", "/api/student/today", {"X-Timezone": "Asia/Shanghai"}),
        ("review-queue", "/api/vocab/review/queue?limit=100", None),
        ("library", "/api/vocab/words?page=1&size=50", None),
    ]
    results = [measure(args.base, cookie, path, args.samples, headers=headers)
               for _, path, headers in targets]

    print("\n端点                p50     p95     p99     max    样本")
    for row in results:
        print(f"{row['path']:<36} {row['p50']:7.1f} {row['p95']:7.1f} "
              f"{row['p99']:7.1f} {row['max']:7.1f} {row['samples']:6d}")

    print("\npg_stat_statements（服务端 mean_exec_time 前 8）：")
    for line in pg_stat_statements(args.db_container, args.db_name):
        print("  " + line)

    today = next(row for row in results if row["path"] == "/api/student/today")
    verdict = "PASS" if today["p95"] < 50 else "FAIL"
    print(f"\n结论：/api/student/today p95={today['p95']:.1f}ms（目标 <50ms）→ {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
