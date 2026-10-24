# 背单词原型 · 2026-10-23（第二版）

对应规格：`specs/004-vocab/spec.md`（US-46–61）
单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 这版新增了什么（第二轮反馈）

| 反馈 | 原型里的落点 |
| :--- | :--- |
| 搜索框必须常驻 | 工具条 `sticky`，滚动时贴在顶栏下 |
| 学习语言要能扩展 | 筛选弹窗里有「英语 / 中文 / 西班牙语」，V1 只开英语 |
| 默认排序 = 下次复习升序 | 表头箭头在「下次复习」上，可点其他列改排序 |
| 每行可「移出复习」/「重新加入」 | 行尾按钮；移出后状态「已移出」、下次复习显示「—」；点「重新加入」回到新词 |
| checkbox 多选 + 批量移出复习 | 表头全选 + 选中后出现批量操作条 |
| 点单词进详情页 | 点单词或释义进 `#detail` |
| 列可自定义、顺序可调 | 「列 ▾」弹层：勾选与上下移 |
| 点统计卡过滤 | 「今天到期 / 新词 / 已掌握」三张卡可点，选中态可见 |
| 筛选按钮 → 弹窗 | 「筛选」打开 modal：来源 / 状态 / 语言 / 标签 |
| 词库内自由复习 | 「▶ 复习这些词（10）」→ 自由复习 → 随时「结束复习」→ 小结页 |
| 详情页内容 | 英美音标与发音、释义、例句、构词、词族（带词频）、收藏、暂停复习、反馈（附 JSON） |
| 多语言/成就/图片 | 语言在筛选里、成就与图片在页面上明确标注 V2 |

## 怎么走完主流程

1. 打开后默认 **学生 · 我的词库**，顶部是「王砚秋老师发来 Unit 5 生词」横幅。
2. 点任一张统计卡 → 列表被过滤；再点一次取消。
3. 勾几个词 → 批量标记「已熟悉」。
4. 点单词 → **单词详情**（同一份模板 + 不同 JSON）。
5. 回词库，点「▶ 复习这些词」→ **自由复习**：空格翻面、1–4 评分、`S` 移出复习、随时「结束复习」。
6. 顶栏切 **教师** → 词集 / 发词。

## 深链

| 链接 | 说明 |
| :--- | :--- |
| `#library` | 学生词库 |
| `#library?ds=empty` / `?ds=error` / `?ds=loading` | 三态 |
| `#library?modal=1` | 筛选弹窗 |
| `#detail` | 单词详情 |
| `#review` / `#review?flip=1` | 今日复习（正面 / 翻面） |
| `#review?free=1` | 自由复习 |
| `#summary` | 复习小结 |
| `#review?theme=dark&flip=1` | 暗色 |
| `#wordset` / `#assign` | 教师词集 / 发词 |

## 仍需讨论（§13）

1. word family 从哪来：等 Paul Nation 词表文件到手后再定（本地遍历 vs AI 生成）。
2. ~~remove 与「已熟悉」的关系~~ 已定：同一个动作「移出复习」，可「重新加入」。
3. ~~纯词表导入怎么补释义~~ 已定：导入与加词同一动作，入库后异步生成内容。

## 截图（`shots/`，1440×1000，无头 Chrome）

`library.png`、`library-empty.png`、`library-error.png`、`library-filter.png`、
`review.png`、`review-flipped.png`、`review-free.png`、`review-empty.png`、`review-dark.png`、
`detail.png`、`summary.png`、`wordset.png`、`assign.png`

重新生成（示例）：

```bash
cd docs/mock/vocab-2026-10-23
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2500 \
  --window-size=1440,1000 --screenshot="$PWD/shots/library.png" \
  "file://$PWD/index.html#library"
```

`.chrome-profile/` 是临时浏览器数据，已在 `.gitignore` 里。
