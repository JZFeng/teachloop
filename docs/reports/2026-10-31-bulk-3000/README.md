# 3000 词批量配图（Qwen-Image）：场景表 + 夜间批量 + 后处理

## 结论（截至 2026-10-31 12:21 PDT / 19:21 UTC）

- **3000 条 scene 已全部生成并落盘**（`scenes.json`，344KB，12–24 词/条，0 重复、0 脏字符）；
- **出图批次已在 ai-ubuntu 后台跑**（`setsid nohup`，pid 191226 包装脚本 + 191237 `batch_qwen.py`），当时进度 **87/3000**（19:21 UTC，含启动开销平均 25.4 秒/张，稳态 **23.3 秒/张**，ComfyUI 自报 23.0–23.4 秒/张），**预计还需 18.4–18.9 小时**、**2026-11-01 06:45–07:20 PDT** 跑完，随后自动进失败重试与 176px WebP 后处理；
- 后处理配方用 113 词样例反标定过：同一张 512 PNG 走 `finalize_176.py` 得到 9276B，参考 `final-176/abandon.webp` 是 9248B（q=92），像素平均差 6.9/255 —— **产物与已定稿的 113 张同一条曲线**；
- **整条链路已用小批次演练过一遍**（主跑 → 失败重试 ×2 → Hunyuan 兜底 → 后处理 → DONE 标记），不是"写完没跑过"。

一句话状态：**脚本、场景表、断点续跑、失败重试、后处理全部就位并已端到端验证；剩下的是等 GPU 把 3000 张跑完（约 19 小时），期间不需要人工干预。**

## 1. 词表怎么选的

| 项 | 值 |
| :--- | :--- |
| 主池 | `fixtures-local/vocab-content/words-top3000.txt`（3000 行，词频序） |
| 过滤 1 | 去掉 113 词已有的 scene（`scripts/vocab/image/prompts/scenes-113.json`，那批图已定稿） |
| 过滤 2 | 去掉纯语法词/数词/程度副词（冠词、代词、介词、连词、助动词、情态、限定词，共约 130 词 → 剩 **2769** 个候选） |
| 补足 | 主池不够 3000（且模型还会拒掉画不出来的词），从 `words-top10k.txt` 顺位补，补池 **6967** 个候选 |
| 最终 | **3000 个词**：主池 2537 + 补池 463，共消费 3360 个候选 |
| 被拒 360 个 | `empty` 149（模型判定画不成一张定格图）、`banned-verb` 121（两次都用了 open/close/push/struggle 这类动作）、`duplicate` 88（与已收 scene 重复）、`length` 2 |

生成脚本：`scripts/vocab/image/make_bulk_scenes.py`（DeepSeek `deepseek-v4.1-flash` via OpenCode Go，8 线程，**651.6 秒 / 3000 词**，成本 < $0.1）。
规则来自 `scripts/vocab/image/prompts/styles.md` §3：一个主体 + 一个定格姿势 + 一个道具 + 一个环境、12–24 词、只用可画动词、禁状态变化与交互、抽象词用代理场景、禁文字。

样例（完整见 `scenes.json`）：

- `abbey` → A monk in a brown robe stands in a stone hall with tall arched windows and a long wooden bench.
- `abnormal` → A child stands alone on a straight row of identical round stepping stones, holding one crooked bent stone out to the side.
- `abolish` → A person stands at a wooden podium, one hand flat on a torn paper sheet, the other hand pointing down at it.

## 2. 出图参数（与 113 张定稿一致，未改）

| 项 | 值 |
| :--- | :--- |
| 机器 | `ai-ubuntu`（RX 7900 XTX / ROCm），ComfyUI `127.0.0.1:8188` |
| 权重 | `qwen_image_fp8_e4m3fn.safetensors` + `qwen_2.5_vl_7b_fp8_scaled.safetensors` + `qwen_image_vae.safetensors` |
| 采样 | 512² · 12 步 · cfg 4 · euler/simple · seed 9 |
| 提示词 | `childrens book illustration, flat colors, thick clean black outlines, … no text: {scene}`；负面 `blurry, low quality, text, photo` |
| 实测 | 稳态 **23.3 秒/张**（67→87 张 / 466 秒；ComfyUI 自报 23.0–23.4 秒/张），跑满 3000 张 ≈ **19.4 小时**（含启动开销平均 25.4 秒/张） |
| 输出 | `~/ai/vocab-images/qwen3000/<word>.png`（512²，约 250KB/张，全集约 740MB） |

