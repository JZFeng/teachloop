# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-22（T356 界面标准收进资料页偏好设置，侧栏删入口；
> T355 答题页共用材料只渲染一次（空数 ≠ 题数时不再重复）；
> T354 所有弹层 Esc 可关（统一弹层栈 + CDP 实测）；
> T341–T353 品牌视觉：新 logo 资产、品牌令牌与 40 组对比度、Brand 组件与壳层、
> 登录页改版、全站页头 eyebrow、/ui-kit 品牌色板、30 张真页面截图；此前 T337–T340 编辑器与 rubric 解析；
> `make test` 260/0/0/0 + 前端 28/28 全绿）

## 当前

- 已实现：**T341（002）补齐剩余编辑框**——试卷向导的评分标准换成 Vditor + 解析预览，并修掉
  `PaperDetail` 不返回 rubric、打开向导永远空白的问题；学生富文本作答加放大 / 原生全屏；
  复核评语、考试作文、整卷/答案表/选项/提示词等原始文本保持 textarea（报告 `docs/reports/t341-remaining-editors/`）
- 已调整：**T356 界面标准收进资料页偏好设置**——用户反馈「组件标准」不该放首页导航。侧栏删 `/ui-kit`
  （教师导航回到 7 项）；`UiKitPage` 收成 `UiKitPanel`，挂在资料页「偏好设置 → 界面标准」行里默认收起、
  按需展开；`?panel=ui-kit` 可深链，旧链接 `/ui-kit` 直接跳到展开态；学生看不到这一行。
  验证：CDP 11/11（导航无入口、默认收起、展开 10 个分区、URL 同步、旧链接跳转、收起、无控制台异常、
  学生无该行），亮/暗/收起截图 `docs/reports/t356-ui-kit-in-profile/`；`typecheck`/`build` 0、
  `check-ui` 0 新增、`check-i18n` 1202 键对齐

- 已修复：**T354 所有弹层 Esc 可关**——用户反馈批改详情的审计弹层按 Esc 没反应。真因：只有交卷前检查
  自己挂了 `window` keydown，审计抽屉、放大编辑器、窄屏目录各写各的，完形选项弹层干脆没写。
  新增组件层 `ui/escapeStack.ts`（纯逻辑 LIFO 栈）+ `ui/useEscapeClose.ts`（document 级监听）：
  后开的先关，栈顶拒绝关闭（提交中 / 浏览器原生全屏）时这次 Esc 被本层拦住不穿透，输入法组合中的
  Esc 不关弹层。接入 6 处：审计抽屉（补 `role="dialog"`/`aria-modal`，复习与批改两页共用）、
  放大编辑器（MarkdownEditor / VditorMarkdown）、交卷前检查、答题页窄屏目录、完形/七选五 A–G 选项。
  验证：CDP 真键盘事件在 dev 5173 实测审计抽屉与放大编辑器 Esc 关闭、路由与表格不动、无控制台异常
  （截图 `docs/reports/t354-esc-dismiss/`）；栈逻辑单测 8/8；`make test` 退出码 0（surefire 255/0/0/0、
  前端 28/28，含本次新增 8 条）、`typecheck`/`build` 0、`check-ui`/`check-i18n`（1200 键）/`contrast` 40 组/
  `check-deps`/`check-boundaries` 全过

