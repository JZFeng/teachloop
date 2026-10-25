# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-22（T359 品牌源图归档与指纹守卫；
> T358 明确「首页/班级是中性导航」的口径并写进决策/规格/UI Kit；
> T357 导入时空里带题号 `______(7)___` / `__(7)__` 不再多算空；
> T356 界面标准收进资料页偏好设置，侧栏删入口；
> T355 答题页共用材料只渲染一次（空数 ≠ 题数时不再重复）；
> T354 所有弹层 Esc 可关（统一弹层栈 + CDP 实测）；
> T341–T353 品牌视觉：新 logo 资产、品牌令牌与 40 组对比度、Brand 组件与壳层、
> 登录页改版、全站页头 eyebrow、/ui-kit 品牌色板、30 张真页面截图；此前 T337–T340 编辑器与 rubric 解析；
> `make test` 265/0/0/0 + 前端 28/28 全绿）

## 当前

- 已实现：**T380–T385 全局 AI provider + 模型选择 + 教师级批改时机（D-30/D-33）**。
  v1 口径按可点 mock 收敛：管理员在 `/admin/ai` 维护 provider 列表（Provider ID、显示名、Base URL、
  OpenAI 兼容协议、可空 Key、`/v1/models` 模型发现），第一个 provider 自动成为当前模型；
  **只有管理员能切换当前模型**（D-33，2026-10-24 修订）：教师页没有任何 AI 入口，教师切模型接口返回 403；学生只看到账号/语言/主题。
  作业批改改为教师级偏好：默认固定时间 02:30 Asia/Shanghai（可改时刻/时区/补跑），可切 due-date；
  due-date 过 `due+grace` 后 60 秒扫描派发，不看任何时段。
  管理员表单可「获取可用模型」后勾选多个模型；教师下拉按 provider+model 展开。验证：干净 worktree
  `make test` **391/0/0/0**；`AiSetupTest` 覆盖建 provider 两个模型、教师切第二个、学生 403；
  前端 `tsc`/`build`/33 测试；`check-deps`/`check-boundaries`（48 表）/`check-i18n`（1552 键）/
  `check-ui` 全过；QA 5174 自带假 OpenAI 服务，`scripts/verify/ai-model-settings.mjs`
  **10/10 PASS**（获取模型→勾两个→保存→教师下拉两个→切换生效），截图 `docs/reports/t386-ai-model-settings/`。
  **未验证**：真实本地模型（Ollama/LM Studio）端到端判分（假服务覆盖了模型发现与选择链路）。

- 已改口径：**决策 014 撤除峰谷调度**（2026-10-24）。峰谷是 DeepSeek 一家的分时计价，provider 可换后不成立；
  避开高峰是管理员的成本判断，不该由代码替人决定。删除 `GradingPeakHours` / `GradingDispatchPolicy` /
  `autoDispatch()` 高峰前置判断 / `grading.peak-aware`，考试到期即派、迟到补派发不再被高峰跳过。
  验证：`GradingDispatchTest` 8/8（含 `autoDispatchIgnoresTimeOfDay`）、`NightlyAssignmentGradingTest` 1/1、
  `FlowIntegrationTest` 3/3。

- 已实现：**T414–T420 AI 调用重试与熔断（D-31 / US-62，2026-10-24）**。
  调用层对 429/5xx/连接失败重试 2 次（2s、8s + 抖动，`Retry-After` ≤30s 优先），
  同一 provider 连续 5 份失败熔断、5 分钟半开只放 1 份；打开/恢复通知教师与管理员，
  恢复后把熔断期间的失败项自动重派一次；教师与管理员在批改总览/详情看到熔断横幅。
  状态存库（V050 `ai_circuit_state`），`submissions` 加 `grading_failed_at` / `grading_circuit_requeues`（V051）。
  验证：`RetryingLlmClientTest`、`GradingWorkerCircuitTest`、`AiCircuitTest`、全量 `./mvnw test` 见执行记录；
  QA 截图 `docs/reports/ai-circuit-2026-10-24/`。

