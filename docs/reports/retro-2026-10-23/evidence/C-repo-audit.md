# TeachLoop 4 天过程审计（2026-10-19 → 2026-10-23）

- 审计员：`auditor`（共享任务 `task-3`）；仓库只读，唯一写入本文件。
- 截止点：`git HEAD = 57db794`（2026-10-23 10:18:40，T370 / D-29）+ 工作区未提交改动。文中「现在/实测」均指 2026-10-23 10:30–10:45 的实测值。
- 方法：只读 grep / git / python 守卫脚本（每个 <1s）。**未跑** `mvn test`、`docker build`、`pnpm build`（按要求跳过）。测试结论取自 `teachloop-*/target/surefire-reports/*.txt`（最新 10-23 10:18）与 `.dsh/logs/`。
- 一个必须声明的限制：`python3 scripts/brand/build-assets.py --check` 在本机失败（缺 Pillow），仓库无 `requirements.txt`，所以「品牌资产校验通过」这一条我**无法复现**（见 §3-9）。

## 0. 先读这个：审计期间仓库在动（影响本文几个数字）

本审计进行中，另一个会话正在把「首页/班级上色」这件事**补落盘**。时间线（来自文件 mtime / `git status`）：

| 时刻 | 状态 |
| :--- | :--- |
| 10:33（审计开始） | `HEAD=57db794`；工作区 15 项未提交；`docs/decisions/006-brand-visual.md` mtime 10-22 23:14，**只写着「首页/班级是中性导航」**；`Shell.tsx:38` 却已引用「决策 006 2026-10-23 修订」 |
| 10:37 | `docs/decisions/006-brand-visual.md` 被改写（8912 字节），新增「### 2026-10-23 修订：首页 / 班级上色，Insights 改靛蓝」并保留旧口径作历史 |
| 10:39 | `specs/006-brand-visual/{spec,tasks}.md` 被改写：AC-31.2/31.4 改成五项胶囊，tasks 新增 T354 行；`docs/reports/nav-color-2026-10-23/` 出现（9 张截图，截至 10:41 尚无 README） |
| 10:41 | `git status --porcelain` = **20 项**未提交 |

因此本文对「决策 006 被代码抢先」这一条的报告口径是：**代码先落地、决策与 spec 约 20 分钟后补写，到我收尾时已基本补齐（未提交）**；问题在顺序与未提交状态，不在「永久不落盘」。其余数字若受并发写入影响，均按上表标注时点。

### 0.1 记分板

| 维度 | 分 | 一句话 |
| :--- | ---: | :--- |
| 1 流程产物齐备度 | 7/10 | 骨架齐；006 缺 plan、004 空壳、spec 005 缺号 |
| 2 文档漂移 | 5/10 | 宪法技术栈已修好；PROGRESS/DELIVERY/README 落后 1 天到 1 个提交；确认的决策被代码抢先约 20 分钟后才补写 |
| 3 可验证性 | 8/10 | `make test` 真覆盖 5 类守卫 + 单测 + 前端；286/0/0/0 可复算；最后 3 天无日志级证据 |
| 4 问卷使用 | 7/10 | 5 份已结算、主题都对，4 份落地；导航着色那份 6 题全跳过，结论来自样张反馈 |
| 5 团队协作痕迹 | 6/10 | 无冲突残留；但有 2 对重复提交、2 次同分支 merge、15→20 项未提交、40 个任务零痕迹 |
| 6 可复用实践 | 9/10 | 守卫脚本、共享夹具、双环境、决策模板、证据目录质量高 |

---

## 1. 流程产物齐不齐

