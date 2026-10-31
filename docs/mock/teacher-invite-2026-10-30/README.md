# 教师邀请码原型（2026-10-30）

对应需求：教师入驻走邀请码（承接 `AC-3.2`）、`AC-3.3` 改成「真升级、只能升自己」。
单文件、无依赖、无构建：**双击 `index.html` 就能点**。演示数据全部是假的（侧栏底部有标注）。

## 已拍板（2026-10-30，用户三轮回答）

| 问题 | 决定 | 落在哪 |
| :--- | :--- | :--- |
| 邀请码界面放哪 | **单开 `/admin/teachers` 一页**，侧栏加「教师管理」 | 第 1 屏；用户列表/改角色 014 也进这一页 |
| 注册页要不要出现邀请码 | **一个字都不改**：不加字段，也不加指路文案，就「创建账号 / 我已注册」两个按钮 | 原型**没有**注册页那一屏（做过、被否，已删） |
| 升级后怎么变 | **即时刷新，不用重新登录** | 第 3 屏；实现要给 `features.tsx` 的 effect 补 `me?.role` 依赖 |
| 老师怎么有账号 | 先按平常流程注册（那时就是学生），**登录后**在 `/profile` 填码升级 | 第 2 屏；不保留「凭码开户」那条腿 |

为什么砍掉注册页那条腿：不为 1% 的老师在所有注册页上挂一个陌生字段；而且它会在公开的
`POST /api/auth/register`（`RegistrationEndpoint.java:64` 只有 channel/email/password/displayName）
里开一个「有码就建 TEACHER」的分支，与 `AC-73.9`「注册只能 STUDENT」正面冲突，得永久背一条例外。
砍掉后这条端点可以直接收紧成**必须登录**，未登录 401。

## 这一版在回答什么

1. **`/admin/teachers` 长什么样**：上面是邀请码（生成 / 列表 / 复制 / 撤销），下面是「下一版：用户列表」灰态占位，一眼看出这页以后会长成什么。
2. **码是常驻列表而不是只弹一次**：今天只有 `POST /api/admin/teacher-invites`（`AdminEndpoint.java:34`），没有 GET，生成后刷新就找不回码。
3. **学生端入口在 `/profile` 资料卡下面独立一块**，不是塞进资料表单。
4. **升级后侧栏当场变**，并且 —— 关键 —— 第 3 屏里**「背单词」还在**（见下节）。

## 升级后，学生期的记录和入口（2026-10-30 用户提问）

三层要分开看，只有第一层靠代码"自动成立"：

| 层 | 现状 | 依据 |
| :--- | :--- | :--- |
| **数据** | 全在 —— 只要做「真升级」 | 学习数据全按 `user_id` 挂（`ReviewPrefsStore.java:36,53`）；升级是翻 `users.role` 一列，id 不变 |
| **接口** | 教师能调，没被角色挡 | vocab 只走 `SecurityConfig.java` 的 `.anyRequest().authenticated()`；路由只受 `features.vocab` 开关（`App.tsx:210`） |
| **入口** | **今天会丢** | `RoleNavMatrix.ROLE_KEYS` 里 TEACHER 只有 `nav.vocab.sets / nav.vocab.assign`（发词用），没有 `nav.vocab.review / lists / library`；前端不做角色判断，所以必须后端加 key |

补充两点容易漏的：

- **今天的升级代码不是真升级**：`AuthEndpoint.java:155-160` 兑换时 `new User(..., TEACHER, ...)`，邮箱已注册直接 409 —— 按现状，老师要么开新号（旧记录留在废号上），要么根本升不了。这正是要改它的原因。
- **落地页也一起变**：`HomeRouter`（`App.tsx:128`）按角色分流，教师落在跨班首页，「今日」页不再出现。所以即便侧栏放出了背单词，教师首页也没有「继续复习」的入口。

## 屏与深链

| 屏 | 深链 | 重点看什么 |
| :--- | :--- | :--- |
| 管理端 · 教师管理 | `#admin` | `/admin/teachers`：生成 / 列表（可用·已兑换·已过期·已撤销）/ 复制 / 撤销；底部「下一版：用户列表（本轮不做）」灰态占位 |
| 学生端 · 填码升级 | `#redeem` | 输入 8 位码 → 成功态 + 侧栏当场变成教师；四种失败文案（无效 / 已过期 / 已兑换 / 已撤销） |
| 升级后 | `#after` | 侧栏多出教师菜单（带「新」标）、**「背单词」仍在**、顶部成功提示 |

演示码：`K7QM4XZP`（可用）、`2FB9WDRT`（可用）；其余三条分别演示「已过期 / 已兑换 / 已撤销」。
右上角「重置演示」把状态打回初始值。

## 定稿参数

| 项 | 值 | 说明 |
| :--- | :--- | :--- |
| 路由 | `/admin/teachers` | 单开一页；侧栏 label「教师管理」，图标一组人 |
| 码形 | 8 位，去掉易混字符（无 `0/O/1/I`） | 与 `AdminService.newCode()` 的字母表一致 |
| 有效期 | 3 天 | 后端 `createTeacherInvite(me.id(), 3)` 已是这个值 |
| 归属 | 生成它的管理员 | `TeacherInvite.createdBy` 已有 |
| 撤销 | 未兑换才可撤销 | 已兑换的不撤账号（撤账号走「停用账号」） |
| 列表 | 常驻，含状态与兑换人 | 需补 `GET /api/admin/teacher-invites` |
| 升级语义 | 登录态 → 只升自己，忽略请求体邮箱；未登录 401 | `AC-3.3` 新语义；非 STUDENT 返回 409 |
| 提权边界 | 码只能升到 TEACHER，永不 ADMIN | `AC-3.5` 不变；管理员由管理员在后台授予（014） |
| 审计 | 每次成功兑换写一条 | 谁 / 什么码 / 何时 / 从哪个角色 |
| 升级后 | 即时刷新，不重登；背单词入口保留 | `features.tsx` 依赖加 `me?.role`；`RoleNavMatrix` 给 TEACHER 补 `nav.vocab.*` |

## 注意 / 取舍

- 平板（≤1023）把侧栏收成抽屉，原型里直接收起 —— 与 `web/src/styles/app.css` 同一个断点，不自造第三档。
- 不做二维码、不做一键发送（没有可用 IM 通道）、不做批量生成。
- 「下一版：用户列表」是灰态占位，只用来钉边界，本轮不实现。

## 截图

`shots/` 下 6 张：3 张桌面（1440×1180：admin / redeem / after）+ 3 张平板
（`tablet-834-admin` / `tablet-834-redeem` / `tablet-1024-admin`）。

```bash
cd docs/mock/teacher-invite-2026-10-30
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for s in admin redeem after; do
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
    --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2000 \
    --window-size=1440,1180 --screenshot="$PWD/shots/$s.png" \
    "file://$PWD/index.html?shot=$s"
done
```

三档（1440 / 1024 / 834）实测无横向溢出：`scrollWidth - innerWidth = 0`。