## 3. 断点续跑 / 进度 / 失败重试（都已实现并验证）

`scripts/vocab/image/batch_qwen.py` 新增：

- `--skip-existing`（默认开）：`<word>.png` 存在且 >1KB 就跳过；写盘走 `<word>.png.part` → `os.replace` 原子落盘，**半截文件不会被当成已完成**；
- `--progress FILE`：每 100 张追加一行 `[x/total] ok=… fail=… avg=…s elapsed=… eta=…`；
- `--state FILE`：机器可读状态（含 avg/eta，覆盖写）；
- `--failures FILE`：失败的词 + 原因写 JSON（`words` / `detail`）；
- `--only-file` / `--only`：只跑指定词，用于失败重试；
- `--attempts 2`：单张失败在进程内重试一次；单张等待上限 `--timeout 600`。

冒烟验证（远端实跑）：

| 场景 | 结果 |
| :--- | :--- |
| 2 词冷启动 | 2/2 成功，44.6s（含模型装载） |
| 5 词热模型 | 5/5 成功，110s（22.0s/张） |
| 同一批重跑 | `[done] nothing to do`，skipped=5 → **续跑生效** |
| `COMFY_API` 指向死端口 | 5/5 记进 `failures.json`，原因 `URLError` → **失败可追溯** |
| 全链路演练（3 词，其中 1 个强制失败） | 主跑 2 ok/1 fail → 重试第 1 轮 fail → 第 2 轮 fail → Hunyuan 兜底 fail → 后处理只转 2 张、坏图记 `errors:1` 不中断 → `qwen3000.DONE` 落盘 → **六个分支全通** |

包装脚本 `scripts/vocab/image/run_qwen3000.sh`（已在跑，**不要 kill**）：

1. 主跑 3000 张（续跑）；
2. 失败词用 Qwen 重试 2 轮；
3. 仍失败 → `batch_hunyuan.py` 兜底（768²/24 步，写进同一目录）；
4. 后处理 `finalize_176.py` → `~/ai/vocab-images/qwen3000_176/`；
5. 打印总体积、`touch qwen3000.DONE`。

ComfyUI 掉线时包装脚本会自己 `setsid nohup` 重启并等就绪（最多 4 分钟）。

演练里发现并修掉的两处细节（3000 张规模下会变成事故）：

1. `finalize_176.py` 原来遇到单张坏图（截断/目录同名）会**整批中断**——改成逐张 try/except，坏图记进 `errors`/`error_detail` 继续跑；
2. `batch_qwen.py` 写盘失败会留下 `<word>.png.part` 垃圾——失败路径改为删掉临时文件再抛错。

## 4. 后处理（裁边 3% → levels → 176px WebP）

```bash
# 在 ai-ubuntu 上
~/ai/train-venv/bin/python3 scripts/finalize_176.py \
  --input ~/ai/vocab-images/qwen3000 --output ~/ai/vocab-images/qwen3000_176 \
  --size 176 --also 96 --quality 92 --margin 0.03 --skip-existing \
  --stats ~/ai/vocab-images/qwen3000_176.stats.json
```

曲线与 `normalize_images.py` 同源（C1 暖纸底），只多两步：**先四周裁 3%**、**levels 用 gain 1.08 / lift −0.02**。
反标定（对 113 定的 final-176，q=92）：`abandon` 9276B vs 9248B、`acquire` 8226B vs 8216B、`adapt` 7386B vs 7438B，像素平均差 6.3–7.0。

实测（当时 26 张）：**平均 8.18KB/张**（6.27–10.93KB），88px 亮度 172.6 / 对比度 81.1。
按 8.2KB 推 **3000 张 ≈ 24–26MB**，与任务给的 26MB 预算吻合。

## 5. 抽样（`samples/`，20 张，均 <15KB）

