#!/usr/bin/env python3
"""T174 粗粒度压测：N 个学生 × 2 场提交，测提交延迟与批改完成时间。

用法（应用已在 8010、演示数据已 seed）：
  python3 scripts/load-test.py --base http://127.0.0.1:8010 --students 25 --concurrency 10
只使用标准库；输出 JSON 摘要，供 .docs/perf-report-v2.md 引用。
"""
import argparse, json, statistics, time, urllib.request, urllib.error, http.cookiejar
from concurrent.futures import ThreadPoolExecutor, as_completed

def session(base, email, password="teachloop123"):
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    req = urllib.request.Request(base + "/api/auth/login", method="POST",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"})
    with op.open(req) as r:
        if r.status != 200: raise RuntimeError(f"login {email}: {r.status}")
    return op

def call(op, method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(path, method=method,
        data=data, headers={"Content-Type": "application/json"})
    try:
        with op.open(req) as r:
            body = r.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code} {e.read().decode(errors='replace')[:300]}") from e

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8010")
    ap.add_argument("--students", type=int, default=25)
    ap.add_argument("--concurrency", type=int, default=10)
    args = ap.parse_args()
    base = args.base.rstrip("/")

    teacher = session(base, "teacher@teachloop.local")
    for op in [teacher]: op.base = base
    classes = call(teacher, "GET", base + "/api/classes")
    papers = call(teacher, "GET", base + "/api/papers")
    clazz = next(c for c in classes if c["name"] == "演示班")
    paper = next(p for p in papers if p["title"] == "演示试卷")

    offerings = []
    for i in range(2):
        o = call(teacher, "POST", base + "/api/offerings", {
            "paperId": paper["id"], "kind": "ASSIGNMENT", "timingMode": "INDIVIDUAL",
            "dueAt": "2026-12-31T23:59:59Z", "durationMinutes": 60,
            "classes": [{"classId": clazz["id"]}]})
        call(teacher, "POST", base + f"/api/offerings/{o['id']}/publish", {})
        offerings.append(o["id"])

    students = [session(base, f"student{i:02d}@teachloop.local") for i in range(1, args.students + 1)]
    for op in students: op.base = base

    def submit(i):
        op = students[i % len(students)]
        offering = offerings[i % len(offerings)]
        t0 = time.perf_counter()
        call(op, "POST", base + f"/api/offerings/{offering}/start", {})
        res = call(op, "POST", base + f"/api/offerings/{offering}/submissions", {
            "answers": [{"number": 1, "answer": "B"}, {"number": 2, "answer": "alone"},
                        {"number": 3, "answer": "Load test essay " + str(i)}]})
        return time.perf_counter() - t0, res

    latencies, failures = [], []
    t_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(submit, i) for i in range(args.students * 2)]
        for f in as_completed(futures):
            try:
                lat, _ = f.result()
                latencies.append(lat)
            except Exception as e:
                failures.append(repr(e))
    submit_wall = time.perf_counter() - t_start

    for o in offerings:
        call(teacher, "POST", base + f"/api/offerings/{o}/grading/dispatch", {})
    t_grade = time.perf_counter()
    states = {}
    while time.perf_counter() - t_grade < 180:
        done = True
        for o in offerings:
            p = call(teacher, "GET", base + f"/api/offerings/{o}/progress")
            states[o] = p
            if p.get("pending", 0) + p.get("running", 0) > 0:
                done = False
        if done:
            break
        time.sleep(1)
    grade_wall = time.perf_counter() - t_grade

    latencies.sort()
    def pct(p):
        if not latencies: return None
        k = min(len(latencies) - 1, int(round((p / 100) * (len(latencies) - 1))))
        return round(latencies[k], 3)
    summary = {
        "students": args.students, "submissions_attempted": args.students * 2,
        "submissions_ok": len(latencies), "failures": failures[:5],
        "concurrency": args.concurrency, "submit_wall_seconds": round(submit_wall, 2),
        "submit_throughput_per_second": round(len(latencies) / submit_wall, 2) if submit_wall else None,
        "latency_seconds": {"p50": pct(50), "p95": pct(95), "max": pct(100)},
        "grading_wall_seconds": round(grade_wall, 2),
        "offering_progress": states,
        "topology": "1 app + 1 worker (embedded scheduler), stub engine; 非 plan 的 2 app + 2 worker",
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
