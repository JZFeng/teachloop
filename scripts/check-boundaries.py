#!/usr/bin/env python3
"""模块边界守卫（T204）。

把 plan §3.2 / §3.3 / §3.5 的三条硬规则变成构建失败：

1. 迁移只能写**本模块拥有的表**（表归属清单见 plan §3.3）。
2. 业务模块不得 import / 直接查询其他模块的内部类型或表（端口在 shared）。
3. 端口签名只收发 `record`：出现 `@Entity`（或任何非白名单类型）即失败。
4. 跨模块外键必须在 plan §3.5 的登记清单内；新增要先登记。

退出码 0 = 干净；1 = 有越界。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MODULES = ["shared", "identity", "classroom", "paper", "sitting",
           "grading", "report", "observability", "app"]

# plan §3.3：表归属。新增表必须同时登记在这里与 plan。
TABLE_OWNER: dict[str, str] = {
    "users": "identity",
    "spring_session": "identity",
    "spring_session_attributes": "identity",
    "teacher_invites": "identity",
    "rate_limits": "identity",
    "notifications": "identity",

    "classes": "classroom",
    "class_members": "classroom",

    "papers": "paper",
    "paper_sections": "paper",
    "objective_items": "paper",
    "section_criteria": "paper",

    "offerings": "sitting",
    "offering_classes": "sitting",
    "submissions": "sitting",
    "objective_answers": "sitting",
    "submission_metrics": "sitting",

    "grading_runs": "grading",
    "reviews": "grading",
    "item_explanations": "grading",
    "ai_calls": "grading",

    "audit_logs": "observability",
    "daily_stats": "observability",
}

# plan §3.5：允许的跨模块外键（(引用方表, 被引用表)）。新增一条要在这里和 plan 同时登记。
ALLOWED_CROSS_FKS: set[tuple[str, str]] = {
    ("classes", "users"),
    ("class_members", "users"),
    ("papers", "users"),
    ("offerings", "papers"),
    ("offering_classes", "classes"),
    ("submissions", "users"),
    ("objective_answers", "objective_items"),
    ("grading_runs", "submissions"),
    ("grading_runs", "offerings"),
    ("reviews", "submissions"),
    ("reviews", "users"),
    ("item_explanations", "objective_items"),
    ("audit_logs", "users"),
}

# plan §3.2：T205 之后必须存在的端口。
REQUIRED_PORTS = {
    "PaperCatalog", "SittingLedger", "GradingWorkQueue", "GradingOutcome",
    "NotificationPort", "ClassMembership", "GradingDispatch",
}

# Ports.java 顶层 record（端口 DTO 白名单）。
PORT_RECORDS = {
    "ItemSpec", "AnswerRow", "PaperSnapshot", "WorkItem",
    "CriterionScore", "ItemExplanation", "GradingResult",
}

# 端口签名里允许出现的 JDK 类型（基类名）。
PORT_JDK_TYPES = {
    "void", "boolean", "int", "long", "String", "UUID", "BigDecimal",
    "List", "Set", "Map", "Collection", "Optional", "Object",
}

COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
SQL_TABLE_OP = re.compile(
    r"\b(?:from|join|update|insert\s+into|delete\s+from)\s+([a-z_][a-z0-9_]*)", re.I)
TABLE_NAME_RE = re.compile(r"^[a-z_][a-z0-9_]*$")


def strip_comments(text: str) -> str:
    return COMMENT_RE.sub("", text)


def sql_statements(text: str) -> list[str]:
    body = re.sub(r"--[^\n]*", "", text)
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    return [s.strip() for s in body.split(";") if s.strip()]


def owner(table: str) -> str | None:
    return TABLE_OWNER.get(table)


def check_migrations(root: Path, problems: list[str]) -> None:
    for module in MODULES:
        migration_dir = root / f"teachloop-{module}" / "src" / "main" / "resources" / "db" / "migration"
        if not migration_dir.exists():
            continue
        for sql_file in sorted(migration_dir.glob("*.sql")):
            name = re.match(r"V\d+__([a-z]+)_", sql_file.name)
            if name is None:
                problems.append(f"迁移命名不合规：{module}/{sql_file.name}"
                                f"（应为 V<序号>__<模块>_<描述>.sql）")
            elif name.group(1) != module:
                problems.append(f"迁移文件模块前缀与目录不符：{module}/{sql_file.name}"
                                f"（前缀是 {name.group(1)}）")

            for stmt in sql_statements(sql_file.read_text(encoding="utf-8")):
                lower = stmt.lower()
                touched: str | None = None
                m = re.search(r"\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_]*)", lower)
                if m:
                    touched = m.group(1)
                else:
                    m = re.search(r"\balter\s+table\s+(?:if\s+exists\s+)?([a-z_][a-z0-9_]*)", lower)
                    if m:
                        touched = m.group(1)
                    else:
                        m = re.search(r"\bcreate\s+(?:unique\s+)?index\s+\w+\s+on\s+([a-z_][a-z0-9_]*)", lower)
                        if m:
                            touched = m.group(1)

                if touched:
                    if owner(touched) is None:
                        problems.append(f"未登记归属的表：{touched}（{module}/{sql_file.name}）")
                    elif owner(touched) != module:
                        problems.append(
                            f"越界写表：{module} 的迁移 {sql_file.name} 触碰了 {touched}"
                            f"（属主是 {owner(touched)}）")

                for ref in re.findall(r"\breferences\s+([a-z_][a-z0-9_]*)", lower):
                    if touched is None:
                        problems.append(f"无法判断外键的引用方：{module}/{sql_file.name}")
                        continue
                    src_owner, ref_owner = owner(touched), owner(ref)
                    if src_owner is None or ref_owner is None:
                        problems.append(f"未登记归属的外键：{touched} -> {ref}（{sql_file.name}）")
                        continue
                    if src_owner != ref_owner and (touched, ref) not in ALLOWED_CROSS_FKS:
                        problems.append(
                            f"未登记的跨模块外键：{touched} -> {ref}（{sql_file.name}）；"
                            f"先在 plan §3.5 登记再改这里")


def check_module_isolation(root: Path, problems: list[str]) -> None:
    """业务模块不得 import 其他模块的内部类型，也不得用原生 SQL 查别人的表。"""
    for module in MODULES:
        if module in {"app", "report"}:  # report 是 plan §3.2 允许的只读聚合豁免；app 是装配层
            continue
        src = root / f"teachloop-{module}" / "src" / "main" / "java"
        if not src.exists():
            continue
        for java_file in sorted(src.rglob("*.java")):
            text = strip_comments(java_file.read_text(encoding="utf-8"))
            for other in re.findall(r"com\.teachloop\.([a-z0-9]+)\.", text):
                if other in {module, "shared"} or other not in MODULES:
                    continue
                problems.append(
                    f"越界引用：teachloop-{module}/{java_file.name} 引用了 "
                    f"com.teachloop.{other}.*（跨模块只能走 shared 里的端口）")

            for sql in re.findall(r'@Query\(\s*(?:value\s*=\s*)?"([^"]+)"', text):
                for table in SQL_TABLE_OP.findall(sql):
                    if table not in TABLE_OWNER or owner(table) in {module, None}:
                        continue
                    if table in {"audit_logs"}:
                        continue
                    problems.append(
                        f"越界 SQL：teachloop-{module}/{java_file.name} 直接查询属于 "
                        f"{owner(table)} 的表 {table}")


def _strip_generics(type_name: str) -> list[str]:
    # 去掉全限定名里的包前缀，报错只留类型名
    simplified = re.sub(r"\b(?:[a-z_][A-Za-z0-9_]*\.)+([A-Z][A-Za-z0-9_]*)", r"\1", type_name)
    return re.findall(r"[A-Za-z_][A-Za-z0-9_]*", simplified)


def _split_top_level(params: str) -> list[str]:
    parts, depth, current = [], 0, ""
    for ch in params:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)
    return parts


def _port_signature_types(signature: str) -> list[str]:
    head, _, tail = signature.partition("(")
    body = tail.rsplit(")", 1)[0]
    head_tokens = head.split()
    # 方法名是最后一个 token，返回类型在它前面；异常声明不参与
    types = _strip_generics(" ".join(head_tokens[:-1]))
    for param in _split_top_level(body):
        cleaned = re.sub(r"@\w+(\([^)]*\))?", "", param).strip()
        if not cleaned:
            continue
        # 去掉参数名（最后一个 token），剩下的是类型
        tokens = cleaned.split()
        if tokens:
            types.extend(_strip_generics(" ".join(tokens[:-1])))
    return types


def check_port_signatures(root: Path, problems: list[str]) -> None:
    ports_file = root / "teachloop-shared" / "src" / "main" / "java" / "com" / "teachloop" / "shared" / "port" / "Ports.java"
    if not ports_file.exists():
        problems.append("找不到 Ports.java（shared 端口契约）")
        return
    text = strip_comments(ports_file.read_text(encoding="utf-8"))

    if re.search(r"import\s+com\.teachloop\.[a-z0-9]+\.domain", text):
        problems.append("Ports.java 引用了业务模块的 domain 类型（端口只能收发 record）")

    found_records = set(re.findall(r"public\s+record\s+(\w+)\s*\(", text))
    found_interfaces: set[str] = set()
    for match in re.finditer(r"public\s+interface\s+(\w+)\s*\{", text):
        name = match.group(1)
        found_interfaces.add(name)
        depth, pos = 1, match.end()
        while pos < len(text) and depth > 0:
            if text[pos] == "{":
                depth += 1
            elif text[pos] == "}":
                depth -= 1
            pos += 1
        body = text[match.end(): pos - 1]
        for chunk in body.split(";"):
            signature = chunk.strip()
            if "(" not in signature or ")" not in signature:
                continue
            signature = re.sub(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*", "", signature)
            # default / public / static 等修饰符不是返回类型
            signature = re.sub(r"^(?:(?:default|public|private|protected|static|final)\s+)+", "", signature)
            for token in _port_signature_types(signature):
                if token in PORT_JDK_TYPES or token in PORT_RECORDS or token in found_records \
                        or token in found_interfaces:
                    continue
                problems.append(
                    f"端口签名不合规：{name} 里出现类型 {token}；"
                    f"端口只允许 record 与 JDK 值类型，不得传 @Entity")

    for required in sorted(REQUIRED_PORTS - found_interfaces):
        problems.append(f"缺少端口定义：{required}（T205 / plan §3.2）")


def main() -> int:
    parser = argparse.ArgumentParser(description="TeachLoop 模块边界守卫（T204）")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()
    root = Path(args.root).resolve()

    problems: list[str] = []
    check_migrations(root, problems)
    check_module_isolation(root, problems)
    check_port_signatures(root, problems)

    if problems:
        print("[check-boundaries] 失败：\n" + "\n".join("  - " + p for p in problems), file=sys.stderr)
        return 1
    print(f"[check-boundaries] 通过：{len(TABLE_OWNER)} 张表归属清晰，"
          f"{len(ALLOWED_CROSS_FKS)} 条跨模块外键已登记，{len(REQUIRED_PORTS)} 个端口只收发 record")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
