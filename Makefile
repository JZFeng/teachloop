JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH ./mvnw
DB_URL ?= jdbc:postgresql://localhost:55432/teachloop
DB_USER ?= teachloop
DB_PASSWORD ?= teachloop
PORT ?= 8010

# T386：本地 MinIO（S3 兼容）测试环境；容器名与端口独立，不碰 Postgres
# 注意：MinIO 上游 2025-10 起不再发布免费镜像，Docker Hub 的 minio/minio 已删除（quay.io 也需登录）；
# 默认用 Chainguard 的公开 MinIO 镜像，仍可用 OSS_MINIO_IMAGE 覆盖成自己的镜像仓库。
OSS_MINIO_IMAGE ?= cgr.dev/chainguard/minio:latest
OSS_MINIO_CONTAINER ?= teachloop-minio
OSS_MINIO_PORT ?= 59000
OSS_MINIO_CONSOLE_PORT ?= 59001
OSS_MINIO_USER ?= teachloop
OSS_MINIO_PASSWORD ?= teachloop-minio-secret
OSS_TEST_BUCKET ?= teachloop-minio
OSS_ENV_FILE ?= .envs/oss.env

.PHONY: help status dev build test test-ai test-oss run start-db stop-db clean check-deps check-boundaries check-i18n check-ui web-test web-e2e web-e2e-tablet web-install web-build web-dev docker-build compose-up compose-down seed qa qa-up qa-down qa-status prod-health oss-dev oss-down vocab-lexicon vocab-lexicon-test

help:
	@echo "make start-db   启动本地 Postgres 容器（55432）"
	@echo "make build      编译并打包"
	@echo "make test       跑全部测试（需要 Docker；真机 AI 默认跳过）"
	@echo "make test-ai    跑 @Tag(\"ai\") 真机 AI 测试（需要 DEEPSEEK_API_KEY）"
	@echo "make oss-dev    用 Docker 起本地 MinIO（59000/59001），参数写入 .envs/oss.env"
	@echo "make test-oss   跑 @Tag(\"oss\") 对象存储测试（需 oss-dev 或阿里云 S3 变量）"
	@echo "make oss-down   停掉本地 MinIO 并清理数据目录"
	@echo "make run        启动应用（8010）"
	@echo "make web-install 安装前端依赖（pnpm）"
	@echo "make web-build  构建前端（等价 cd web && pnpm build）"
	@echo "make web-dev    启动前端开发服务器（5173，代理到 8010）"
	@echo "make clean      清理 target"
	@echo "make check-deps 依赖方向守卫（T203）"
	@echo "make status     一眼看清：提交/工作区/测试/进度"
	@echo "make vocab-lexicon 构建本地词族索引（28,276 族 / 80,579 形）并校验 sha256"
	@echo "make vocab-lexicon-test 跑词表构建脚本自测（合成小数据）"
	@echo "make docker-build 构建生产镜像（含 Noto CJK/ffprobe）"
	@echo "make compose-up   起 app + worker + postgres（需 .env）"
	@echo "make seed         可重复演示数据（1 班 + 30 学生 + 1 试卷）"
	@echo "make dev          一键启动后端+前端，Ctrl-C 自动清理"
	@echo "make qa           构建快照并起第二个环境（8011/5174），我改代码不影响它"
	@echo "make qa-up        起已有快照（不构建）"
	@echo "make qa-down      停掉第二个环境"
	@echo "make qa-status    两个环境的健康与版本"
	@echo "make qa-logs      跟踪测试台后端日志"
	@echo "make prod-health  看 prod 健康（公网探针/容器/5xx/worker/资源，只读，exit≠0 即有致命项）"

start-db:
	@docker start teachloop-pg 2>/dev/null || docker run -d --name teachloop-pg \
	  -e POSTGRES_USER=teachloop -e POSTGRES_PASSWORD=teachloop -e POSTGRES_DB=teachloop \
	  -p 55432:5432 postgres:16-alpine

stop-db:
	@docker stop teachloop-pg

status:
	@bash scripts/status.sh

dev:
	@bash scripts/dev.sh $(ARGS)

check-deps:
	@python3 scripts/check-deps.py

check-boundaries:
	@python3 scripts/check-boundaries.py

check-i18n:
	@python3 scripts/check-i18n.py

# UI 标准守卫：样式只能从令牌来，颜色对比度与两套令牌一致性也在这里核。
check-ui:
	@python3 scripts/check-ui.py
	@python3 scripts/ui/contrast.py

# T388：词表构建脚本自测（合成小数据，不依赖 fixtures-local/真实词表）。
vocab-lexicon-test:
	@python3 scripts/vocab/test_build_wordlist_index.py

# T388：本地词族索引。原始 txt 与产物都不进 git；应用启动时装载，manifest 不变则跳过写库。
vocab-lexicon:
	@python3 scripts/vocab/build-wordlist-index.py
	@python3 scripts/vocab/build-wordlist-index.py --verify

