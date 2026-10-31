# 教师邀请码原型（2026-10-30）

对应需求：教师入驻走邀请码（承接 `AC-3.2`）、`AC-3.3` 改成「真升级、只能升自己」。
单文件、无依赖、无构建：**双击 `index.html` 就能点**。演示数据全部是假的（侧栏底部有标注）。

## 已拍板（2026-10-30，用户三点回答）

| 问题 | 决定 | 落在哪 |
| :--- | :--- | :--- |
| 邀请码界面放哪 | **单开 `/admin/teachers` 一页**，侧栏加「教师管理」 | 本原型的第 1 屏；用户列表/改角色 014 也进这一页 |
| 注册页上的码 | **可选字段**（填了建教师、不填建学生） | 第 3 屏；`AC-73.9` 要补一句例外 |
| 升级后怎么变 | **即时刷新，不用重新登录** | 第 4 屏；实现要给 `features.tsx` 的 effect 补 `me?.role` 依赖 |

## 这一版在回答什么

1. **`/admin/teachers` 长什么样**：上面是邀请码（生成 / 列表 / 复制 / 撤销），下面是「下一版：用户列表」的占位，让人一眼看出这页以后会长成什么。
2. **码是常驻列表而不是只弹一次**：今天只有 `POST /api/admin/teacher-invites`（`AdminEndpoint.java:34`），没有 GET，生成后刷新就再也找不回码；列表要配一条 GET。
3. **学生端入口在 `/profile` 资料卡下面独立一块**，不是塞进资料表单。
4. **两条腿都要有**：已登录学生填码升自己；还没账号的新老师可在注册页凭码开户 —— 否则「真升级」意味着老师必须先装作学生注册一次。
5. **升级后侧栏当场变**（第 4 屏），并给出成功提示。

## 屏与深链

| 屏 | 深链 | 重点看什么 |
| :--- | :--- | :--- |
| 管理端 · 教师管理 | `#admin` | `/admin/teachers`：生成 / 列表（可用·已兑换·已过期·已撤销）/ 复制 / 撤销；底部「下一版：用户列表（本轮不做）」灰态占位 |
| 学生端 · 填码升级 | `#redeem` | 输入 8 位码 → 成功态 + 侧栏当场变成教师；四种失败文案（无效 / 已过期 / 已兑换 / 已撤销） |
| 注册页 · 凭码开户 | `#register` | 公开页无壳层；邀请码是**可选字段** |
| 升级后 | `#after` | 侧栏多出教师菜单（带「新」标）+ 顶部成功提示 |

演示码：`K7QM4XZP`（可用）、`2FB9WDRT`（可用）；其余三条分别演示「已过期 / 已兑换 / 已撤销」。
右上角「重置演示」把状态打回初始值。

## 定稿参数

| 项 | 值 | 说明 |
| :--- | :--- | :--- |
| 路由 | `/admin/teachers` | 单开一页；侧栏项 label「教师管理」，图标一组人 |
| 码形 | 8 位，去掉易混字符（无 `0/O/1/I`） | 与后端 `AdminService.newCode()` 的字母表一致 |
| 有效期 | 3 天 | 后端 `createTeacherInvite(me.id(), 3)` 已经是这个值 |
| 归属 | 生成它的管理员 | `TeacherInvite.createdBy` 已有 |
| 撤销 | 未兑换才可撤销 | 已兑换的码不撤销账号（撤账号走「停用账号」） |
| 列表 | 常驻，含状态与兑换人 | 需要补 `GET /api/admin/teacher-invites`（今天只有 POST） |
| 升级语义 | 登录态 → 只升自己，忽略请求体邮箱 | `AC-3.3` 新语义；非 STUDENT 返回 409 |
| 未登录 | 码 + 邮箱 + 密码 → 建 TEACHER | 旧路径保留，同一个码、同样一次性 |
| 提权边界 | 码只能升到 TEACHER，永不 ADMIN | `AC-3.5` 不变；管理员由管理员在后台授予（014） |
| 审计 | 每次成功兑换写一条 | 谁 / 什么码 / 何时 / 从哪个角色 |
| 升级后 | 即时刷新，不重登 | `features.tsx` 的 effect 依赖要加 `me?.role` |

## 注意 / 取舍

- 平板（≤1023）把侧栏收成抽屉，原型里直接收起 —— 与 `web/src/styles/app.css` 同一个断点，不自造第三档；实测三档无横向溢出。
- 不做二维码、不做一键发送（没有可用 IM 通道）、不做批量生成：码走复制粘贴，一个人一天发不出十张。
- 「下一版：用户列表」是**灰态占位**，只用来告诉 review 这页的边界，本轮不实现。

## 截图

`shots/` 下 7 张：4 张桌面（1440×1180：admin / redeem / register / after）+ 3 张平板
（`tablet-834-admin` / `tablet-834-redeem` / `tablet-1024-admin`）。

```bash
cd docs/mock/teacher-invite-2026-10-30
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for s in admin redeem register after; do
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
    --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2000 \
    --window-size=1440,1180 --screenshot="$PWD/shots/$s.png" \
    "file://$PWD/index.html?shot=$s"
done
```

平板两档（834×1194 / 1024×1366）实测无横向溢出：`scrollWidth - innerWidth = 0`（四屏 × 三档全部 0）。
