# 决策 015 — 站内通知存事实、读取时渲染

日期：2026-10-24　状态：生效　相关：AC-12.4、AC-12.5、AC-15.3、T114

## 问题

用户反馈：中文界面下的通知内容是英文，而且没有作业信息。

代码盘点（2026-10-24）发现两条独立原因：

1. `OfferingEndpoint.notifyStudents` 用**布置的 `feedback_language`** 决定中英文，和收件人无关。库里证据：56 条 `zh-CN` 学生收到英文 `New assignment…`，46 条 `en` 学生收到中文 `新作业…`。
2. `ReviewEndpoint.notifyPublished` 用 `users.locale`。但界面语言存在前端 localStorage（`teachloop.lang`），只有点「个人资料 → 保存」才回写 `users.locale`；两者脱节时，通知语言和界面语言就不一致（实测 student01 的 `users.locale='en'`，界面是中文）。

共同根因：**通知文案在写入那一刻按语言固化成 text 落库**。顺带违反 AC-15.3「代码中不得硬编码用户可见文案（i18n 资源文件除外）」——四类通知的中英文都写在 Java 里。

## 决定

**A1：通知只存事实，读取时按请求的 `Accept-Language` 渲染。**

- `notifications` 新增 `payload jsonb`；`title`/`body` 降级为「历史行 + 未知 kind」的回退原文，新行可为空（V052 去掉 `title` 的 NOT NULL）。
- 模板放 `messages_zh_CN.properties` / `messages.properties`，键为 `notification.<kind>[.<variant>].title|body`；`variant` 目前用于 `offering_published` 的 `exam`/`assignment`、`feedback_published` 的 `scored`，以及 `vocab_assignment` 的 `detail`（带截止）/`detail_nodue`（不带截止）。
- `NotificationEndpoint` 用 `LocaleContextHolder.getLocale()`（与错误文案同一套机制）渲染；前端零改动，仍显示 `title`/`body`。
- 调用方只传 payload：`offering_published` 传 `{title, offeringKind}`，`scores_published` 传 `{title}`，`feedback_published` 传 `{title, score, total}`，`VOCAB_ASSIGNMENT` 传 `{setName, teacher, className, dueAt?}`（2026-10-24 起；早前的空 payload 行仍能用泛化模板渲染）。
- `teacher` 存 `users.display_name` **原文**；渲染时按 `notification.vocab_assignment.teacherSuffix`（中文「老师」，英文为空）先去掉末尾已有的称谓再套模板，避免「周杰伦老师老师」（T425）。
- 发词通知的 `detail` / `detail_nodue` 变体各自必须有 `.title` 键（「新的词集」/ "New word set"）：只定义 `.body` 时 `templateKey` 会拼出不存在的键，标题就渲染成空（T425 的实际故障）。
- 模板参数按固定顺序传给 MessageFormat：`{0}` title、`{1}` score、`{2}` total、`{3}` count、`{4}` teacher、`{5}` className、`{6}` setName、`{7}` dueAt。**新参数只能追加**，插到前面会让老模板串位。
- `dueAt` 在 payload 里是 ISO-8601 瞬间，正文里的时间按 `teachloop.notification.due-zone`（默认 `Asia/Shanghai`）渲染成短日期时间；不能用 JVM 默认时区——容器里通常是 UTC，学生会看到差 8 小时的时间。
- `WorkItem` 增加 `offeringTitle`（由 `SittingLedgerImpl` 从快照带出），复核发布时用它拼「《作业名》的反馈已发布」。

## 为什么不是 B（按收件人 locale 拼文案）

B 只修「写的时候猜对语言」：切语言后旧通知不变，改文案要么补迁移要么新旧不一致。A 把文案变成**渲染规则**，一次改完。

## 代价与边界

- 历史行（本文档之前写入的 714 条）保留原文，不会变语言——这是有意的回退，不是遗漏。
- 模板里的分数取发布那一刻的 `objectiveTotal + subjectiveTotal`，之后改分不会回头改通知，符合「通知是事件快照」。
- `ai_circuit_*` 通知原来仍由 `GradingWorker` 按 `Locale.SIMPLIFIED_CHINESE` 拼好再传（`notification.aiCircuit.*` 模板被它直接调用），没有走读取时渲染；2026-10-25 已按同样方式迁到 payload（见下）。
- 新行为需要重启后端才生效；前端改动经 Vite HMR 即时生效。

## 2026-10-25 修订：其余通知补上「谁、对什么、什么时候」

用户给出范例句式（发词通知）并要求「全局扫描 notification msg，酌情添加详细信息」：

> 新的词集 / 2026/10/25 12:36 / 演示班的JZ老师发来一组生词「不规则动词」，请在2026/10/27 03:36之前完成预习。

扫描结果：5 类通知仍只有「老师已布置…请按时完成」这类泛化文案，`ai_circuit_*` 还在写入时拼死中文。按 A1 的既有做法补齐：

| kind | 新增 payload 事实 | 变体与回退 |
| --- | --- | --- |
| `offering_published` | `teacher`、`className`（学生所在的第一个参与班）、`dueAt`（按班覆盖优先） | `exam_detail` / `assignment_detail`；缺任一字段回退原 `exam` / `assignment` |
| `scores_published` | `score`、`total`（只取已判完的 active 份）、`teacher`、`className` | `detail`（有班级与老师）/ `scored`（只有分）；都没有回退原泛化文案 |
| `ai_not_ready` | `dueAt`（最早截止）、`waiting`（批次内 PENDING/RUNNING/QUEUED 份数） | `due`；缺字段回退原文案 |
| `ai_circuit_open` | `provider`、`failures`（连续失败份数）、`errorKind`、`nextProbeAt` | 模板键 `notification.ai_circuit_open.*`；旧 `notification.aiCircuit.*` 与写入时拼接一起删除 |
| `ai_circuit_close` | `provider`、`requeued` | 模板键 `notification.ai_circuit_close.*` |
| `feedback_published` | 不改 | 已有作业名 + 得分/满分；grading 只有 ledger、没有班级/目录端口，加「哪个班」要新开端口，暂不做 |

- 模板参数**追加**到 {8}–{13}：`provider`、`failures`、`requeued`、`nextProbeAt`、`errorKind`、`waiting`；前面的 {0}–{7} 序号不动。
- 缺参数的键传空串而不是 `null`：`MessageFormat` 会把 `null` 渲染成字面量「null」，「预计 null 自动试探」比少一句细节更糟。
- `ai_not_ready` 的统计失败不阻断通知：`waiting`/`dueAt` 在 try/catch 里取，取不到就发原有事实。
- 成绩只在提交已判完（`grading_state = DONE`）时写分数：没判完显示 0 分会误导学生。
