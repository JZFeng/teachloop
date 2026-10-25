# AI 未配置时的前置提示（AC-27.2 / AC-27.5）验收报告

日期：2026-10-24　范围：AI 就绪信号 + 5 个「直接调 AI」入口的前置闸门 + 批改详情页提示

## 结论

代码完成，接口与界面入口都验过。用户报的路径（AI 没设置好 → 点「让 AI 先批」）现在
**一个请求都不发**，直接给出「请联系管理员设置 AI」；后端同一入口也会 409 拦在入队之前，
不再出现「先入队、worker 失败、界面只剩『失败』」。

- 前端：`isAIReady()`（用户点名要的那个函数）+ 批改详情页常驻横幅 + 4 个 AI 按钮的前置守卫 + 背单词重试守卫。
- 后端：`GET /api/ai-readiness`（唯一真源）+ 5 处手动入口的 409 闸门。
- 自动派发 / 夜间作业（2026-10-24 追加拍板）：**quick fail** —— 不入队、提交留在 QUEUED、固定时刻不消耗，
  每个「未就绪周期」记一条日志并给管理员与相关老师各发一条站内通知；配好模型后下一轮扫描自动继续。

## 问题与预期（用户报的 Bug）

前置条件：系统没配 AI（dev 库无 `ai_providers`、无 `DEEPSEEK_API_KEY`，`teachloop.grading.engine=ai`）。
实际：点「让 AI 先批」→ 提交被推进队列 → worker 调模型失败 → 界面出现一行「失败」，没有任何「要配 AI」的提示。
预期：「请联系管理员设置 AI」之类的提示；系统中所有直接调 AI 的地方都要这个判断。

## 怎么改的

### 后端：一个真源 + 五道闸门

| 位置 | 文件 | 行为 |
| :--- | :--- | :--- |
| 就绪真源 | `teachloop-identity/.../ai/AiReadinessService.java`（新） | `configured` = 当前 provider 已选 或 `DEEPSEEK_API_KEY` 非空；`ready` = `configured` 或 `engine=stub` |
| 接口 | `AiReadinessEndpoint.java`（新） | `GET /api/ai-readiness`，教师/管理员 200，学生 403；返回 `{configured, engine, ready, source, provider, model}`，绝不回显 Key |
| 端口 | `teachloop-shared/.../port/Ports.java` | `AiSettingsLookup.readiness()` 默认方法（无 identity 装配时不拦人）+ `record AiReadiness` |
| 闸门 | `OfferingGradingDispatcher.dispatchNow` / `dispatchSubmission` | 未就绪 409 `error.ai.notConfigured`，在改状态/入队之前 |
| 闸门 | `OfferingEndpoint.retryFailed` | 同上（重试只会再失败一次） |
| 闸门 | `ExplanationEndpoint.generate` | 看 `configured`（错题解析没有 stub 替身） |
| 闸门 | `VocabularyWordActionsEndpoint.retry` | 看 `configured`；只放宽「词库生成引擎=stub」这一种测试/演示组合 |
| 自动派发闸门 | `OfferingGradingDispatcher.autoDispatch` / `dispatchAssignmentsForTeacher` / `dispatchAssignmentsDueForTeacher` | 未就绪时不建 run、不推进队列；记日志 + 通知；固定时刻不消耗 |
| 调度器 | `TeacherGradingScheduler` | 被 quick fail 拦住时**不**写 `last_fixed_run_at`，配置后下一轮扫描继续 |

为什么区分 `configured` / `ready`：`stub` 是开发替身，没有 Key 也能出分。若只用「有没有 Key」判断，
QA/测试环境（`teachloop.grading.engine=stub`）点「让 AI 先批」会被误拦，演示流程直接坏掉。
所以派发看 `ready`，必须真模型的入口（错题解析、背单词内容）看 `configured`。

文案走 `messages.properties` / `messages_zh_CN.properties` 的 `error.ai.notConfigured`（中英各一条），
`api/`、`service/` 包里没有新增中文字面量。

### 自动派发 / 夜间作业：quick fail（2026-10-24 用户拍板）

