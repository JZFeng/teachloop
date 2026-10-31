# 113 张词卡配图接入产品（task-1）

**结论：端到端验收通过**——qa 内容版本（v2.5）下，`review-back` 与 `word-details` 在「语义核心」块右侧渲染
88×88 的真实 WebP（圆角 8px、1px `var(--line)` 边框、`object-fit: cover`），无图的词不渲染、不占位；
`GET /api/vocab/contents/{contentId}/image` 取得到字节，缺图 404。

验收档位按仓库口径是第三档（**端到端**）：后端 160 个测试全绿 + 浏览器实测（不是只做结构校验）。

| 项 | 落点 |
|---|---|
| 词表 | qa 库 `words_with_images`（113 词，id `ce67477c-b18d-42e2-a77d-c2553a86cae5`，属 `teacher@teachloop.local`） |
| 图片文件 | `STORAGE_LOCAL_DIR/vocab/images/<content_id>.webp`（qa = `.envs/qa/uploads`，113 张 976.5 KB） |
| 内容视图 | 读路径多一个 `imageUrl` 字段（**不是** `blocks` 里的第 9 块，T594 的块契约本任务不做） |
| 图片接口 | `GET /api/vocab/contents/{contentId}/image` → `image/webp`，`Cache-Control: max-age=604800, public`，缺图 404 |
| 前端 | `review-back`（今日复习 / 自由复习）与 `word-details` 共用 `VocabReviewCard`，两处一起生效 |
| 映射清单 | `image-manifest.json`（词 → content_id → key → 字节数 → sha256） |

## 1. 端到端证据在哪个实例上采的（重要，归档别混）

采证据时 **qa 的 8011/5174 被另一个会话的 v3 沙箱占用**（进程 cwd `/private/tmp/teachloop-qa-v3`），
那个 jar 没有本任务的图片接口。所以证据全部采在**隔离复验实例**上，用的仍是 qa 的产物与数据：

| 角色 | 端口 | 来源 |
|---|---|---|
| 后端 | `127.0.0.1:8021` | `.envs/qa/app.jar`（`VERSION: 95a233b+dirty`，built `2026-10-31 11:55:20`） |
| 前端 | `127.0.0.1:5184` | `vite preview .envs/qa/web`，`/api` 代理到 8021 |
| 库 | `127.0.0.1:55433/teachloop_qa` | 与 qa 同一个库 |
| 内容 / 附件 | `.envs/qa/content-delta.db`（v2.5）/ `.envs/qa/uploads` | 与 qa 同一份 |

隔离实例的起法（等价于 qa 的启动命令，只换端口）：

```bash
JAVA=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java
DB_URL=jdbc:postgresql://localhost:55433/teachloop_qa DB_USER=teachloop DB_PASSWORD=teachloop \
STORAGE_LOCAL_DIR="$PWD/.envs/qa/uploads" \
VOCAB_CONTENT_STORE_PATH="$PWD/.envs/qa/content-delta.db" \
VOCAB_CONTENT_PACK_DIR="$PWD/fixtures-local/vocab-content" \
SPRING_PROFILES_ACTIVE=qa PORT=8021 TEACHLOOP_GRADING_ENGINE=stub \
  "$JAVA" -jar .envs/qa/app.jar &

cd web && TEACHLOOP_API="http://127.0.0.1:8021" WEB_PORT=5184 \
  pnpm exec vite preview --outDir "$PWD/../.envs/qa/web" --port 5184 --host 127.0.0.1
```

## 2. qa 重建步骤（下次 `deploy` 之后照这个顺序复现）

qa 是「快照产物 + 独立库 + 独立 uploads」的环境，**图片与词表都不在 git 里**，所以 `deploy` 之后必须补两步。