1. **research 9 篇，日期分布不均在最后一天**：`docs/research/` 实际 9 个 `.md`——10-18 ×1、10-19 ×1、10-21 ×2、10-22 ×2、10-23 ×3。其中 2 篇**未提交**（`git status` 的 `?? docs/research/2026-10-23-local-llm-and-grading-timing.md`、`?? .../2026-10-23-nav-color-codes.md`）。README 只写「7 份，至 2026-10-22」（`README.md:15`）。
2. **002 是唯一四件套齐全的 spec**：`specs/002-v2-platform/` 有 `spec.md`(943 行)/`plan.md`(1084)/`tasks.md`(713)/`mockups/`（`interface.html` 内 23 个 `class="screen"`，另有 `exam-material.html`、`_paper.json`）。
3. **003 缺 mockups**：`specs/003-ui-standard/` 只有 `spec.md`(133)/`plan.md`(97)/`tasks.md`(56)。UI 标准没有独立原型，验收靠 `docs/reports/ui-standard-2026-10-21/` 的 21 张截图替代——可接受但偏离 AGENTS 的 spec ⇄ mock 循环。
4. **006 缺 plan.md 和 mockups/**：`ls specs/006-brand-visual/` 只有 `spec.md`、`tasks.md`（HEAD 时 tasks 13 行 T341–T353；审计期间工作区追加 T354，见 §0）。品牌原型实际在 `docs/mock/brand-2026-10-22/index.html`，截图报告在 `docs/reports/brand-mock-2026-10-22/`（15 张）与 `docs/reports/brand-app-2026-10-22/`（41 张）。产物在，但不在这套约定目录里。
5. **spec 005 完全缺号，004 只有壳**：`ls specs/` = `002-v2-platform 003-ui-standard 004-vocab 006-brand-visual`；004 只有 `README.md`。006 的 `AC-32.5` 却写「003 顶部标注『调色板与圆角被 005 取代』」（`specs/006-brand-visual/spec.md:83`）——这个「005」既不是 spec 也不是决策 006，见 §2-11。
6. **决策记录 9 条，编号连续、结构统一**：`docs/decisions/001…009`，日期 10-19 → 10-23，无缺号无重号。结构一致：`## 问题 / ## 决策 / ## 代价与对策 / ## 影响面`（003–005、007–009），006 另加「为什么 / 不采纳 / 后果」。决策 001 就是 10-18 问卷答案的结构化落盘（`docs/decisions/001-survey-answers.md` 标题行）。
7. **PROGRESS.md 头部停更**：`docs/PROGRESS.md` 201 行，第 4 行仍写「最近一次更新：2026-10-22（T359 品牌源图归档…）」，但该文件在 HEAD `57db794` 里被改过——只改了 1 行正文（`git show 57db794 -- docs/PROGRESS.md` 显示 `+1/-1` 的 D-29 追加），「当前」段仍是 T359，T368–T370 没有任何记录。
8. **DELIVERY-STATUS.md 落后 14 个任务号（T357–T370）**：头部「日期：2026-10-20」（`docs/DELIVERY-STATUS.md:3`），最后一次提交是 `160bed5`（10-22 21:21）。全文对 T357–T370 只出现 1 处（`:97` 写 T356），D-28/D-29 零记录；这份文件的定位已从「交付状态」退化为「10-20~10-22 变更流水」。
9. **关键文档最后更新时间 vs 代码提交（全部对照）**：

   | 文档 | 最后提交 | 最后提交日期 | 与代码 HEAD（10-23 10:18）的差 |
   | :--- | :--- | :--- | :--- |
   | `README.md` | 0903636 | 10-23 10:04 | 早 1 个提交（T370 之前） |
   | `.specify/memory/constitution.md` | a3b4fd6 | 10-22 | 晚 1 天 |
   | `docs/PROGRESS.md` | 57db794 | 10-23 10:18 | 同步提交，但内容停在 T359 |
   | `docs/DELIVERY-STATUS.md` | 160bed5 | 10-22 21:21 | 晚 1 天，内容停 10-20 |
   | `specs/002-v2-platform/{spec,plan,tasks}.md` | 57db794 | 10-23 10:18 | 同步 ✓ |
   | `docs/decisions/009-*.md` | 57db794 | 10-23 10:18 | 同步 ✓ |

10. **工作区 15 项未提交（审计收尾时 20 项）**：10:33 的 `git status --porcelain` = 15（9 改 + 6 新）：改 `web/src/app/Shell.tsx`、`UiKitPanel.tsx`、`styles/{app,tokens,ui}.css`、`ui/tokens.ts`、`locales/{zh-CN,en}.json`、`scripts/ui/contrast.py`、`docs/mock/brand-2026-10-22/index.html`；新 `docs/research/2026-10-23-*.md` ×2、`docs/mock/{nav-color,insights-color}-2026-10-23/`、`.dsh/surveys/20261023-1014-*.json`、`scripts/ui/__pycache__/`。10:37–10:39 又加入 `docs/decisions/006-brand-visual.md`、`specs/006-brand-visual/{spec,tasks}.md`、`docs/reports/nav-color-2026-10-23/`（§0）。其中 `__pycache__` 未被 `.gitignore` 覆盖（`grep -n pycache .gitignore` 无命中）。

**小结**：七步流水线的「骨架」是齐的（9 研究 / 9 决策 / 3 spec / 1 mock 套 / 1 宪法），但 003/006 缺 mock/plan、004 空壳、005 缺号，「每份产物各自过 review」只有 002 是完整形态。

---

## 2. 文档漂移

1. **宪法技术栈：已纠正，不再是 Python/FastAPI/Celery**。`grep -ni 'python\|fastapi\|celery' .specify/memory/constitution.md` 只命中 1 处，且是自我否定的说明：`:102`「版本 2.1 修正：早期写的是 Python/FastAPI/Celery/SSE…已作废」。技术栈表已写 Java 21 / Spring Boot 4.1.x / Jackson 3 / Postgres 16 / 无 MQ / 轮询（`:90-100`）。**这条红线是这 4 天里被真正修好的漂移。**
2. **宪法版本号却没跟上内容**：`:6-7` 仍是「版本 2.1 / 日期 2026-10-19」，而内容在 10-21 加了第十五条（commit d669c52）、10-22 又加品牌层三条（a3b4fd6，`# 第十五条 — UI 与可访问性（2026-10-21 新增）` 见 `:163`）。**不可协商文档改了 2 次、版本号 0 次。**
3. **README 的功能需求数差 1**：`README.md:17` 写「28 用户故事 / 265 验收标准 / **65 功能需求**」；实测 `grep -c '^| FR-' specs/002-v2-platform/spec.md` = **66**（T370 在 HEAD 里加了 FR-086）。在 README 自己的提交 `0903636` 上确实是 65，所以这是「最后一个代码提交没回写 README」。
4. **README 的任务数差 1**：`:20` 写「002：**224** 个」；实测 `grep -cE '^\| T[0-9]+'` = **225**（T370 是第 225 行）。同一原因。
5. **README 的决策范围差 1**：`:50` 写「`docs/decisions/`（**001–008**）」；实际已有 `009-retire-paper-rubric.md`（10-23 10:05）。
6. **模块数三份文档打架，代码是第十个模块**：`pom.xml` 有 10 个 `<module>`（含 `teachloop-vocab`，10-21 接入）；`README.md:61` 写「十个」✓；但 `AGENTS.md:3` 写「**九个** `teachloop-*` 子模块」✗、`docs/DELIVERY-STATUS.md:20`「九模块编译」✗、`:33`「九模块、依赖守卫…」✗。AGENTS.md 最后修改 10-21（`2a9a2a1`），此后一直没回改。
7. **同一批质量守卫，三份文档三组数字，全部对不上现在的实测**：

   | 指标 | README (`:143-149`) | spec 006 §6 | 现在实测 |
   | :--- | :--- | :--- | :--- |
   | check-ui 存量违规 | 122 处 | 123 处 | **121 处**（`python3 scripts/check-ui.py`） |
   | 对比度组数 | 40 组 | 44 组 | **44 组**（`scripts/ui/contrast.py`） |
   | i18n 键数 | 1219 | 1197 | **1220 / 1220，diff 0**（`scripts/check-i18n.py`） |
   | 前端金标 | 28/28 | 5/5 | **28**（21 静态 + 7 夹具，见 §3-4） |

   README 的数字在 `0903636` 时是对的，spec 006 的数字在 10-22 写时就已过时（1197 vs 当时 1219）。
8. **AC 编号跨 spec 冲突 9 个**：003 的 `AC-27.1–27.6`、`AC-28.1–28.3`（`specs/003-ui-standard/spec.md:75-86`）与 002 的 `AC-27.1–27.20`、`AC-28.1–28.11`（`specs/002-v2-platform/spec.md:605-731`）同号不同义。按 AGENTS.md 的自检命令跑会**静默通过**，原因见下一条。
9. **仓库自带的自检命令有假阴性**：`grep -o '^- \[ \] AC-[0-9]*\.[0-9]*' specs/*/spec.md | sort | uniq -d` 输出为空；但加 `-h`（去掉文件名前缀）后同一命令输出 9 条重复（AC-27.1…28.3）。多文件 grep 会给每行加 `path:` 前缀，`uniq -d` 永远比较不到重复。AGENTS.md「查重复编号必须锚定『定义行』」这条规则本身没锚住跨文件前缀。任务是同样的坑：`grep -o '^| T[0-9]*' specs/*/tasks.md | sort | uniq -d` 也输出空，而 002 与 006 **各自定义了一个 T341**（`specs/002-v2-platform/tasks.md:495` 是编辑器任务；`specs/006-brand-visual/tasks.md:10` 是 logo 资产任务）。
10. **003 的编号约定已失效**：`specs/003-ui-standard/tasks.md:6` 写「编号从 T301 起（002 用到 T253）」，但 002 现在用到 **T370**，且 002 与 003 的号段是人工穿插保留的（002 无 T301–319/354–356）。没有任何单一登记表能防止下一次撞号。
11. **交叉引用错号**：`specs/006-brand-visual/spec.md:83`「003 顶部标注『调色板与圆角被 **005** 取代』」——003 顶部实际写的是「决策 **006** / specs/006-brand-visual」（`specs/003-ui-standard/spec.md:10-12`），且不存在 spec 005。
12. **有 commit、有 PROGRESS、却没任务定义**：`T357`（导入空题号）、`T358`（导航着色口径）在 `git log` 与 `docs/PROGRESS.md:18-25` 都有，但 `grep -n 'T357\|T358' specs/*/tasks.md` = **0 行**。任务清单不是唯一真源。
13. **决策晚于代码约 20 分钟（顺序倒置，但已补齐）**：10:33 时未提交的 `web/src/app/Shell.tsx:38` 已写「首页与班级…（**决策 006 2026-10-23 修订**）」并加了 `tone: 'home' | 'classes'`（`:41-42`、`:54`），而 `docs/decisions/006-brand-visual.md` 当时仍是 10-22 的「首页、班级…是**中性导航**，刻意不配 `tone`」。10:37 该决策被补写（新增「### 2026-10-23 修订：首页 / 班级上色，Insights 改靛蓝」，旧口径保留作历史），10:39 spec 006 的 AC-31.2/31.4 也同步改掉。**到收尾时四份产物（代码/决策/spec/tasks）已一致，但三者都比代码晚 2–6 分钟到 20 分钟，且全部未提交。** 这条流程的正确顺序应是「用户拍板 → 决策/spec 落盘 → 改代码」。
14. **README 的「前端 28/28」可复算，不是漂移**：`web/tests/*.test.mjs` 有 21 个静态 `test(...)`，`rubric-summary.test.mjs:26` 再按 7 个夹具 case 参数化生成 7 个，21+7=28，与断言一致。这是「声称与实现对得上」的正面样本。
15. **多份任务/验收表对「完成」的定义不一致**：002 `tasks.md:13` 的表头是 `| ID | 任务 | 依赖 | 验收 |`（**无状态列**，完成只能看文件底部 154 行执行日志）；003/006 有 `| 状态 |` 列（003 完成 14/21，006 在 HEAD 为 13/13，工作区加 T354 后 14/14）。同一流程两套记账方式。

---

## 3. 可验证性

1. **`grep -rc '@Test'` 会高估 11%**：全仓原始匹配 309 处，但其中 `@Testcontainers` 33 处、`@TestConfiguration` 2、`@TestFactory` 2；真正 `@Test` 272 处。**实际执行数 286**（`grep -h 'Tests run' $(find . -path '*/surefire-reports/*.txt')` 汇总：tests=286 / failures=0 / errors=0 / skipped=0，69 个报告文件，最新 mtime 10-23 10:18:16）。所以「286/0/0/0」可信；「309 个测试」是计数方法的错觉。
2. **`make test` 的覆盖面是真实的**：`Makefile:63` 一行 = `check-deps check-boundaries check-i18n check-ui web-test` + `$(MVN) -q test`；`check-ui` 再套 `scripts/ui/contrast.py`（`Makefile:56-58`）。单测 / 前端金标 / 对比度守卫 / 架构守卫都在闸门里，不是文档里吹的。
3. **四条架构/文案守卫现在全绿**（本次实跑，非引用历史日志）：
   - `[check-deps] 通过：shared(0)，identity(1)…vocab(1)，app(9)`
   - `[check-boundaries] 通过：25 张表归属清晰，13 条跨模块外键已登记，7 个端口只收发 record`
   - `[check-i18n] 通过：前端 zh/en 各 1220 键且完全对齐；新增中文字面量后端 0 处（基线 290）、前端 0 处（基线 1）`
   - `[check-ui] 通过：没有新增违规（当前存量 121 处）`；`[contrast] 通过：44 组对比度全部达标`
4. **前端 28/28 的构成可复算**：21 个静态用例 + `docs/rubrics/fixtures/rubric-cases.json` 的 7 个 case（`web/tests/rubric-summary.test.mjs:26-40`）。`web/package.json:11` 用 `node --test tests/*.test.mjs`，无浏览器依赖。
5. **验证日志只覆盖前 1.5 天**：`.dsh/logs/` 有 **25 个 `verify-*.log`**，时间全在 10-19 21:49 → 10-20 00:20；全仓最新日志是 `dev-backend.log`（10-21 18:57）、`full-test.log`（10-21 16:24，604KB）。**10-22 与 10-23 没有任何机器日志**，这两天的验证证据只有 `docs/reports/` 下的报告与截图。
6. **日志里能看到真实的失败→重试链**（好证据）：`verify-class.log` 尾部 `- 端口签名不合规：ClassSubmissionStats 里出现类型 Integer…`、`make: *** [check-boundaries] Error 1`、`CB_EXIT=2`；随后的 `verify-final.log` 尾部为 `CB_EXIT=0`。说明守卫真的拦过人，不是摆设。
7. **提交正文的验证密度**：114 个提交中 **81 个（71%）** 正文含「验证 / 截图 / test / make test」；测试计数从 206→209→212→225→242→243→250→255→265→286 **单调递增、无回落**，与「不许先提交、测试以后再补」的宪法第九条方向一致。
8. **验收标准从不打勾**：`grep -rhcE '^- \[x\] AC-' specs/*/spec.md` = **0**；`- [ ]` = **296**。265 条 002 验收标准、16 条 003、15 条 006 全部保持未勾选状态——「这条 AC 验过了吗」只能去翻提交正文和执行日志，文档本身不提供验收状态。
9. **一条无法复现的 `make` 级验证**：`python3 scripts/brand/build-assets.py --check` 在本机 `ModuleNotFoundError: No module named 'PIL'`；仓库没有 `requirements.txt`/`pyproject.toml`，只有 `docs/brand/README.md:25` 与 `README.md:149` 的括号备注「需要 Pillow」。README 表格里这一行的「通过」在当前环境无法验证。
10. **端点分布与测试分布错位**：HTTP 入口不只在一个模块——`*Endpoint.java` 共 21 个，分布 identity 8 / sitting 4 / grading 2 / report 2 / observability 2 / classroom 1 / paper 1 / app 1。其中 identity、report、observability、classroom **本模块内 0 个测试文件**，覆盖依赖 `teachloop-app` 的集成测试（35 个测试文件、33 个用 `MockMvc`/`@SpringBootTest`，如 `ProdAuthTest`/`RateLimitTest`/`AggregateReportTest`/`ExportTest`）。286 个用例分布：app 91 / paper 81 / grading 65 / sitting 38 / shared 11。**结论**：不能按「模块里有几个测试文件」判断端点覆盖，必须按端点核；本次没有逐端点核对（§附录 B）。`teachloop-vocab` 确实无端点无测试（空骨架，PROGRESS 也承认）。
11. **`.dsh/logs` 的验证比例口径**：如果按「有日志的验证 / 总提交」算，25 份 verify 日志覆盖 10-19~10-20 的 65 个提交约 38%；10-21 起改用 `docs/reports/`（10:33 时 27 个目录、183 张 PNG；10:42 已 28 目录、211 张，因 nav-color 报告仍在写）＋提交正文承载证据。**验证没有停，载体换了，但没有统一索引能一屏看全。**

---

## 4. 问卷使用

1. **仓库内 5 份已结算问卷，1 份未提交**：`.dsh/surveys/` 有 5 个 JSON（43.9/44.0/47.8/32.0/34.2 KB），时间 10-20 09:52、10-21 00:00、10-21 13:03、10-22 13:45、10-23 10:14；最后一份（`20261023-1014-color-code-e7b52670.json`）是 `git status` 里的未跟踪文件。
2. **机器库里 7 份，其中 6 份属于本项目**：`~/.dsh/rich-questions/surveys/` 7 个文件 = 本项目 6 份（含 10-18 需求澄清 `8019a203`，早于本次窗口）+ 1 份无关话题（`90f9c2a0`「一键多平台发布：做成 skill 还是做成 app」，10-22 00:04，7 题只答 1 题）。**注意**：`.dsh/surveys/` 只存了项目内的 5 份，10-18 那份只存在机器库里，仓库侧不完整。
3. **主题与时间线对得上开发节奏**：10-20 Mockup 对等性（8 题）、10-21 UI 标准（6 题）、10-21 批改质量（7 题）、10-22 Markdown 编辑器（4 题）、10-23 导航着色（6 题）；4 天 5 场 ≈ 每天 1.2 场，符合「需要用户拍板就用问卷」的规则。
4. **落地抽查 A（UI 标准，成功）**：`specs/003-ui-standard/spec.md:6` 直接写「决策来源：`.dsh/surveys/20261021-0000-ui-6-fb9093b8.json`（6 项，用户已选）」；6 个答案（稳重蓝/引入组件库/5 屏先落/亮暗双主题/守卫进 make test//ui-kit+截图基线）逐条对应 spec 与 tasks T301–T319。唯一被后续改掉的是「Ant Design **5**」→ 实装 antd 6，**且这个变更被写进了 spec**（`specs/003-ui-standard/spec.md:39`「组件库版本 | antd 6（不是 5）| React 19.3 下 v6 原生支持」）——改主意有落盘，这是正面样本。
5. **落地抽查 B（批改质量，成功）**：`20261021-1303` 的 7 题里有 4 题用户没选选项、直接写自由文本（如「set up 的时候就要让管理员输入 api key…但是不能明文显示 key」「greeting 不作为段落」「优先用作业本身的 rubric，系统 rubric 是 fallback」）；这些原话在 `specs/002-v2-platform/spec.md:594` 的 US-27 里以「2026-10-21 用户拍板」承接，展开为 AC-27.1–27.20，并由 `tasks.md:674`（T254）记录「用户拍板的 7 项都有对应 AC」。
6. **落地抽查 C（Mockup 对等性，成功）**：8/8 有答案，逐条能在 spec 找到落点：班级概览「做独立页 + 新接口」→ US-13/AC-13.6（`specs/002-v2-platform/spec.md:323,331`）+ T211；开考后改期「只能延长」→ AC-26.15（`:584`）；管理员范围「全部班级/作业」→ D-11（`:63`）。
7. **落地抽查 D（导航着色，问卷零产出）**：`20261023-1014` 的 **6 题全部 `skipped: true`、0 个选项、0 条自由文本**（JSON `answers` 逐条 `{"selected": [], "skipped": true}`）。真正的拍板来自用户看样张后的两句话——「首页、班级太素」「报表玫红在暗色下像出错」——由 10:37 补写的决策 006 修订承接（`docs/decisions/006-brand-visual.md`「### 2026-10-23 修订」），而不是这份问卷。**结论：这场问卷白问了**；用户可以跳过整场，说明 6 题的设计没有接住当时真正要定的两个点（要什么颜色、Insights 要不要换）。
8. **问卷质量本身不错**：每份都有长 intro（说明为什么现在问、答完做什么、预计几分钟）与逐选项 insight；JSON 32–48KB，选项带「做法/Proven at/Breaks when/这里现在」。10-22 Markdown 问卷第 1 题用户回「我不是很明白这几个选项你解释一下几个选项区别。另外，啥叫中文要自己天一份词表？」——说明那次选项解释没到位，是问卷设计侧的真实失败信号。
9. **问卷结论与决策编号可追**：决策 001 就是 10-18 问卷的结构化答案（`docs/decisions/001-survey-answers.md` 的「## 已确认的决策 / ## 由原话解析的决策 / ## 尚未决策」三节），决策 004 的「验收证据」节直接对应 Markdown 问卷的 4 个答案。
10. **问卷没有覆盖到的地方**：4 天里的技术选型并非都走问卷——夜间批量（决策 003）、品牌视觉（决策 006）、作文 rubric（007/008/009）都是用户口头拍板后直接落决策，没走问卷。这不是错，但意味着「用问卷拍板」的覆盖率约为 9 条决策中的 3 条（001/004 及 003 spec 来源）。

---

## 5. 团队协作痕迹

1. **提交节奏极端偏斜**：114 个提交 / 5 天 = 10-19 **57**、10-20 **8**、10-21 **24**、10-22 **20**、10-23 **5**。第一天 50% 的提交量，与「初版纵切一次成型、之后按反馈迭代」的模式一致。
2. **2 对重复提交（patch-id 相同）**：`9352705` 与 `eb789da` 是同一份 T341 补丁，`3d37ed5` 与 `f14940a` 是同一份 T340 补丁——`git show <h> --format='' --patch | git patch-id --stable` 两两相同（`a6cd580…`、`fabe291…`）。同一改动在 main 与 `feat/brand-visual` 上各提交一次，之后靠 merge 收进同一条历史。
3. **同一分支 merge 了两次**：`3adb428`（父母 `9352705` + `eb789da`，即同一工作的两份提交）与 `75d9d57`（父母 `3adb428` + `160bed5`）。`git log --merges` 只有这 2 个 merge，全部来自 `feat/brand-visual`。合并策略本身没错，但产生了重复历史，`git log --graph` 里能看到两条平行同内容分支。
4. **WIP 痕迹**：`docs/PROGRESS.md:138`「未提交 WIP：以 `git status` 为准（本行不再写快照，避免文档腐化）」——这是好实践；但实际有 **15→20 项未提交**（§1-10），其中首页/班级着色正是最后 1 小时才补决策的半成品（§0）。
5. **一次破坏性命令事故留下了善后记录**：`fix: 恢复被误删的 001 归档（find -delete 误伤，内容取自 6f31fc6）`——说明有人对工作区跑过 `find -delete` 并误伤已提交文档，靠 git 历史救回。这是流程风险（对仓库跑通配删除）而非账目问题。
6. **无冲突残留、无垃圾入库**：全仓 `<<<<<<<`/`>>>>>>>` 0 处、`*.orig`/`*.rej` 0 个；`git ls-files | grep DS_Store` = 0（e2a3d1c 清理后没再入库，虽然磁盘上仍有 3 个 `.DS_Store`，已被 ignore）。`TODO/FIXME/XXX` 在 Java/TS 里仅 1 处。这几项干净程度高于多数个人项目。
7. **任务记账不齐**：002 的 225 个任务里，执行日志（`| 2026-MM-DD | T…` 行）覆盖 **154 个**，**71 个任务没有任何完成记录**；003 覆盖 14/21（T308/T309/T310 明确未做是诚实的，但 T314–T317 也没记录）。006 在 HEAD 13/13、工作区 14/14 全覆盖。
8. **零痕迹任务 40 个**：「既没被任何 commit 引用（含区间展开）、也没进执行日志」的任务：002 有 33 个（T003–T016 等早期骨架、T211 学生班级概览等），003 有 7 个（T308–T310、T314–T317）。**任务清单里有 15% 的行无法追溯到任何动作。**
9. **提交→任务可追溯率约 53%**：3 份 tasks.md 共 258 个唯一 T 编号，commit 正文（含 `T360–T368` 这类区间展开）引用 136 个。反过来有 2 个被提交引用的号（T357/T358）在任务清单里不存在（§2-12）。
10. **有未跟踪的 Python 缓存**：`scripts/ui/__pycache__/` 出现在 `git status` 的 `??` 里且不在 `.gitignore`——下一次 `git add -A` 就会入库。

---

## 6. 亮点证据（可直接复用的好实践）

1. **架构边界做成了可执行文件，不是口号**：`scripts/check-deps.py` 用一张 `ALLOWED` 表（`:17-29`）作为模块依赖唯一真源；`scripts/check-boundaries.py` 把 plan §3.2/§3.3/§3.5 的四条硬规则变成退出码（表归属 / 禁止跨模块查表 / 端口只收发 record / 跨模块外键白名单）。实测输出「25 张表 / 13 外键 / 7 端口」。**建议原样复制到其他项目**：把 review 清单里的每条结构约束映射成一个脚本，再挂进 `make test`。
2. **反证文化**：003 的 AC-30.1 要求「手动插入 `#ff0000` 时 `check-ui` 退出码非 0」，T203/T204 的验收写「故意让 classroom 依赖 grading 时构建失败」。守卫不是「跑绿了」，而是「证明它会红」——`.dsh/logs/verify-class.log` 里还留着 `CB_EXIT=2` 的真实失败。
3. **基线式增量守卫**：`scripts/check-i18n.py`（`scripts/i18n-baseline.txt` 290 条后端、`i18n-web-baseline.txt` 1 条）与 `scripts/check-ui.py`（`scripts/ui-baseline.txt` 230 行、只许下降）解决了「老代码有一堆违规，全量失败就推不动」的典型死局。实测 zh/en 各 1220 键、diff 0。
4. **同一份夹具被三处测试共用，防止解析规则漂移**：`docs/rubrics/fixtures/rubric-cases.json`（7 个 case）同时被 `teachloop-sitting/.../RubricValidatorCasesTest.java`、`teachloop-grading/.../RubricCriteriaTest.java`、`web/tests/rubric-summary.test.mjs` 读取（`grep -rln 'rubric-cases.json'`），前端注释也写明了这个目的（`rubric-summary.test.mjs:1-9`）。
5. **决策记录模板统一且包含「不采纳」**：9 份决策都是「问题 → 决策 → 代价与对策 / 为什么不是别的做法 → 影响面」，006 还专门有「## 不采纳」（不引 webfont、不做 9 套主题、不改 Tag tone 语义）。`009-retire-paper-rubric.md` 把「删界面/留列/留 PUT 接口」的兼容边界写清楚了。
6. **证据目录按任务命名，可逐条点开**：`docs/reports/` 28 个目录、200+ 张 PNG（10:42 实测 211），目录名即任务号（`t354-esc-dismiss/`、`t355-cloze-grouping/`、`t358-nav-tone-note/`），每个目录配 README 写「现象/结论/依据/证据」。`specs/006-brand-visual/spec.md` §6.1 还反向列出了「已截图的路由」清单。
7. **Mockup 对等性有统一审计协议**：`docs/reports/mockup-parity/00-protocol.md` 定义三级审计（页面级 / 控件级 / 接口级）、严重度分级与「缺接口 → 标 `阻塞: 缺接口`，不要笼统写未实现」的写法。这是一份可复用的「原型 vs 实现」审计模板。
8. **双环境隔离**：`make qa`（`scripts/env.sh`）在 8011/5174 起一份构建快照 + 独立库 `teachloop_qa` + 独立上传目录，`Makefile:98-111` 与 `README.md:126-136` 有对照表；配合 4 个 CDP 验证脚本（`scripts/verify/{escape-dismiss,cloze-grouping,nav-tone-note,ui-kit-in-profile}.mjs`）做真浏览器断言（输出 PASS/FAIL + 截图）。
9. **「不伪造状态」写得比多数项目硬**：`docs/PROGRESS.md:185`「**未验证**：浏览器里的第 4 步交互与导入页视觉（本轮未重启 dev/QA 前端，只做了 tsc/build）」；`specs/006-brand-visual/spec.md` §6.2 标题就是「明确未验证（不许写成已优化）」，连 live 页截图是「临时改 due_at、截完还原」的手段都写了；`specs/003-ui-standard/spec.md:4` 状态写「已实现（待用户验收视觉）」。README 的「当前缺口」还如实承认学生班级概览整屏未落地——实测无学生 ClassOverview 页面、后端无 `/overview` 端点（只有反馈详情里的 `feedback.overview` 三个聚合数），**这条自我批评是准的**。
10. **给文档加「防腐化」机制**：`scripts/status.sh` 一条命令打印最近提交 / 工作区 / 最近 surefire 汇总 / PROGRESS 的「当前/剩余」；`docs/PROGRESS.md:138` 不再手抄 WIP 快照而指向 `git status`。方向正确（把易腐信息换成指针），只是 PROGRESS 头部日期还没跟上（§1-7）。

