# prod 生命体征看板（vitals）— V1

**一句话**：一台**独立于 prod** 的监控机上跑一个小服务，每 1–3 秒刷新「prod 活没活、还剩多少油」，页面走局域网 + 口令；挂了发邮件。

> 决策与边界以本文为准；`ops/vitals/README.md` 只放操作步骤。

## 1. 为什么不在 prod 里做

监控不能和被监控对象同生共死：页面若挂在 `teachloop.org`（prod 的 nginx）上，prod 一挂你连"它挂了"都看不见。
所以 vitals 部署在 **`ai-ubuntu`（192.168.68.101）**，只通过两条**只读**通道看 prod：

| 通道 | 内容 | 频率 |
|---|---|---|
| 公网 HTTPS | `https://teachloop.org/healthz?deep=1` | 10s |
| SSH（强制命令） | `~/vitals-probe/probe.sh`：容器、磁盘、内存、CPU、今日 5xx | 5s |

## 2. 这一版做什么、不做什么

**做**（9 个生死指标）：应用深浅探针、公网入口响应时间、四个容器 Up/healthy/重启数、磁盘、内存、CPU/load、今日请求与 5xx、采集本身是否失联。

**不做**（V2 再说）：错误/异常统计与堆栈、历史曲线与留存、日志搜索、告警分级、多主机、AI 队列深度。

## 3. 入口与认证

- 监听 `0.0.0.0:8099`，页面 `http://<监控机 LAN IP>:8099/vitals/`，**HTTP Basic 口令**（凭据在 `config.env`，600 权限，不进 git）。
- 只走局域网：**不碰 Cloudflare、不碰 prod 的 nginx**。这也是为什么 `status.teachloop.org` 那个方案被放弃——它要求隧道把公网流量转到监控机，且只能在 Cloudflare 后台配。
- `/vitals/healthz` 免认证（给本机自检用），`/vitals/api` 与页面都要口令。
- 页面右上角两个开关：**中文 / EN** 与 **☀ 亮色 / ☾ 暗色**，选择记在 `localStorage`（`vitals.lang` / `vitals.theme`），刷新与换设备不共享。
- 暗色用 `tokens.ts` 的 dark 色板（`#0A1120` 底 / `#2A6FD6` 主色）；告警文案按服务端返回的 `code` 在前端翻译，**邮件正文仍为中文**（收件人只有你自己，先不做双语邮件）。

## 4. 部署

### 4.1 监控机（一次性）

**实际部署（2026-10-29）**：`ai-ubuntu`（192.168.68.101，Python 3.12.3），看板 `http://192.168.68.101:8099/vitals/`，目录 `~/vitals/`，口令在 `~/vitals/config.env`（600）。

```bash
scp -r ops/vitals ai-ubuntu:~/
ssh ai-ubuntu
cd ~/vitals && bash install.sh          # 生成 config.env 后停下
vi config.env                            # 填 VITALS_PASS（≥16 位）、SMTP_*、MAIL_TO
bash install.sh                          # 再跑一次：启动 + 加 crontab @reboot
```

不需要 Docker、不需要 sudo；只需要 `python3`（3.8+）。

**这台机器上没有 `curl`**（实测），所以 `install.sh` 的自检与 `vitals.py` 的探针都只用 Python 标准库；
启动用 `setsid --fork` + `</dev/null`，否则从 SSH 远程执行时安装脚本会一直等那个后台子进程（实测挂住 2 分钟+）。

### 4.2 prod（一次性，两个文件）

```bash
mkdir -p ~/vitals-probe
scp ai-ubuntu:~/vitals/prod-probe.sh ~/vitals-probe/probe.sh
chmod +x ~/vitals-probe/probe.sh
# 把监控机公钥追加到 ~/.ssh/authorized_keys，并限制成只能跑这个脚本：
# command="$HOME/vitals-probe/probe.sh",no-port-forwarding,no-agent-forwarding,no-pty,no-x11-forwarding ssh-ed25519 AAAA... vitals@ai-ubuntu
```

这样即使监控机被攻破，也只能拿到「容器列表 / 磁盘 / 内存」这堆事实，**拿不到 shell，读不到 `.env`**。

## 5. 告警规则（V1 只有 4 条）

| 条件 | 级别 | 触发 |
|---|---|---|
| 公网探针非 200 | down | 立刻 |
| SSH 采集失联（>3 个周期） | down | 立刻 |
| 容器不在运行 / app·db unhealthy | down / warn | 立刻 |
| 磁盘 ≥ 90%、内存 ≥ 90% | warn | 立刻 |

