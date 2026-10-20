# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-20（T174 补齐 2+2 拓扑；T175 待 Key）

## 当前

- 正在做：T072/T173 完成；T175 执行器就绪，AI 偏差待 `DEEPSEEK_API_KEY`
- 分支 / HEAD：`main`，最近提交见 `git log`
- 未提交 WIP：本批剩余改动（提交后以 `git status` 为准）
- 最近验证：`make build` / `make test` / `make web-build` / 两个守卫全部 exit 0（`.dsh/logs/verify-parity.log`）

## 下一步

1. 提交 T072/T173/T175 基线
2. 有 Key 时跑 `python3 scripts/golden-set.py`，记录平均/最大偏差
3. 无 Key 时保持 T175 部分完成，不宣称 golden set 已校准

## 剩余

- T175（部分：人工基线已就绪，AI 偏差待 Key）
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；T174 仅 1+1 拓扑
