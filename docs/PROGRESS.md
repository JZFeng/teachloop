# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-21（背单词模块骨架接线 + 分支约定落盘）

## 当前

- 正在做：T072/T173 完成；T175 执行器就绪，AI 偏差待 `DEEPSEEK_API_KEY`
- 背单词：`teachloop-vocab` 空模块已接线（**无表、无端口、无路由**），规格目录 `specs/004-vocab/` 待写
- 分支 / HEAD：`main`，最近提交见 `git log`；`feat/ui-standard` 是并行的 UI 标准分支（未合并）
- 未提交 WIP：无（工作区干净）
- 最近验证：`make test` / `make build` / `make web-build` / 边界守卫 exit 0；147 份 surefire 报告 0 失败；`make test-ai` Skipped 0；T175 golden set 偏差 0.0

## 下一步

1. 生产上线前按 `docs/DELIVERY-STATUS.md` 补已知偏差（OSS/PDF 字体/AC-18.5 等）
2. 用真实微信凭据与生产数据库做一次部署演练

## 剩余

- 无未完成任务；已知偏差见 docs/DELIVERY-STATUS.md
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；压测为 stub 引擎