- 已改口径：**站内通知存事实、读取时渲染（D-33，2026-10-24）**。
  用户反馈中文界面收到英文通知。根因是文案在写入那一刻按「作业 `feedback_language`」或「`users.locale`」固化成 text。
  `notifications` 增 `payload jsonb`（V052 去掉 `title` NOT NULL），四类通知只传事实，
  读取时按 `Accept-Language` 用 `messages*.properties` 渲染；历史 714 行保留原文，不补迁移。

- 已改口径：**T371 首页/班级上色，报表改靛蓝**（2026-10-23，**推翻 T358 的「中性导航」结论**）。
  用户先说首页、班级「太素」，选定海军蓝 + 淡紫；随后说报表的玫红在暗色下像出错，从七种候选里选了靛蓝。
  落地：新增 `SPACE_*` 命名空间（`--space-home` / `--space-classes`，只进导航、不进口 eyebrow），
  Insights 改 `#3F51B5` / 暗 `#A5B4FC`，Practice 接玫红 `#A63960`；对比度 40 → **44 组**。
  证据：`docs/mock/nav-color-2026-10-23/`、`docs/mock/insights-color-2026-10-23/`、
  `docs/reports/nav-color-2026-10-23/`（37 张真页面截图）；AC-31.2 与决策 006 已回写。

- 已整理：**T359 品牌源图归档**——用户给的两张图确认都在仓库里且与附件逐字节一致：
  `docs/brand/teachloop-logo-source.png` 是前端资产的**构建源**（生成 `web/public/brand/` 9 个文件），
  `docs/brand/teachloop-brand-sheet.png` 是**四功能位参考页**（不参与构建，留给以后品牌页/关于页/对外素材）。
  新增 `docs/brand/README.md` 写清用途、sha256、重新生成命令与以后怎么取用；把参考图的 sha256
  一并写进 `scripts/brand/build-assets.py`，`--check` 现在同时校验两张源图 + 产物，删了/改名/替换会直接报错。
  验证：`python3 scripts/brand/build-assets.py --check` 通过（两张源图指纹一致、产物齐全）。

- 已确认：**T358 首页/班级的导航为什么没有颜色**——用户问「是故意的吗」。结论：是，不是漏做。
  决策 006 / AC-31.2 只给三个已上线功能位配功能色（作业·试卷 Create、批改·复核 Assess、
  报表·我的反馈 Insights）；首页、班级、资料、通知、管理员 AI 是中性导航，图标用 `--muted-2`，
  `Shell.tsx` 的 `NavItem` 不配 `tone`。本轮只补口径不改配色：决策 006 加「导航着色口径」、
  spec 006 §5.3 加说明并在 AC-31.2 补「其余导航无胶囊」、`Shell.tsx` 导航定义处加防误改注释、
  UI Kit 品牌区加一句中英说明（`ui.kit.navToneNote`）。
  验证：`node scripts/verify/nav-tone-note.mjs` 对 QA 5174 实测 10/10（首页/班级无胶囊、
  三组功能色正确、UI Kit 中英说明可见、切英文后仍中性、无控制台异常），截图
  `docs/reports/t358-nav-tone-note/`；`typecheck`/`build` 0、`check-i18n` 1203 键、`check-ui` 无新增、
  `make test` 265/0/0/0。