```bash
cd /Users/jzfeng/Documents/deepseek-harness/teachloop

# 0) 前提：8011/5174 没被别的会话占着（lsof -ti tcp:8011 应该是空）
./scripts/env.sh deploy qa          # 构建 jar + 前端 dist，复制快照，重启 8011/5174

# 1) 图片落盘：113 张 176px WebP 按 content_id 写进 qa 的 uploads
#    幂等；写完打印映射清单（词 → content_id → key → sha256）
python3 scripts/vocab-images-import.py \
    --uploads .envs/qa/uploads \
    --content-db .envs/qa/content-delta.db

# 2) 建词表 + 放词：走业务接口（不是手写 SQL），账号 teacher@teachloop.local
#    幂等：词表已存在就复用，词已在表里计入 duplicated
python3 scripts/vocab-list-import.py --base http://127.0.0.1:8011

# 3) 三条自检（期望：imageUrl 有、图片 200 webp、缺图 404）
curl -s -b /tmp/qa.jar http://127.0.0.1:8011/api/vocab/words/by-form/apple | grep -o '"imageUrl":"[^"]*"'
curl -s -b /tmp/qa.jar -o /dev/null -w '%{http_code} %{content_type}\n' \
     http://127.0.0.1:8011/api/vocab/contents/1000000022/image
curl -s -b /tmp/qa.jar -o /dev/null -w '%{http_code}\n' \
     http://127.0.0.1:8011/api/vocab/contents/999999999/image
```

`/tmp/qa.jar` 是登录后的 cookie（qa 开了 CSRF，取 token 再登录）：

```bash
curl -s -c /tmp/qa.jar http://127.0.0.1:8011/api/csrf -o /dev/null
TOKEN=$(grep XSRF-TOKEN /tmp/qa.jar | awk '{print $7}')
curl -s -b /tmp/qa.jar -c /tmp/qa.jar -X POST http://127.0.0.1:8011/api/auth/login \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"email":"teacher@teachloop.local","password":"teachloop123"}'
```

**dev 环境**：`python3 scripts/vocab-images-import.py --uploads ./uploads` 即可落盘；
但 dev 的 `teachloop.vocab.content.prompt-version` 已是 `vocab-content-v3.0`，而 113 张图对应 v2.5 内容，
配图是按「版本匹配的 delta 行」反查的（见 §4），所以 dev 上这 113 个词**不会**显示图——
这是有意的，不是 bug：内容换版后旧图不该继续挂在新的内容上。要让 dev 也有图，得先用 v3.0 的 delta 重跑
`--content-db <dev 的 v3 delta> --prompt-version vocab-content-v3.0`。

## 3. 截图（全部来自 §1 的 8021/5184 实例）

| 文件 | 说明 |
|---|---|
| `word-details-apple.png` | word-details，apple 右侧真实配图（儿童抱苹果） |
| `word-details-brave.png` | word-details，brave（迎风雨向前） |
| `word-details-abandon.png` | word-details，abandon（走开留下婴儿车） |
| `review-back-apple.png` | 今日复习翻面后的 `review-back`，apple，图在语义核心块右侧 |
| `word-details-no-image-compass.png` | **反例**：compass 有内容但不在 113 张里 → 不渲染、不占位 |
| `list-words-with-images.png` | 词表 `words_with_images`：113 词，全部真实入库 |
| `samples/*.webp` | 仓库里只留 3 张样例（apple / brave / abandon），成品在 `.envs/qa/uploads` |

量出来的样式（`getComputedStyle`，不是目测）：

```
img.review-word-image  → width/height 88px、naturalWidth 176px、border-radius 8px、
                         border 1px solid rgb(220,228,242)（= tokens.css --line #dce4f2）、object-fit cover
```

## 4. 设计决定与踩到的坑

**（1）图片按 content_id 命名，但地址里的 id 由「词形 → delta id」反查得到。**
读路径优先命中**内容包**，包里的 `contentId` 是「按词形排序后的 1..N 排列」
（`ContentPackIndex` 装载时校验 `contentId 恰好是 1..count 的一个排列`），而图片文件名用的是 delta 的
稳定 id（1_000_000_022 起）。若直接用读路径拿到的 contentId 拼文件名，**包重建一次（加一个词，
后面 id 顺移）就会静默把别人的图挂到这个词上**。词形是稳定身份，所以 `VocabImageStore.url(language, form)`
先按 `(language, form)` 查 delta 拿 id，再按那个 id 拼 URL 并检查文件存在——URL 里的 id 始终是
「图片所属的那条内容」的 id，包重建也不会串词。代价：每次读内容多一次 delta 查询（SQLite 进程内，可忽略）。

