# 首页/班级的导航为什么没有颜色（T358）

## 现象与结论

用户问：「首页和班级两个没有颜色，这是故意设计的吗？」

**是故意的，不是漏做。** 侧栏的功能色只标注三个**已上线**功能位：

| 导航 | tone | 颜色 |
| :--- | :--- | :--- |
| 首页、班级、资料、通知、管理员 AI | 无 `tone` | 中性 `--muted-2`，无胶囊底 |
| 作业、试卷 | `create` | Create 蓝 |
| 批改、复核、我的反馈、考试作答 | `assess` | Assess 青 |
| 报表 | `insights` | Insights 玫红 |
| Practice（紫色） | 未用于导航 | 模块未上线，只作令牌/登录页占位 |

依据三处本来是一致的：

- `web/src/app/Shell.tsx` 的 `NavItem` 只给上面三项配 `data-tone`；
- `web/src/app/PageHeader.tsx:8-10` 的注释写明「中性页面（首页、班级、资料、通知）用 `--muted-2`，不上功能色」；
- `web/src/styles/app.css` 只有 `.nav-link[data-tone="create|assess|insights"]` 三条胶囊规则。

## 为什么保持中性（而不是补第五种颜色）

- 功能色编码的是「模块身份」：Create（出题/建卷）、Assess（判分/复核）、Insights（报表）。
  首页和班级是**入口/组织**，不属于这四个功能位；硬给颜色等于造出第五条语义。
- 唯一空闲的 Practice 紫属于未上线模块，借给班级会提前消费一个还没兑现的功能身份。
- 决策 006 与 AC-31.4 要求功能色只出现在导航胶囊、eyebrow、登录页/UI Kit 功能位与图表；
  给中性项加色会让「什么颜色代表什么」的边界变模糊。

## 本轮改动（只补说明，不改配色）

1. `docs/decisions/006-brand-visual.md`：新增「导航着色口径」段落，写明中性导航名单与理由。
2. `specs/006-brand-visual/spec.md`：§5.3 加同一口径；AC-31.2 补「首页、班级、资料、通知、管理员 AI 为中性导航、无胶囊」。
3. `web/src/app/Shell.tsx`：`teacherNav` / `studentNav` 上方加防误改注释（「不要给中性项补色当成漏做」）。
4. `web/src/app/UiKitPanel.tsx` + `web/src/locales/{zh-CN,en}.json`：UI Kit 品牌区新增一句说明 `ui.kit.navToneNote`，
   老师可在 `资料页 → 偏好设置 → 界面标准` 看到。

## 验证

- `BASE=http://127.0.0.1:5174 node scripts/verify/nav-tone-note.mjs` → **10/10 通过**：
  首页/班级无 `data-tone` 且图标底透明；作业·试卷 `create`、批改·复核 `assess`、报表 `insights`；
  UI Kit 中文与英文说明都可见；切到英文后首页/班级仍是中性；全程无控制台异常。
- `cd web && pnpm typecheck` / `pnpm build`：0 错误；`python3 scripts/check-i18n.py`：zh/en 各 1203 键对齐；
  `python3 scripts/check-ui.py`：无新增违规；`make test`：surefire 265/0/0/0。
- 截图（本目录）：
  - `home-nav.png` / `classes-nav.png`：首页、班级图标无胶囊，其余三项有功能色胶囊；
  - `ui-kit-zh.png` / `ui-kit-en.png`：UI Kit 品牌区的口径说明（中/英）。

## 以后要改这条口径时

先改决策 006 与 spec §5.3（例如新增一个「结构导航」色），再动 `Shell.tsx` / `app.css` / `contrast.py`
与 UI Kit；不要在某个页面里临时给导航加色。Practice 上线后若要使用紫胶囊，同样走这条顺序。
