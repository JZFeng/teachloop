# 环境探测报告（T001）

- 日期：2026-10-18
- 用途：确定阶段 1 能否开工、端口分配、以及三个可替换边界的落地可行性

---

## 1. 结论摘要

| 能力 | 状态 | 对计划的影响 |
| :--- | :--- | :--- |
| Docker CLI | ✅ 29.5.3 | — |
| Docker daemon | ✅ 已启动（原为停止） | 阶段 1 可用容器 Postgres |
| PostgreSQL（本机原生） | ❌ 未安装，无 `psql` | 必须走 Docker |
| Swift / swiftc | ✅ 6.3.2（CommandLineTools） | **T055 OCR 小工具可行** |
| tesseract | ❌ 未安装 | 作为可选 provider，v1 不需要 |
| Node / npm | ✅ v22.23.2 / 10.9.8 | 前端脚手架可用 |
| Python 3.12 | ✅ 3.12.14（DSH 运行时） | 后端**必须用这个**，不是 3.14 |
| 端口 8000 | ❌ 被 PID 929（`python3.1`）占用 | **后端改用 8010** |
| 端口 5173 | ✅ 空闲 | 前端开发服务器 |
| 端口 5432 | ✅ 空闲 | Postgres 容器映射 |
| 端口 19387 | ⚠️ DSH GUI 占用 | 本项目不得占用或干扰 |

## 2. 关键决策（对 plan.md 的修正）

### 2.1 后端端口：8000 → **8010**

`lsof` 显示 8000 被一个已运行的 `python3.1`（PID 929）占用。该进程不属于本项目，
**不终止、不干扰**。plan.md 与所有配置改用 **8010**。

> 需同步修改：`plan.md` 端口说明、`vite.config.ts` 代理目标、compose 端口映射。

### 2.2 数据库：Docker 容器（本机无原生 Postgres）

```yaml
postgres:
  image: postgres:16-alpine
  port: 5432:5432
```

镜像 `postgres:16-alpine` 已在后台拉取中。

### 2.3 后端 Python 版本：**Homebrew 3.14.7**（原计划 3.12，已推翻）

**第一版结论是错的，实测后推翻，记录如下。**

原计划：用 DSH 运行时自带的 CPython 3.12.14 建 `.venv`，理由是 3.14 兼容性未知。

**实测失败**：在 DSH 运行时 Python 上安装依赖后，导入 `pydantic_core` 直接报错：

```
ImportError: dlopen(.../_pydantic_core.cpython-312-darwin.so, 0x0002):
  code signature ... not valid for use in process: mapping process and mapped file
  (non-platform) have different Team IDs
```

**根因**：DSH 运行时的 Python 二进制带 **hardened runtime + library validation** 签名。
macOS 会拒绝加载 Team ID 不一致的第三方 `.so`。这不是版本兼容问题，而是**代码签名问题**——
凡是带原生扩展的依赖（pydantic_core、asyncpg、uvloop…）在这个解释器下都无法加载。
该运行时只适合跑纯 Python 包（如之前装的 pypdf）。

**验证过程**：用 `/opt/homebrew/bin/python3`（3.14.7）建临时 venv 装 pydantic，
`import pydantic_core` 成功。说明 Homebrew 解释器未启用 library validation。

**新决定**：
- 项目 `.venv` 基于 `/opt/homebrew/bin/python3`（3.14.7）。
- 实测全部后端依赖在 3.14 上安装并运行成功（详见下方版本清单）。
- 不再使用 DSH 运行时 Python 承载本项目。

**副作用（正面）**：不再受 DSH 升级重置 site-packages 的影响。

**实测版本清单**（`.venv`，Python 3.14.7）：
fastapi 0.146.0、uvicorn 0.54.0、sqlalchemy 2.1.5、asyncpg 0.32.0、alembic 1.20.0、
pydantic 2.14.1、pydantic-settings 2.16.0、pytest 9.1.1、pytest-asyncio 1.4.0、
python-docx 1.2.0、pypdf 6.21.1、httpx 0.28.1、itsdangerous 2.2.0。

**教训（写入后续判断依据）**：在本机挑选解释器时，先跑一个"原生扩展能否加载"的探针，
再看版本兼容。前者是硬阻塞，后者是软风险。

### 2.4 前端端口绑定：IPv6 问题（已修）

Vite 默认只监听 `[::1]:5173`，导致 `http://127.0.0.1:5173` 无法访问（curl 返回 000）。
已在 `vite.config.ts` 显式设 `host: "127.0.0.1"`，两个主机名都可用，且仍只暴露本机。

### 2.5 npm 缓存目录不可写（已绕过）

`~/.npm` 属主不是当前用户，`npm install` 因无法写缓存而失败（且**退出码被 `| tail` 掩盖成 0**）。
处理：使用项目内缓存 `--cache ../.npm-cache`，已在 `.gitignore` 中忽略。
未修改 `~/.npm` 权限，避免影响用户其他项目。