用户口径：「小概率事件，这种情况 quick fail，计入 log，发送 notification 给管理员和相关老师」。

| 场景 | 行为 |
| :--- | :--- |
| 到期考试（`autoDispatch`） | 未就绪时整轮跳过：不 `markGradingDispatched`、不建 run、不把 QUEUED 转 PENDING |
| 固定时刻作业（`dispatchAssignmentsForTeacher`） | 同上；返回 `AssignmentDispatch(0, blockedByAi=true)`，调度器**不**记「今天跑过了」 |
| due-date 作业（`dispatchAssignmentsDueForTeacher`） | 同上；每分钟扫描会重试，配好模型后自动继续 |
| 日志 | 每个「未就绪周期」一条 `WARN`（含场次数、老师、首个场次 id）；重复扫描不再刷屏 |
| 站内通知 | 每个「未就绪周期」给**相关老师**（作业/考试创建者）与**全部管理员**各一条，kind=`ai_not_ready`，文案按收件人语言渲染（`notification.ai_not_ready.title/body`） |
| 去重 | 老师按 id 去重、管理员一个全局标志；恢复就绪后清空，下次断供再通知一次 |
| 恢复后 | 提交仍是 QUEUED，下一轮扫描自动派发；不需要老师点「重试失败」 |

多实例部署时每个实例各通知一次（可接受的重复）；语义仍由 `readiness()` 决定，不会因为实例不同而状态分叉。

### 前端

| 文件 | 内容 |
| :--- | :--- |
| `web/src/api/ai.ts`（新） | `getAiReadiness()` / `isAIReady()` / `isAIConfigured()`；只做飞行中去重、无长缓存；**探测失败返回放行**，最终由后端 409 兜底（网络抖动不该挡住合法批改） |
| `web/src/components/useAiReadiness.ts`（新） | 页面级就绪状态 `{view, loading, reload}`，风格对齐 `useListState` |
| `web/src/teacher/GradingDetailPage.tsx` | `ready=false` 时常驻 warning 横幅「尚未配置 AI 模型：请联系管理员设置 AI。」；四个动作（全部派发 / 让 AI 先批 / 重试失败 / 生成解析）在 `busy` 与 `api.post` 之前拦下并给提示 |
| `web/src/vocab/LibraryPage.tsx` | 「重试内容生成」发请求前判 `isAIConfigured()`，否则 toast 提示 |
| `web/src/locales/{zh-CN,en}.json` | 4 个键中英同步（`teacher.detail.aiNotConfiguredHint` / `aiNotConfiguredBlocked` / `goSetAi`、`vocab.library.aiNotConfigured`） |

「去设置」按钮只给 **ADMIN**（跳 `/admin/ai`）；教师端只显示「请联系管理员设置 AI」。
依据 2026-10-24 拍板「模型只在 /admin/ai 配，教师页不再有 AI 入口」，与 `Shell.tsx` 顶栏的处理一致。

## 自动化证据

| 测试 / 守卫 | 结果 |
| :--- | :--- |
| `AiReadinessTest`（新，唯一 `engine=ai` 的 app 测试） | 1 / 1：就绪契约（无 provider → `configured=ready=false`、学生 403）；手动派发与单份派发 409 且 run=0/仍 QUEUED；**自动派发 quick fail**（固定时刻扫描不推进队列、不消耗 `last_fixed_run_at`、老师+管理员各一条通知、重复扫描不重复通知；到期考试同样跳过）；配好 provider 后考试按到期、作业按固定时刻自动派发，手动派发幂等 200 |
| `ExplanationEndpointTest` | 8 / 8（新增「未配置 → CONFLICT 且不选题」） |
| `GradingDispatchTest` / `AiSetupTest` / `ClassAccessTest` / `ManualGradingTest` / `ReviewWorkflowTest` / `NightlyAssignmentGradingTest` | 21 / 21（stub 引擎下 `ready` 恒 true，没有被误拦） |
| `VocabularyWordActionsTest` / `VocabGenerationTest` | 3 / 3、4 / 4 |
| `NightlyAssignmentGradingTest` | 1 / 1（固定时刻/due-date 派发未被破坏） |
| `NotificationTest` | 7 / 7（新增「ai_not_ready 按语言渲染、作业名与场次数进文案」） |
| `check-boundaries` / `check-deps` / `check-i18n` / `check-ui` | 退出码 0（48 表 / 8 端口；zh/en 键完全对齐；无新增中文硬编码；无新增 UI 违规） |
| `cd web && pnpm typecheck` / `pnpm test` | 退出码 0；38 / 38 |

