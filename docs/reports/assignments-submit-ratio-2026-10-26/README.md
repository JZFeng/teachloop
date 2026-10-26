# 作业列表「提交」列改成「已交 / 应交」（2026-10-26）

## 用户要求

截图 `/assignments`（5174）两点：

1. 提交列应该写成 `1/30` 这种，而不是只写已交份数；
2. 去掉「2 个班」副行——前面班级列已经写了。

> 用户原文第 2 点只写了一个「2」，没有后续正文。这里按「删除副行」执行；如果原意是别的（例如第二点要提新问题），需要用户补一句。

## 顺带发现并修掉的口径分裂

改「1/30」必须先定分母，查下来两个数字原先都不是人数：

| 位置 | 原口径 | 症状 |
| --- | --- | --- |
| `OfferingEndpoint.view()`（`teachloop-sitting/.../OfferingEndpoint.java:626`） | `submissionCount` 数的是**提交行**：`submittedAt != null` 的行数，含被拒收的份、含同一学生的历史 attempt | QA 库里的「TESOL Writing lesson plan」两行都是同一个学生（`2b37cc56…`，一行 active 一行被替换），列表显示 `2`，实际只有 1 人交 |
| `GradingDetailPage.tsx:112`（改前） | 分母 = 各班 `memberCount` **相加** | QA 库「Unit4写作」两个班 3 人 + 30 人（3 人是 30 人的子集），详情页写「应交 33」，永远交不齐 |

列表页和详情页于是会互相打脸：列表 `2/30`、详情 `1/33`。

## 改法

1. `SittingDtos.OfferingView` 新增 `expectedCount`：本作业各班成员的**并集**人数（同一学生带两个班只算一次）。
2. `OfferingEndpoint.view()`：`submissionCount` 改为**按学生去重且排除被拒收**的份数；`expectedCount` 用并集。
3. `web/src/teacher/AssignmentsPage.tsx:77`：提交列渲染 `已交/应交`（分母浅色），删「N 个班」副行；排序值改成交齐比例（`30/30` 排在 `1/30` 之前）。
4. `web/src/teacher/GradingDetailPage.tsx:112`：分母改用 `offering.expectedCount`，与列表同源。
5. `specs/002-v2-platform/mockups/interface.html`：作业列表四行的提交列同步成 `18/52`、`0/28`、`41/41`、`24/24`。

## 验证

| 档位 | 内容 | 结果 |
| --- | --- | --- |
| 接口 | `Phase2ExamTimingTest`（新增断言：一个学生进 3 个班 → `expectedCount=1`，不是 3） | 3/3 通过，12.6s，EXIT=0 |
| 接口 | `GET /api/offerings`（QA 8011，教师账号） | Unit4写作 `已交=1 应交=30`（各班人数 `[3,30]`，相加是 33）；其余三条 `1/30` |
| 界面入口 | `pnpm -C web typecheck` | 0 错误 |
| 界面入口 | 5174 部署后截图 | `docs/reports/assignments-submit-ratio-2026-10-26/assignments-1280.png`：四行都显示 `1/30`，没有「个班」副行 |
| 端到端 | 详情页 `/grading/e96789fd…` | 同一份作业显示「已交 1 / 应交 30」，与列表一致（改前会是 1/33） |

部署：`./scripts/env.sh deploy qa` → 版本 `35b60f1+dirty`，前端 5174 / 后端 8011 已重启，QA 数据库容器未动，数据保留。

## 未验证

- 英文界面下这一列（纯数字 + `/`，无文案，理论上无差异，但没截图）。
- 暗色主题下分母浅色的对比度。
- 1024 / 768 宽度下这一列是否挤压（本列内容只有 5 个字符，实际不会换行）。
- 该列改成按比例排序后，用户是否认可这个默认方向（需要用户看完决定）。
