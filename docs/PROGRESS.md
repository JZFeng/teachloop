# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-19（停止后重建仪表盘）

## 当前

- 正在做：无（用户要求先停；T130 已完成待提交，T037/T140 为被中断的 WIP）
- 分支 / HEAD：`main`，最后提交 `3271b01`
- 未提交 WIP：
  - T130 后端 i18n：`I18nConfig`、`GlobalExceptionHandler`、`messages*.properties`、`I18nErrorTest`
  - T037 限流：`identity/rate/`、`V019`、`SecurityConfig`、`RateLimitTest`
  - T140–T144 可观测：`observability/log/`、`service/`、`EventEndpoint`、`DailyStat*`、`V020`、`ObservabilityTest`
- 最近验证：T130 的 `make build` + `I18nErrorTest` 通过；RateLimitTest 3 例、ObservabilityTest 4 例的报告也显示通过，但中断后的工作区尚未整体回归

## 下一步

1. 提交 T130（已验证）
2. 续完 T037 限流、T140–T144 可观测（WIP 已存在）
3. 再进入部署/硬化/身份等剩余任务

## 剩余

- 身份：T030–T036、T037（WIP）
- 边界/班级：T042、T043
- 试卷：T053–T056、T062、T206
- 判分收尾：T071、T072、T102
- 可观测：T140–T144（WIP）
- 报表：T150、T151
- 部署与硬化：T160–T163、T170–T175
- 其他：T007、T020、T021
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做
