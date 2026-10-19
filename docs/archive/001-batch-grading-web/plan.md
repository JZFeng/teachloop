# 技术方案 — AI 批量批改作业 Web 系统（v1）

- 特性编号：`001-batch-grading-web`
- 上游：`spec.md`、`.specify/memory/constitution.md`
- 日期：2026-10-18

---

## 1. 架构总览

```
浏览器 (React + TS, Vite)
   │  REST (JSON) + SSE
   ▼
FastAPI 应用
   ├── AuthProvider ──► WeChatOpenProvider / DevProvider
   ├── 业务层（班级 / 作业 / 提交 / 复核 / 审计）
   ├── 批改编排
   │     ├── 客观指标（纯代码）── ported from collect_submissions.py
   │     ├── OcrProvider ─────► MacVisionOcr / TesseractOcr / CloudOcr
   │     └── GradingEngine ───► DshHeadlessEngine / DirectApiEngine
   ├── 存储抽象 ──► LocalStorage（S3 兼容签名，可换 MinIO）
   └── SSE 通道
   ▼
PostgreSQL 15（Alembic 迁移）
```

关键约束（来自宪法）：**客观项只由代码算**；**模型只写主观判定**；**读不到不算分**；
**三处可替换边界**（认证 / OCR / 引擎）。

---

## 2. 技术栈

### 后端

| 组件 | 选型 | 理由 |
| :--- | :--- | :--- |
| 框架 | FastAPI 0.115+ | 异步、SSE 原生支持、Pydantic 校验 |
| ORM | SQLAlchemy 2.0（async） | 与 FastAPI 异步一致 |
| 迁移 | Alembic | 宪法第八条强制 |
| 校验 | Pydantic v2 | 模型输出结构化校验 |
| 文档解析 | python-docx、pypdf | 已在本机验证可用 |
| 媒体 | ffprobe | 已安装于 `/opt/homebrew/bin/ffprobe` |
| 会话 | itsdangerous 签名 Cookie + DB session 表 | 可主动吊销 |
| 测试 | pytest + httpx AsyncClient | 每个端点有测试 |
| 运行 | uvicorn（端口 **8010**） | 8000 已被本机其他服务占用；19387 为 DSH GUI 保留 |

### 前端

| 组件 | 选型 |
| :--- | :--- |
| 框架 | React 18 + TypeScript |
| 构建 | Vite（端口 **5173**） |
| 路由 | react-router |
| 数据 | TanStack Query（含 SSE 断线重连的自定义 hook） |
| 样式 | 原生 CSS Modules（不引入重型 UI 库，保持可控） |
| 图表 | 暂不需要；统计页留到 v2 |

### 基础设施

| 组件 | v1 方案 | 后续 |
| :--- | :--- | :--- |
| 数据库 | PostgreSQL 15（Docker Compose；若本机已有 Postgres 可直接连） | 托管实例 |
| 对象存储 | 本地目录 `data/objects/`，经 `StorageBackend` 接口 | MinIO / S3 |
| 部署 | 开发：两个进程；生产：docker compose（db / backend / web） | 反向代理 + TLS |

> 端口说明：本项目使用 **8010** 与 5173，**不替换也不占用** DSH GUI 的 19387。
> 8000 已被本机一个 `python3.1` 进程（PID 929）占用，故后端不用 8000。

---

## 3. 三处可替换边界

### 3.1 AuthProvider

```python
class AuthProvider(Protocol):
    name: str
    async def build_login(self, redirect_uri: str) -> LoginChallenge: ...
    async def exchange(self, code: str, state: str) -> ExternalIdentity: ...
```

| 实现 | 用途 | 状态 |
| :--- | :--- | :--- |
| `WeChatOpenProvider` | 生产：开放平台网站应用扫码登录 | 需 AppID/AppSecret（B-1） |
| `WeChatMpProvider` | 微信内 H5 网页授权 | 留接口，v2 |
| `DevProvider` | 本地开发替身，直接选角色登录 | v1 立即可用 |

