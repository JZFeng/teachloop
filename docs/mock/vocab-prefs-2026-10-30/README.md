# 背单词偏好原型（2026-10-30）

对应规格：`US-88`（拟，`specs/004-vocab/spec.md` 增补）；变更清单见对话记录。
单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 这一版在回答什么

1. 4 组设置（任务量 / 策略 / 反面内容开关 / 顺序）**一屏放不放得下**——这是文字 AC 写不出来的。
2. 入口按定稿做「`/profile` 为主 + 复习页齿轮快捷入口」：齿轮抽屉只放最常改的两项。
3. 顺序只做 ↑↓ + 恢复默认，不做拖拽；**顺序不决定有无**，开关与顺序是两个系统。

## 屏与深链

| 屏 | 深链 | 重点看什么 |
| :--- | :--- | :--- |
| 资料页 · 背单词 | `#prefs` | 任务量 3–200（默认 20）+ 软提示；三个策略各带一句后果；4 个开关（无图片） |
| 反面顺序 | `#order` | 8 块、↑↓ 实时交换、两个边界按钮置灰、恢复默认 |
| 复习页齿轮 | `#gear` | 抽屉只放任务量 + 策略，其余指向资料页；避免把整页设置搬进抽屉 |

## 定稿参数

| 项 | 值 | 说明 |
| :--- | :--- | :--- |
| `dailyNewCards` | 3–200，默认 20 | 硬上限 `daily-new-cards-max` 默认 **200**（用户 2026-10-30 定） |
| 任务量档位 | 正常 3–49（点选 = 20）· 进阶 50–99（= 50）· 挑战 100–200（= 100） | 档位只决定「区间 + 一键取值」，数字框仍可填 3–200 任意值；档位由数值自动判定，两者不会打架。三色只用 token 的 `--green/--amber/--red`，不做渐变 |
| `strategy` | `MIXED`（默认）/ `REVIEW_FIRST` / `NEW_FIRST` | `MIXED` = 现状（按 `due_at`） |
| `blocks` | `core, senses, examples, synonyms, antonyms, family, derivation, analysis` | 8 块；未知 key 丢弃、缺失 key 追加到末尾 |
| 开关 | 构词与词根 / 近义词 / 反义词 / 词族，默认全开 | 近义 98.2%、反义 66.8%、词根 98.6%、词族 98.5%（3000 词实测） |
| 图片 | **不做** | 内容包实测 0% 有图；界面不出现开关，不给假承诺 |

200 的连带：单次最多取 100 张（`queue-page-size: 100`），所以 200 是**两轮**；内容是 AI 现场生成，200 词/天/人最坏 $0.136/天（$0.00068/词）。

## 截图

`shots/` 下 3 张，1440×1180：

```bash
cd docs/mock/vocab-prefs-2026-10-30
node -e "const fs=require('fs');const h=fs.readFileSync('index.html','utf8');
const m=h.match(/<script>([\s\S]*)<\/script>/);new Function(m[1]);console.log('JS OK')"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for s in prefs order gear; do
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
    --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=1800 \
    --window-size=1440,1180 --screenshot="$PWD/shots/$s.png" \
    "file://$PWD/index.html#$s"
done
```
