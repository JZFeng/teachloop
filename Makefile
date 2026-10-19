JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH ./mvnw
DB_URL ?= jdbc:postgresql://localhost:55432/teachloop
DB_USER ?= teachloop
DB_PASSWORD ?= teachloop
PORT ?= 8010

.PHONY: help build test run start-db stop-db clean

help:
	@echo "make start-db   启动本地 Postgres 容器（55432）"
	@echo "make build      编译并打包"
	@echo "make test       跑全部测试（需要 Docker）"
	@echo "make run        启动应用（8010）"
	@echo "make clean      清理 target"

start-db:
	@docker start teachloop-pg 2>/dev/null || docker run -d --name teachloop-pg \
	  -e POSTGRES_USER=teachloop -e POSTGRES_PASSWORD=teachloop -e POSTGRES_DB=teachloop \
	  -p 55432:5432 postgres:16-alpine

stop-db:
	@docker stop teachloop-pg

build:
	@$(MVN) -q -DskipTests package

test:
	@$(MVN) -q test

run: build
	@DB_URL=$(DB_URL) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) PORT=$(PORT) \
	  $(JAVA_HOME)/bin/java -jar teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar

clean:
	@$(MVN) -q clean
