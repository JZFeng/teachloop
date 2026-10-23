# 并行任务：worktree + 短命分支 + 一个属主

> 2026-10-23 复盘产物。适用于「同时推进多个互不依赖的任务」。
> 一句话：**一个任务一个工作目录（worktree）+ 一条从 main 拉出的短命分支 + 一个属主**；分支当天合回。

## 1. branch 和 worktree 不是一回事

- `branch` = 指向提交的指针，几十字节，可以有任意多条；
- `worktree` = 磁盘上一个工作目录，绑定一条分支；同一个分支不能同时被两个 worktree 检出；
- `git worktree add <dir> -b feat/x main` 一次做两件事：建分支 + 摊开一个基于 main 的新目录。

多个 agent 能同时干活，靠的是**多个工作目录**；只建分支、仍在同一个目录里，两个 agent 会互相抢 checkout 和暂存区。

## 2. 开工（负责人或你自己执行一次）

```bash
# 在 teachloop 根目录
slug=rubric                      # 换成本次任务名
git worktree add ../teachloop-wt/$slug -b feat/$slug main

# 本地环境（被 .gitignore 挡住，不会自动带过去）
cp -R .envs ../teachloop-wt/$slug/ 2>/dev/null || true
```

然后把该 session 的**工作目录设成** `../teachloop-wt/<slug>`（DSH 的文件沙箱只允许改会话工作目录内的文件）。

新 worktree 里第一次构建前：`web/` 下 `pnpm install`，Java 侧直接 `make test`（`target/` 会重建）。

## 3. 任务卡（每个 session 开场贴一次）

```
【任务卡】
目标：<一句话，可验收>（Txxx）
仓库：/Users/jzfeng/Documents/deepseek-harness/teachloop（只读参考，别在这里改）
工作区：/Users/jzfeng/Documents/deepseek-harness/teachloop-wt/<slug>
分支：feat/<slug>（已基于 main 建好；不要切分支、不要 merge 到 main）

你拥有：<目录/文件清单，越具体越好>
不要动（负责人改）：AGENTS.md、docs/PROGRESS.md、specs/**/tasks.md、
  web/src/locales/**、docs/decisions/**、Makefile、*.sql 迁移
数据库：不要自己加迁移；确实需要就停下来问负责人要 V 号

验收：<命令 + 预期结果>；跑完贴真实输出（通过/失败数、退出码）
      没做浏览器验收就写「仅结构校验」
提交：只 add 你拥有的文件，不 git add -A；一个可测单元一个 commit；
      message = "feat(scope): 做了什么（Txxx）"
汇报：自上一条文字后连续 8 次工具调用、或要跑 >120 秒的命令、或完成一个单元，
      发三行【刚做完 / 正在做 / 卡住】
合并：不要自己 merge 到 main；做完告诉负责人，由负责人按顺序合 + 跑全量
冲突：万一撞了，列出每个冲突文件 + 你选哪边 + 为什么；不许静默丢弃任一边；
      如果发现两边改的是同一个默认值/上限/规则，停下来问负责人
```

## 4. 合并与清理（负责人）

```bash
git switch main
git merge --no-ff feat/<slug>        # 保留任务边界
git branch -d feat/<slug>
git worktree remove ../teachloop-wt/<slug>   # 有未提交改动时加 --force
```

- 合并顺序：不依赖谁的先合；
- 每合一个，跑一次 `make test`；合并后红了，先怀疑语义冲突，不是代码写错；
- 语义冲突（两边改同一个默认值、上限、评分规则）停下来问人，不许二选一；
- 合完更新 `specs/**/tasks.md` 与 `docs/PROGRESS.md`，单独提交这两个文件。

## 5. 属主与共享物

- **一个属主**：每个文件、每条业务规则，同一时刻只有一个人写；不是「没有冲突」，是「不用靠合并决定谁说了算」；
- 共享物归负责人：`AGENTS.md`、`docs/PROGRESS.md`、`specs/**/tasks.md`、`web/src/locales/*.json`、`docs/decisions/**`、`Makefile`、依赖锁文件、迁移号；
- 数据库迁移是**全局 V 序号**（六个模块共用），并行时由负责人串行发放。

## 6. 验证的现实约束

- 端口和数据库是全局的：三个 worktree 不能同时各起一套 5173/5174，也不能共用同一个 `teachloop_qa` 库；
- 要么排队验收，要么给每个 worktree 配独立的端口 + 库（改 `scripts/env.sh` 的端口/库名）；
- 分支当天合回 main。挂久了会出现「6 个提交停在分支上」和 merge 重放造成的重复提交（T340/T341 就是这么来的）。