`check-boundaries.py` 有一处必要修复：原解析器按 `;` 劈接口体，会把新加的 default 方法体当签名误报；
改为扫描签名前剥掉方法体。**规则没有放松**——已用注入 `void injectedViolation(UserEntity user)` 的临时副本验证，
脚本仍然 EXIT 1 并指出 `Type UserEntity`。

## 浏览器验收

环境：临时后端 `8012`（新 jar，指向 dev 库 `teachloop`，`TEACHLOOP_GRADING_ENGINE=ai`，`DEEPSEEK_API_KEY` 置空，
定时作业间隔调到 1 小时）+ 临时前端 `5175`（`TEACHLOOP_API=http://127.0.0.1:8012`）。
教师 `teacher@teachloop.local` 打开 `/grading/0c44132b-…`（用户截图里的那一场，状态「失败 1」）。

| 动作 | 证据 | 结果 |
| :--- | :--- | :--- |
| 打开页面 | `01-blocked-click.png` | 横幅「尚未配置 AI 模型：请联系管理员设置 AI。」；教师端无跳转按钮（与顶栏「AI 未配置，请联系管理员」一致） |
| 点「让 AI 先批」 | 同一张截图的下方提示；`window.fetch` 记录 | 出现「还没配置 AI 模型，请联系管理员设置 AI 后再试。」；**只发出 1 个 `GET /api/ai-readiness`，没有 dispatch POST** |
| 点「全部交给 AI 批改」 | fetch 记录 | 同样被拦，只有 `GET /api/ai-readiness` |
| 直接打接口（模拟非 UI 调用方） | 页面内 `fetch` 返回 | `409 {"code":"CONFLICT","message":"No AI model is available yet. Please ask your administrator to set up AI."}`（消息随 `Accept-Language` 本地化，截图里是中文） |

## 没验证 / 已知限制

- **管理员分支的跳转没目视**：`me.role === 'ADMIN'` 时按钮跳 `/admin/ai` 只有代码与 typecheck，未用管理员账号登录点过
  （登录会覆盖浏览器里教师的同名 host cookie，影响用户正在用的 5173 环境）。
- 没有真实模型的端到端批改：`AiReadinessTest` 配好 provider 后只断言 200 / 入队，worker 在测试窗口内不会真的调模型。
- `source="env"`（只配 `DEEPSEEK_API_KEY`、没有 provider 行）只有代码路径，没有自动化用例。
- 背单词「重试内容生成」的 toast 没在浏览器里点过（dev 库没有失败词条）；后端闸门与单测覆盖。
- 通知中心页面没做目视验收：`ai_not_ready` 的标题/正文渲染由 `NotificationTest` 覆盖（中英各一条断言），
  但没在浏览器里打开通知列表看这条新通知。
- 多实例部署时「未就绪周期」去重是进程内的，两个实例会各通知一次；没有做跨实例去重表。
- 自动派发被 quick fail 时，**已派发场次的迟到提交**（`enqueueQueuedOfDispatched*`）同样留在 QUEUED，
  但这条路径不产生通知（无法判断是否有迟到提交）；只记 `WARN`。
- 前端探测失败一律放行：若后端还没部署这个接口，教师点按钮仍会走旧路径（只看到「失败」）。
  这是刻意的取舍——只读探测失败不该挡住合法批改。

## 环境备注

本次与另一条并行改动共享同一个工作区（模型只允许管理员配置、顶栏「AI 未配置」、题目要求导入等）。
提交只包含本次改动的文件；`web/src/locales/en.json` 只提交本次新增的 4 个键（其余改动属于并行任务）。
验收用的 8012/5175 临时进程已关闭，用户的 8010/5173 没动过。
