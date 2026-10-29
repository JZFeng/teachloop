# ops/vitals — prod 生命体征看板

独立于 prod 的小服务：一个 Python 文件 + 一个页面，读两条只读通道（公网 `healthz?deep=1` + prod 上的 SSH 强制命令探针），
页面每 2 秒刷新，出事发邮件。**设计、边界、告警规则、回退见 [`docs/release/vitals.md`](../../docs/release/vitals.md)。**

## 文件

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `vitals.py` | 监控机 | 采集 + HTTP 服务 + 告警状态机（只用标准库） |
| `page.html` | 监控机 | 单页看板，`/vitals/` |
| `prod-probe.sh` | **prod** | 被 SSH 强制命令调用，只输出容器/磁盘/内存/CPU/5xx 事实，不接受参数、不写任何东西 |
| `install.sh` | 监控机 | 安装 / 重启 / 停止 / 自检 |

## 三条命令

```bash
bash install.sh            # 安装或重启（首次会生成 config.env 并停下等你填）
bash install.sh status     # 是否在跑 + 本地自检
bash install.sh prod-help  # prod 侧那一次性的两行操作
```

## 本地调试（不上 prod 也能看页面）

```bash
VITALS_USER=test VITALS_PASS=testpass123456 \
PROD_SSH=jzfeng@192.168.68.166 PROD_PROBE_CMD="bash ~/vitals-probe/probe.sh" \
python3 vitals.py            # 页面 http://127.0.0.1:8099/vitals/
python3 vitals.py --once     # 采集一次，打印 JSON
python3 vitals.py --check    # 只验证配置与两条通道
python3 vitals.py --test-mail
```

`PROD_PROBE_CMD` 留空 = 依赖 prod 侧 `authorized_keys` 的 `command=` 限制（生产用法）；
填上 = 普通 SSH 跑指定脚本（调试用法）。

## 约定

- **只读**：对 prod 不做任何写操作，不连数据库，不 import 产品代码。
- **不公开**：页面只在局域网 + HTTP Basic；不接 Cloudflare、不改 prod nginx。
- **界面**：颜色/圆角/字号取自 `web/src/ui/tokens.ts`（主色 `#1A63E0`，圆角 12/8/6，正文 ≥14px），无渐变、无彩色投影。