- 同一条告警 **30 分钟冷却**后重发；恢复时发一封 `🟢 已恢复`，附持续时间。
- 邮件走 prod 同款 163 发件箱（`smtp.163.com:465`，授权码不是登录密码），收件人 `jzhfeng001@gmail.com`。
- **验证方式**：`python3 vitals.py --test-mail`（`ALERT_ENABLED=1` 时才真发）。

## 6. 告警二重保险（外部哨兵，2026-10-29 决定）

vitals 与 prod 共享四个单点：同一间屋的电、同一条宽带、监控机自己、以及本服务的代码。整屋故障时它连邮件都发不出去。
所以另配一个**屋外**的免费哨兵，只做一件事：每 5 分钟请求同一个 URL，失败发邮件。

- 选型：**UptimeRobot 免费版**（`https://teachloop.org/healthz?deep=1`，5 分钟间隔，收件人 `jzhfeng001@gmail.com`）。
- 不选 Cloudflare Health Checks：免费版没有该功能（[官方可用性表](https://developers.cloudflare.com/health-checks/)：Free = No，Pro 起 10 个 check）。
- 不选自写 Cloudflare Worker：技术上可行（Workers 免费版支持 Cron），但要多维护 Worker + 发信 API 两个组件，告警可靠性反而更低。规则：**vitals 给画面，外部哨兵给闹钟。**

## 7. 自检

```bash
bash ~/vitals/install.sh status        # 进程 + 自检接口 + 首屏 JSON
curl -u user:pass http://127.0.0.1:8099/vitals/api | python3 -m json.tool | head -40
tail -f ~/vitals/vitals.log
```

三种降级形态（都算正常）：

| 现象 | 含义 |
|---|---|
| 公网红 + SSH 绿 | 隧道/Cloudflare 断了，app 本身好的 |
| 公网红 + SSH 红 | 机器或内网断了 |
| 公网绿 + SSH 红 | 隧道通但采集不到（查 `probe.sh` 权限/公钥） |

## 8. 回退（一条命令）

```bash
ssh ai-ubuntu 'bash ~/vitals/install.sh stop && crontab -l | grep -v teachloop-vitals | crontab -'
# prod 侧可选：删掉 ~/vitals-probe/ 与 authorized_keys 里那行 vitals
```

停掉后不留任何痕迹在 prod 业务链路上；vitals 从不写 prod（只读探针 + 只读 SSH）。

## 9. 已知代价

- SSH 每 5 秒一次，单次约 1.1 秒（`docker exec` 读 jsonl 占大头）；prod 负载可忽略（`load1 0.3`），但如果以后 5xx 统计改全量扫描，会变重——那时改成增量 tail。
- 今日 5xx 的「今日」按 **UTC 日期**（应用日志文件名用 UTC：2026-10-29 实测容器本地已是 10-30 01:11，最新文件仍是 `requests-2026-10-29.jsonl`），页面标题带日期避免误读。
- 没有历史：`history` 只在内存里存 60 个点（约 5 分钟），重启即清空。

## 10. 演练记录：2026-10-29 真实故障

| 时间（PDT） | 事件 |
|---|---|
| 09:58 | vitals 本机测试实例全绿（容器 Up 12 分钟，healthz 241ms） |
| 09:59–10:00 | 公网开始 502（Cloudflare 边缘），随后变 **530**（隧道/源站不可达） |
| 10:00–10:11 | `ping 192.168.68.166` 100% 丢包、`ssh` 超时——整机离线（人工重启，非崩溃） |
| 10:11:22 | 恢复：公网 200；`make prod-health` 15/15 全绿；四容器 Up 40 秒、重启次数 0 |
| 10:12:16 | 又一次 `Power key pressed short` → 干净 poweroff（用户手动），10:12:44 重新开机 |
| 10:33 | **vitals 正式部署到 ai-ubuntu**：`overall=ok`，公网 207ms、四容器 healthy、磁盘 16%、内存 26%、今日 3604 请求 / 5xx 0 |

结论：
1. **判据正确**——同一套代码在故障期间给出 `overall=down` + 两条告警（`HTTP 530（error code: 1033）`、`ssh … Host is down`），恢复后自动回绿。
2. **当时没有人知道**——vitals 尚未部署、UptimeRobot 还在 Preparing，整段 12 分钟只靠手动 curl 发现。这就是“屋外哨兵”的必要性。
3. 顺带修掉一个真 bug：请求日志文件名按 **UTC**，探针原先按容器本地日期取文件，导致「今日请求」恒为 0。
