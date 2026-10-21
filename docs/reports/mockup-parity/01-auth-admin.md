# 登录 / 资料补全 / 初始化与 AI 设置 对等性审计

审计范围：`interface.html:497-527`（login）、`530-540`（profile）、`544-590`（admin-ai），
外加 `462-489` 的左侧导航分组与 `Shell` 顶栏、i18n 横切。
实现基线：`web/src/App.tsx`、`web/src/app/{Shell,LoginPage,ProfilePage,SetupPage,AdminAiPage,NotificationsPage}.tsx`、
`web/src/i18n.ts`、`web/src/api/{auth.tsx,client.ts,types.ts}`；后端 `teachloop-identity`、`teachloop-classroom`、`teachloop-grading`。

忽略项（协议 §5）：mockup `interface.html:516` 的「二维码占位」是原型占位，只作为「微信流程缺失」的旁证，不单独计缺失；
mockup 左侧导航里的「登录」「资料补全」按钮是原型切屏装置，不作为 IA 要求；`#themebar` 不在本区域，未审计。

## 0. 汇总

| 屏 | 路由 | 结论 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- | --- |
| login · 登录 | `/login`（`web/src/App.tsx:65`） | 部分缺失 | 1 | 1 | 1 | 2 |
| profile · 资料补全 | `/profile`（`web/src/App.tsx:86`） | 部分缺失 | 0 | 1 | 2 | 3 |
| admin-ai · 初始化与 AI 设置 | `/setup`（`App.tsx:66`）+ `/admin/ai`（`App.tsx:85`） | 部分缺失 | 0 | 2 | 2 | 9 |
| 导航与顶栏（横切） | `web/src/app/Shell.tsx` | 部分缺失 | 0 | 1 | 1 | 6 |
| i18n（横切） | `web/src/i18n.ts` | 部分缺失 | 0 | 0 | 5 | 1 |

## 1. login · 登录

- 路由与入口：`/login`（`web/src/App.tsx:65`）；未登录访问任意受保护路由由 `RequireAuth` 重定向到此（`web/src/App.tsx:31-35`）。导航可见性：仅未登录用户；已登录访问 `/login` 被弹回 `/`（`App.tsx:65`）。
- 主操作可达性：**不能完整成立**。管理员邮箱密码可登录；微信扫码登录在生产 profile 下缺失，而生产 profile 的密码登录只放行 ADMIN（`teachloop-identity/.../api/AuthEndpoint.java:68-80`），因此教师、学生在生产环境没有任何可用登录方式。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 副标题「微信扫码为主，管理员用邮箱密码」(499) | 改为「开发期用邮箱密码登录；微信扫码在接入后启用」 | `web/src/i18n.ts:28`、`web/src/i18n.ts:53`；`LoginPage.tsx:43-44` | P3 |
| 2 | 左侧卖点「让批改回到教学」+ 三条理由(501-508) | 三条等价存在，首条措辞略简（「AI 判分时看不到」） | `web/src/app/LoginPage.tsx:33-41` | OK |
| 3 | 「微信登录」tab，默认选中(512) | 无任何 tab；页面上没有微信入口 | `LoginPage.tsx:42-56` 只有邮箱密码表单；全目录搜 `微信登录`/`管理员登录`/`wechat` 在 `web/src` 仅命中 `i18n.ts:28,53` 文案；`web/src/styles/mockup.css:182-188` 的 `.tabs`/`.qr` 样式无组件使用 | P0 |
| 4 | 二维码区(516) | 原型占位，按协议 §5 忽略；实现侧无等价微信登录界面 | 同上 | OK |
| 5 | 「请用微信扫码」(517) | 无对应提示 | 同上 | OK（并入 #3） |
| 6 | 「管理员登录」tab + 邮箱/密码/登录按钮(513,519-522) | 邮箱、密码、全宽登录按钮齐全，含提交中禁用 | `LoginPage.tsx:45-54` | OK |
| 7 | 首次部署提示「从『初始化 / AI 设置』创建管理员；之后只能登录，不能自助注册」(523) | 缺失；`login.hint` 被换成开发账号提示，全站也没有任何指向 `/setup` 的链接 | `web/src/i18n.ts:29`；`LoginPage.tsx:55`；grep `/setup` 在 `web/src` 仅命中路由定义 `App.tsx:66`（无 `to=`/`href=`） | P1 |
| 8 | 登录失败提示（错误态） | 有：直接显示后端 message（含「邮箱或密码不正确」）；429 只显示「请求过于频繁，请稍后再试」，不显示 Retry-After 秒数 | `LoginPage.tsx:51`；`web/src/api/client.ts:40-48`；`teachloop-identity/.../rate/RateLimitFilter.java:116-123` | P3 |
| 9 | 不提供自助注册（523 的反面） | 前端无注册入口，符合 | grep `注册`/`register` 在 `web/src/app` 无命中 | OK |
| 10 | 无（mockup 输入框为空） | 邮箱/密码被硬编码预填开发账号 | `LoginPage.tsx:12-13` | P2 |

