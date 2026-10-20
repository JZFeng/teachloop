# TeachLoop 进度仪表盘

> 每个批次结束时由 Lead 更新；`make status` 会打印下面的「当前」与「剩余」。
> 最近一次更新：2026-10-19（T172 完成；T170/T171 记录为部分执行）

## 当前

- 正在做：T172 已完成；T170/T171 已记录（部分）；T173–T175 阻塞/未开始
- 分支 / HEAD：`main`，最后提交 `3271b01`
- 未提交 WIP：无（本批即将提交）
- 最近验证：`make build` / `make test` / `make web-build` / 两个守卫全部 exit 0（`.dsh/logs/verify-security.log`）；`IdentityAuthTest`、`ProdAuthTest`、`ProfileCompletionTest`、`WeChatAuthProviderTest`、`FlowIntegrationTest` 通过

## 下一步

1. 提交 T130（已验证）
2. 续完 T037 限流、T140–T144 可观测（WIP 已存在）
3. 再进入部署/硬化/身份等剩余任务

## 剩余


- 边界/班级：T042、T043
- 试卷：T053–T056、T062、T206
- 判分收尾：T071、T072、T102

- 报表：T150、T151
- 部署与硬化：T170、T171（部分）、T173（阻塞 T072）、T174（未开始）、T175（缺语料）
- 其他：T007、T020、T021
- 已知偏差：PDF 中文非嵌入字体；OSS 仅骨架；解析自动生成（AC-18.5）未做；资料补全页前端未做；E2E 未按 3 班 77 题重放；无 2+2 压测；无 golden set 语料