---

## 附录 A：复现命令（全部只读）

```bash
# 产物与漂移
ls docs/research | wc -l; for f in docs/research/*.md; do echo "$(git log -1 --format=%ad --date=short -- $f) $f"; done
grep -c '^| FR-' specs/002-v2-platform/spec.md          # 66
grep -cE '^\| T[0-9]+' specs/002-v2-platform/tasks.md   # 225
grep -c '^### US-' specs/*/spec.md                      # 28 / 4 / 3
grep -oh '^- \[ \] AC-[0-9]*\.[0-9]*' specs/*/spec.md | sort | uniq -d   # 9 条重复
grep -oh '^| T[0-9]*' specs/*/tasks.md | sort | uniq -d                  # 应为 T341（需去掉文件名前缀）
grep -c '<module>' pom.xml                              # 10

# 测试与守卫
grep -rhoE '@Test\b' --include='*.java' . | wc -l       # 272（`grep -c @Test` 会得 309，含 @Testcontainers）
grep -h 'Tests run' $(find . -path '*/surefire-reports/*.txt') | awk -F'[:,]' '{gsub(/ /,"",$2);t+=$2} END{print t}'  # 286
python3 scripts/check-deps.py && python3 scripts/check-boundaries.py \
  && python3 scripts/check-i18n.py && python3 scripts/check-ui.py \
  && python3 scripts/ui/contrast.py

# 问卷
ls .dsh/surveys; ls ~/.dsh/rich-questions/surveys
# 工作区与协作痕迹
git status --porcelain | wc -l                          # 10:33 为 15，收尾 20（并发写入）
git log --merges --oneline; git log --format='%s' | sort | uniq -cd | head
```

## 附录 B：本审计没有做的事

- 没有跑 `mvn` 全量测试、`pnpm build`、`docker build`，因此「现在跑一遍仍全绿」未经我复验；286/0/0/0 是 surefire 报告（10-23 10:18）的事实，不是我的重跑结果。
- 没有逐条核对 265 条 AC 是否真的被实现（样本抽查了 US-13/AC-26.15/AC-27.17/AC-27.20 与班级概览未实现这一条）。
- 没有评估 UI 视觉是否达标（需要人眼/设计判断）。
- `scripts/brand/build-assets.py --check` 因缺 Pillow 未复现（见 §3-9），品牌资产完整性只能引用 `docs/brand/README.md` 里的 sha256 记录。