### 差异说明

- #3：微信扫码是 mockup 的主路径（tab 默认选中），实现完全没有任何微信 UI；后端已有 `POST /api/auth/wechat`（`AuthEndpoint.java:84-90`），但需要外部 OAuth 取 `code`，前端既无 tab 也无扫码流程，生产环境教师/学生无法登录，属核心任务不可完成。
- #7：mockup 唯一的首次部署指引在实现中丢失，而 `/setup` 页面又没有导航或链接可达，首次部署者只能靠猜 URL。
- #8：限流已实现且返回 `Retry-After`，但前端丢弃响应头，用户看不到还需等待多久。
- #10：开发账号写死在初始 state，生产构建里也会预填，既误导用户也暴露账号命名规则。

## 2. profile · 资料补全

- 路由与入口：`/profile`（`web/src/App.tsx:86`），入口是顶栏「资料」按钮（`web/src/app/Shell.tsx:75`）；mockup 把它放在导航「通用」组（`interface.html:465`）。导航可见性：所有登录用户；未登录被 `RequireAuth` 拦回 `/login`。
- 主操作可达性：能保存（手机号+邮箱必填，前后端都有约束），但保存后不进入下一步，也没有 mockup 要求的「退出登录」。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 页标题「补全资料」+ 副标题「首次登录后必须完成，用于通知与账号找回」(531-532) | 标题「资料补全」，副标题「首次登录后必须补全手机号与邮箱才能使用其他功能。」 | `web/src/app/ProfilePage.tsx:44-45` | OK |
| 2 | 拦截说明「这一步不能跳过。未补全前，除本页与退出外其余页面都会被拦截」(534) | 只有一句「必须补全…才能使用其他功能」，没有「除本页与退出外」的范围限定 | `ProfilePage.tsx:45` | P3 |
| 3 | 拦截是否真实现（534 的行为要求） | 后端有 `ProfileCompletionFilter`，但仅 `application-prod.yml` 开启（默认 false）；前端无 403/`PROFILE_INCOMPLETE` 处理，登录后一律 `navigate('/')`，`Me` 里也没有「资料是否完成」字段，未补全用户不会被送到本页，只会在别的页面看到错误卡 | `teachloop-identity/.../profile/ProfileCompletionFilter.java:44-56`；`teachloop-identity/.../security/SecurityConfig.java:79-81`；`teachloop-app/src/main/resources/application-prod.yml:13`；`LoginPage.tsx:23`；`web/src/api/types.ts:4-10`；grep `PROFILE_INCOMPLETE` 在 `web/src` 0 命中 | P1 |
| 4 | 手机号（必填 *）(535) | 输入框存在，必填由后端 `@NotBlank` + 按钮禁用来保证，但无星号 | `ProfilePage.tsx:53-55`、`ProfilePage.tsx:59`；`teachloop-identity/.../profile/ProfileEndpoint.java:24` | OK |
| 5 | 手机号 hint「用于成绩发布通知，v1 不发送验证码」(536) | 缺失 | grep `不发送验证码`/`验证码` 在 `web/src` 0 命中 | P3 |
| 6 | 邮箱（必填 *）(537) | 输入框存在，`type=email` | `ProfilePage.tsx:56-58` | OK |
| 7 | 两个字段的必填星号 `*`(535,537) | 页面上没有任何 `*` 必填标记 | `ProfilePage.tsx:50-58` | P3 |
| 8 | 「保存并进入」按钮(538) | 文案为「保存」，保存成功只显示「已保存」，不跳转、不刷新资料完成状态 | `ProfilePage.tsx:30-40`、`ProfilePage.tsx:59-61` | P2 |
| 9 | 「退出登录」按钮(538) | 本页没有；退出只在侧栏底部 | `Shell.tsx:65-70`（`ProfilePage.tsx:42-65` 无退出） | P2 |
| 10 | 无（mockup 无姓名字段） | 多出「姓名」字段，可写回 | `ProfilePage.tsx:50-52`；`ProfileEndpoint.java:46-48` | OK（扩展） |
| 11 | 微信用户首次补邮箱（本屏用途） | 接口语义支持按用户写回邮箱 | `ProfileEndpoint.java:13`、`ProfileEndpoint.java:36-53` | OK |
| 12 | 邮箱重复等校验反馈 | 后端 409「该邮箱已被使用」透出到页面 | `ProfileEndpoint.java:42-43`；`ProfilePage.tsx:37-39` | OK |

