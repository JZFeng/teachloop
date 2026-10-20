JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH ./mvnw
DB_URL ?= jdbc:postgresql://localhost:55432/teachloop
DB_USER ?= teachloop
DB_PASSWORD ?= teachloop
PORT ?= 8010

.PHONY: help status build test test-ai run start-db stop-db clean check-deps check-boundaries web-install web-build web-dev

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

start-db:
	@docker start teachloop-pg 2>/dev/null || docker run -d --name teachloop-pg \
	  -e POSTGRES_USER=teachloop -e POSTGRES_PASSWORD=teachloop -e POSTGRES_DB=teachloop \
	  -p 55432:5432 postgres:16-alpine

stop-db:
	@docker stop teachloop-pg

status:
	@bash scripts/status.sh

check-deps:
	@python3 scripts/check-deps.py

check-boundaries:
	@python3 scripts/check-boundaries.py

build:
	@$(MVN) -q -DskipTests package

test: check-deps check-boundaries
	@$(MVN) -q test

test-ai:
	@$(MVN) -q -pl teachloop-grading -am -Pai test

web-install:
	@cd web && pnpm install --store-dir .pnpm-store

web-build:
	@cd web && pnpm run build

web-dev:
	@cd web && pnpm run dev

run: build
	@DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) PORT=$(PORT) \
	  $(JAVA_HOME)/bin/java -jar teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar

clean:
	@$(MVN) -q clean
