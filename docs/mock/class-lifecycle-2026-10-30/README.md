# 班级生命周期原型（2026-10-30）

对应规格：[`specs/012-class-lifecycle/spec.md`](../../../specs/012-class-lifecycle/spec.md)（3 US / 16 AC / 12 FR）

单文件、无依赖：**双击 `index.html` 就能点**。

## 怎么 review

顶部两组开关：

| 开关 | 值 | 看什么 |
| :--- | :--- | :--- |
| 角色 | 学生 / 老师 | 学生看到「退出班级」，老师看到「移出学生 / 结课」 |
| 状态 | 无弹窗 / 三个确认弹窗 | 二次确认的三条后果文案 |

深链：

- `index.html#student`（进行中 + 已结束两个分区）
- `index.html#student?modal=leave`（退出确认：先告诉你还有几份没交）
- `index.html#teacher`（班级行 + 成员表，可切换「含已离开」）
- `index.html#teacher?modal=archive`（结课确认）
- `index.html#teacher?modal=remove`（移出确认）

## 屏与要点

| 屏 | 要点 |
| :--- | :--- |
| 学生 · 我的班级 | 「进行中」可退出；「已结束」只读，按钮是「查看成绩」而不是「进入/提交」 |
| 退出确认 | 先报未交数量，再说三件事：看不到该班作业 / 已交成绩保留 / 词库与复习进度不受影响 |
| 老师 · 班级 | 班级行有「结课 / 恢复」；成员表默认显示在班学生，勾选后显示已离开 |
| 结课确认 | 说明学生只读、邀请码失效、不能再布置作业、可随时恢复 |

## 平板自检

9 个组合（学生 / 老师 / 弹窗 × 3 个视口）全部通过：`scrollWidth == innerWidth`、首屏外按钮 0。834 宽下侧栏隐藏（与真实应用同一档 `max-width:1023px`）。

## 截图

`shots/` 下 7 张：`student` `student-leave` `teacher` `teacher-archive` `teacher-remove` `student-834` `teacher-1024`

## 数据口径

班级名、人数、成员与提交数都是**示例数据**；「还有 2 份作业未交」用于演示确认弹窗的信息密度，不代表真实统计。