**为什么这样设计**：微信凭据是阻塞项（B-1/B-2），但宪法要求可替换。开发替身让全流程
在拿到凭据前就能跑通；凭据到位后只改环境变量。

### 3.2 OcrProvider

```python
class OcrProvider(Protocol):
    name: str
    async def extract(self, data: bytes, mime: str) -> OcrResult: ...

@dataclass
class OcrResult:
    text: str
    confidence: float | None
    pages: int | None
    provider: str
```

| 实现 | 说明 | 状态 |
| :--- | :--- | :--- |
| `MacVisionOcr` | 调用本地 Swift 小工具（Vision + PDFKit），图片与 PDF 都支持 | v1 默认 |
| `TesseractOcr` | `brew install tesseract`，跨平台 | 留接口 |
| `CloudOcr` | 云厂商 API | 留接口，需隐私确认 |

**macOS Vision 的落地方式**：编译一个 `ocrtool` 命令行小工具（Swift，约 120 行），
输入文件路径输出 JSON `{text, pages}`。后端以子进程调用。这样避免 Python 绑定不稳定，
也让 `MacVisionOcr` 只需要管子进程与错误映射。

### 3.3 GradingEngine

```python
class GradingEngine(Protocol):
    name: str
    async def grade(self, req: GradeRequest, on_event: Callable) -> GradeResult: ...
```

| 实现 | 说明 | 状态 |
| :--- | :--- | :--- |
| `DshHeadlessEngine` | 子进程 `dsh headless --json`，解析 NDJSON 事件 | v1 默认 |
| `DirectApiEngine` | 直连 DeepSeek/OpenAI 兼容端点 | 留接口 |
| `ScriptOnlyEngine` | 只算客观项，主观项留空待人工 | 降级开关 |

`DshHeadlessEngine` 事件映射（已实测）：

| NDJSON `type` | 用途 |
| :--- | :--- |
| `session` | 记录 sessionId，便于排错 |
| `status`（turn/step） | 转成进度百分比 |
| `text` | 累积为模型输出 |
| `final` | 最终文本，进入结构化解析 |
| `status.step_end.usage` | 记录 token 消耗 |

---

## 4. 数据模型

```
users(id, role[teacher|student], wechat_openid, wechat_unionid, display_name,
      avatar_url, created_at, updated_at)
      -- 实现说明：wechat_openid 同时承担「当前 provider 的外部身份 id」，
      -- 采用 `provider:key` 前缀约定（wechat_open:oABC… / dev:teacher:张老师），
      -- 由 app/auth/base.py 的 make_external_id() 生成。
      -- 若将来需要多个 provider 同时活跃，再拆出 identities(provider, external_id) 表。

sessions(id, user_id→users, expires_at, created_at, revoked_at)

classes(id, name, teacher_id→users, invite_code, invite_active, created_at)

class_members(id, class_id→classes, user_id→users, role[teacher|student],
              joined_at, UNIQUE(class_id, user_id))

assignments(id, class_id→classes, title, instructions, due_at, max_points,
            length_mode[paragraphs|minutes|none], length_min, length_max,
            length_note, status[draft|published|closed], created_at)

rubric_criteria(id, assignment_id→assignments, name, max_points, description,
                position)

submissions(id, assignment_id→assignments, student_id→users, version, status
            [uploaded|needs_ocr|processing|ready|unreadable|failed],
            late, original_filename, content_type, size_bytes, storage_key,
            extracted_text, ocr_provider, submitted_at, superseded_by)

submission_metrics(id, submission_id→submissions UNIQUE, word_count,
                   paragraphs_by_model json, boundary_sensitive bool,
                   duration_minutes numeric, computed_at)

grading_jobs(id, assignment_id→assignments, status[queued|running|completed|
             interrupted|failed], total, done, failed, concurrency,
             created_by→users, created_at, started_at, finished_at)

grading_job_items(id, job_id→grading_jobs, submission_id→submissions,
                  status[queued|running|done|failed|skipped], attempts,
                  error, started_at, finished_at)

reviews(id, submission_id→submissions UNIQUE, ai_payload json, ai_total,
        state[pending|approved|minor_edit|major_edit|rejected],
        final_payload json, final_total, reviewer_id→users,
        approved_at, published_at, updated_at)

audit_logs(id, actor_id→users, entity_type, entity_id, action,
           before json, after json, created_at)   -- 只追加

notifications(id, user_id→users, type, payload json, read_at, created_at)
```