### 差异说明

- #3：mockup 说「其余页面都会被拦截」，实现只做了一半——后端在 prod 才拦，前端既不识别拦截结果也不把人送到本页，开发/测试环境完全无拦截；用户看到的是各业务页的红色错误卡，而不是被带回资料补全。
- #8：主操作链在「保存」处断开，mockup 的「保存并进入」意味着保存即离开本页。
- #9：mockup 明确要求本页可退出；虽然侧栏有退出，但在被拦截状态下用户不该依赖侧栏。
- #5、#7：必填与用途说明缺失，用户不知道手机号拿来做什么、也看不出哪些字段必填。

## 3. admin-ai · 初始化与 AI 设置

- 路由与入口：mockup 是一屏三段（`interface.html:544-590`）；实现拆成 `/setup`（`App.tsx:66`，公开路由、无导航入口）与 `/admin/ai`（`App.tsx:85`，仅 ADMIN 导航可见，`Shell.tsx:23`）。
- 主操作可达性：管理员创建与 AI 配置都能完成；但首次部署的「创建管理员 → 配模型」一次性流程被拆到两个地址，且 `/setup` 没有任何链接入口。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 一屏两段「① 创建首个管理员」「② AI 大模型」+「保存之后」(548-589) | 拆为 `/setup`（仅 ①）与 `/admin/ai`（仅 ②），③ 整块没有 | `App.tsx:66`、`App.tsx:85`；`SetupPage.tsx:35-60`；`AdminAiPage.tsx:53-81` | P2 |
| 2 | ① 状态行「当前状态：还没有管理员（首次部署）。创建后此入口自动关闭，只保留登录。」(549) | `needsAdmin` 时没有状态行；已有管理员时才显示「系统已有管理员…」 | `SetupPage.tsx:45-51`（无状态行）、`SetupPage.tsx:52-58` | P3 |
| 3 | ① 管理员姓名(552) | 有，默认值「管理员」 | `SetupPage.tsx:13`、`SetupPage.tsx:47` | OK |
| 4 | ① 邮箱必填 *(553) | 有输入框与 `type=email`，但无星号、无前端格式校验（按钮只判非空） | `SetupPage.tsx:48`、`SetupPage.tsx:50` | P3 |
| 5 | ① 密码必填 *(555) | 有，label 已写「至少 8 位」 | `SetupPage.tsx:49` | OK |
| 6 | ① 密码规则「至少 8 位；服务端只存密码哈希，不存明文」(556) | 长度规则在 label，哈希说明在页首说明；后端 `@Size(min=8)` + BCrypt 编码 | `SetupPage.tsx:38`、`SetupPage.tsx:49`；`teachloop-identity/.../ai/SetupEndpoint.java:29-30`、`SetupEndpoint.java:63-64` | OK |
| 7 | ① 「创建管理员」按钮(557) | 有，邮箱非空且密码≥8 才可点 | `SetupPage.tsx:50` | OK |
| 8 | ① 「创建后此入口自动关闭」(549) | 后端在已有 ADMIN 时 409；前端在已有管理员时隐藏表单 | `SetupEndpoint.java:54-56`；`SetupPage.tsx:52-57` | OK |
| 9 | ① 创建完成后的去向 | 后端顺手建了会话，前端不刷新 auth，页面只给「去登录」，点进去仍停在登录页 | `SetupEndpoint.java:65-66`；`SetupPage.tsx:24-25`、`SetupPage.tsx:40-44` | P3 |
| 10 | ① 首次部署入口本身可达 | `/setup` 无导航项、无登录页链接，只能手输 URL | grep `/setup` 在 `web/src` 仅 `App.tsx:66`；`Shell.tsx:16-29` 无该项 | P1 |
| 11 | ② 状态行「当前：DeepSeek / deepseek-flash · Key 已配置 sk-****1234 · 保存后需重启 app 与 worker 生效」(562-564) | 有 provider/model/Key 掩码/更新时间；缺「需重启 app 与 worker」（只在保存成功后提到「重启应用」） | `AdminAiPage.tsx:59-61`、`AdminAiPage.tsx:79` | P3 |
| 12 | ② 说明「支持 DeepSeek 与 OpenAI 兼容端点。Key 使用 AES-GCM 加密存库，页面永远只显示掩码」(565) | 有兼容端点与「加密形式存库、不会回显」，未写 AES-GCM | `AdminAiPage.tsx:56`；后端确为 `AES/GCM/NoPadding`（`teachloop-identity/.../ai/SecretBox.java:30`、`SecretBox.java:39-42`） | P3 |
| 13 | ② Provider 下拉（DeepSeek / OpenAI 兼容）(567-568) | 两个选项齐全 | `AdminAiPage.tsx:63-68` | OK |
| 14 | ② Base URL(569) | 有，后端校验必须 http(s) 开头 | `AdminAiPage.tsx:69`；`teachloop-identity/.../ai/AiSettingsService.java:70-77` | OK |
| 15 | ② 模型(572) | 有 | `AdminAiPage.tsx:70` | OK |
| 16 | ② Temperature(573) | 有，`min=0 max=1 step=0.1`，后端也校验 0–1 | `AdminAiPage.tsx:71`；`AiSettingsService.java:79-84` | OK |
| 17 | ② API Key 必填 *(575) | 有 password 输入，label 已含「留空表示不修改」 | `AdminAiPage.tsx:72-74` | OK |
| 18 | ② Key hint「留空表示不修改；保存后只回掩码，不回明文。加密密钥来自 TEACHLOOP_SECURITY_SECRET」(576) | 留空与只回掩码有；`TEACHLOOP_SECURITY_SECRET` 这个名字在全前端没有出现 | `AdminAiPage.tsx:72-74`、`AdminAiPage.tsx:56`、`SecretBox.java:24-26`；grep `TEACHLOOP_SECURITY_SECRET` 在 `web/src` 0 命中 | P3 |
| 19 | ② hint「未配置 Key 时真实判分会明确失败，不会给学生假分数；本地演示可用 stub 引擎」(577) | 缺失 | grep `假分数`/`stub` 在 `web/src` 0 命中 | P3 |
| 20 | ② 「保存」按钮(578) | 有，保存后提示「重启应用后新的 AI 配置生效」 | `AdminAiPage.tsx:76`、`AdminAiPage.tsx:79` | OK |
| 21 | ② 「测试连接」+ 结果显示耗时(578-579) | 有，显示通过/失败、message、`latencyMs` | `AdminAiPage.tsx:77`、`AdminAiPage.tsx:80`；后端计时并返回 `latencyMs`（`AiSettingsService.java:98-107`） | OK |
| 22 | ② 当前状态「已配置 / 未配置」(563) | 有，`Key 已配置 sk-****1234` / `未配置` | `AdminAiPage.tsx:60`；掩码算法 `AiSettingsService.java:134-141` | OK |
| 23 | ③「保存之后」三条规则(585-587) | 整块缺失；仅第 1 条的「重启应用」在保存提示里出现一次，worker 未提，后两条完全没有 | `AdminAiPage.tsx:53-81`（无该卡片）；grep `worker`/`无法解密`/`重新填写` 在 `web/src/app` 0 命中 | P2 |
| 24 | ③「管理员仍可从左侧『教师』入口查看班级、复核与报表」(587) | 菜单可见、复核队列对 ADMIN 放行，但班级列表对 ADMIN 恒为空（按 `teacher_id = me.id()` 过滤） | `Shell.tsx:16-24`、`Shell.tsx:30`；`teachloop-grading/.../ReviewEndpoint.java:110-115`；`teachloop-classroom/.../ClassEndpoint.java:59-62` | P1 |
| 25 | 保存后「需重启」状态呈现（restartRequired） | 接口 `View` 带 `restartRequired`（更新后为 true），前端类型声明了但从不渲染 | `AiSettingsService.java:51-56`、`AiSettingsService.java:91-94`；`AdminAiPage.tsx:12`（未使用） | P3 |
| 26 | 仅管理员可进入本屏 | 后端 `/api/admin/**` 要求 ADMIN；前端路由无角色守卫，非管理员直达 URL 只会得到 403 错误卡 | `teachloop-identity/.../security/SecurityConfig.java:64`；`AiSettingsEndpoint.java:42-46`；`App.tsx:85` | P3 |
| 27 | ② 保存后是否需要重新填写 Key（Key 已存在的编辑态） | 有：「留空表示不修改」+ 掩码 | `AdminAiPage.tsx:29`、`AdminAiPage.tsx:72-74` | OK |

