# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-19（T030/T031/T032/T036 完成，T033–T035 待做）

## 当前

- 正在做：T030–T036 身份批次（T030/T031/T032/T036 已完成；T033–T035 下一批）
- 分支 / HEAD：`main`，最后提交 `3271b01`
- 未提交 WIP：无（本批即将提交）
- 最近验证：`make build` / `make test` / `make web-build` / 两个守卫全部 exit 0（`.dsh/logs/verify-identity.log`）；`IdentityAuthTest`、`ProdAuthTest`、`WeChatAuthProviderTest`、`FlowIntegrationTest` 通过

## 下一步

1. 提交 T130（已验证）
2. 续完 T037 限流、T140–T144 可观测（WIP 已存在）
3. 再进入部署/硬化/身份等剩余任务

## 剩余

- 身份：T033–T035（CSRF 双提交、资料补全拦截、角色守卫测试）
- 边界/班级：T042、T043
- 试卷：T053–T056、T062、T206
- 判分收尾：T071、T072、T102

- 报表：T150、T151
- 部署与硬化：T170–T175
- 其他：T007、T020、T021
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做
