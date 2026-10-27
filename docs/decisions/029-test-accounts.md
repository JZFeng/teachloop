# 决策 029 — 测试账号密码登录白名单（2026-10-27）

- 状态：**已实现（待部署 prod）**
- 关联：`specs/002-v2-platform/spec.md` `AC-1.10`、`teachloop-identity/.../auth/PasswordAuthProvider.java`

## 背景

用户 2026-10-27：「先把测试账号打开吧😂，班级先不要建，得先让朋友用起来。」

盘点后的事实链：

1. 生产环境密码登录只对 ADMIN 开放（`PasswordAuthProvider` 过滤 `role == ADMIN`），
   `ProdAuthTest.devLoginDisabledInProd` 明确断言「普通教师密码登录返回 503」。
2. 微信通道未配置 appId/secret → `/api/auth/wechat` 恒 503。
3. 注册功能还没有实现（见 028）。
4. 因此**朋友今天拿不到任何可用账号**，除非给 ADMIN。

## 决定

| 议题 | 决定 | 被否掉的选项与原因 |
| :--- | :--- | :--- |
| 打开方式 | `users.password_login_enabled` **按账号**的白名单列；`=true` 的 ACTIVE 账号可在 prod 用邮箱 + 密码登录 | 放开所有人（去掉 `role == ADMIN` 过滤）会把「白名单」变成「对公网开放密码登录」，且推翻既有断言 |
| 建号方式 | CLI `--create-user=email [--role=STUDENT\|TEACHER] [--display-name=…]`，密码取 `USER_PASSWORD` 或随机打印 | 手写 SQL 要自己算 BCrypt；管理界面建号是另一个特性 |
| 默认值 | `--create-user` 建出来的账号**默认** `password_login_enabled=true` | 让「建了却登不进去」这种状态不存在 |
| ADMIN | 不走这条路，仍用 `--create-admin` | 管理员权限过大（能看全部班级、AI 设置），不该给试用者 |
| 班级 | **不建** | 用户明确要求；朋友先进来看产品，内容和班级后补 |

## 影响

- `ProdAuthTest` 新增一条：白名单账号 prod 密码登录 200；原「普通教师 503」断言保留且仍为绿。
- `002` 新增 `AC-1.10`。
- 迁移 `V070__identity_password_login_flag.sql`（V069 被 `vocab_lookup_jobs` 占用过，避开）。
- 朋友登录后仍会被 `ProfileCompletionFilter` 要求补手机号 + 邮箱（prod 开启），这是有意的 onboarding，不是故障。
- 账号是**长期有效**的，不会自动过期；要收回用 `POST /api/admin/users/{id}/disable`。

## 遗留

1. 部署到 prod 需要用户明确口令（见 AGENTS 的生产部署闸门）。
2. 白名单账号没有「测试」标记列，将来清理只能按邮箱人工核对；若测试账号超过 10 个再考虑加 `is_test`。
3. 注册功能上线后，`password_login_enabled` 与「已验证邮箱」是两套概念，届时需要决定是否合并。