### 2.6 OCR：Swift 工具可行

Swift 6.3.2 + CommandLineTools 可用，`swiftc` 可编译 Vision/PDFKit 小工具。
T055 的可行性验证有技术基础，不需要 Xcode 完整版。

## 3. 未解决 / 需用户提供

| 项 | 说明 | 阻塞程度 |
| :--- | :--- | :--- |
| 微信 AppID / AppSecret | 真实扫码登录的必需凭据 | 阻塞 T112，不阻塞 T020–T024、T111 |
| 微信授权回调域名 | 需备案且可公网访问 | 同上 |
| Docker 开机自启 | daemon 当前已由本次手动启动；重启后需再次启动 | 影响日常开发体验，可选 `brew install --cask docker` 的开机自启设置 |

## 4. 与 plan.md 的差异记录

| plan.md 原文 | 实际 | 处理 |
| :--- | :--- | :--- |
| 后端端口 8000 | 8000 被占用 | 改为 8010 |
| PostgreSQL 15+ | 采用 16-alpine | 满足"15+"，无需改文档约束 |
| 认证开发替身 | 未变 | — |
| macOS Vision 需 Swift 工具 | 已确认可编译 | 风险降级 |

## 5. 下一步（阶段 1）

1. 创建 `.venv`（Python 3.12）并安装后端依赖。
2. 启动 Postgres 容器，验证连接。
3. T010：SQLAlchemy async engine + Alembic 初始化。

---

## 6. OCR 实测结论（2026-10-18，T055）

**结论：macOS Vision 在本机不可用；改用 Tesseract，效果良好。**

### 6.1 Swift 工具本身是可行的

`backend/tools/ocrtool/main.swift` 用 Vision + PDFKit 实现，编译成功
（需 `-module-cache-path ./重定向`，默认模块缓存目录被沙箱拒绝）。
`--render` 模式能把 PDF 逐页栅格化，实测 6 页 42MB 扫描件渲染正常（4784×6898/页）。

### 6.2 Vision 识别失败

用机械二分法测试了 9 种配置组合：

| 配置 | 结果 |
| :--- | :--- |
| `.fast` | ✅ 成功，0.41s，99 个观测 |
| `.accurate` | ❌ `nilError`（沙箱内）／挂起无输出（沙箱外） |
| 指定 `recognitionLanguages` | ❌ 同样失败 |
| 指定 revision 3 | ❌ 同样失败 |
| 全组合 | ❌ `unknownError` |

`.fast` 虽然能跑，但输出不可用：
`"I l. Il'ho did Ilic maii mosily ini'ilL' to Ilic"`

**判定**：`.accurate` 依赖的模型在本机未安装/不可获取（沙箱外表现为挂起等待）。
这不是我们的代码问题，是运行环境问题。

### 6.3 Tesseract 替代有效

```bash
brew install tesseract tesseract-lang   # 需 danger-full-access
tesseract --version                     # 5.5.3
```

对同一页扫描件：

```
南昌十九中 2026一2027 学年上学期 10 月考试高一英语试卷
第一部分 听力(共两节，满分 30 分)
1. What film will the speakers watch next Saturday?
A. A comedy film.   B. A horror film.   C. A historical film.
```

全流程实测（真实 42MB / 6 页扫描件）：
**31.4 秒 → 20,542 字符，status=ready，ocr_provider=tesseract。**

少量噪声（"10 种钟" / "一小愿"）属 OCR 正常误差，不阻碍批改，且教师复核环节兜底。

### 6.4 决定与影响

| 项 | 决定 |
| :--- | :--- |
| 默认 OCR provider | **tesseract**（原计划 macvision） |
| macvision 实现 | 保留，`available()` 如实报告不可用 |
| 失败语义 | OCR 失败 ⇒ `needs_ocr` + 提示人工录入，**绝不当成空白作业给 0 分** |
| 教师补救 | `PATCH /api/submissions/{id}/text` 手动录入后转 `ready` |
| 切换成本 | 改 `OCR_PROVIDER` 一个环境变量 |

**与你最初要求的偏差**：你指定 v1 用 macOS 自带识别，但它在本机不可用。
切换逻辑已按你的要求做成可替换，所以这只是配置层面的偏离，不是架构偏离。

### 6.5 沙箱相关的两个陷阱（只影响我的测试，不影响真实运行）

1. **tesseract 读不到 `/tmp` 下的图片**（`failed to open locally`）。
   真实应用不在沙箱内运行，不受影响；测试时用工作区内路径即可。
2. **Swift 模块缓存目录**默认在沙箱禁写的 `/var/folders/...`，
   编译需 `-module-cache-path` 指到工作区内。