`samples/samples.json` 记了每张的词、scene、KB。当前抽样取自**前 26 张已完成的图**（等间隔挑 20），因此词形集中在 a–b；
跑完后请用同一脚本重挑一次，覆盖全字母表：

```bash
# 远端挑样 → 本地覆盖
~/ai/train-venv/bin/python3 ~/ai/vocab-images/scripts/emit_samples.py \
  --images ~/ai/vocab-images/qwen3000_176 --scenes ~/ai/vocab-images/scenes-3000.json \
  --out ~/ai/vocab-images/qwen3000_samples --count 20 --max-kb 15
scp -r jzfeng@ai-ubuntu:ai/vocab-images/qwen3000_samples/* \
  docs/reports/2026-10-31-bulk-3000/samples/
```

## 6. 监控与继续（下一轮直接抄）

```bash
# 进度数字（一行）
ssh jzfeng@ai-ubuntu 'cd ~/ai/vocab-images; find qwen3000 -maxdepth 1 -name "*.png" | wc -l; tail -2 qwen3000.progress'
# 机器可读状态
ssh jzfeng@ai-ubuntu 'cat ~/ai/vocab-images/qwen3000.state.json'
# 失败数
ssh jzfeng@ai-ubuntu 'cd ~/ai/vocab-images; python3 -c "import json;print(json.load(open(\"qwen3000.failures.json\"))[\"count\"])"'
# 是否跑完（自动后处理完成后才会出现）
ssh jzfeng@ai-ubuntu 'ls -la ~/ai/vocab-images/qwen3000.DONE; du -sh ~/ai/vocab-images/qwen3000_176'
```

中断后续跑（**不需要任何参数，已存在的图自动跳过**）：

```bash
ssh jzfeng@ai-ubuntu
cd ~/ai/vocab-images
setsid nohup bash scripts/run_qwen3000.sh >> qwen3000.log 2>&1 < /dev/null & disown
```

## 7. 已知风险 / 待办

1. **耗时 19.3h 略高于任务估的 18–19h 上限**：单张 22.1 秒是 ComfyUI 自报的稳定值（`--fp8_e4m3fn-unet` + `--reserve-vram 1`），没有把步数降到 10 步——那会偏离 113 张定稿的观感；
2. **抽象词语义漂移**是 113 张就存在的问题（`final-stack.md` §5.1），本批 scene 已按代理场景规则重写，但 88px 下仍需抽检；抽检口径：`samples/` 全看一遍，可疑词重写 scene 后 `--only` 重跑；
3. **偶发文字**（113 张里 `explicit` 出现过字母）：负面词已含 `text`，抽检时留意；
4. `near_white` 指标（88px ≥250 的像素占比）实测 5.6%，高于 113 张的 0.4%——是这批图底色偏亮，**不影响可读性**，但换卡片底色时要复核；
5. 抽样现在是 a–b 段，跑完后按 §5 重挑；报告里的「已跑/成功率/耗时」最终数字要等 `qwen3000.DONE` 之后再补。

## 8. 文件清单

| 文件 | 说明 |
| :--- | :--- |
| `scenes.json` | 3000 个词的英文 scene（词 → 场景，纯文本 344KB） |
| `scenes-stats.json` | 选词与生成统计（候选数、拒绝原因分布、耗时） |
| `samples/` | 20 张 176px WebP 抽样 + `samples.json`（词/scene/KB） |
| `scripts/vocab/image/make_bulk_scenes.py` | 词表 → scene（本次新增） |
| `scripts/vocab/image/batch_qwen.py` | 加了续跑/进度/失败清单（本次改） |
| `scripts/vocab/image/finalize_176.py` | 裁边 3% → levels → 176/96 WebP（本次新增） |
| `scripts/vocab/image/run_qwen3000.sh` | 远端一键：主跑 → 重试 → 兜底 → 后处理（本次新增） |
| `scripts/vocab/image/emit_samples.py` | 等间隔挑抽样 + manifest（本次新增） |

> 远程大图集（`~/ai/vocab-images/qwen3000/`、`qwen3000_176/`）**不进 git**，仓库里只有 20 张抽样 + 这份报告 + scenes.json。
