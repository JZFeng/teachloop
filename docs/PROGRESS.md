# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-19（T160–T163 完成，镜像与深健康验证通过）

## 当前

- 正在做：T160–T163 已完成，准备提交
- 分支 / HEAD：`main`，最后提交 `3271b01`
- 未提交 WIP：无（本批即将提交）
- 最近验证：`make build/test/web-build/check-*` 全部 exit 0（`.dsh/logs/verify-deploy.log`）；`DeepHealthTest` 通过；`docker compose config` 通过；`docker build` 成功（1.82GB），起容器连 Postgres 后 `/healthz?deep=1` 返回 UP/UP/UP

## 下一步

1. 提交 T130（已验证）
2. 续完 T037 限流、T140–T144 可观测（WIP 已存在）
3. 再进入部署/硬化/身份等剩余任务

## 剩余

- 身份：T030–T036
- 边界/班级：T042、T043
- 试卷：T053–T056、T062、T206
- 判分收尾：T071、T072、T102

- 报表：T150、T151
- 部署与硬化：T170–T175
- 其他：T007、T020、T021
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做
