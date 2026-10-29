#!/usr/bin/env python3
"""会话检索（find-session）：按关键字找回历史 session，输出「标题」供左侧列表定位。

场景：只记得聊过什么、不记得是哪一次会话。GUI 里按内容搜索不可靠（本机 Web 部署
把 full-text 搜索关成了 `openAt: never`），本脚本直接读 DSH 的 session 日志。

数据来源（全在本机，只读）：
  $DSH_HOME/sessions/<workspace>/session-<id>/session.v4.jsonl.zstd  会话正文
  $DSH_HOME/storages/session_projcache/sessions/<id>.json            标题 / cwd / 时间
  $DSH_HOME/storages/workspace.json                                  归档 / 置顶状态

排序：你自己的发言权重 3、助手回复权重 1、标题/首条命中再加 6——
        这样「AGENTS.md 里到处都有的词」不会把真正的对话顶到后面。
      出现在一半以上会话里的词（例如「会话」「session」）会被当作通用词忽略并提示。

缓存：`.dsh/tmp/session-index/`（gitignored）。首次建索引要解压全部日志（本机 ~150 个
会话约 3–5 秒），之后按「文件大小 + mtime」增量刷新，单次查询 < 1s。`--refresh` 强制重建。

用法：
  python3 scripts/find-session.py 多屏 tablet
  python3 scripts/find-session.py "登录页 靠下" --limit 5
  python3 scripts/find-session.py smtp --fast          # 只搜标题与首条消息（毫秒级）
  python3 scripts/find-session.py 平板 --all           # 每个词都要命中
  python3 scripts/find-session.py 平板 --json          # 机器可读
  python3 scripts/find-session.py --rebuild            # 重建索引后退出

退出码：0 有命中，1 无命中，2 环境错误（找不到 DSH 目录 / 缺 zstd）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".dsh" / "tmp" / "session-index"
SNIPPET_RADIUS = 90
USER_WEIGHT = 3   # 用户原话权重
ASST_WEIGHT = 1   # 助手回复权重（含 AGENTS 引言，容易灌水）
TITLE_BONUS = 6   # 标题/首条消息命中
COMMON_RATIO = 0.5  # 出现在超过这个比例的会话里 → 视为通用词


# ── 环境定位 ────────────────────────────────────────────────────────────────

def dsh_home() -> Path:
    env = os.environ.get("DSH_HOME")
    return Path(env).expanduser() if env else Path.home() / ".dsh"


def session_dirs(home: Path) -> list[Path]:
    roots = sorted((home / "sessions").glob("*/session-*"))
    return [p for p in roots if (p / "session.v4.jsonl.zstd").is_file()]


def load_projections(home: Path) -> dict[str, dict]:
    """session id -> {title, cwd, createdAt, lastMessageTime, first}"""
    out: dict[str, dict] = {}
    for f in (home / "storages" / "session_projcache" / "sessions").glob("*.json"):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        rec = d.get("record") or {}
        rows = rec.get("rows") or {}
        ident = rec.get("identity") or {}
        title = ((rows.get("title") or {}).get("val") or "").strip()
        first = (((rows.get("titleInput") or {}).get("val") or {}).get("first") or {}).get(
            "text"
        ) or ""
        sid = f.stem if f.stem.startswith("session-") else "session-" + f.stem
        last = (
            ((rows.get("timeContext") or {}).get("val") or {}).get("lastMessageTime")
            or ((rows.get("sessionListMetadata") or {}).get("val") or {}).get("lastPromptAt")
            or ident.get("createdAt")
            or 0
        )
        out[sid] = {
            "title": title,
            "first": first.strip(),
            "cwd": ident.get("cwd") or "",
            "createdAt": ident.get("createdAt") or 0,
            "lastMessageTime": last,
        }
    return out


def load_workspace_state(home: Path) -> tuple[set[str], set[str]]:
    try:
        g = json.loads((home / "storages" / "workspace.json").read_text(encoding="utf-8"))["global"]
    except Exception:
        return set(), set()
    return set(g.get("archivedSessionIds") or []), set(g.get("pinnedSessionIds") or [])


# ── 索引 ────────────────────────────────────────────────────────────────────

def extract_records(log_path: Path) -> str:
    """把 session.v4.jsonl.zstd 压成 JSONL：{"r": role, "t": ms, "x": text}。"""
    proc = subprocess.run(["zstd", "-dc", str(log_path)], capture_output=True, check=False)
    if proc.returncode != 0:
        return ""
    out: list[str] = []
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        if '"type":"user/message"' in line:
            try:
                r = json.loads(line)
            except Exception:
                continue
            src = (r.get("data") or {}).get("source") or {}
            # 只收「用户真的打的字」：agent-instructions / runtime-context / skill-catalog /
            # time-context 是每条会话都注入的样板文本，混进来会把任何词都变成「到处都有」。
            if src.get("kind") not in (None, "user"):
                continue
            content = (r.get("data") or {}).get("content") or []
            text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
            if text.strip():
                out.append(json.dumps({"r": "user", "t": r.get("time") or 0, "x": text.strip()},
                                      ensure_ascii=False))
        elif '"type":"assistant/message"' in line:
            try:
                r = json.loads(line)
            except Exception:
                continue
            content = ((r.get("data") or {}).get("message") or {}).get("content") or []
            text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
            if text.strip():
                out.append(json.dumps({"r": "assistant", "t": r.get("time") or 0, "x": text.strip()},
                                      ensure_ascii=False))
            for c in content:
                if c.get("type") == "tool-call" and c.get("name"):
                    out.append(json.dumps({"r": "tool", "t": r.get("time") or 0, "x": c["name"]},
                                          ensure_ascii=False))
    return "\n".join(out)


def build_index(dirs: list[Path], jobs: int, refresh: bool) -> tuple[int, int]:
    CACHE.mkdir(parents=True, exist_ok=True)
    todo: list[Path] = []
    cached = 0
    for d in dirs:
        log = d / "session.v4.jsonl.zstd"
        st = log.stat()
        want = f"{st.st_size}:{int(st.st_mtime)}"
        txt, stamp = CACHE / f"{d.name}.jsonl", CACHE / f"{d.name}.stamp"
        if not refresh and txt.is_file() and stamp.is_file() and stamp.read_text().strip() == want:
            cached += 1
            continue
        todo.append(d)

    def work(d: Path) -> None:
        log = d / "session.v4.jsonl.zstd"
        st = log.stat()
        tmp = CACHE / f"{d.name}.jsonl.tmp"
        tmp.write_text(extract_records(log), encoding="utf-8")
        tmp.replace(CACHE / f"{d.name}.jsonl")
        (CACHE / f"{d.name}.stamp").write_text(f"{st.st_size}:{int(st.st_mtime)}")

    if todo:
        with ThreadPoolExecutor(max_workers=max(1, jobs)) as pool:
            list(pool.map(work, todo))
    return len(todo), cached


# ── 检索 ────────────────────────────────────────────────────────────────────

def split_tokens(query: str) -> list[str]:
    return [t.lower() for t in re.split(r"[\s,，、;；|]+", query.strip()) if t]


def count_tokens(text: str, tokens: list[str]) -> dict[str, int]:
    low = text.lower()
    return {t: low.count(t) for t in tokens}


def snippet(text: str, tokens: list[str]) -> str:
    low = text.lower()
    pos = [low.find(t) for t in tokens]
    pos = [p for p in pos if p != -1]
    if not pos:
        return ""
    best = min(pos)
    start = max(0, best - SNIPPET_RADIUS)
    end = min(len(text), best + SNIPPET_RADIUS)
    frag = re.sub(r"\s+", " ", text[start:end]).strip()
    return ("…" if start else "") + frag + ("…" if end < len(text) else "")


def load_records(path: Path) -> list[dict]:
    recs: list[dict] = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return recs
    for line in lines:
        try:
            recs.append(json.loads(line))
        except Exception:
            continue
    return recs


def fmt_time(ms: object) -> str:
    try:
        v = int(ms) / 1000
    except (TypeError, ValueError):
        return "?"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(v)) if v > 0 else "?"


def workspace_name(cwd: str) -> str:
    return Path(cwd).name if cwd else "?"


def collect(dirs: list[Path], projections: dict[str, dict], archived: set[str],
            pinned: set[str], tokens: list[str], fast: bool) -> list[dict]:
    out: list[dict] = []
    for d in dirs:
        sid = d.name
        meta = projections.get(sid) or {}
        title, first = meta.get("title") or "", meta.get("first") or ""
        head = count_tokens(title + "\n" + first, tokens)
        user_hits: dict[str, int] = {t: 0 for t in tokens}
        asst_hits: dict[str, int] = {t: 0 for t in tokens}
        if not fast:
            for rec in load_records(CACHE / f"{sid}.jsonl"):
                role = rec.get("r")
                x = rec.get("x") or ""
                c = count_tokens(x, tokens)
                if role == "user":
                    for k, v in c.items():
                        user_hits[k] += v
                else:
                    for k, v in c.items():
                        asst_hits[k] += v
        total = {t: head[t] + user_hits[t] + asst_hits[t] for t in tokens}
        if not any(total.values()):
            continue
        out.append({
            "id": sid,
            "title": title or "（无标题）",
            "first": first,
            "cwd": meta.get("cwd") or "",
            "createdAt": meta.get("createdAt") or 0,
            "lastMessageTime": meta.get("lastMessageTime") or meta.get("createdAt") or 0,
            "archived": sid in archived,
            "pinned": sid in pinned,
            "head": head,
            "user_hits": user_hits,
            "asst_hits": asst_hits,
            "snippets": [],
        })
    return out


def pick_snippets(sid: str, tokens: list[str], limit: int = 2) -> list[str]:
    """用最终参与排序的词挑片段：先取用户原话，其次助手回复。"""
    user_snips: list[str] = []
    asst_snips: list[str] = []
    for rec in load_records(CACHE / f"{sid}.jsonl"):
        x = rec.get("x") or ""
        c = count_tokens(x, tokens)
        if not any(c.values()):
            continue
        frag = snippet(x, tokens)
        if not frag:
            continue
        if rec.get("r") == "user":
            if frag not in user_snips:
                user_snips.append(frag)
        elif rec.get("r") == "assistant":
            if frag not in asst_snips:
                asst_snips.append(frag)
        if len(user_snips) >= limit:
            break
    return (user_snips + asst_snips)[:limit]


def score(row: dict, tokens: list[str]) -> tuple[int, dict[str, int]]:
    per: dict[str, int] = {}
    for t in tokens:
        per[t] = (
            USER_WEIGHT * row["user_hits"][t]
            + ASST_WEIGHT * row["asst_hits"][t]
            + TITLE_BONUS * row["head"][t]
        )
    return sum(per.values()), per


def run(args: argparse.Namespace) -> int:
    home = dsh_home()
    if not (home / "sessions").is_dir():
        print(f"找不到会话目录：{home / 'sessions'}", file=sys.stderr)
        return 2
    dirs = session_dirs(home)
    if not dirs:
        print(f"{home / 'sessions'} 下没有会话日志", file=sys.stderr)
        return 2

    projections = load_projections(home)
    archived, pinned = load_workspace_state(home)

    if not args.fast and not _has_zstd():
        print("缺少 zstd（brew install zstd），退回 --fast：只能搜标题与首条消息", file=sys.stderr)
        args.fast = True

    if not args.fast:
        t0 = time.time()
        built, cached = build_index(dirs, args.jobs, args.refresh)
        if built:
            print(f"[index] 新建 {built} 个、复用 {cached} 个，用时 {time.time() - t0:.1f}s",
                  file=sys.stderr)
        if args.rebuild:
            print(f"[index] 已就绪：{len(dirs)} 个会话 → {CACHE}")
            return 0

    tokens = split_tokens(" ".join(args.query))
    if not tokens:
        print("用法：python3 scripts/find-session.py <关键字...>", file=sys.stderr)
        return 2

    rows = collect(dirs, projections, archived, pinned, tokens, args.fast)

    # 通用词降噪：出现在过半会话里的词（如「会话」「session」）不参与排序
    if not args.keep_common and len(rows) > 3:
        df = {t: sum(1 for r in rows if (r["user_hits"][t] + r["asst_hits"][t] + r["head"][t]) > 0)
              for t in tokens}
        total_sessions = max(1, len(dirs))
        common = [t for t in tokens if df[t] / total_sessions > COMMON_RATIO]
        if common and len(common) < len(tokens):
            tokens = [t for t in tokens if t not in common]
            print(f"[提示] 忽略通用词 {', '.join(common)}（出现在 {df[common[0]]}/{total_sessions} 个会话里），"
                  f"只按 {' '.join(tokens)} 排序", file=sys.stderr)

    results = []
    for row in rows:
        if args.all and not all(
            row["user_hits"][t] + row["asst_hits"][t] + row["head"][t] > 0 for t in tokens
        ):
            continue
        total, per = score(row, tokens)
        if total <= 0:
            continue
        row = dict(row, hits=total, token_hits={k: v for k, v in per.items() if v})
        results.append(row)

    results.sort(key=lambda r: (-r["hits"], -(r["lastMessageTime"] or 0)))

    if args.json:
        print(json.dumps(results[: args.limit], ensure_ascii=False, indent=2))
        return 0 if results else 1

    if not results:
        print(f"没有会话命中：{' '.join(tokens)}")
        print("可以试：拆成更短的词、换成当时原话里的词（中英各试一次）、或去掉 --all。")
        return 1

    shown = results[: args.limit]
    if not args.fast:
        for r in shown:
            r["snippets"] = pick_snippets(r["id"], tokens)
    print(f"命中 {len(results)} 个会话，按相关度排序（{'、'.join(tokens)}），显示前 {len(shown)} 个：\n")
    for i, r in enumerate(shown, 1):
        flags = [f for f, on in (("已归档", r["archived"]), ("已置顶", r["pinned"])) if on]
        print(f"{i}. 【{r['title']}】" + (f"  [{', '.join(flags)}]" if flags else ""))
        print(f"   最近活动 {fmt_time(r['lastMessageTime'])}　创建 {fmt_time(r['createdAt'])}"
              f"　工作区 {workspace_name(r['cwd'])}")
        mine = sum(r["user_hits"].values())
        print(f"   会话 {r['id']}　得分 {r['hits']}（你的发言命中 {mine} 次）")
        if r["first"]:
            print(f"   首条 “{re.sub(r'\s+', ' ', r['first'])[:70]}”")
        for s in r["snippets"][:2]:
            print(f"   · {s}")
        print()
    if any(r["archived"] for r in shown):
        print("注：标「已归档」的会话在左侧默认列表里看不到——把会话列表的筛选切到"
              "「全部对话（显示已归档）」或「仅显示已归档」，再按标题找。")
    return 0


def _has_zstd() -> bool:
    from shutil import which

    return which("zstd") is not None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="按关键字找回历史会话，输出标题以便在左侧列表定位")
    ap.add_argument("query", nargs="*", help="关键字（空格分隔；默认任一命中，--all 为全部命中）")
    ap.add_argument("--limit", type=int, default=8, help="最多显示几个（默认 8）")
    ap.add_argument("--all", action="store_true", help="要求每个关键字都命中")
    ap.add_argument("--fast", action="store_true", help="只搜标题与首条消息，不解压正文")
    ap.add_argument("--keep-common", action="store_true", help="不过滤通用词（会话/session 这类）")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--refresh", action="store_true", help="强制重建索引")
    ap.add_argument("--rebuild", action="store_true", help="只建索引后退出")
    ap.add_argument("--jobs", type=int, default=8, help="解压并行度（默认 8）")
    return run(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
