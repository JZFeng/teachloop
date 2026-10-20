# TeachLoop

公网 AI 教学平台：教师出卷 → 学生作答 → AI 批改 → 教师复核 → 发布反馈与报告。
面向一位教师、若干班级、几十名学生的真实课堂场景，部署在阿里云 + 自有域名。

`loop` 同时指两件事：批改反馈的闭环（出卷 → 作答 → 批改 → 复核 → 报告），以及将来「背单词」模块的间隔重复循环。所以加模块不需要改名。

## 现在到哪一步了

| 阶段 | 状态 | 产出物 |
| :--- | :--- | :--- |
| Research | 完成 | [`docs/research/`](docs/research/) |
| Constitution | 完成（v2.1，14 条） | [`.specify/memory/constitution.md`](.specify/memory/constitution.md) |
| Spec | 完成，待最终确认 | [`specs/002-v2-platform/spec.md`](specs/002-v2-platform/spec.md) — 26 用户故事 / 203 验收标准 / 42 功能需求 |
| Mock | 完成 | [`specs/002-v2-platform/mockups/interface.html`](specs/002-v2-platform/mockups/interface.html) — 20 个界面、四套主题、可点击 |
| Plan | 完成，逐节评审中 | [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md) |
| Tasks | 完成，待 Plan 过闸后重排 | [`specs/002-v2-platform/tasks.md`](specs/002-v2-platform/tasks.md) — 128 个任务 |
| Implement | **未开始** | 等用户明确说「开始实现」 |

## 文档怎么读

1. 先读 [`AGENTS.md`](AGENTS.md)——七步顺序与红线，只有一屏。
2. 再读 [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md) 的 §0「怎么评审这份文件」。
3. 界面长什么样，直接用浏览器打开 `specs/002-v2-platform/mockups/interface.html`（单文件，无需构建）。

## 技术栈

- 后端：Java 21 + Spring Boot 4.1.x（Spring Framework 7）+ Jackson 3（`tools.jackson`）+ Flyway + PostgreSQL 16
- 模型：DeepSeek `deepseek-flash`，经 Spring AI 直连官方 API
- 前端：React + TypeScript + Vite
- 队列：不引入消息队列，工作项就是提交行，条件更新领取；定时任务 `@Scheduled` + 数据库条件更新（不引入独立锁表）

九个 Maven 子模块（`teachloop-shared` … `teachloop-app`）与依赖方向见 plan §3。**模块边界是编译期强制的**，不是约定。

## 目录

```
teachloop/
├── specs/          规范：用户故事、验收标准、技术方案、任务、界面原型
├── docs/           调研、决策记录、评分模板与样例、历史归档、报告
└── .specify/       项目宪法
```

`pom.xml` 与九个模块目录尚未建立——它们属于实现阶段的第一步（T001）。

## 隐私

真实试卷与真实学生作答**不进入本仓库**（宪法第七条）。仓库内的样例提交与评语是脱敏的教学样例，仅用于测试评分流程。