**（2）`imageUrl` 在 `blocks` 算完之后才写。**
图片不是第 9 块（T594 的块契约本任务不做），前端把它渲染在语义核心块里；写得早会让同一张图
在 `blocks` 里再出现一次。

**（3）挂载点是 `ContentStore.imageUrl(...)` 的默认方法。**
`ContentView.resolve` 是全站读路径的唯一入口（队列 / 会话 / 详情 / 小测 / 词库列表共 7 处调用），
在它里面补字段就一处覆盖全部；默认实现返回 `Optional.empty()`，所以旧测试替身与 `enabled=false` 都不需要改。

**（4）前端改动落在别人的提交里（追溯用）。**
`web/src/vocab/VocabReviewCard.tsx` 与 `web/src/vocab/vocab.css` 的配图改动被
`8e2adf5 feat(vocab): 卡背改版「方案 5」` 一起提交了（那个提交动了同一批文件）。已核对 HEAD 里完整存在：
`VocabReviewCard.tsx:131`（`imageUrl` 类型）、`:261-274`（`imageUrl` 取值 + `renderWordImage`）、
`:296`（旧 9 块路径的 core 右侧）、`:595`（V3 6 块路径的 core 右侧）；
`vocab.css:329-341`（`.review-word-image` 88×88 + 边框 + 圆角、`.v3-core-row`）。
本任务的 commit 只含后端 + 测试 + 脚本 + 报告，不重复提交这两个文件。

## 5. 测试

```bash
./mvnw -pl teachloop-vocab -am -Dmaven.compiler.useIncrementalCompilation=false test
# Tests run: 160, Failures: 0, Errors: 0, Skipped: 1
# BUILD SUCCESS
```

新增的 11 个测试：
- `VocabImageStoreTest`（5）：key 约定、按词形反查 delta id、**地址用 delta id 不是包位置号**、
  `enabled=false` 全链路当没图、接口 200/404 + `image/webp` + 7 天缓存；
- `ContentViewImageTest`（6）：有图才输出 `imageUrl`、无图字段整个不出现、词条 `content_id` 为 NULL 时
  按词形回退仍带图、包内位置号命中时图片仍指向稳定 id、V3 内容也带、`imageUrl` 不进 `blocks`。

`-Dmaven.compiler.useIncrementalCompilation=false` 不是可选项：并行会话同时构建同一模块时，
增量编译会留下陈旧 class（实测：源码是对的，`ContentViewV3Test` 报 block 顺序不符；强制全量重编译后 3/3 通过）。

前端：`npx tsc --noEmit` 通过；`npm test` 226/226 通过。

## 6. 遗留

1. **生产未动**（按约定）。生产切 S3 时，113 张图还要另走一次对象上传（本任务只落了本地目录）；
   `VocabImageStore` 走的是 `BlobStore` 端口，代码不用改。
2. **改图无审计**：文件名即 content_id，覆盖即更新；没有版本与回滚。量小、可重跑，先不做。
3. **删内容会留孤儿图**：delta 行被删/换版后，`vocab/images/<旧 id>.webp` 不再被引用但不会自动清理。
   当前 113 个 id 是稳定的，暂不影响；以后要有清理任务。
4. **没有「哪些词有图」的运营面**：只能看 `image-manifest.json`。要批量换图 / 查缺图，得先把清单当数据源
   做一个小页面（本任务范围外）。
5. **`words_with_images` 是 teacher 的私人词单**：`VocabularyListEndpoint` 的词单是按用户的容器
   （AC-69.9：他人词单一律 404），管理员也没有「给别人建词单」的接口，所以词单只能建在 teacher 名下；
   qa 里唯一的 ADMIN（`admin@teachloop.local`）`password_login_enabled=false`，无法用密码登录建单。
