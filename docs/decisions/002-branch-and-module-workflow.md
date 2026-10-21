# 决策 002 — 分支约定与「新模块接入」的顺序

日期：2026-10-21　状态：生效　相关：宪法第十四条、`specs/004-vocab/`

## 问题

到 2026-10-20 为止，所有工作都直接提交在 `main` 上（`git log` 里没有一条来自其他分支）。
背单词（新模块，与现有功能几乎无关）提上日程后暴露两个问题：

1. **没有回滚点**。一次跨多模块的改动如果做错，只能 revert 一串提交。
2. **「新增模块不动阅卷」这条宪法约束无法被验证**。在 `main` 上没有 baseline，
   拿不出「阅卷模块改动数 = 0」的证据。

## 决策一：什么时候开分支

| 改动性质 | 做法 |
| :--- | :--- |
| 能一条提交说完、且做完时 `main` 始终可运行（改文案、补文档、一行配置） | 直接提交到 `main` |
| 跨多次提交，或中途会让 `main` 跑不起来 / 让守卫变红 | 开分支 |
| 只是想验证一个想法，不打算合并 | `exp/<主题>`，用完删 |

**判据只看一件事：这条改动做完之前，`main` 还能不能跑。** 不能，就必须开分支。

## 决策二：命名

`feat/<主题>`、`fix/<主题>`、`docs/<主题>`、`exp/<主题>`。
主题用短横线英文，例如 `feat/vocab`、`feat/ui-standard`。一条分支只做一件事。

## 决策三：合并策略

- **特性分支用 `--no-ff`**：保留分支点，整个特性可以用一条 `git revert -m 1` 撤掉。
- 单点小修用 fast-forward 或 squash。
- 合并前必须把 `main` 合进分支一次（`git merge main`），别攒到合并当天。
- 合并后立刻跑一次 `make test`（守卫都在里面），再更新 `docs/PROGRESS.md`。

## 决策四：Flyway 迁移序号的铁律

九个模块的迁移目录都挂在同一个 `classpath:db/migration` 下，**共用一串 V0xx 序号**（现到 V028）。

- 两条分支各写一个 V029 → 合并即冲突，`Flyway` 运行时也会报重复版本。
- **禁止为某个模块预留高位号段**（如 V100+）：`application.yml` 只配了
  `spring.flyway.enabled` 与 `locations`，`outOfOrder` 默认 `false`，
  已应用 V029 之后再出现低位号的 pending 迁移会直接校验失败。
- 正确做法：**合并前**把分支上的迁移 `git mv` 到 `main` 当下第一个空号，再跑一次启动确认。

## 决策五：新模块接入的顺序（骨架先行）

新增一个业务模块时，**先铺管线，再开分支写业务**：

1. **先落地与数据模型无关的接线**（一条提交，落在 `main`）：
   - 根 `pom.xml`：`<modules>` + `<dependencyManagement>`
   - `teachloop-<模块>/pom.xml`、`package-info.java`、空的 `db/migration/.gitkeep`
   - `teachloop-app/pom.xml`：依赖 + `copy-migrations-<模块>` 执行
     （漏了这条，`dev` 下看不出问题，只有 fat jar 里 Flyway 扫不到迁移）
   - `scripts/check-deps.py` 的 `ALLOWED`、`scripts/check-boundaries.py` 的 `MODULES`
   - 宪法对应条款补一句「已接线、尚无业务」
2. **表、`TABLE_OWNER`、迁移、路由、导航、页面一律等 plan 定稿**。
   提前写这些等于在 spec 之前定数据模型。
3. **第一次写业务代码时**才开 `feat/<模块>`。此时骨架已在 `main`，分支 diff 退化成
   「几乎全是新文件」，冲突面最小。

配套代价（已确认接受）：空模块会常驻参与构建，多一个 jar、`app` 多一条依赖。
如果该模块最终被砍掉，删回去是 `git revert` 那一条提交。

## 不用 worktree

`git worktree`（同一 `.git` 挂多个工作目录）只在**需要同时跑两套服务**时才有价值
（例如一边跑 `qa` 测试台、一边改代码）。本次不需要，因此不引入第二个工作目录、
第二套 `node_modules` 与第二个 Postgres 容器。真需要时再开，`scripts/env.sh`
已经支持命名环境与端口覆盖。

## 与本次批准方案的偏差（落盘）

1. **规格目录从 `specs/003-vocab` 改为 `specs/004-vocab`**：
   `003` 已被 `specs/003-ui-standard/` 占用（2026-10-21 的新事实）。
2. **原计划「阶段 0：清理未提交 WIP」已由他人完成**，无需重做：
   `6ac1a16`（T253 AI 逐项意见落库）、`7817bbf`（T253 前端）、`6aaff59`（UI 问卷记录）。
3. 骨架提交落在 `main`，执行时 `main` 未推送（ahead 7），与 `feat/ui-standard`
   分支并存；两者改动文件不重叠，只有宪法文件可能需要一次人工合并。
