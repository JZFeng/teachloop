# 表格视图统一（T441–T446）验收报告 — 2026-10-25

**结论**：15 张列表表格已统一到唯一实现 `web/src/ui/DataTable.tsx`（手写表体，以「我的词库」原表格为真源），
旧的 `components/DataTable.tsx` 与 antd `Table` 一起退场；`typecheck` / `build` / 前端 61 单测 /
`make check-ui` / `make check-i18n` 全绿，14 条路由实测无错误态。
验收过程中发现并修复两个真实缺陷：**后端只接受 `size=20/50/100`（默认 10 会 400）**、**操作列按钮折行**。

## 1. 改了什么

| 层 | 文件 | 内容 |
| :--- | :--- | :--- |
| 组件 | `web/src/ui/DataTable.tsx` | 手写表体：表头排序（`aria-sort` + 键盘）、页脚（共 N 条 / 第 x/y 页 / 每页 10-20-50-100）、选择列与批量条、工具栏/空态插槽、加载态；传 `total` = 服务端分页；无翻页回调时自带分页 |
| 状态 | `web/src/ui/listState.ts`、`ui/useListState.ts` | URL 参数统一 `sort` / `dir` / `page` / `size`，读兼容旧 `pageSize`；默认每页 10（AC-24.3 修订） |
| 样式 | `web/src/ui/ui.css` | `tl-table*` 一套规则（单元格 9/10、表头次要色、行悬浮、选中行、页脚、批量条、图标按钮） |
| 我的词库 | `vocab/LibraryPage.tsx` | 表格/分页/批量条交给组件；列配置、筛选弹窗、统计卡、5s 轮询仍在本页；默认每页改 10 |
| 12 个列表 | 首页、试卷、批改总览、批改详情、复核、班级、作业、报表 ×2、班级汇总、学生反馈、界面标准 | 换 import、删 `PageSizePicker`（8 处）、接 `onPageSizeChange`；复核队列与学生反馈的排序/分页首次真正写进 URL |
| 其余列表 | `vocab/SetsPage.tsx`（词集 + 发词记录）、`vocab/AssignmentPreviewPage.tsx`、`ClassesPage` 成员表 | 并入组件；词集表补排序/分页/选择/批量/URL |
| 只读表 | 班级汇总（分数分布、题目正确率）、复核逐题得分 | 只加 `tl-table` 类统一视觉，不加排序分页 |
| 后端 | `teachloop-vocab/.../VocabularyWordsEndpoint.java` | `PAGE_SIZES` 增 10；`size` 默认值 20 → 10；错误文案同步 |
| 守卫 | `scripts/check-ui.py`、`scripts/ui-baseline.txt` | 新增第 6 条规则 `RAW_TABLE`（页面不再新写 `<table>`）；基线 145 → 128 处（14 张只读/编辑表冻结） |

## 2. 没改什么（有意保留）

- 编辑器/表单表：组卷向导 ×5、导入试卷、一次布置（班级覆盖）、考试入场 kv 信息表、词集编辑页词条排序表
- 非数据表：`vocab/ReviewPage` 快捷键图例、`components/Markdown.tsx` 的 Markdown 表格
- 后端数据模型、接口契约、`/api/vocab/prefs` 均未改动（唯一后端改动是分页大小白名单 + 默认值）

## 3. 实测（2026-10-25，dev 5173 + 后端 8010，`--stub --seed`）

14 条路由逐页加载，取 `<table class="tl-table">` 数量与页脚文案：

| 路由 | 表格数 | 页脚 | 错误态 |
| :--- | ---: | :--- | :--- |
| `/` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/papers` | 1 | 共 2 条 · 第 1/1 页 | 无 |
| `/assignments` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/grading` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/grading/:id` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/review` | 2（队列 + 逐题得分） | 共 1 条 · 第 1/1 页 | 无 |
| `/reports` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/classes` | 1（+ 成员表 1） | 共 1 条 · 第 1/1 页 | 无 |
| `/vocab/library` | 1 | 共 25 条 · 第 1/3 页 | 无 |
| `/vocab/sets` | 1 | 共 1 条 · 第 1/1 页 | 无 |
| `/vocab/review` | 0 | — | 无 |
| `/feedback`（学生） | 0（无已提交数据） | — | 无 |
| `/profile` | 0 | — | 无 |
| `/profile?panel=ui-kit` | 1（24 行演示） | 共 24 条 · 第 1/3 页 | 无 |

交互实测（真浏览器）：

- 点表头「单词」→ URL `?sort=form&dir=desc&page=1`，箭头由 ↑ 变 ↓
- 页脚每页改 20 → URL `?size=20`，页码回到第 1 页，行数变 20
- 勾选一行 → 行高亮 + 出现批量条「已选 1 个词 / 批量暂停 / 批量删除」
- 暗色主题：边框、表头、标签、按钮均随令牌变化，无漏色

截图见本目录：`library-light.png`、`library-dark.png`、`library-selection.png`、`papers-light.png`、
`review-light.png`、`sets-light.png`、`classes-members-light.png`、`ui-kit-table-dark.png`、
`grading-detail-dark.png`。

## 4. 验证中发现并修掉的缺陷

1. **默认每页 10 被后端拒绝**：首访 `/vocab/library` 报 `size must be one of 20, 50, 100`。
   `VocabularyWordsEndpoint` 的白名单与默认值都写死 20，已加 10 并同步错误文案；`VocabWordsQueryTest`
   里原本用 `size=10` 断言 400，改为断言 `size=7` 400、`size=10` 正常。
2. **操作列按钮折行**：试卷「编辑/布置考试/复制/归档」被挤成三行。加 `.tl-table td > .row { flex-wrap: nowrap }`，
   并把试卷操作列 240px → 320px。

## 5. 未验证 / 已知缺口

- **改造前截图未留存**：模型改造前未启动服务，`AC-68.5` 的「改造前后截图对照」只有改造后；可替代证据是
  「组件内渲染的正是词库页原来那段 `<table>` markup 与同一套 `tl-table*` 样式」（见 `git show 7602f98` 前的
  `vocab/LibraryPage.tsx:599`）。AC-68.5 因此**未勾选**，留待人工确认。
- **学生反馈页只有空态**：示例学生没有已提交作业，卡片/表格切换与 `?sort/page/size` 的有数据路径未实测。
- **报表「TOP 错题」第二张表**未触发真实查询（无错题数据），本地分页只做了类型与结构校验。
- **无自动化视觉基线**：T308 仍未完成（未装 Playwright），本报告只能给人工截图，不声称视觉回归自动化。
- **后端全量 `make test` 未跑**：只改了 vocab 一处白名单 + 一个测试类，跑的是 `VocabWordsQueryTest`（见下），
  其余模块未回归。
- 界面标准面板的演示表用了 24 行重复数据（为了让排序/翻页/每页条数真的可点），不是真实数据。
