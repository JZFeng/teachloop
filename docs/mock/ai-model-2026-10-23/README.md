# AI 模型设置 · 可点 mock（2026-10-23）

为什么做这一版：上一版把 **provider ID / 地址 / Key / 拉模型** 全塞进教师 `/profile`，信息层级错了。
用户确认 v1 应该是：

- **管理员**在 `/admin/ai` 维护 provider 列表：provider ID、显示名称、API 地址、API 协议（v1 只做
  OpenAI 兼容）、API 密钥；点「获取可用模型」后勾选模型。
- **教师**在 `/profile` 只从「配置好的模型」下拉里选一个；**选中的模型对全站生效**（v1 不做
  按考试/作业覆盖）。
- 学生仍然看不到 AI 设置。

打开 `index.html` 可点；也支持直接用 hash 打开对应状态：

- `index.html#admin` — 管理员 provider 列表
- `index.html#admin-form` — 添加 provider 表单
- `index.html#admin-form-modal` — 获取模型弹窗
- `index.html#teacher` — 教师只选模型

截图：`admin-list.png` / `admin-add.png` / `admin-models.png` / `teacher.png`。

**状态**：仅 mock，不是产品代码；代码里现有的「教师自定义 provider」表单在确认后会被这版替换。
