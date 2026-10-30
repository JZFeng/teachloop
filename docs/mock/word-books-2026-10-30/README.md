# 系统词书原型（2026-10-30）

对应规格：[`specs/013-word-books/spec.md`](../../../specs/013-word-books/spec.md)（6 US / 26 AC / 25 FR）

单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 怎么 review

顶部三组开关：

| 开关 | 值 | 看什么 |
| :--- | :--- | :--- |
| 角色 | 学生 / 管理员 | 侧栏与可见页面随角色变；管理员才有「词书管理」 |
| 屏 | 我的词单 / 书详情 / 副本复习 / 管理端 / 导入词条 | 五屏直接切换 |
| 数据 | 有数据 / 空态（新用户） | 新用户第一次打开词单页看到什么 |

深链直接开某一屏：

- `index.html#lists`
- `index.html#lists?data=empty`
- `index.html#book`
- `index.html#session`
- `index.html#admin`

## 屏与深链

| 屏 | 深链 | 这一屏看什么 |
| :--- | :--- | :--- |
| 我的词单 · 含推荐 | `#lists` | **词书入口在词单页、不在词库**；先「我的词单」，**推荐区在下面**（2 本 + 覆盖率）；词单里有一份带「词书副本」标签 |
| 我的词单 · 空态 | `#lists?data=empty` | 没有自建词单时，推荐区仍在（不是只在空态出现） |
| 书详情 | `#book` | 名称/词数/覆盖率/加入人数；预览 50 词一页，含「生成中」行；底部固定操作条 |
| 副本复习 | `#session` | 全屏无侧栏；标题写「托福核心 · 第 9 张 / 共 800 张」；卡片先给正面，点击翻面 |
| 管理端 | `#admin` | 全部词书（含草稿）+ 编辑面板（元信息 / 覆盖率 / 发布 / 导入入口） |
| 导入词条 | `#import` | 复用「加词」页的粘贴/上传与预览：格式清洗、拼写检查、跳过提示，只是提交目标换成书 |

## 这一版定的口径

1. **入口在词单页**，词库不放词书与推荐；词库只做「我有哪些词」的总览（AC-104.5）。
2. 推荐区固定 2 本，排序由 `Ports.BookRecommendation` 给（v1：已发布 → position → 加入人数），界面不写死规则。
3. 「加入」生成**副本词单**（带「词书副本」标签），点「继续背」直接进全屏复习。
4. 覆盖率实时计算并在学生端与管理端同时显示；复制不预生成内容。
5. 管理端只有 ADMIN；学生端响应不含价格字段（v1 免费，字段为 v2 预留）。

## 平板自检（834×1194 与 1024×1366）

18 个组合（5 屏 + 空态 × 3 视口）全部通过：`scrollWidth == innerWidth`、首屏外按钮数 0。

期间修掉两个真问题：① 管理端在 834 宽下「保存 / 下架」掉到折叠线以下 → 底部常驻操作条（`.savebar`）；② 导入页在 1440 宽下「确认导入」掉出首屏 → 预览表底部常驻（`.pager.sticky`）。

复跑：

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=3000 \
  --window-size=834,1194 --dump-dom "file://$PWD/index.html?measure=1#lists" \
  | grep -o 'data-measure="[^"]*"'
```

## 截图

`shots/` 下 11 张：

`lists` `lists-empty` `book` `session` `admin` `import`
`lists-834` `lists-1024` `book-834` `admin-834`

重新生成前先做语法自检：

```bash
node -e "const fs=require('fs');const h=fs.readFileSync('index.html','utf8');
const m=h.match(/<script>([\s\S]*)<\/script>/);new Function(m[1]);console.log('JS OK')"
```

## 数据口径

书名、词数、覆盖率、加入人数、预览词条都是**示例数据**；其中覆盖率数字（498/800、440/500、312/600）用来演示「同一本书里有的词已就绪、有的还在生成」这一状态，不代表真实内容包命中率。