- 已修复：**T355 同一篇材料只渲染一次（空数 ≠ 题数）**——用户报答题页里第 7、8 题各渲染了一整篇
  同样的七选五文章，9/10、11/12 也一样。先查提交历史：T321（`2a9a2a1`）的「共用材料只渲染一次」在，
  不是被回退。真因三条：①七选五原文 5 个空、这份卷只导入 7、8 两题，分组校验要求「空数 = 题数」→ 整组退回；
  ②同一材料在每道题的题干里各存一份，兄弟题的空写成 `______ (9)`，两份材料不相等 → 分组断在 9/10、11/12；
  ③前端按数组下标配对空位。改法：后端放宽为「空数 ≥ 题数 + 空位序号严格递增不重复 + 一个空只放一道题」，
  并洗掉卷内题号的空位提示（`(2019)` 这类正文括号不动）；前端按 `blankIndex` 落位，没有题目的空位画横线。
  验证：真实快照分组 g5/g7/g9/g11 正确，`MaterialGrouperTest` 13/13、前端 `exam-grouping` 6/6、
  CDP 真页面 18/18（三篇材料各只出现一次、控件顺序 `7,8,gap,gap,gap`、点空弹 A–G、选 G 自动保存），
  截图 `docs/reports/t355-cloze-grouping/`；`pnpm test` 28/28、`typecheck` 0、`check-ui`/`check-i18n` 通过

- 已实现：**T341–T353 品牌视觉（决策 006 / `specs/006-brand-visual/`）**——前端换成新 logo 品牌层：
  亮色只做填充（亮蓝对白 3.34:1，不能当按钮底），主操作 `#1A63E0`（白字 5.38:1）、暗色 `#2A6FD6`；
  四个功能位 Create / Assess / Practice / Insights 与判分三态分命名空间；暗底改 `#0A1120`；
  `contrast.py` 16 → 40 组（暗色背景改读令牌，不再写死 `#141414`）；新增 `--accent-text`
  （暗色 `#7DB1FF`，对面板 7.96:1）承接全站文字蓝；`Brand` 组件（mark / text / lockup，暗色文本字标）、
  侧栏功能色胶囊、登录页整标 + 四个功能位、全站页头 eyebrow 按路由映射；`/ui-kit` 加品牌色板；
  品牌资产由 `scripts/brand/build-assets.py` 生成（源图 sha 固定、四底色无白边）；
  30 张真页面截图在 `docs/reports/brand-app-2026-10-22/`。
  守卫：contrast 40/40、check-ui 0 新增、check-i18n 1197 键对齐、typecheck / build 0、
  `make test` 250/0/0/0 + 前端 14/14。
- 已修复：**T340 rubric 解析 + 预览（决策 005）**——`#` 分组标题/说明行不再被当成「缺分值的评分项」
  （此前会 400 拒绝发布，判分侧还会 6×2.3 均分、丢 92 字符长问句）；三处解析统一选择规则与 500 字符上限，
  无分值条目只提示不阻断；「AI 将看到」改成编号清单 + 分值右对齐 + 「另有 N 行没有分值」，
  不再承诺「可以发布」；共享夹具 + 三处测试锁规则
- 已实现：**T339 两个字段换 Vditor IR（决策 004）**——新建作业的题目要求 / 评分标准改成 Typora 式
  即时渲染（光标行显示 Markdown 标记、其余行实时渲染），顶部只有一个「源码」按钮（源码模式变「返回编辑」），源码是纯 Markdown textarea、
  等宽字体、无预览、Cmd+Z 立即撤销；保留 T337 的放大 / 原生全屏；编辑器懒加载（chunk 72 kB gzip，Lute 引擎 592 kB gzip 初始化时加载）；
  `make web-test` 用真实 rubric 做 Markdown 往返金标（5/5），并接入 `make test`
- 已修复：**T337 两个 Markdown 编辑器窗口太小**——工具栏右侧加「放大」，放大态铺满右侧内容区
  （侧栏保留）、默认并排编辑 + 预览、可再进浏览器原生全屏、Esc 收起；窄屏铺满整屏
- 已修复：**T335 批改 run 收尾**——worker 每批跑完后把没有剩余工作项的场次标 `done` + `finished_at`，
  有新提交进队时重新打开；此前所有 run 永远 `running`，分不清一次派发跑完没有
- 已实现：**T330–T333 作业夜间批量批改（决策 003）**——作业不再等 due+宽限，改成每晚 02:30
  （Asia/Shanghai）扫一次「还有排队提交」的场次批量派发，启动补跑；考试保持 due+宽限 ~1 分钟派发；
  复核队列只显示最新 active attempt（旧 attempt 的草稿不占队列）；新建作业页与批改详情页写明这条规则