- 已修复：**T357 导入时「空里带题号」的写法**——用户实测：`______(7)` 正常，写成 `______(7)___`
  或把 `(7)` 用下划线包起来（`__(7)__`、`______<u>(7)</u>`）就导入失败。真因：`BLANK` 只认连续 3 个以上
  下划线，题号两边的下划线被数成两个空（七选五 5 个空变成 6 道题，题号与答案区整体错位）；
  `__` 写法更是一个空都认不到。改法：`PaperTextParser.BLANK` 把「空 + 括号题号 + 紧贴下划线」认成一个空
  （括号里只认 1–3 位数字，`______ (2019)` 这种正文年份不吃），并以卷面写的题号为准
  （`______(7)` 就是第 7 题，没写题号的空继续顺推）；`labelBlank` 把其余空收成 `______` / `______ (8)`，
  答题页的 `MaterialGrouper` 仍能把同一篇材料只渲染一次；`MarkdownSyntax` 剥掉 `<u>` / `<ins>` 标签。
  验证：`PaperTextParserTest` 新增 5 条；QA 5174/8011 四种写法各导入 5 题（7–11）且答案
  7 G / 8 A / 9 E / 10 D / 11 F 全对，导入后的题干交给 `MaterialGrouper` 实跑为 `g7`（blankIndex 0–4，
  材料只渲染一次）；`make test` 265/0/0/0。

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
- 背单词：`teachloop-vocab` 已实现并合 main（T387–T413；17+3 张表 V040–V049，28,276 族 / 80,579 形词表索引，
  词库/详情/复习/自由复习/加词导入/词集/发词/预习页，SM-2 两档调度）；详情与已知偏差见本文件
  「背单词（004-vocab，2026-10-24）」
- 分支 / HEAD：`main`（`feat/ui-standard` 与背单词分支均已合入并删除，当前只剩 `main`）
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

## 2026-10-22 · 写作题按题项存 rubric、按篇判分（D-23~D-27，T360–T367）

用户拿两份真实卷子试导入后提出的问题：`整个考卷含写作.md` 第 191 行报
「无法解析的答案行「15. 应用文写作示范范文：」」；`paper and answer.md` 能导入但第四部分写作
0 题、只有 120 分（卷面 150），向导第 4 步只有一个 rubric 输入框。

- **规格**：D-23~D-27；AC-4.21~4.23（写作建题、分值语法、参考范文）、AC-27.17~19（题项级
  rubric、逐篇判分、正文来源与空白）；FR-082~084。调研 `docs/research/2026-10-22-essay-rubric.md`
  （12 个同类系统）、决策 `docs/decisions/007-essay-per-item-rubric.md`。
- **数据**：`V036` 给 `objective_items` 加 `rubric_markdown` / `reference_markdown`（可空、不进总分）；
  `Ports.ItemSpec` 增题项 rubric，旧构造器与旧快照空值回退。
- **导入**：写作/口语部分按「第X节」或带题号的写作任务行建 `ESSAY` 题项；`满分 N 分`、
  `(N points)`、`（N分）`、`N pts`、`每小题 X points` 都能解析；答案区按写作题号绑定参考范文，
  范文里以数字开头的行不再报错；粘贴答案表遇到写作题号整块跳过并提示。
  实测：`整个考卷含写作.md` 0 报错、14 客观 + 15/16 两道写作题（15 分/篇，范文各归其题）；
  `paper and answer.md` 75 客观 + 76/77 两道写作题、总分 150、缺答案 0。
- **判分**：`SubjectiveGradeRequest.forEssay` 先取 `answers[]` 里该题号正文（仅单篇作业回退
  `content_text`），本题 rubric 优先、空回退试卷级；`SubjectiveGradingLoop` 逐篇调用并合并
  （主观分求和、criteria 带 `itemNumber`、三段文本分段、空白篇 0 分不调用）；prompt 升 `subjective-v3`。
  这同时修掉「考试作文只读 `content_text`、等于空文本判分」的旧缺陷。
- **前端**：向导第 4 步每道写作题一块 rubric（Vditor + 合计/满分预览）+ 参考范文编辑器；
  导入页写作题提示；复核页 AI 评分项按题分组、写作题 rubric 可展开；zh/en 1218 键对齐。
