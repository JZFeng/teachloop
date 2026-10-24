# 阿里云部署（T162）

目标形态：一台 ECS 跑 `docker compose`（app + worker + postgres），或 RDS 承担数据库；对象存储用 OSS（T386 起走 S3 兼容协议，`STORAGE_BACKEND=s3`，不再需要阿里云私有 SDK）。

## 1. 前置

- 域名与备案：`teachloop.example.com` A 记录指向 ECS 弹性公网 IP。
- 安全组：只开 22（限办公 IP）、80、443；8010 不直接对公网。
- 安装 Docker 24+ 与 compose v2；ECS 建议 4C8G 起（AI 调用是外网等待，CPU 压力不高；内存要容纳 100MB 上传峰值）。

## 1.5 首次初始化（管理员 + AI）

第一次打开 `https://teachloop.example.com/setup`：

1. 创建第一个管理员（没有 ADMIN 时此页可用；创建后自动关闭）。
2. 用管理员登录 → 左侧「AI 设置」→ 填 Provider / Base URL / 模型 / API Key → 保存。
3. 保存后需重启 `app`（与 worker）让新配置生效；密钥以 AES-GCM 加密存库，API 只返回掩码。
4. 点「测试连接」可先验证 Key 与端点（不会落库）。

`TEACHLOOP_SECURITY_SECRET` 必须设置为长随机串；它是 AI Key 的加密密钥，丢失后需要重新填写 AI Key。

## 2. 配置

```bash
cp .env.example .env
# 必填：POSTGRES_PASSWORD、DB_PASSWORD、DEEPSEEK_API_KEY、TEACHLOOP_SECURITY_SECRET
# 生产：SPRING_PROFILES_ACTIVE=prod、TEACHLOOP_SEED_ENABLED=false、TEACHLOOP_GRADING_ENGINE=ai
# 注意：prod 会启用 Secure Cookie；必须经 HTTPS 访问，直接 http://ECS:8010 登录会失败
```

数据库可选择：
- **RDS PostgreSQL 16**：`DB_URL=jdbc:postgresql://<rds-host>:5432/teachloop`，把 compose 里的 `db` 服务删掉；worker/app 都指向 RDS。RDS 白名单放 ECS 内网 IP。
- **容器 Postgres**：保留 compose 的 `db`，数据在 `pgdata` 卷；务必配置每日备份（`pg_dump` 到 OSS）。

对象存储（T386）：OSS 通过 **S3 兼容 API** 接入（AWS SDK v2），换 MinIO / R2 只改配置：

```bash
STORAGE_BACKEND=s3
STORAGE_S3_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
STORAGE_S3_REGION=cn-hangzhou
STORAGE_S3_BUCKET=<私有桶名>
STORAGE_S3_ACCESS_KEY_ID=<RAM 用户的 AccessKeyId>
STORAGE_S3_ACCESS_KEY_SECRET=<RAM 用户的 AccessKeySecret>
STORAGE_S3_PATH_STYLE=false          # OSS 只支持 virtual-hosted 风格，必须 false
STORAGE_S3_PATH_PREFIX=teachloop/
```

- 桶保持**私有**；附件下载仍走应用鉴权接口代理读取（后端 `load` 出字节再返回），不暴露桶地址。
- RAM 用户只给该桶 `oss:PutObject` / `oss:GetObject` / `oss:HeadObject` 的最小权限（按前缀 `teachloop/*` 进一步收窄更好），不要用主账号 AccessKey。
- 选 `s3` 但缺 `endpoint` / `bucket` / 密钥时**启动即失败**：报错只列缺失的配置项名，不含 AccessKey 或 secret 的值；日志同样不打印密钥。
- 本地验证不需要阿里云账号：`make oss-dev` 起本地 MinIO（`path-style=true`），`make test-oss` 真上传并读回同样字节。

## 3. 起服务

```bash
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
curl -fsS http://127.0.0.1:8010/healthz?deep=1   # 期望 {"status":"UP","db":"UP","pdfFont":"UP"}
```

- Flyway 由 `app` 启动时执行；`worker` profile 关闭 Flyway，不重复迁移。
- worker 是同镜像的第二进程（`SPRING_PROFILES_ACTIVE=worker`），共享 uploads/logs 卷；只跑调度与批改，不开 Web 端口。

## 4. 反向代理与 TLS

Nginx（或 SLB）→ `127.0.0.1:8010`：

```nginx
server {
  listen 443 ssl http2;
  server_name teachloop.example.com;
  client_max_body_size 100m;          # 与 T061 的 100MB 上限一致
  ssl_certificate     /etc/letsencrypt/live/teachloop.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/teachloop.example.com/privkey.pem;
  # 前端静态资源由 nginx 压缩：Vditor 的 Lute 引擎是 3.74 MB 的单文件，
  # 不压的话打开「新建作业」要多传约 3 MB。
  gzip on;
  gzip_comp_level 5;
  gzip_min_length 1024;
  gzip_types text/css application/javascript application/json image/svg+xml;

  location / {
    proxy_pass http://127.0.0.1:8010;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Request-Id $request_id;
  }
}
```

TLS 用 certbot（`certbot --nginx -d teachloop.example.com`）或阿里云 SSL 证书；到期前自动续期。

## 5. 备份与恢复

- 每日 `pg_dump`：`docker compose exec -T db pg_dump -U teachloop teachloop | gzip > /backup/teachloop-$(date +%F).sql.gz`，再上传 OSS。
- `uploads` 卷同样备份（学生附件）。切到 `STORAGE_BACKEND=s3` 后附件在 OSS 桶里：开启桶版本控制或跨区域复制，或定期 `ossutil cp -r oss://<bucket>/teachloop/ /backup/`。
- 恢复演练：空库 → `gunzip -c ... | psql` → `app` 重启前先 `flyway` 校验（应用启动会自动 migrate）。
- `audit_logs` 永不删除；日志/前端事件按 90 天清理（应用内置任务）。

## 6. 可观测与运维

- `/healthz?deep=1` 纳入云监控/SLB 健康检查；字体或数据库异常返回 503。
- 请求日志：容器内 `/app/logs/requests-YYYY-MM-DD.jsonl`；前端事件 `events-*.jsonl`；`daily_stats` 每日 00:10 UTC 聚合。
- 如需 ES：实现 `LogSink` 的 Elasticsearch 版本，业务代码不变。

## 7. 安全清单

- `.env` 不提交、权限 600；不要复用开发密码。
- `TEACHLOOP_SEED_ENABLED=false`；管理员账号用 CLI 创建（T036，待实现）。
- 反向代理开启 HSTS；Cookie 生产要求 `Secure`（T033 待加固）。
- 上传目录不要被 Nginx 直接暴露；附件下载必须走鉴权接口。
- OSS 桶私有；附件下载走应用鉴权接口代理读取（后端读字节再返回）。本期不做签名 URL / CDN / 公共读，桶不要开公共读。

## 8. 已知未完成（部署时注意）

- 附件存储已支持 S3 兼容对象存储（OSS / MinIO），但**不做签名 URL、CDN、分片上传**：下载始终经应用代理，单文件上限 100 MB。
- 切 `s3` 前先用测试桶跑一次上传/下载；凭据只放环境变量（`.env` 权限 600），不进镜像、不进日志。
- PDF 中文用内置非嵌入 CJK 字体：视觉正常，复制/检索中文不可用；如需要请嵌入 Noto Sans CJK。
- 微信登录、管理员 CLI/邀请码、CSRF 加固等任务尚未完成；上线前需补齐。
