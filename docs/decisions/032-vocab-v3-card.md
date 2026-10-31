# 032 背单词 V3 卡片：6 块固定顺序 + 构词与派生带 POS/含义

日期：2026-10-31  
状态：用户已确认「按 mock 改实现」，本决策对应 `docs/mock/vocab-card-v1-2026-10-31/`。

## 决定

1. **卡片反面固定 6 个 block**，顺序固定：
   `senses → morphology → core → examples → synonyms → etymology`。
   单词与发音是卡头，不算 block。
2. **`morphology` 讲现代构词 + 派生 + 屈折 + 词族**，`family` 的每一项必须带
   `partOfSpeech` 与 `meaning`，不能再只输出词形（用户 2026-10-31 明确「构词与派生是最重要的信息」）。
3. **`etymology` 独立一段**，只讲历史来源；不允许和现代构词说明写成同一句，也不在卡面列裸词源链。
4. **V3 内容 schema（schemaVersion=3）**：
   - `basicInfo` / `meanings` / `coreSemantics` / `examples` / `memoryAids` 沿用 v2；
   - 新增 `morphology{modernNote, parts[], chain[], derivatives[], inflections[], family[]}`；
   - 新增 `etymology{text}`；
   - 不再把 `wordFormation.analysis` 当作构词与词源共用的唯一字段。
5. **复用 T594 的 `{id,payload}` envelope**：后端仍输出有序 `blocks`，前端仍只消费 payload；
   但 V3 的顺序由后端固定，不读用户 `prefs.blocks` 顺序；`hidden` 仍决定有无，旧 9 块 key
   映射到新模块（family/derivation→morphology，analysis→etymology）。
6. **页面 chrome**：
   - 删除自由复习底部常驻快捷键行；
   - 页头 ⓘ 显示快捷键表（不再放「说明整理中」占位）；
   - 首次进入一次性提示，可去设置或关闭；
   - profile「背单词」标题右侧 ⓘ 承担「每天背多少 / 先复习还是先学新词 / 卡面显示什么」副标题；
   - 设置页删「反面顺序」，只留显示开关；快捷键块本轮只读。
7. **「下一个」**：尺寸/样式/文案不变；反面 `sticky` 在视口 `50vh`，滚动和长卡都不下移；
   正面保持原来的卡片垂直居中。≤760 窄屏仍落回卡片下方。
8. **标签不做颜色编码**：词性用中性缩写 `adj./adv./n./v.`；设置页选中态用浅底+主色描边，
   不再实色填充。

## 落地

- 内容：113 词 V3 JSONL + pack，dev 配置 `fixtures-local/vocab-content-v3` + `vocab-content-v3.0`。
- 后端：`ContentView` 新增 V3 分支；`ReviewPrefs.CARD_BLOCKS`；旧 hidden key 映射。
- 前端：`VocabReviewCard` V3 渲染器；`FreeReviewPage` 首用提示/删底部提示/sticky；
  `VocabPrefsCard` V3 开关；`ProfilePage` InfoHint。
- 验证：见 `docs/reports/2026-10-31-vocab-card-v3/README.md`。

## 已知缺口

- 运行时 AI 生成仍是 v2.5 prompt/validator；`PromptVersionConsistencyCheck` 会警告
  `prompt-version=v3.0` 与契约 `v2.5` 不一致。113 词 pack 可用；新词生成需要后续把
  运行时 prompt + 校验升到 V3。
- `image` 块不进入 V3 7 步卡片；T594 的 image 代码保留但默认不启用。
- 改键是后续独立功能；本轮快捷键块只读。
