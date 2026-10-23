# 决策 012 — 存储端口提到 shared，并用 S3 协议落地对象存储

日期：2026-10-23（同日修订：原定「阿里云私有 SDK」改为 **S3 协议 + AWS SDK v2**）
状态：已排期（T386，计划 2026-10-24 01:00 本地时间执行）
上游：`docs/decisions/011-vocab-requirements.md`「音频缓存放哪里」、宪法第十四条、T067（存储抽象骨架）。

## 问题

1. 存储抽象 `StorageBackend`（`store` / `load`）写在 `teachloop-sitting` 内部
   （`com.teachloop.sitting.storage`），是模块私有类型。
2. 宪法第十四条：跨模块只走 `shared` 里的端口；`scripts/check-boundaries.py` 会拦住
   「业务模块 import 其他模块的内部类型」。
3. 因此 `teachloop-vocab` 拿不到这个能力（它的 TTS 音频缓存要用）。
4. 更要紧：`OssStorage` 目前是**占位实现**，配置齐全也只抛 503
   （`StorageUnavailableException: OSS 存储尚未接入`）。生产附件存储一直没落地。

## 决策一：端口搬到 `shared`，实现跟随端口

- 新增 `com.teachloop.shared.storage.BlobStore`（`store` / `load` / `exists`），**只放接口**，不含业务。
- `LocalStorage` 与新的 `S3BlobStore` 实现该接口；sitting 与将来的 vocab 都只依赖 `shared` 的接口。
- sitting 的 6 处引用从 `...sitting.storage.StorageBackend` 改成 `com.teachloop.shared.storage.BlobStore`，
  **行为不变**。
- `shared` 是「不含业务的共享层」；存储端口属于基础设施，与宪法第十四条不冲突。
  只在条文下补一行事实记录，不改规则。

## 决策二：用 S3 协议，不用阿里云私有 SDK

事实依据（2026-10-23 查证）：

- 阿里云 OSS **官方支持 S3 兼容 API**：`PutObject`、`GetObject`、`HeadObject`、`DeleteObject`、
  `ListObjectsV2`、分片上传均兼容（[官方文档](https://www.alibabacloud.com/help/en/oss/developer-reference/compatibility-with-amazon-s3)）。
- 关键差异：**OSS 只支持 virtual-hosted 风格，不支持 path-style**；ETag 大小写规则不同；
  ACL 语义不完全一致（我们只用私有，不影响）。
- 没有阿里云账号时，本地可用 **MinIO**（S3 兼容、免费、离线、Docker 一条命令）跑真上传下载。

因此实现用 **AWS SDK v2 的 S3 客户端**，而不是 `aliyun-sdk-oss`：

| 好处 | 代价 |
| :--- | :--- |
| 没有阿里云账号也能真测（MinIO） | 本地测 MinIO 用 path-style，OSS 用 virtual-hosted，差一个配置项 |
| 换供应商只改配置（R2 / B2 / 自建 MinIO 都行） | 不能使用 OSS 私有能力（图片处理等），本期不需要 |
| 与现有「代理读取」模型一致（后端 load 字节再返回） | — |

保持现有**代理读取**模型：浏览器打我们的接口，后端 `load` 出字节再返回。
本期不做签名 URL、不做 CDN、不做公共读——那是性能优化，不是存储落地的前置。

## 决策三：全部可配置，默认 local

| 配置项 | 环境变量 | 默认 |
| :--- | :--- | :--- |
| `teachloop.storage.backend` | `STORAGE_BACKEND` | `local` |
| `teachloop.storage.local-dir` | `STORAGE_LOCAL_DIR` | `./uploads` |
| `teachloop.storage.s3.endpoint` | `STORAGE_S3_ENDPOINT` | 空 |
| `teachloop.storage.s3.region` | `STORAGE_S3_REGION` | `cn-hangzhou` |
| `teachloop.storage.s3.bucket` | `STORAGE_S3_BUCKET` | 空 |
| `teachloop.storage.s3.access-key-id` | `STORAGE_S3_ACCESS_KEY_ID` | 空 |
| `teachloop.storage.s3.access-key-secret` | `STORAGE_S3_ACCESS_KEY_SECRET` | 空 |
| `teachloop.storage.s3.path-style` | `STORAGE_S3_PATH_STYLE` | `false`（OSS 必须 false；MinIO 设 true） |
| `teachloop.storage.s3.path-prefix` | `STORAGE_S3_PATH_PREFIX` | `teachloop/` |

- 选 `s3` 但缺 endpoint/bucket/密钥时**启动即失败**（沿用「别跑到线上才发现附件全丢」的口径）。
- 密钥只从环境变量读取，**不进仓库、不进日志、不进错误信息**。

## 决策四：不影响本地测试

- `make test` 默认仍全走 `local`，不联网、不需要任何凭据。
- S3 相关测试用 `@Tag("oss")` + 环境变量守卫，默认跳过；新增：
  - `make oss-dev`：用 Docker 起一个 MinIO（独立端口与目录，不碰 Postgres）；
  - `make test-oss`：对着 MinIO 或阿里云跑真上传/下载。
- 没有凭据也没有 MinIO 时，`make test-oss` 要**跳过并说明原因**，不能静默通过。

## 验收（T386）

1. `make test` 退出码 0，用例数与改动前一致（新增 S3 用例默认跳过）。
2. `make check-deps`、`make check-boundaries`、`make check-i18n`、`make check-ui` 全部退出码 0。
3. `teachloop-shared` 里没有业务逻辑；`check-boundaries` 的端口规则不报警。
4. `STORAGE_BACKEND=local` 时附件上传/下载行为与改前完全一致（既有集成测试）。
5. MinIO 起来后 `make test-oss` 能**上传后读回同样字节**（这条不需要阿里云账号）。
6. 配齐阿里云参数时同样能通过（可选，取决于用户是否已开通）。
7. 缺配置的报错信息里不出现 AccessKey。

## 免费获取测试资源的路径（供参考，不在代码范围内）

| 途径 | 条件与额度 |
| :--- | :--- |
| 阿里云 OSS 新用户免费试用 | 实名账号且**从未开通 OSS**；20GB 标准存储 + 2GB 外网流出 + 20 万请求，各 3 个月（[官方说明](https://help.aliyun.com/zh/oss/free-quota-for-new-users)，入口 `free.aliyun.com`）。**一旦开通 OSS 就不符合条件**，先想好再领 |
| 本地 MinIO | 完全免费、离线，够验证代码正确性 |
| Cloudflare R2 / Backblaze B2 | 有免费额度、S3 兼容；国内访问延迟高，合规另核 |

## 本次不做

- 签名 URL / CDN / 公共读。
- 分片上传、断点续传（现有接口是 `byte[]`，先保持一致）。
- 迁移历史附件（存量在本地目录，另立任务）。
- 把 vocab 的音频存储接过来（等 vocab 落地时直接依赖 `BlobStore`）。
