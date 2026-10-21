# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-21（T321 材料分组 + RubricValidator 文案 i18n；`make test` 225/0/0/0 全绿）

## 当前

- 正在做：**评分质量 T255–T267 已实现并全量回归通过**；服务端 rubric 校验、AI 溯源两列、锚点简化版已补；剩按项编辑器与批量通过并发布
- 已修复：**T320 答题页题干标签**——阅读材料标签 A–D 不再拼进题干，V032/V033 清理存量
  题干与已发布快照；QA 已部署到 v033，学生接口与页面不再出现孤立字母
- 已实现：**T321 共用材料只渲染一次**——阅读材料一次 + 题目列表；完形/七选五空在原文里
  （点空弹 A–D/A–G）；语法填空行内输入；QA 实测整卷高度 32,147 → 14,286 px
- 已修复：**T322 RubricValidator 文案 i18n**——5 条问题文案进 zh/en 消息包，解析词表移到
  domain；`check-i18n` 基线从 311 降到 290，不再靠扩基线绕过
- 已修复：**T323 复核页逐题判分可视化**——题项表拆「得分/满分」，对绿/错红/部分琥珀；
  教师判分区前置学生正文、主观题作答、答题卡与 AI 草稿预填提示；QA 5174 已部署
- 已确认：初始化/登录/资料三处配 DeepSeek Key、Key 永不明文、反馈语言跟学生界面语言、
  作业 rubric 优先、问候语不计段、AI 反馈预填 + 批量通过
- 背单词：`teachloop-vocab` 空模块已接线（**无表、无端口、无路由**），规格目录 `specs/004-vocab/` 待写
- 分支 / HEAD：`main`（`feat/ui-standard` 已合入并删除）；背单词的实现分支尚未创建，
  届时用 `git switch -c feat/vocab main`
- 未提交 WIP：以 `git status` 为准（本行不再写快照，避免文档腐化）
- 最近验证：真实 DeepSeek（教师个人 DB Key，不设环境变量）对 Maria/Mark 与 Iam/Alejandro 判分成功；
  golden set 逐项 exact 29/30、总分 9/10、契约失败 0、英文语言合规 10/10；
  `pnpm typecheck` / `pnpm build` 0、`check-i18n` 1103 键、`check-ui` 通过；
  **`make test` 退出码 0，surefire 225 tests / 0 failures / 0 errors / 0 skipped**；
  `check-deps` / `check-boundaries` / `check-i18n`（后端基线 290）/ `check-ui` 全通过。
  T322 已把 `RubricValidator` 的校验文案改成消息 key，中文解析词表移到 `domain/RubricVocabulary`。

## 下一步

1. 按评分项编辑器、锚点自动匿名化、批量通过并发布（可选）
2. 生产上线前按 `docs/DELIVERY-STATUS.md` 补已知偏差（OSS/PDF 字体/AC-18.5 等）
3. 用真实微信凭据与生产数据库做一次部署演练

## 剩余

- 评分质量：按评分项编辑器、锚点自动匿名化、批量通过并发布（可选）
- UI 标准：T308（21 屏截图基线）、T309（剩余 13 屏组件化）、T310（删兼容层）未做
- 其余无未完成任务；已知偏差见 docs/DELIVERY-STATUS.md
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；压测为 stub 引擎
