# TeachLoop v2 交付状态

日期：2026-10-20　分支：`main`　最近提交见 `git log -1`

## 一、可运行入口

```bash
make start-db          # Postgres 16 @ 55432
make run               # 后端 @ 8010
make web-dev           # Vite @ 5173（/api 代理到 8010）
make seed              # 可重复演示数据：1 班 + 30 学生 + 1 份 4 题试卷
```

本地账号：`teacher@teachloop.local` / `student@teachloop.local`，密码 `teachloop123`。
生产部署：`docs/deploy-aliyun.md`、`Dockerfile`、`docker-compose.prod.yml`、`.env.example`。

## 二、验收命令

```bash
make build             # 九模块编译 + 打包
make test              # Testcontainers 集成测试 + check-deps/check-boundaries/check-i18n
make web-build         # tsc + vite build
make status            # 最近提交/工作区/测试/进度
```

真实 AI：需要 `DEEPSEEK_API_KEY`，`make test-ai` 跑 2 个真机调用测试。
golden set：`DEEPSEEK_API_KEY=... python3 scripts/golden-set.py`。

## 三、已完成（按批次）

| 批次 | 内容 | 证据 |
| :--- | :--- | :--- |
| 骨架/边界 | 九模块、依赖守卫、端口守卫、迁移表归属 | `make check-*` |
| 认证/账号 | Dev/Admin/微信 provider、教师邀请码、管理员 CLI、CSRF（prod）、资料补全、角色守卫、限流 | `IdentityAuthTest`、`ProdAuthTest`、`ProfileCompletionTest`、`RateLimitTest` |
| 班级 | 建班/邀请码/加入、成员列表含姓名与提交数、访问控制 | `ClassAccessTest` |
| 试卷 | CRUD/导入/题型/匹配策略/发布锁/rubricVersion/答案表/75 题拆分/复制/归档 | `Paper*Test`、`MatchStrategyTest`、`PaperLibraryTest` |
| 布置/作答 | 快照、多班、按班覆盖、服务端计时、自动交卷、迟交/拒收、new attempt | `FlowIntegrationTest`、`Phase2ExamTimingTest`、`NewAttemptTest` |
| 提交形态 | 富文本消毒、文件（PDF/DOCX/TXT/MD）、视频（ffprobe 360s/1920x1080）、附件下载 | `SubmissionFormsTest`、`VideoRulesTest`、`VideoSubmitTest` |
| 批改 | 客观判分、AI 判分、调度/僵尸清扫、总分派生、改答案重算 | `GradingDispatchTest`、`ObjectiveRecomputeTest`、`make test-ai` |
| 复核 | 三态、编辑/发布/撤回/批量、审计、发布可见与通知 | `ReviewWorkflowTest` |
| 反馈/报表 | 错题/解析/班级概览、Top N、按作业/班级/学生聚合、XLSX/PDF 导出 | `AggregateReportTest`、`ReportWrongItemsTest`、`ExportTest` |
| 可观测 | 请求日志、前端事件、90 天清理、daily_stats | `ObservabilityTest` |
| 部署 | 镜像（Noto CJK/ffprobe）、compose、深健康探针、阿里云文档 | `DeepHealthTest` + 镜像实跑 |
| E2E | 旅程 A：3 班 + 77 题；旅程 B：富文本 + 3 次 attempt + 迟交/拒收 | `.docs/e2e-report-v2.md` |
| 压测 | 50 份提交、并发 10；1+1 p95 45ms/33.8s，2+2 p95 97ms/7.22s | `.docs/perf-report-v2.md` |
| 质量基线 | v1 四模型段落计数移植，10 份 parity 全一致 | `TextLengthCounterFixtureTest` |

## 四、部分完成 / 已知偏差

1. **T175 golden set**：人工基线均分 9.6/10 已记录；真实 AI 偏差未跑（缺 `DEEPSEEK_API_KEY`），执行器 `scripts/golden-set.py` 已就绪。
2. **T174 判分引擎**：1+1 与 2+2 拓扑都跑过（批改 33.8s → 7.22s），但压测用 stub 引擎，不代表真实 DeepSeek 吞吐与成本。
3. **PDF 中文字体**：使用 OpenPDF 内置 STSong-Light，非嵌入；视觉正常，复制/检索中文受限。
4. **OSS**：只有 `StorageBackend` 骨架，`STORAGE_BACKEND=oss` 当前运行期 503。
5. **解析自动生成（AC-18.5）**：目前教师按需触发，不是提交后自动生成。
6. **CSRF/资料补全**：只在 `prod` profile 强制；开发/测试关闭。
7. **管理员豁免资料补全**：CLI 创建的管理员无手机号，避免锁死；与「所有角色」字面要求有偏差。
8. **T007**：中文字面量检查采用「基线 + 新增即失败」，存量 275 处未迁移到消息 key。

## 五、仍待用户输入

- `DEEPSEEK_API_KEY`：用于 T175 真实 AI 偏差与 `make test-ai` 真机复验。

除此之外，`specs/002-v2-platform/tasks.md` 中的实现任务均已完成或明确标注为上述偏差。
