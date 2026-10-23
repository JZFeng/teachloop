# 004 — 背单词与间隔重复（vocab）

> 本文件只钉住**位置、顺序与闸门**。用户故事、验收标准、界面与数据模型一律留到对应阶段写。
> 建立于 2026-10-21；编号 `004`（`003` 已被 `specs/003-ui-standard/` 占用）。

## 流水线状态

| 阶段 | 产出物 | 状态 |
| :--- | :--- | :--- |
| 0. 来源输入 | 外部需求文档 `~/Downloads/flashcard req.md` 的功能抽取（剔除账号/DDL/API/技术栈） | **已完成** 2026-10-23 → [`source-requirements.md`](source-requirements.md)；余 6 个问题待拍板 |
| 1. Research | 5–10 个同类系统（Anki / SuperMemo / Quizlet / 墨墨 / 扇贝 等）的做法与取舍 | **未开始** |
| 2. Constitution | 复核是否需要新增条款（学生个人学习数据的隐私与保留期最可能） | **未开始** |
| 3. Spec | 用户故事 + 可验收的验收标准 + 功能需求（编号从本目录自己的 001 起） | **未开始** |
| 4. Mock ⇄ Spec | 可点击原型，画图时发现的缺口回写 Spec | **未开始** |
| 5. Plan | 架构、数据模型、模块与端口、迁移路径、测试策略（6 条清单） | **未开始** |
| 6. Tasks | 按依赖排序，每个任务自带验收 | **未开始** |
| 7. Implement | 代码 | **未授权**（用户明确说「开始实现」之后才动） |

## 已就位的前置（骨架，2026-10-21）

`teachloop-vocab` 空模块已接进构建与守卫：

- 根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>`
- `teachloop-vocab/pom.xml`（**只依赖 `shared`**）、`package-info.java`、空的 `db/migration/`
- `teachloop-app/pom.xml`：依赖 + `copy-migrations-vocab`
- `scripts/check-deps.py` 的 `ALLOWED["vocab"] = {shared, identity, classroom}`
- `scripts/check-boundaries.py` 的 `MODULES`
- 宪法第十四条已记「已接线、尚无表与业务逻辑」

**骨架里没有任何 `vocab_*` 表、端口、路由、页面与导航。**

## 本模块的铁律

1. **依赖只允许** `shared` / `identity` / `classroom`。需要别的模块的数据时，先改宪法第十四条并说明理由，再改 `scripts/check-deps.py`。
2. **`teachloop-paper` / `teachloop-sitting` / `teachloop-grading` / `teachloop-report` 的改动数必须为 0。** 合并前用这条命令取证，输出贴进 `docs/reports/`：
   ```bash
   git diff --stat main...feat/vocab -- teachloop-grading teachloop-paper teachloop-report
   ```
3. **表前缀 `vocab_`**：新建表必须同时登记到 `scripts/check-boundaries.py` 的 `TABLE_OWNER` 和 plan 的表归属清单，否则 `make test` 直接红。
4. **迁移序号不预留**：迁移文件名 `V<序号>__vocab_<描述>.sql`，序号在合并前重排到 `main` 当下第一个空号（理由见 `docs/decisions/002-branch-and-module-workflow.md`）。
5. **界面文案走 JSON**：新增文案进 `web/src/locales/{zh-CN,en}.json`，组件里禁止中文字面量（`scripts/check-i18n.py` 守）。

## 下一步

写第一步调研：`docs/research/2026-10-21-vocab-spaced-repetition.md`，覆盖至少 5 个同类系统在
「词表来源 / 复习调度算法 / 教师是否介入 / 与班级的关系 / 移动端」上的取舍。
调研过后再定 spec 的第一组用户故事。