索引要点：`submissions(assignment_id, student_id, version)`、
`grading_job_items(job_id, status)`、`audit_logs(entity_type, entity_id, created_at)`。

---

## 5. 批改管线（一次任务的完整时序）

```
1. POST /grading-jobs
   └─ 创建 job + items（跳过 unreadable 与已完成的）
2. 后台 worker（asyncio，并发默认 3）
   对每份 submission：
   a. 确保有文本：解析 docx/pdf/txt；图片或扫描件 → OcrProvider
   b. 若文本仍为空 → status=unreadable，item=skipped，不发模型（宪法第四条）
   c. 计算客观指标（纯代码）：
      word_count / paragraphs_by_model(4 模型) / boundary_sensitive / duration
   d. 组装 GradeRequest：
      rubric + 作业说明 + 学生文本 + 客观指标（作为"证据"而非结论）
   e. GradingEngine.grade()，NDJSON 事件逐条转 SSE
   f. Pydantic 校验模型输出；不合规重试 1 次
   g. 写入 reviews(ai_payload, ai_total, state=pending)
   h. item=done，SSE 推一条 item_done
3. 全部完成 → job=completed，SSE 推 job_done
4. 单份超时 180s → item=failed，继续其余（宪法不阻塞整批）
```

**段落计数为什么仍在代码里**（与用户"交给 AI 判"的关系）：

用户主张"不同作业不同 rubric，rubric 写清楚后 AI 自行判断"——这一点被采纳为
`assignments.length_mode/min/max/length_note` 的**作业级配置**，代码不设全局政策。
但同一次实测显示：在没有任何计数约定时，模型对结构相近的两份作业给出了相反判定。
因此代码仍计算四模型段数并作为**证据**随 prompt 传入，同时把 `boundary_sensitive`
显式暴露给教师复核（AC-6.6）。这既尊重 rubric 权威，又不丢掉可复现性。

---

## 6. API 轮廓

| 分组 | 端点 |
| :--- | :--- |
| 认证 | `GET /api/auth/me`、`POST /api/auth/logout`、`GET /api/auth/wechat/login`、`GET /api/auth/wechat/callback`、`POST /api/auth/dev-login`（仅 dev） |
| 班级 | `POST /api/classes`、`GET /api/classes`、`GET /api/classes/{id}`、`POST /api/classes/join`、`POST /api/classes/{id}/invite/rotate`、`GET /api/classes/{id}/members` |
| 作业 | `POST /api/classes/{cid}/assignments`、`GET /api/assignments/{id}`、`PATCH /api/assignments/{id}`、`POST /api/assignments/{id}/publish` |
| 提交 | `POST /api/assignments/{id}/submissions`、`POST /api/assignments/{id}/submissions/import`、`GET /api/assignments/{id}/submissions`、`GET /api/submissions/{id}` |
| 批改 | `POST /api/assignments/{id}/grading-jobs`、`GET /api/grading-jobs/{id}`、`GET /api/grading-jobs/{id}/events`（SSE）、`POST /api/grading-jobs/{id}/resume` |
| 复核 | `GET /api/assignments/{id}/reviews`、`PATCH /api/reviews/{id}`、`POST /api/reviews/{id}/approve`、`/reject`、`/regrade`、`/publish`、`POST /api/assignments/{id}/reviews/approve-all` |
| 学生 | `GET /api/me/assignments`、`GET /api/submissions/{id}/feedback` |
| 通知 | `GET /api/notifications`、`POST /api/notifications/{id}/read` |
| 导出 | `GET /api/assignments/{id}/export.zip`、`GET /api/assignments/{id}/export.csv` |

统一约定：错误返回 `{code, message, detail?}`；越权返回 **404**（不泄露存在性）；
所有写操作校验 CSRF token。

