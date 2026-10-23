# 决策 012 — 存储端口提到 shared，并落地真正的 OSS

日期：2026-10-23　状态：已排期（T386，计划 2026-10-24 01:00 本地时间执行）
上游：`docs/decisions/011-vocab-requirements.md`「音频缓存放哪里」、宪法第十四条、T067（存储抽象骨架）

## 问题

1. 存储抽象 `StorageBackend`（`store` / `load` 两个方法）写在 `teachloop-sitting` 内部
   （`com.teachloop.sitting.storage`），是模块私有类型。
2. 宪法第十四条：跨模块只走 `shared` 里的端口；`scripts/check-boundaries.py` 会拦住
   「业务模块 import 其他模块的内部类型」。
3. 结果：`teachloop-vocab` 按规则**拿不到**这个存储能力。背单词的 TTS 音频缓存因此要么自己另造一套，
   要么等这个端口搬进 `shared`。
4. 更要紧的事实：`OssStorage` 目前是**占位实现**——配置齐全也只抛 503
   （`StorageUnavailableException: OSS 存储尚未接入`）。也就是说生产环境的附件存储一直没落地。

## 决策

### 一、端口搬到 `shared`，实现跟随端口

- 新增 `com.teachloop.shared.storage.BlobStore`（`store` / `load` / `exists`），**只放接口**，不含业务。
- `LocalStorage` 与新的 `AliyunOssStorage` 实现该接口；sitting 与将来的 vocab 都只依赖 `shared` 的接口。
- sitting 的 6 处引用从 `com.teachloop.sitting.storage.StorageBackend` 改成
  `com.teachloop.shared.storage.BlobStore`，**行为不变**。
- `shared` 是「不含业务的共享层」；存储端口属于基础设施，不放业务规则，与宪法第十四条不冲突，
  因此不需要修改宪法条文，只在条文下补一行事实记录。

### 二、落地真正的阿里云 OSS

- 使用阿里云 OSS SDK（`com.aliyun.oss:aliyun-sdk-oss`），不用 S3 兼容层，避免第二个抽象。
- 保持现有**代理读取**模型：浏览器仍然打我们的接口，后端 `load` 出字节再返回；
  本期不做签名 URL、不做 CDN、不做公共读——那是性能优化，不是存储落地的前置。
- 全部可配置，默认 `local`：

| 配置项 | 环境变量 | 默认 |
| :--- | :--- | :--- |
| `teachloop.storage.backend` | `STORAGE_BACKEND` | `local` |
| `teachloop.storage.local-dir` | `STORAGE_LOCAL_DIR` | `./uploads` |
| `teachloop.storage.oss.endpoint` | `STORAGE_OSS_ENDPOINT` | 空 |
| `teachloop.storage.oss.bucket` | `STORAGE_OSS_BUCKET` | 空 |
| `teachloop.storage.oss.access-key-id` | `STORAGE_OSS_ACCESS_KEY_ID` | 空 |
| `teachloop.storage.oss.access-key-secret` | `STORAGE_OSS_ACCESS_KEY_SECRET` | 空 |
| `teachloop.storage.oss.path-prefix` | `STORAGE_OSS_PATH_PREFIX` | `teachloop/` |

- 选 `oss` 但缺 endpoint/bucket/密钥时，**启动即失败**（沿用现有「避免跑到线上才发现附件全丢」的口径）。
- 密钥只从环境变量或加密配置读取，**不进仓库、不进日志、不进错误信息**。

### 三、不影响本地测试

- `make test` 默认仍然全走 `local`，不联网、不需要任何阿里云凭据。
- OSS 相关测试用 `@Tag("oss")` + 环境变量守卫，默认跳过；新增 `make test-oss` 才真正连云端。
- 没有凭据时跑 `make test-oss` 要**跳过并说明原因**，不能静默通过。

## 验收（T386）

1. `make test` 退出码 0，用例数与改动前一致（新增的 OSS 用例默认跳过）。
2. `make check-deps`、`make check-boundaries`、`make check-i18n`、`make check-ui` 全部退出码 0。
3. `teachloop-shared` 里没有业务逻辑；`check-boundaries` 的端口 DTO 规则不报警。
4. 开关验证：`STORAGE_BACKEND=local` 时附件上传/下载行为与改前完全一致（同一个集成测试）。
5. 配置齐全时能对真实 bucket 上传再读回同样字节（`make test-oss`，需要用户提供的环境变量）。
6. 缺配置时报错信息里**不出现** AccessKey。

## 本次不做

- 签名 URL / CDN / 公共读。
- 分片上传、断点续传、大文件流式（现有接口是 `byte[]`，先保持一致）。
- 把 vocab 的音频存储接过来（vocab 尚未实现，等它落地时直接依赖 `BlobStore` 即可）。
- 迁移历史附件（存量在本地目录里，迁移脚本另立任务）。
