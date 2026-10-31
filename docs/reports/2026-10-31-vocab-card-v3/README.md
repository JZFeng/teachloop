# 背单词 V3 卡片实现报告（2026-10-31）

## 结论

113 个词的 V3 内容已经生成并装进 dev 内容包；真实内容 API 返回 `schemaVersion=3`，复习卡按 mock 的 6 块顺序渲染，`morphology.family` 带 `partOfSpeech + meaning`。真实会话实测通过，「下一个」反面固定在视口 50vh、滚动不下移；设置页/首用提示/ⓘ 已接上。

**当前实现只覆盖 dev 的 113 词 V3 pack；运行时 AI 生成仍是 v2.5 prompt/validator（见「已知缺口」）。**

## 1. 数据

| 项 | 路径 |
|---|---|
| V3 JSONL（113 条） | `fixtures-local/vocab-content/vocab-content-en-v3.0.jsonl` |
| V3 内容包 | `fixtures-local/vocab-content-v3/content-en-v3.0.pack` / `.idx` / `manifest.json` |
| 生成脚本 | `scripts/vocab/enrich_v3.py` |
| dev 配置 | `teachloop-app/src/main/resources/application-dev.yml` → `pack-dir=fixtures-local/vocab-content-v3`、`prompt-version=vocab-content-v3.0` |

- 113 条、113 个唯一词；`manifest.count=113`。
- `morphology`：`modernNote`、`parts/chain`、`derivatives`、`inflections`、`family`（每项带 POS + 含义）。
- `etymology` 独立中文段落；不再把 `wordFormation.analysis` 同时当构词和词源。
- 只允许 v2 `wordFamily` 里已有的词形进入 `derivatives/inflections`，模型不能发明 `eagerer/eagerest` 这类形式；`jolly/interpret` 等历史词根被降级为整体词说明，避免把 `-ly/-er` 说成现代后缀。
- 8 个词有现代 `parts/chain`；其余以「整体词/整体借词」+ 派生/词族为主（这是保守选择，宁可少拆不硬拆）。

## 2. 后端

- `ContentView` 增加 V3 分支：6 个固定 block，`morphology` 原样带出（含 family POS/meaning），`etymology` 独立；旧 9 块 hidden key 映射到新模块。
- `ReviewPrefs.CARD_BLOCKS` 固定 6 块；`normalizeHidden` 接受新 id；旧 `ALL_BLOCKS` 保留给 v2/回退。
- 真实 API 实测（student 登录）：

```json
{"status":200,"schema":3,"hasMorph":true,
 "blocks":["senses","morphology","core","examples","synonyms","etymology"],
 "family":[{"word":"eager","partOfSpeech":"adjective","meaning":"渴望的；热切的"},
           {"word":"eagerly","partOfSpeech":"adverb","meaning":"热切地"}]}
```

- 后端测试：`teachloop-vocab` **149/149**（含 `ContentViewV3Test` 3、`ReviewPrefsTest` 10）；`teachloop-app VocabPrefsTest` **1/1**。

## 3. 前端

- `VocabReviewCard` 增加 V3 渲染器：词性缩写 `adj./adv./n./v.`、构词 note、派生词/屈折/词族行（POS + 含义）、核心理解、例句 3+展开、近义词/反义、词源。
- `FreeReviewPage`：删底部快捷键行；页头 ⓘ 放快捷键表；首次进入一次性提示（localStorage）；反面「下一个」`is-sticky` 固定 50vh。
- `VocabPrefsCard`：V3 显示开关（构词与派生/核心理解/核心例句/近义词辨析/词源补充）；删「反面顺序」；快捷键块只读。
- `ProfilePage`：`背单词` 标题右侧 ⓘ 承担原副标题，删除标题下的常驻说明。
- 标签去色：词性/词族/反义全部中性文字；设置页选中态浅底 + 主色描边；颜色只留给核心理解与主操作。
- 前端：`pnpm typecheck` 0；`pnpm test` **224/224**；`check-i18n` 2022 键对齐；`check-ui` 无新增。

## 4. 界面验收

- 真实 `eager` 会话（student@teachloop.local，session 由 API 创建）：
  [review-real-session-1440.png](shots/review-real-session-1440.png)
- iPad Air 11（834×1194）：无横向溢出；右侧「下一个」可见，y=568：
  [review-back-834.png](shots/review-back-834.png)
- 首用提示 / 页头 ⓘ / 例句展开 / 正面 / 设置页：
  [first-run](shots/review-first-run-1440.png) ·
  [info](shots/review-info-1440.png) ·
  [examples](shots/review-examples-1440.png) ·
  [front](shots/review-front-1440.png) ·
  [profile-prefs](shots/profile-prefs-1440.png)
- 1440 / 1024 / 834 实测 `scrollWidth == innerWidth`，body 14px；1440×900 下「下一个」y=421，滚动 688px 后仍 421；834 下 y=568。
- 744（≤760 堆叠）时「下一个」落回卡片下方，不在首屏；834/1024 目标档通过。

## 5. 已知缺口

1. **运行时 AI 生成仍是 v2.5**：`application-dev.yml` 的 `prompt-version=v3.0` 与契约 `content-prompt-v2.5` 不一致，启动会 warn。113 词 pack 不受影响；新词 miss 时生成 v2.5，前端会回退旧卡面。需要后续把运行时 prompt + `ContentSchemaV3` 校验升到 V3。
2. **V3 JSONL/pack 在 `fixtures-local/`（gitignored）**：本机可用；部署/换机需用 `enrich_v3.py` + `build_content_pack.py` 重生成，或把 pack 挂卷。
3. **113 词内容质量**：3 条曾因模型输出不合法走 fallback，已重跑；`jolly/conform/interpret` 采用保守整体词说明。发布前建议人工抽检这 3 条和 8 条带 parts 的词。
4. **快捷键只读**：`去设置` 能看到快捷键表，但不能改键；改键是独立功能。
5. **`image` 块**：不进入 V3 7 步卡片；T594 的 image 代码保留未启用。
6. 工作区里还有不属于本任务的 T595/T597 未提交改动（ReviewPage/ViewTabs 等），本次没有 stage 它们。

## 6. 下一步

- 把运行时内容生成升到 V3（prompt + validator），消除启动 warning，并让新词也走 V3 卡面。
- 在 qa/prod 构建/挂载 v3 pack；`application-qa.yml` / `prod` 同步 `prompt-version`。
- 发布前人工抽检 113 条内容（构词 note、派生/屈折分类、词源）。
- 若要改键，另开设置面板 + 冲突检测 + 恢复默认。
