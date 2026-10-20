# 压测记录（T174）

日期：2026-10-20　机器：本机 macOS（开发机），非生产规格
脚本：`scripts/load-test.py`（仅标准库）

## 场景

- 演示数据：1 个班、30 个学生、1 份 3 题试卷（单选/填空/写作）
- 25 个学生 × 2 场作业 = **50 份提交**，并发 10
- 判分引擎：`stub`（不调 DeepSeek；真实 AI 延迟见 `make test-ai`，不在本压测范围）
- 第一轮拓扑：1 app + 1 embedded worker；第二轮补齐 **2 app + 2 worker**（`docker-compose.scale.yml`，两实例共享 Postgres/Session/uploads/logs，提交按学生轮询分发）
- 限流：为避免登录限流干扰压测，启动时把四类 limit 调到 1000

## 结果（2026-10-20 05:20 PT）

```json
{
  "submissions_attempted": 50,
  "submissions_ok": 50,
  "failures": [],
  "concurrency": 10,
  "submit_wall_seconds": 0.2,
  "submit_throughput_per_second": 255.11,
  "latency_seconds": {"p50": 0.038, "p95": 0.045, "max": 0.046},
  "grading_wall_seconds": 33.82,
  "offering_progress": {"total": 25, "done": 25, "pending": 0, "failed": 0}
}
```

- 提交：50/50 成功，p95 45ms。
- 批改：两场各 25 份全部 `done`，从派发到清零 33.8s；worker 轮询间隔 2s、stub 单份有模拟延迟，主要耗时在轮询与串行消费。
- 500/429/超时：0。

## 2 app + 2 worker 复测（2026-10-20 06:18 UTC）

```json
{
  "submissions_attempted": 50,
  "submissions_ok": 50,
  "failures": [],
  "concurrency": 10,
  "submit_wall_seconds": 0.31,
  "submit_throughput_per_second": 159.25,
  "latency_seconds": {"p50": 0.052, "p95": 0.097, "max": 0.098},
  "grading_wall_seconds": 7.22,
  "offering_progress": {"total": 25, "done": 25, "pending": 0, "failed": 0}
}
```

两实例健康（18010/18011），两 worker 并发领取；批改清零从 33.8s 降到 **7.22s**。50/50 成功，p95 97ms。

## 偏差与限制

1. **双 app 无负载均衡**：压测脚本按学生轮询两个入口；生产需 Nginx/SLB。
2. **stub 判分**：不含 DeepSeek 网络延迟与重试；真实 AI 单份约数秒（见真机测试）。
3. **单机 Postgres 容器**，非 RDS；连接池默认，非生产参数。
4. 提交阶段只提交文本，不含 100MB 附件上传。
5. 50 份来自 25 个学生 × 2 场，不是 50 个不同学生。

结论：单实例在 10 并发下提交与排队路径健康；2+2 拓扑与真实 AI 吞吐需要生产前另行压测。
