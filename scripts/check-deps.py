#!/usr/bin/env python3
"""依赖方向守卫（T203）。

plan §3.1 规定依赖只能单向：app -> report/grading/sitting/paper/classroom/identity/shared。
Maven 不会阻止你写一条反向依赖——它只会照你说的解析。所以这里把它变成构建失败。

退出码 0 = 干净；1 = 有越界或环。
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# 唯一真源：每个模块**允许**直接依赖哪些兄弟模块（plan §3.1）
ALLOWED = {
    "shared":        set(),
    "identity":      {"shared"},
    "classroom":     {"shared", "identity"},
    "paper":         {"shared"},
    "sitting":       {"shared", "identity", "classroom", "paper"},
    "grading":       {"shared", "identity", "classroom", "paper", "sitting"},
    "report":        {"shared", "paper", "sitting", "grading"},
    "observability": {"shared"},
    "app":           {"shared", "identity", "classroom", "paper", "sitting",
                      "grading", "report", "observability"},
}

ROOT = Path(__file__).resolve().parent.parent


def internal_deps(module: str) -> set:
    pom = ROOT / f"teachloop-{module}" / "pom.xml"
    if not pom.exists():
        raise SystemExit(f"[check-deps] 找不到 {pom}")
    tree = ET.parse(pom)
    deps = set()
    for dep in tree.getroot().iter(f"{{{NS['m']}}}dependency"):
        gid = dep.findtext("m:groupId", default="", namespaces=NS)
        aid = dep.findtext("m:artifactId", default="", namespaces=NS)
        if gid == "com.teachloop" and aid.startswith("teachloop-"):
            deps.add(aid.removeprefix("teachloop-"))
    return deps


def detect_cycle(graph: dict) -> list:
    colour = {n: 0 for n in graph}          # 0 未访问 1 在栈上 2 完成
    stack, cycles = [], []

    def walk(n):
        colour[n] = 1
        stack.append(n)
        for m in graph[n]:
            if colour[m] == 1:
                cycles.append(stack[stack.index(m):] + [m])
            elif colour[m] == 0:
                walk(m)
        stack.pop()
        colour[n] = 2

    for n in graph:
        if colour[n] == 0:
            walk(n)
    return cycles


def main() -> int:
    graph, problems = {}, []
    for module in ALLOWED:
        deps = internal_deps(module)
        graph[module] = deps
        for d in sorted(deps - ALLOWED[module]):
            problems.append(f"越界依赖：teachloop-{module} -> teachloop-{d}"
                            f"（允许：{', '.join(sorted(ALLOWED[module])) or '无'}）")
    for c in detect_cycle(graph):
        problems.append("存在环：" + " -> ".join(c))

    if problems:
        print("[check-deps] 失败：\n" + "\n".join("  - " + p for p in problems), file=sys.stderr)
        return 1
    print("[check-deps] 通过：" + "，".join(f"{m}({len(graph[m])})" for m in ALLOWED))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