---

## 7. 目录结构

```
batch-grading/
├── backend/
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── db.py
│   │   ├── models/            # SQLAlchemy
│   │   ├── schemas/           # Pydantic
│   │   ├── api/               # 路由，按资源分文件
│   │   ├── auth/              # AuthProvider + 实现
│   │   ├── ocr/               # OcrProvider + 实现
│   │   ├── grading/
│   │   │   ├── length.py      # 四模型段落计数（移植自已验证脚本）
│   │   │   ├── objective.py   # 词数/时长/格式
│   │   │   ├── engine.py      # GradingEngine + 实现
│   │   │   └── pipeline.py    # 编排与 SSE
│   │   └── storage/           # StorageBackend + 实现
│   ├── tools/ocrtool/         # Swift Vision 小工具
│   ├── migrations/            # Alembic
│   └── tests/
├── frontend/
│   ├── src/
│   │   ├── pages/            # 登录/班级/作业/复核/学生
│   │   ├── components/
│   │   ├── api/              # client + SSE hook
│   │   └── types/
│   └── vite.config.ts
├── docker-compose.yml
├── Makefile
└── README.md
```

---

## 8. 实施顺序（与 tasks.md 对应）

| 阶段 | 内容 | 依赖 |
| :--- | :--- | :--- |
| 0 | 脚手架、`make`、健康检查、CI 骨架 | — |
| 1 | 数据模型 + Alembic 迁移 | 0 |
| 2 | 认证抽象 + DevProvider + 会话 | 1 |
| 3 | 班级 / 成员 / 邀请码 | 2 |
| 4 | 作业 / rubric / 篇幅口径 | 3 |
| 5 | 上传 + 文本提取 + OCR 抽象 | 4 |
| 6 | 客观指标（移植 length.py + 测试） | 5 |
| 7 | 批改编排 + GradingEngine + SSE | 6 |
| 8 | 四态复核 + 审计日志 | 7 |
| 9 | 学生端反馈 + 通知 | 8 |
| 10 | 导出 ZIP/CSV | 8 |
| 11 | 微信登录接入（凭据到位后） | 2 |
| 12 | 端到端测试与硬化 | 全部 |

---

## 9. 宪法符合性检查

| 原则 | 本方案如何满足 |
| :--- | :--- |
| 一 教师最终决定权 | `reviews.state` 是发布前置条件；无跳过路径 |
| 二 可审计 | `audit_logs` 只追加；发布可追溯到 review 版本 |
| 三 Rubric 优先 | 评分口径存于 `rubric_criteria` 与 `assignments.length_*`，代码无硬编码 |
| 四 读不到 ≠ 0 | pipeline 步骤 b 直接跳过评分，标 `unreadable` |
| 五 客观项由代码算 | `length.py` / `objective.py` 纯代码；模型只读结果 |
| 六 可替换边界 | 三处 Protocol 接口 + 实现表 |
| 七 隐私与班级隔离 | 所有查询带 class 归属校验；越权返回 404 |
| 八 技术标准 | FastAPI / Postgres / Alembic / React+TS / UTC |
| 九 可运行验证 | 每阶段要求 `make test` 全绿 |
| 十 不伪造状态 | tasks.md 区分"已实现"与"未实现" |

---

## 10. 已知风险

| 风险 | 影响 | 缓解 |
| :--- | :--- | :--- |
| 微信凭据缺失（B-1/B-2） | 真实扫码无法联调 | DevProvider 保证流程可跑；凭据到位后仅改配置 |
| 本机无 Docker / Postgres | 阶段 1 阻塞 | 阶段 0 先探测；必要时用 `postgres` 容器或本地安装 |
| macOS Vision 需编译 Swift 工具 | 阶段 5 风险 | 先做最小可行性验证再接入；失败则回退 Tesseract |
| `dsh headless` 依赖本机 DSH | 部署受限 | 保留 `DirectApiEngine` 接口；生产可切换 |
| 模型输出不稳定 | 批改质量波动 | Pydantic 校验 + 重试 + 人工复核兜底 |
