# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-21（侧栏图标 T319 + 暗色当前项对比修正）

## 当前

- 正在做：T072/T173 完成；T175 执行器就绪，AI 偏差待 `DEEPSEEK_API_KEY`
- 背单词：`teachloop-vocab` 空模块已接线（**无表、无端口、无路由**），规格目录 `specs/004-vocab/` 待写
- 分支 / HEAD：`main`（`feat/ui-standard` 已合入并删除）；背单词的实现分支尚未创建，
  届时用 `git switch -c feat/vocab main`
- 未提交 WIP：以 `git status` 为准（本行不再写快照，避免文档腐化）
- 最近验证：`make test` 退出码 0（209 tests / 0 failures / 0 errors）；`pnpm typecheck` / `pnpm build` 0；
  `check-ui` 125 处、`check-i18n` 1067 键、`contrast.py` 16 组；T175 golden set 偏差 0.0

## 下一步

1. 生产上线前按 `docs/DELIVERY-STATUS.md` 补已知偏差（OSS/PDF 字体/AC-18.5 等）
2. 用真实微信凭据与生产数据库做一次部署演练

## 剩余

- UI 标准：T308（21 屏截图基线）、T309（剩余 13 屏组件化）、T310（删兼容层）未做
- 其余无未完成任务；已知偏差见 docs/DELIVERY-STATUS.md
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；压测为 stub 引擎
