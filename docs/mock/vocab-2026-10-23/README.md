# 背单词原型 · 定稿版（2026-10-23）

对应规格：[`specs/004-vocab/spec.md`](../../../specs/004-vocab/spec.md)（US-46–61 / 100 条 AC / FR-091–115）
对应计划：[`specs/004-vocab/plan.md`](../../../specs/004-vocab/plan.md)

单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 怎么 review

1. 顶部三个开关先玩一遍：**四态**（有数据/空/出错/加载）、**角色**（学生/教师）、**亮暗**。
2. 按下面「屏与深链」逐屏看，重点看每屏的**空 / 错**两种状态。

## 屏与深链

| 角色 | 屏 | 深链 | 这一屏看什么 |
| :--- | :--- | :--- | :--- |
| 学生 | 我的词库 | `#library` | 常驻搜索（Enter 查任意词）、统计卡可点过滤、暂停/删除、批量、生成中状态 |
| 学生 | 我的词库 · 筛选 | `#library?modal=1` | 来源多选、卡组视图、状态、**已收藏**、语言 |
| 学生 | 加词与导入 | `#add` / `#add?preview=1` | 粘贴四种写法、上传文件；预览含**三态拼写校验**与跳过行 |
| 学生 | 今日复习 | `#review` | 两档评分（✕/✓）、卡片外右侧「下一个」、ⓘ 说明 |
| 学生 | 今日复习 · 不认识 | `#review?rated=again` | 自动翻面、按钮置灰、看答案后点「下一个」 |
| 学生 | 自由复习 | `#review?free=1` | 不受到期限制、可随时结束、S 暂停 |
| 学生 | 复习小结 | `#summary` | 看了多少 / 暂停多少 / 认识·不认识分布 |
| 学生 | 单词详情 | `#detail` | 音标发音、按词性释义、例句、构词、词族、收藏/暂停/反馈 |
| 学生 | 详情 · 不在库 | `#detail?word=serendipity` | 「＋ 加入词库」，其余内容提示加入后生成 |
| 教师 | 词集 | `#wordset` | 表格视图、批量启用/停用/删除、点行进编辑页 |
| 教师 | 发词记录 | `#wordset?tab=assignments` | 词集、班级、发送时间、截止、领取情况 |
| 教师 | 词集编辑 | `#wordsetEdit` | 单栏：名称/语言/说明 → 导入/加词 → 取消/保存 → 词条列表 |
| 教师 | 发词给班级 | `#assign` | 可搜索词集下拉、班级平铺多选（20 个班）、ⓘ 说明 |

暗色：任意屏 URL 后加 `&theme=dark`（例如 `#review?theme=dark&flip=1`）。

## 定稿口径（与 spec 一致）

| 主题 | 结论 |
| :--- | :--- |
| 词库结构 | 单库；卡组 = 保存的筛选，不拥有卡片 |
| 评分 | 两档：认识 / 不认识；认识直接下一张，不认识自动翻面停留 |
| 调度 | 认识→良好、不认识→忘记；连续 4 次认识且间隔 ≥45 天毕业（可配） |
| 收藏 / 暂停 / 删除 | 收藏=标记（可筛选）；暂停=不排期仍在库；删除=软删除，**只在词库页** |
| 导入 | CSV/MD/TXT + 粘贴；格式清洗自动报告；拼写三态提示、不自动改 |
| 词族 | 本地 Paul Nation 词表优先，未命中走异步生成 |
| 教师侧 | 词集=表格+编辑页；发词=可搜索词集 + 班级平铺多选；**无年级层级** |
| V1 不做 | 笔记、图片、打卡成就、教师看进度、例句朗读、FSRS |

## 截图

`shots/` 下 19 张，1440×1000，无头 Chrome 打：

`library` `library-empty` `library-error` `library-filter` `add` `add-preview`
`review` `review-flipped` `review-dontknow` `review-empty` `review-dark` `review-free` `summary`
`detail` `detail-new` `wordset` `wordset-assignments` `wordset-edit` `assign`

重新生成前先做语法自检，避免截出空白页：

```bash
cd docs/mock/vocab-2026-10-23
node -e "const fs=require('fs');const h=fs.readFileSync('index.html','utf8');
const m=h.match(/<script>([\s\S]*)<\/script>/);new Function(m[1]);console.log('JS OK')"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2500 \
  --window-size=1440,1000 --screenshot="$PWD/shots/library.png" \
  "file://$PWD/index.html#library"
```

`.chrome-profile/` 是临时数据，已在 `.gitignore` 里。