- 已修复：**T329 复核与报表按归属收口**——新增 `Ports.OfferingOwnership` 与 `classIdsOwnedBy`：待复核队列、
  复核详情/结论/批量通过、报表与导出都只认自己布置的提交与班级（越权 404）。首页「待复核」数字不再混入别人的提交
- 已修复：**T324 布置按教师收口**——`GET /api/offerings` 只返回自己的布置，`detail`/`submissions`/
  `class-summary` 越权 404，`V035` 回填 V030 之前的空 `created_by`。教师首页「最近作业」不再出现
  别的老师的作业与解析不出名字的裸 UUID 班级
- 已实现：**T325 新建作业页 Markdown 化**——评分标准不再预填英文示例、导入 .md 变成标签旁边的真按钮、
  题目要求与评分标准都换成 Markdown 编辑器（工具栏 + 编辑/预览）、「AI 将看到」改成解析出的评分项与
  合计对比；参考范文去掉「锚点」字样
- 已修复：**T326「成员」白屏**——后端返回 `{studentId,displayName,submissionCount}` 而前端按 `string[]`
  渲染对象，React 19 卸载整棵树；修类型与成员表，并加路由级 `ErrorBoundary`
- 已修复：**T327 导入器题号占用**——七选五「5 个空但只有 2 道题」时填空顺推吃掉了完形 9/10 与语法填空 11，
  答案区「11. challenging」被当选择题答案；现在遇到卷面已有题号即停并告警，`AI立即.md` 解析 1–14 题答案全对
- 已修复：**T328 rubric 两处误杀**——`是否…` 问句式评分项被当 Yes/No 判定行、超过 80 字符的整句被静默丢弃，
  这正是「评分项合计 8 分，作业总分 14 分」的真因；`评分标准2.md` 现在解析 2/6/3/3 = 14
- 已修复：**T334 复核页去重 + 题干格式化**——学生正文只渲染一次（删掉「学生作答」块里重复的正文与
  「AI 草稿」标签）；主观题题干改用学生答题页同一个 `Markdown` 组件，`Instructions`、标题、列表与换行
  按老师输入显示；按用户拍板删掉「采用 AI 建议分」按钮（主观分首次打开已预填 AI 分，按钮只在改分后能改回）
- 已修复：**T336 Markdown 编辑器撤销/重做 + 快捷键**——`/assignments/new` 原来 Cmd+Z 退不动：受控 textarea
  每次按键都被 React 回写，原生撤销被拆成一次一个字符，工具栏插入的 `**` 更是退不掉；改为自维护「编辑前快照」
  撤销/重做栈（同类输入 700ms 合并一步；光标移动、粘贴、工具栏动作断步；IME 组合结束记一步；导入 .md 也记一步），
  支持 Cmd/Ctrl+Z、Cmd/Ctrl+Shift+Z（Ctrl+Y）、Cmd/Ctrl+B、Cmd/Ctrl+I、Cmd/Ctrl+Shift+8 / +7（列表），
  工具栏 title 带快捷键
- 正在做：**评分质量 T255–T267 已实现并全量回归通过**；服务端 rubric 校验、AI 溯源两列、锚点简化版已补；剩按项编辑器与批量通过并发布
- 已修复：**T320 答题页题干标签**——阅读材料标签 A–D 不再拼进题干，V032/V033 清理存量
  题干与已发布快照；QA 已部署到 v033，学生接口与页面不再出现孤立字母
- 已实现：**T321 共用材料只渲染一次**——阅读材料一次 + 题目列表；完形/七选五空在原文里
  （点空弹 A–D/A–G）；语法填空行内输入；QA 实测整卷高度 32,147 → 14,286 px
- 已修复：**T322 RubricValidator 文案 i18n**——5 条问题文案进 zh/en 消息包，解析词表移到
  domain；`check-i18n` 基线从 311 降到 290，不再靠扩基线绕过