- **验证**：`make test` 退出码 0，surefire **286 tests / 0 failures / 0 errors / 0 skipped**；
  前端 28/28；`check-deps` / `check-boundaries`（25 表 / 13 外键 / 7 端口）/ `check-i18n` / `check-ui` 全过。
  新增 `FlowIntegrationTest.twoEssaysAreGradedPerItem`：两道写作题各自 rubric 进快照 → 学生按
  `answers[]` 交两篇 → 逐篇判分合并 → 复核 `ai_criteria` 带题号 15/16、主观分 22.6（stub 0.75×30）。
- **追加（2026-10-23）D-28**：没有 rubric 的作文改为「内容 80% + 语法与拼写 20%」判分（内容四舍五入取整，14 分 → 11+3、70% → 11+4），比例走配置
  `teachloop.grading.essay-default-rubric.content-percent`（默认 80，env `TEACHLOOP_ESSAY_CONTENT_PERCENT`）；
  老师的题项级 rubric 永远优先；**试卷级 rubric 已退役**（界面移除、考试不再读取，列保留兼容，D-29）；
  题目全文完整进 prompt；向导第 4 步回显当前比例
  （`PaperDetail.essayContentPercent`）。`EssayRubricWeightsTest` 6/6、
  `AiGradingEngineTest` 10/10、`AiGradingConfigTest` 5/5。
- **未验证**：浏览器里的第 4 步交互与导入页视觉（本轮未重启 dev/QA 前端，只做了 `tsc`/`build`）；
  真实 DeepSeek 判分（测试用 stub 引擎），`subjective-v3` 的 golden set 未重跑。

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

## 背单词（004-vocab，2026-10-24）

- **已完成并合 main**：T387–T413（T400 预生成按决策暂缓）。17+3 张表（V040–V049）、词表索引（28,276 族 / 80,579 形）、
  导入清洗/拼写三态/异步生成、词库/详情/复习/自由复习/加词导入/词集/发词/预习页、SM-2 两档调度、TTS 缓存+Piper sidecar。
- **验证**：`make test` **375/0/0/0**（6m22s）+ web 33/33 + 词表脚本 8/8；四守卫全 0；paper/sitting/grading/report diff **0 行**；
  队列 1 万词 p95 **25ms**；导入 1000 行 681ms、10000 行 5.3s。
- **开关**：`teachloop.vocab.enabled` 默认 true（`VOCAB_ENABLED=false` 可关）。
- **未验证/已知偏差**：① T403/T404/T407/预习页浏览器视觉（仅有 T389 导航、T399 详情截图）；② T411 容器内 sidecar `/health`（Piper 模型需网络）；
  ③ AC-48.9 峰值内存（导入非流式，仅测前后堆差）；④ AC 未逐条勾选；⑤ T400 暂缓。
  详见 `specs/004-vocab/tasks.md` 执行记录。

## 背单词全局内容与例句朗读（2026-10-25 凌晨）

- **已完成（feature branch `feat/vocab-global-content-sentence-tts`）**：
  ① **T429 全局内容 P1**：`vocab_words.content_id` + `ContentStore` 端口 + 本地 SQLite delta（WAL、单写者、id ≥ 1e9、prompt 版本过滤）；prepare 命中全局内容直接 READY、不再入队；AI 生成与发词快照写 delta。
  ② **T430 全局内容 P2**：只读内容包读取器（manifest/sha256/28B idx，unsigned hash 二分 + `FileChannel` pread，10 万词装载 12ms / 索引 3.9MB）+ `scripts/vocab/build_content_pack.py` 导入器（JSONL → pack/idx/manifest，可复现）。
  ③ **T431 全局内容 P3**：V063 把存量内容搬进 legacy 表后删列，启动回填 22/22 写 delta + content_id 并 drop legacy；词库/详情/队列/自由复习/发词领取全部按 content_id 解析；`ContentView` 把 v2 包内容适配为前端当前 v1 形状。
  ④ **决策 019 例句朗读**：`/api/vocab/audio?text=&lang=`（≤200、4xx 契约、缓存键含语言与文本）、`/api/vocab/config` 开关、zh/es 音色；前端每条例句喇叭 + 按词条语言选音色 + 关闭开关不渲染。