### 差异说明

- #1：mockup 把「创建管理员」与「配模型」设计成同一次部署动线；实现拆成两个地址，`/setup` 完成后只给「去登录」，AI 配置要登录后自己找菜单。
- #10：`/setup` 在登录页和侧栏都没有入口，与 #2 的「入口自动关闭」叠加后，首次部署者没有任何可发现的路径。
- #23：mockup 用一整张卡说明三条运营规则，其中「密钥丢失则已存 Key 无法解密」和「管理员仍可从教师入口查看」都涉及操作安全与权限预期；实现只留下「重启应用生效」一句半。
- #24：mockup 明确承诺管理员可用教师入口看班级，实现里该承诺不成立——菜单在、接口也在，但 `/api/classes` 只查 `teacher_id = 当前用户`，ADMIN 没有自己名下的班级，`/classes` 因此是空列表。
- #11、#18、#19：三条都是安全/运维说明，其中 `TEACHLOOP_SECURITY_SECRET` 名称缺失会让运维在换机时不知道 Key 为何解不开。
- #9：创建成功后后端已建会话而前端未同步，用户点「去登录」会看到登录页（已登录又被弹回去），属于流程毛刺。

## 4. 导航与顶栏（横切）

- 路由与入口：侧栏由 `web/src/app/Shell.tsx:16-30` 按角色给出；mockup 的导航分组在 `interface.html:462-489`。
- 主操作可达性：一级入口都能点；但 mockup 的一级/二级划分与班级页内的二级入口没有对齐。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 左侧只保留一级入口；二级页从行操作或按钮进入(494) | 实现有 6 个一级入口，多出「试卷」 | `Shell.tsx:16-24`；mockup 一级仅 469-473 | P3 |
| 2 | 二级页「导入试卷」「新建试卷流程」从上级进入(481-482) | 入口只在 `/papers` 页头；mockup 班级页里的「新建作业」「导入试卷」按钮在实现的班级页不存在 | `PapersPage.tsx:110-113`；`interface.html:696-697`；`ClassesPage.tsx:120-132`（只有 成员/邀请码/停用 类动作）、`ClassesPage.tsx:216-241` | P2 |
| 3 | 复核工作台角标 18(472) | 侧栏无任何计数；只有顶栏通知计数 | `Shell.tsx:54-63`（NavLink 无 badge）、`Shell.tsx:76-78` | P3 |
| 4 | 导航分组「通用 / 管理员 / 教师 / 学生」(463-479) | 平铺一列；ADMIN 看到教师菜单 + 「AI 设置」追加在末尾，没有分组语义 | `Shell.tsx:16-24`、`Shell.tsx:30` | P3 |
| 5 | 管理员同时拥有教师入口(587) | 是：非 STUDENT 一律用 `teacherNav`，ADMIN 额外加 AI 设置 | `Shell.tsx:16-24`、`Shell.tsx:30` | OK |
| 6 | 学生菜单（考前须知/考试作答/作业提交/我的反馈/班级概览）(474-479) | 导航只有三个：首页 / 班级 / 我的反馈；考试作答与作业提交由学生首页卡片进入，属等价动线；「班级概览」缺失另见 #7 | `Shell.tsx:25-29`；`student/HomePage.tsx:77-92` | OK |
| 7 | 学生「班级概览」屏(479, 1411+) | 没有独立页面/路由；只有反馈详情里的一小块「全班概览」，且文案自述后端没有学生概览接口 | `student/FeedbackDetailPage.tsx:156-171`；`student/locales.ts:154`；`App.tsx:64-93` 无该路由 | P1 |
| 8 | 顶栏（mockup 无） | 新增「资料」「通知」「中文/EN」与邮箱显示 | `Shell.tsx:73-83` | P3 |
| 9 | 资料补全的定位（被拦截页） | 被做成常驻入口，用户可在未补全状态下反复进出 | `Shell.tsx:75`、`App.tsx:86` | P3 |
| 10 | 通知（mockup 无此屏） | 新增 `/notifications` 一级入口与后端通知接口，属合理扩展 | `App.tsx:84`、`Shell.tsx:76-78`；`teachloop-identity/.../api/NotificationEndpoint.java:39-70`；mockup 内无「通知」屏（grep `通知` 仅 532/536/749 文案） | OK（扩展） |
| 11 | 退出登录 | 侧栏底部按钮，调用 `/api/auth/logout` 后回登录页 | `Shell.tsx:65-70`；`web/src/api/auth.tsx:40-46` | OK |
| 12 | 当前用户标识 | 显示 `displayName · role` 原始枚举（如 `ADMIN`）与邮箱 | `Shell.tsx:66`、`Shell.tsx:82` | P3 |

