# 调研：用户自助 opt-in 实验（experiment system）

- 日期：2026-10-24
- 起因：管理员/教师/学生菜单要按角色可配置；更重要的诉求是「将来加新功能，可以部分学生 enable」，方式是给一个学生一个 opt-in 链接，点了就加入该功能，也能退出。
- 结论落点：`specs/007-experiments/spec.md`（待写），以及 `specs/003-ui-standard/spec.md` AC-27.6 的修订。

## 1. 先把问题定性

要做的东西在 feature flag 分类学里属于 **permission flag / beta program**（按人放行的功能），不是 release flag（上线用的临时开关），也不是 experiment flag（A/B 测效果）。

行业参考文档把 flag 分成四类并给出生命周期（[Flag Types and Patterns](https://github.com/borghei/Claude-Skills/blob/ddca910/engineering/feature-flags-architect/references/flag-types-and-patterns.md)）：

| 类型 | 目的 | 生命周期 | 本项目对应 |
|---|---|---|---|
| Release flag | 代码先上线、功能后放量 | 天到周，用完删 | `teachloop.vocab.enabled`（现在这种用法） |
| Ops flag | 出故障时快速关掉 | 长期 | 无 |
| Experiment flag | A/B 对比效果 | 2–6 周 | 明确不做（用户已澄清不是 AB） |
| **Permission flag** | 按套餐/角色/**beta 名单/个人 opt-in** 放行 | **长期** | **本次要做的** |

这一条定性很重要：它说明「部分学生 enable」不该当成临时灰度开关去做，它本来就该长期存在，所以不能沿用「上线完就删」的 release flag 模型，也不能塞进角色权限矩阵。

## 2. 同类系统怎么做的

### GitHub Feature Preview

GitHub 把预览功能放在个人 Settings 的 Feature preview 列表里，每项一个开关，用户自己勾选即启用、取消即退出。私有预览（private preview）不开放自助，需要被邀请或加入等待名单后才能看到入口。

- 来源：[GitHub Docs · 通过功能预览提早了解发行版](https://docs.github.com/zh/get-started/using-github/exploring-early-access-releases-with-feature-preview)
- 可借鉴：**自助开关 + 列表页**是标准形态；「公开预览」和「邀请制预览」并存，同一个列表承载两种来源。
- 代价：没有渠道追踪，谁从哪来的不可知。

### Google Labs

Google 在搜索、Workspace 里用 Labs 承载实验功能，用户在 Labs 页面主动报名加入，随时可以退出。部分实验需要申请或邀请。

- 来源：[Google 搜索帮助 · 在 Labs 中试用实验](https://support.google.com/websearch/answer/13572013)
- 可借鉴：**报名与退出都在同一个页面**，不靠邮件链接来回。这与用户拍板的「资料页里加一块实验功能」一致。
- 代价：Labs 页是独立入口，产品要额外维护一个页面。

### Flagsmith

数据模型是三层：**Environment 默认值 → Segment 覆盖 → Identity（个人）覆盖**，身份级覆盖优先级最高。Identity 上还可以挂 Traits（键值对），用来做分段条件。

- 来源：[Flagsmith Docs · Data Model](https://docs.flagsmith.com/flagsmith-concepts/data-model)
- 可借鉴：**三层解析顺序**是成熟做法，个人覆盖永远最高。本项目的对应是「角色默认 → 全局 kill switch → 用户 enrollment」。
- 代价：完整模型带 Organization / Project / Environment 三层归属，单校单环境用不上，只能裁剪成两层。

### Unleash

用「激活策略列表」表达放行规则：default、按 userId、按百分比、按 IP、按约束（constraint 表达式），任一策略命中即启用。

- 来源：[Unleash Docs · Activation strategies](https://docs.getunleash.io/concepts/activation-strategies)
- 可借鉴：规则是**数据**不是代码，加规则不改程序。这正是用户说的「业务逻辑与非业务逻辑解耦」。
- 代价：策略表达力强但运营界面重，且它是「管理员配」模型，没有用户自助 opt-in 的入口——这一点必须自己补。

### 托管型服务（LaunchDarkly / Statsig / GrowthBook / ConfigCat / OpenFeature）

同一份参考文档的对照表显示，这类服务的能力差异主要在「是否自带实验分析」「是否可自托管」。文档给的建议是：**flag 数量少于 50 时不要上多套系统，一套自建即可**；超过 10–15 个权限型 flag 时再考虑独立 entitlements 服务。

- 来源：[Flag Types and Patterns · Provider feature comparison](https://github.com/borghei/Claude-Skills/blob/ddca910/engineering/feature-flags-architect/references/flag-types-and-patterns.md)
- 对本项目的意义：现在 flag 只有 1 个（vocab），将来按每学期 1–3 个新功能的节奏，三五年也到不了 50。**引入外部 flag 服务是过度设计**。

### 一次性邀请 token（Logto magic link 一文）

Logto 用一次性 token 实现无密码登录、邀请制注册、组织成员邀请三类场景，核心是 token 一次性、有有效期、绑定接收主体。

- 来源：[Logto Blog · Magic link authentication](https://blog.logto.io/magic-link-authentication)
- 可借鉴：**token 绑人绑事、用一次即失效、有明确过期时间**。本项目 opt-in 链接应该是「一个 token 对应 一个用户 + 一个实验」，而不是一个公开的分享链接。
- 代价：token 过期或重复点击要有明确文案，否则用户以为链接坏了。

## 3. 取舍对比

| 方案 | 用户自助 | 渠道追踪 | 改完生效 | 引入成本 | 适配本项目 |
|---|---|---|---|---|---|
| 环境变量（现状） | ✗ | ✗ | 需重启 | 低 | 做不了「部分人」 |
| 复用角色权限矩阵 | ✗ | ✗ | 需发版 | 低 | 会把矩阵变垃圾场 |
| 外部 flag 服务（Unleash/Flagsmith） | 部分 | 部分 | 即时 | 高（多一个进程 + 运维） | 过重 |
| **自建 DB 表 + 一次性 token + 资料页出口** | **✓** | **✓** | **即时** | **中** | **选它** |

## 4. 对 TeachLoop 的结论

1. **定性**：这是 permission flag / beta program，长期存在，不是临时灰度开关。
2. **解析顺序**（与 Flagsmith 一致）：角色默认 → 全局 kill switch → 用户 enrollment 覆盖。
3. **存储**：Postgres 一张 enrollment 表，本地缓存（flag 数量少，缓存失效用版本号即可，不必上 Redis）。
4. **入口**：一次性 token 链接（一 token = 一用户 + 一实验 + 过期时间），点击后确认页，确认即写入 enrollment；退出统一在资料页的「实验功能」块。
5. **解耦**：业务代码只声明自己属于哪个 flag key，判定集中在后端一个 interceptor 和前端 Shell 一处，不在 service 里写 if。
6. **不引入**：外部 flag 服务、A/B 分流、百分比放量。这三样在当前规模下都没有收益。

## 5. 与现有实现的差距

| 现状 | 问题 |
|---|---|
| `FeaturesEndpoint` 构造时 `Map.of("vocab", ...)` | 所有登录用户拿到同一份，无法按人 |
| 10 个 `@ConditionalOnProperty` | 关闭时端点根本不注册（404），部分开启做不到 |
| `nav.ts` 里 if/else 判角色 + 开关 | 权限与功能发布混在一处 |
| 无 enrollment 表、无 token、无退出入口 | 本次全新的部分 |

## 6. 未定项

- 一次性 token 的有效期（行业常见 15 分钟到 7 天，邀请场景常给 7 天）
- 已 opt-in 的用户再点一次旧链接的行为（建议：直接提示「已加入」，不报错）
- 实验功能的菜单项是叠在现有菜单后面，还是单独一个「实验室」入口（影响导航视觉规范）
- 管理员是否需要看「谁加入了哪个实验」的名单（影响是否要管理页）