- **验证**：全量 `make test` **434/0/0/0**（432s，101 类）；前端 typecheck/build + 52/52；四守卫全过（49 表/13 外键/1559 键/44 组对比度）；pack 脚本 9/9；QA 5174/8011 浏览器实测：清单页/复习卡例句喇叭、点击 `?text=…&lang=en` 返回 200 audio/wav、`VOCAB_TTS_SENTENCES_ENABLED=false` 时喇叭 0 个且 text=404；`struggle` 命中内容包（content_id=1411、0 个 AI job）。
- **已知取舍/未验证**：① 删列后「按释义搜索词库」降级为只按词形（恢复需另立搜索索引）；② 真实 Piper 未试听（测试用 stub），zh/es 模型未实机合成；③ 内容包已按最终 3000 条 JSONL 重建（pack 8.73MB / idx 84KB；产物 gitignored，部署时重建或挂卷）；④ T432（v2 生成/校验）、T433（语言档案）、T434（页面模板）未做，`ContentView` 是过渡适配层。

## 试卷录入重构（2026-10-25，D-34）— **已实现，含浏览器验收**

- **用户口径**：`/papers` 内容太多，先定需求与 spec。去掉「导入考卷」按钮；流程简化；
  第 2 步的三个内容卡（内容（部分）/ 上传整卷导入 / 批量改题型）全删，改成与 `/assignments/new` 同款
  Markdown 编辑器（源码 / 所见即所得互切）+ 一个「生成考题」按钮 + 进度条 + 只读预览表；
  第 3 步（标准答案）删除；第 4 步按写作题出 tab、不要参考范文；预览发布评分项空值显示「无 / N/A」，
  两道作文按题号分块；第一步加命题人、审题人、总分。
- **已交付（Phase 1，只有文档）**：
  ① 调研 `docs/research/2026-10-25-paper-authoring-single-source.md`（7 个对象 / 10 条实际打开的引用；
  结论：单源文本成熟但都是受限 DSL，重导主流是追加，「整卷替换重建」在题库类产品里**没有先例**）；
  ② 决策 `docs/decisions/021-paper-single-source-authoring.md`（D-34）；
  ③ spec：AC-4.9 重写为四步、AC-4.3/4.10/4.17④/4.23④ 修订、新增 **AC-4.24–4.29** 与 **FR-146–150**；
  ④ 原型 `docs/mock/paper-flow-2026-10-25/index.html`（四屏 + 七种状态，数据由 `build-data.py` 从真实卷抽取：
  77 题 / 合计 150 分 / 命题人 李璐 / 审题人 刘嘉伦；14 张浏览器截图在 `shots/`）；
  ⑤ plan §20（数据模型 / 接口 / 流程 / 失败恢复 / 迁移 / 测试策略 / 6 条清单自评）；
  ⑥ tasks 阶段 31（**T456–T466**，取号实测 T455 已被占用）。
- **护栏（来自调研 §3）**：确认框必须数出三类会丢的界面内编辑（rubric 已填 / 错题解析状态非 none /
  旧 section_criteria）；重建前写快照（每卷留 3 条，界面不提供撤销）；已发布卷禁止重建。
- **未实现**：一行业务代码都没动（红线：规范未确认前不写实现）。Phase 2–4（后端迁移 V065 + 生成/发布接口、
  前端四步向导、守卫与三档验收）等 spec + 原型确认后开始。
- **校验**：`grep` 重号检查只余已知的 `AC-27.x` / `AC-28.x` 与 `FR-052–061`；spec 计数 30 US / 287 AC / 82 FR，
  与 plan 头部、tasks 追踪矩阵一致；原型用 ego 浏览器逐屏点过，`data.js` 自带「合计 == 卷面总分」断言。