### 差异说明

- #2：mockup 把「新建作业/导入试卷」放在班级页（这是教师日常动线：先进班再建作业）；实现把两者挪进 `/papers` 页头，班级页没有任何方式直接建作业或导入试卷。
- #7：学生缺少 mockup 的「班级概览」整屏，只有反馈详情内的局部概览，且实现自己注明后端无学生概览接口——属缺页面 + 缺接口，需与学生区结论合并处理。
- #1、#8、#9：`/papers`、`/profile`、`/notifications` 三个新增一级入口都属合理扩展，但叠加起来让 mockup「左侧只保留一级入口、资料补全是被拦截页」的 IA 语义变淡；其中 `/notifications` 有后端支撑，是本区域唯一有接口兜底的新增入口。
- #3、#4、#12：属于原型细节，不阻塞任务，但侧栏无待复核角标会削弱「今天该做什么」的提示。

## 5. i18n 横切

- 路由与入口：语言切换在顶栏（`Shell.tsx:79-80`），资源在 `web/src/i18n.ts`（zh/en 两套，含 teacher/exam/student 子资源）。
- 主操作可达性：能切换，能刷新记忆（localStorage），但四个本区域页面里三个完全没有 i18n。

### 功能点对照

| # | 要求 | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 中英双语可切换（spec AC-15.x / plan §11） | 有 `zh-CN`/`en` 资源与切换按钮 | `i18n.ts:58-72`；`Shell.tsx:44-47`、`Shell.tsx:79-80` | OK |
| 2 | 登录页文案走 i18n | 标题/标签/按钮走 `t()`，但左侧三条卖点硬编码中文 | `LoginPage.tsx:43-55`；`LoginPage.tsx:36-40` | P2 |
| 3 | 资料补全页文案走 i18n | 整页硬编码中文，无一处 `t()` | `ProfilePage.tsx:44-61` | P2 |
| 4 | 初始化页文案走 i18n | 整页硬编码中文 | `SetupPage.tsx:37-56` | P2 |
| 5 | AI 设置页文案走 i18n | 整页硬编码中文 | `AdminAiPage.tsx:55-80` | P2 |
| 6 | 语言偏好持久化到用户资料（plan §11「语言存用户资料与 localStorage」） | 只写 localStorage；资料接口收 `locale` 但前端从不发送；`Me.locale` 也从不用于初始化界面语言 | `i18n.ts:70`；`Shell.tsx:44-47`；`ProfileEndpoint.java:24-25`、`ProfileEndpoint.java:49-51`；`ProfilePage.tsx:34`；grep `.locale` 在 `web/src` 仅类型定义与 `DataTable.tsx:37` 排序 | P2 |
| 7 | 服务端错误消息语言 | 后端固定返回中文（限流、403、409 等），英文界面下错误提示仍是中文 | `RateLimitFilter.java:117`；`ProfileEndpoint.java:43`；`AuthEndpoint.java:78` | P3 |
| 8 | 语言自名（中文 / EN） | 按钮文案硬编码，属可接受的语言自称 | `Shell.tsx:79-80` | OK |

