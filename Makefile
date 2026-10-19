# TeachLoop —— 常用命令
# 不需要全局 Maven：统一走 ./mvnw（Maven Wrapper，3.9.9）

JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

MVN := ./mvnw

.PHONY: help java-home check-deps validate build test run migrate clean

help:
	@echo "make java-home   # 打印需要 export 的 JAVA_HOME"
	@echo "make check-deps  # 依赖方向守卫（plan §3.1）"
	@echo "make validate    # 结构校验，不编译"
	@echo "make build       # 编译并打包（跳过测试）"
	@echo "make test        # 全量测试"
	@echo "make run         # 启动 teachloop-app"
	@echo "make migrate     # 数据库迁移（T010 起可用）"

java-home:
	@echo 'export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"'

check-deps:
	@python3 scripts/check-deps.py

validate: check-deps
	@$(MVN) -q validate

build: check-deps
	@$(MVN) -q -DskipTests package

test: check-deps
	@$(MVN) -q test

run:
	@$(MVN) -q -pl teachloop-app -am spring-boot:run

migrate:
	@echo "Flyway 尚未接入（T010）。届时本目标执行 ./mvnw -pl teachloop-app flyway:migrate"

clean:
	@$(MVN) -q clean
