JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH ./mvnw
DB_URL ?= jdbc:postgresql://localhost:55432/teachloop
DB_USER ?= teachloop
DB_PASSWORD ?= teachloop
PORT ?= 8010

.PHONY: help status dev build test test-ai run start-db stop-db clean check-deps check-boundaries check-i18n check-ui web-test web-install web-build web-dev docker-build compose-up compose-down seed qa qa-up qa-down qa-status vocab-lexicon vocab-lexicon-test

help:
	@echo "make start-db   启动本地 Postgres 容器（55432）"
	@echo "make build      编译并打包"
	@echo "make test       跑全部测试（需要 Docker；真机 AI 默认跳过）"
	@echo "make test-ai    跑 @Tag(\"ai\") 真机 AI 测试（需要 DEEPSEEK_API_KEY）"
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
	@echo "make qa-status    两个环境的健康与版本
	@echo "make qa-logs      跟踪测试台后端日志""

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

web-install:
	@cd web && pnpm install --store-dir .pnpm-store

# 前端金标往返测试（Vditor IR：Markdown 进 → 出，评分项与合计不变）
web-test:
	@cd web && pnpm test

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

qa-logs:
	@bash scripts/env.sh logs qa

seed: build start-db
	@DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		JAVA_HOME=$(JAVA_HOME) java -jar teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar \
		--teachloop.seed.demo=true --seed-exit --server.port=0