### 差异说明

- #2–#5：本区域四屏中三屏完全没走 i18n，直接违反 spec AC-15.3「代码中不得硬编码用户可见文案」；英文用户进入资料补全或 AI 设置会看到整页中文。
- #6：mockup 的「反馈语言」字段依赖用户语言偏好，实现里语言只存在于浏览器 local storage，资料页保存时也不回写，服务端无法据此决定通知/反馈语言。
- #7：错误消息是后端固定中文，与界面语言无关；spec 只要求错误码稳定，故按 P3 记。

## 6. 本区域阻塞项

| 问题 | 缺的是什么（接口 / 字段 / 组件） | 建议处理 |
| --- | --- | --- |
| 生产环境教师/学生无法登录（`AuthEndpoint.java:68-80` 只放行 ADMIN；微信 UI 缺失） | 缺前端：微信登录 tab、扫码流程与二维码渲染；后端已有 `POST /api/auth/wechat`，但缺「取扫码 code 的登录票据/回调」接口 | 补微信登录 tab 与票据接口；未配置 `teachloop.wechat.*` 时按钮置灰并提示「微信登录未配置」 |
| 资料未补全无前端引导（`RequireAuth` 只看 `me`，`Me` 无完成标记） | 缺字段：`Me.profileComplete`（或 `phone`）；缺前端 403/`PROFILE_INCOMPLETE` 处理与重定向 | `Me` 增加完成标记；`RequireAuth` 命中时 `navigate('/profile')`；保留后端拦截作为兜底 |
| 管理员看不到班级（`ClassEndpoint.java:59-62` 按 `teacher_id = me.id()`） | 缺接口能力：`/api/classes` 对 ADMIN 的 scope 或「管理员查看全部班级」参数 | 明确管理员是否代管班级；若承诺如 mockup，需要 admin scope 查询，否则改掉 `interface.html:587` 的承诺文案 |
| 学生「班级概览」屏缺失（`interface.html:479,1411`） | 缺页面 + 缺接口（`student/locales.ts:154` 自述无学生概览 API） | 交学生区确认，与教师端「是否公布他人分数」开关一起设计后再补页面 |
| 首次部署入口不可达（`/setup` 无链接，`App.tsx:66`） | 缺入口：登录页没有「首次部署」链接，也没有内嵌创建管理员表单 | 在登录页管理员 tab 内放「首次部署？创建管理员」链接，或把创建表单嵌回登录页 |