- 已修复：**T323 复核页逐题判分可视化**——题项表拆「得分/满分」，对绿/错红/部分琥珀；
  教师判分区前置学生正文、主观题作答、答题卡与 AI 草稿预填提示；QA 5174 已部署
  （T334 起正文只在「学生正文」显示一次，重复块已删）
- 已确认：初始化/登录/资料三处配 DeepSeek Key、Key 永不明文、反馈语言跟学生界面语言、
  作业 rubric 优先、问候语不计段、AI 反馈预填 + 批量通过
- 背单词：`teachloop-vocab` 空模块已接线（**无表、无端口、无路由**），规格目录 `specs/004-vocab/` 待写
- 分支 / HEAD：`main`（`feat/ui-standard` 已合入并删除）；背单词的实现分支尚未创建，
  届时用 `git switch -c feat/vocab main`
- 未提交 WIP：以 `git status` 为准（本行不再写快照，避免文档腐化）
- 最近验证：真实 DeepSeek（教师个人 DB Key，不设环境变量）对 Maria/Mark 与 Iam/Alejandro 判分成功；
  golden set 逐项 exact 29/30、总分 9/10、契约失败 0、英文语言合规 10/10；
  `pnpm typecheck` / `pnpm build` 0、`check-i18n` 1153 键（zh/en 对齐，T334 删掉 3 个不再使用的 key）、
  `check-ui` 无新增违规；
  **`make test` 退出码 0，surefire 243 tests / 0 failures / 0 errors / 0 skipped**；
  `check-deps` / `check-boundaries` / `check-i18n`（后端基线 290）/ `check-ui` 全通过。
  T322 已把 `RubricValidator` 的校验文案改成消息 key，中文解析词表移到 `domain/RubricVocabulary`。
  T334 复核页三处改动已在 QA 5174 实测（截图 `docs/reports/t334-review-cleanup/`）；T334 为前端改动，`make test` 仍退出码 0（243/0/0/0）。
  T336 编辑器撤销/重做与快捷键已在 QA 5174 用 CDP 键盘事件实测（`hello` → Cmd+Z 清空、Cmd+Shift+Z 恢复；选中 `abc` + Cmd+B → `**abc**`；Cmd+Shift+8 → `- abc`；工具栏加粗后可撤销；中文「你好世界」可撤销/重做；两个编辑器都生效），截图 `docs/reports/t336-editor-shortcuts/`；`make test` 仍 243/0/0/0。
  T354 弹层 Esc 关闭已在 dev 5173 用 CDP 真键盘事件实测：审计抽屉（Jason · 审计，6 条事件）与放大编辑器
  打开后按 Esc 均关闭，路由 `/grading/bfd90d97-…` 与表格行不变，关闭后再按 Esc 无副作用；截图
  `docs/reports/t354-esc-dismiss/`；栈逻辑 `web/tests/escape-stack.test.mjs` 8/8、`make test` 退出码 0（前端 28/28）。
  **未用浏览器验证**的弹层：交卷前检查、答题页窄屏目录、完形/七选五选项弹层（走同一个 hook，无独立按键证据）。

## 下一步

1. 按评分项编辑器、锚点自动匿名化、批量通过并发布（可选）
2. 生产上线前按 `docs/DELIVERY-STATUS.md` 补已知偏差（OSS/PDF 字体/AC-18.5 等）
3. 用真实微信凭据与生产数据库做一次部署演练

## 剩余

- 评分质量：按评分项编辑器、锚点自动匿名化、批量通过并发布（可选）
- UI 标准：T308（21 屏截图基线）、T309（剩余 13 屏组件化）、T310（删兼容层）未做
- 品牌视觉（006）未验证视觉：考试作答 live 页（要真实开始一次作答）、管理员 AI 设置（要 ADMIN 角色）、
  反馈详情页、`/setup`、跨浏览器；证据与口径见 `specs/006-brand-visual/spec.md` 6.2
- 其余无未完成任务；已知偏差见 docs/DELIVERY-STATUS.md
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；压测为 stub 引擎
