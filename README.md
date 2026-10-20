# TeachLoop

公网 AI 教学平台：教师出卷 → 学生作答 → AI 批改 → 教师复核 → 发布反馈与报告。
面向一位教师、若干班级、几十名学生的真实课堂场景，部署在阿里云 + 自有域名。

`loop` 同时指两件事：批改反馈的闭环（出卷 → 作答 → 批改 → 复核 → 报告），以及将来「背单词」模块的间隔重复循环。所以加模块不需要改名。

## 现在到哪一步了

| 阶段 | 状态 | 产出物 |
| :--- | :--- | :--- |
| Research | 完成 | [`docs/research/`](docs/research/) |
| Constitution | 完成（v2.1，14 条） | [`.specify/memory/constitution.md`](.specify/memory/constitution.md) |
| Spec | 完成（已确认） | [`specs/002-v2-platform/spec.md`](specs/002-v2-platform/spec.md) — 26 用户故事 / 203 验收标准 / 42 功能需求 |
| Mock | 完成 | [`specs/002-v2-platform/mockups/interface.html`](specs/002-v2-platform/mockups/interface.html) — 20 个界面、四套主题、可点击 |
| Plan | 完成（已确认） | [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md) |
| Tasks | 完成 | [`specs/002-v2-platform/tasks.md`](specs/002-v2-platform/tasks.md) — 133 个任务 |
| Implement | **进行中** | 后端纵切 + T198–T202 + T204/T205 + T091–T094 + T080–T083 + T186 导入 + T192 错题报表 + Markdown/ZIP 导出 + 错题解析；前端 `web/` 覆盖 T180–T197；导出支持 Markdown / XLSX / DOCX / ZIP / PDF；复核工作台（改分/发布/撤回/批量）与站内通知已接入；真实 DeepSeek 调用已验证 |

## 文档怎么读

1. 先读 [`AGENTS.md`](AGENTS.md)——七步顺序与红线，只有一屏。
2. 再读 [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md) 的 §0「怎么评审这份文件」。
3. 界面长什么样，直接用浏览器打开 `specs/002-v2-platform/mockups/interface.html`（单文件，无需构建）。

## 技术栈

- 后端：Java 21 + Spring Boot 4.1.x（Spring Framework 7）+ Jackson 3（`tools.jackson`）+ Flyway + PostgreSQL 16
- 模型：DeepSeek `deepseek-flash`，经 Spring AI 2.0.1 `ChatClient` 调用 OpenAI 兼容端点；`LlmClient` 的 JDK HttpClient 实现保留为 `teachloop.ai.client=http` 回退
- 前端：`web/`，React 19 + TypeScript + Vite 8 + i18next；视觉与交互按 `specs/002-v2-platform/mockups/interface.html`
- 队列：不引入消息队列，工作项就是提交行，条件更新领取；定时任务 `@Scheduled` + 数据库条件更新（不引入独立锁表）

九个 Maven 子模块（`teachloop-shared` … `teachloop-app`）与依赖方向见 plan §3。**模块边界是编译期强制的**，不是约定。

## 目录

```
teachloop/
├── pom.xml                Maven 聚合工程（Java 21 / Spring Boot 4.1）
├── teachloop-shared/      端口与共享原语（record、错误、时钟、审计）
├── teachloop-identity/    登录、会话、账号、通知
├── teachloop-classroom/   班级与成员
├── teachloop-paper/       试卷、部分、题项、评分项
├── teachloop-sitting/     布置、参与班级、作答、计时、提交
├── teachloop-grading/     派发、批改、复核、AI 调用留痕
├── teachloop-report/      报表（只读聚合）
├── teachloop-observability/ 审计与统计
├── teachloop-app/         装配与迁移调度
├── web/                   前端（Vite + React + TS + i18next）
├── specs/                 规范：用户故事、验收标准、技术方案、任务、界面原型
├── docs/                  调研、决策记录、评分模板与样例、历史归档、报告
├── scripts/               依赖方向与模块边界守卫
└── .specify/              项目宪法
```

## 快速开始

```bash
./scripts/dev.sh     # 一键：DB + 后端 + 前端，Ctrl-C 自动清理
# 或分步：
make start-db        # 本地 Postgres（55432）
make build           # 后端编译打包（make test 会先跑两个边界守卫）
make test            # 全部测试（Testcontainers，需要 Docker）
make run             # 后端 :8010

make web-install     # 安装前端依赖
make web-dev         # 前端 :5173，/api 代理到 :8010
make web-build       # 前端生产构建
```

开发账号：`teacher@teachloop.local` / `student@teachloop.local`，密码 `teachloop123`（仅 dev profile 由 `DevDataSeeder` 创建）。

## 隐私

真实试卷与真实学生作答**不进入本仓库**（宪法第七条）。仓库内的样例提交与评语是脱敏的教学样例，仅用于测试评分流程。