build:
	@$(MVN) -q -DskipTests package

test: check-deps check-boundaries check-i18n check-ui web-test vocab-lexicon-test
	@$(MVN) -q test

test-ai:
	@$(MVN) -q -pl teachloop-grading -am -Pai test

test-oss:
	@if [ -z "$${STORAGE_S3_ENDPOINT:-}" ] && [ -f $(OSS_ENV_FILE) ]; then \
	  set -a; . ./$(OSS_ENV_FILE); set +a; fi; \
	 if [ -z "$${STORAGE_S3_ENDPOINT:-}" ]; then \
	  echo "跳过 test-oss：未设置 STORAGE_S3_ENDPOINT（先 make oss-dev 起 MinIO，或导出阿里云 S3 变量）"; \
	  exit 0; fi; \
	 $(MVN) -q -pl teachloop-sitting -am -Poss test

# 起本地 MinIO：独立容器与端口，数据目录在 .envs/minio-data（已 gitignore）
oss-dev:
	@mkdir -p .envs/minio-data
	@docker rm -f $(OSS_MINIO_CONTAINER) >/dev/null 2>&1 || true
	@docker run -d --name $(OSS_MINIO_CONTAINER) \
	  -p $(OSS_MINIO_PORT):9000 -p $(OSS_MINIO_CONSOLE_PORT):9001 \
	  -e MINIO_ROOT_USER=$(OSS_MINIO_USER) -e MINIO_ROOT_PASSWORD=$(OSS_MINIO_PASSWORD) \
	  -v $(CURDIR)/.envs/minio-data:/data \
	  $(OSS_MINIO_IMAGE) server /data --console-address ":9001"
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
	  curl -fsS http://127.0.0.1:$(OSS_MINIO_PORT)/minio/health/live >/dev/null 2>&1 && break; \
	  sleep 1; \
	done
	@{ echo "STORAGE_S3_ENDPOINT=http://127.0.0.1:$(OSS_MINIO_PORT)"; \
	   echo "STORAGE_S3_REGION=us-east-1"; \
	   echo "STORAGE_S3_BUCKET=$(OSS_TEST_BUCKET)"; \
	   echo "STORAGE_S3_ACCESS_KEY_ID=$(OSS_MINIO_USER)"; \
	   echo "STORAGE_S3_ACCESS_KEY_SECRET=$(OSS_MINIO_PASSWORD)"; \
	   echo "STORAGE_S3_PATH_STYLE=true"; \
	   echo "STORAGE_S3_PATH_PREFIX=teachloop/"; } > $(OSS_ENV_FILE)
	@echo "MinIO 已启动：S3 http://127.0.0.1:$(OSS_MINIO_PORT)，控制台 http://127.0.0.1:$(OSS_MINIO_CONSOLE_PORT)（账号 $(OSS_MINIO_USER)）"
	@echo "测试参数写入 $(OSS_ENV_FILE)（已 gitignore）；接着跑 make test-oss"

oss-down:
	@docker rm -f $(OSS_MINIO_CONTAINER) >/dev/null 2>&1 || true
	@rm -rf .envs/minio-data $(OSS_ENV_FILE)
	@echo "MinIO 已停止，数据目录 .envs/minio-data 已清理"

web-install:
	@cd web && pnpm install --store-dir .pnpm-store

# 前端金标往返测试（Vditor IR：Markdown 进 → 出，评分项与合计不变）
web-test:
	@cd web && pnpm test

# Playwright 端到端（阶段 1：平板守卫）。首次需 `make web-e2e-install` 装 chromium。
# 设计见 docs/process/e2e-playwright.md；需要前端起着（5173），未起会由 Playwright 拉起。
web-e2e:
	@cd web && pnpm e2e

web-e2e-tablet:
	@cd web && pnpm e2e:tablet

web-e2e-install:
	@cd web && pnpm e2e:install

web-build:
	@cd web && pnpm run build

web-dev:
	@cd web && pnpm run dev

docker-build:
	@docker build -t teachloop:latest .

compose-up:
	@docker compose -f docker-compose.prod.yml up -d --build

compose-down:
	@docker compose -f docker-compose.prod.yml down

run: build
	@DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) PORT=$(PORT) \
	  $(JAVA_HOME)/bin/java -jar teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar

clean:
	@$(MVN) -q clean

qa:
	@bash scripts/env.sh deploy qa

qa-up:
	@bash scripts/env.sh up qa

qa-down:
	@bash scripts/env.sh down qa

qa-status:
	@bash scripts/env.sh status

prod-health:
	@bash scripts/prod-health.sh

qa-logs:
	@bash scripts/env.sh logs qa

seed: build start-db
	@DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		JAVA_HOME=$(JAVA_HOME) java -jar teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar \
		--teachloop.seed.demo=true --seed-exit --server.port=0