/**
 * 侧栏菜单**目录**（007-role-nav）：只描述「有哪些菜单项、长什么样、按什么顺序」，
 * 不描述「谁能看到」——可见性由后端 `GET /api/features` 按角色算好后返回 key 集合，
 * 前端只做过滤。
 *
 * 单独抽成纯数据的原因：`web/tests/vocab-nav.test.mjs` 能直接断言三套角色的结果，
 * 不必起浏览器或登录。
 */
export type NavTone = 'create' | 'assess' | 'insights' | 'home' | 'classes' | 'practice'

export type NavIcon =
  | 'home'
  | 'classes'
  | 'assignments'
  | 'papers'
  | 'grading'
  | 'review'
  | 'reports'
  | 'ai'
  | 'settings'
  | 'library'
  | 'lists'
  | 'vocabReview'
  | 'sets'
  | 'assign'

export interface NavSpec {
  /** 与后端 `RoleNavMatrix` 的 key 一致，是前后端唯一的约定。 */
  key: string
  to: string
  labelKey: string
  icon: NavIcon
  end?: boolean
  tone?: NavTone
  /**
   * 011 / T563 / FR-203 / FR-204：同一 `group` 的多项在侧栏折叠成**一项**。
   *
   * 不新增一级 key：三个原 key（`nav.vocab.review` / `library` / `lists`）仍然由后端发放，
   * 折叠只发生在前端目录层——教师侧（`nav.vocab.sets` / `assign`）不分组，保持两项一级入口。
   */
  group?: 'vocab'
  /** 折叠项的高亮路径（AC-91.2）：任一匹配即高亮，否则在词库页侧栏会没有选中项。 */
  activePaths?: string[]
}

/**
 * 全局顺序。三套角色菜单由「同一份目录 + 后端给的 key 集合」过滤得出：
 * - 管理员：系统设置（2026-10-28 起由「AI 设置」改名；2026-10-27 起不再有班级——班级只属于师生）
 * - 教师：首页 / 班级 / 作业 / 试卷 / 词集 / 发词 / 批改 / 复核 / 报表（vocab 关时少两项）
 * - 学生：首页 / 班级 / 我的词库 / 我的词单 / 今日复习（vocab 关时只有前两项；
 *   2026-10-26 AC-72.8 删掉「成绩与评语」入口，反馈详情从行内进；
 *   2026-10-26 AC-69.11 修订加「我的词单」，紧跟我的词库）
 *
 * 加菜单 = 这里加一项 + 后端矩阵加一个 key，不需要任何角色分支。
 */
export const NAV_CATALOG: NavSpec[] = [
  { key: 'nav.home', to: '/', labelKey: 'nav.home', icon: 'home', end: true, tone: 'home' },
  { key: 'nav.classes', to: '/classes', labelKey: 'nav.classes', icon: 'classes', tone: 'classes' },
  { key: 'nav.assignments', to: '/assignments', labelKey: 'nav.assignments', icon: 'assignments', tone: 'create' },
  { key: 'nav.papers', to: '/papers', labelKey: 'nav.papers', icon: 'papers', tone: 'create' },
  { key: 'nav.vocab.sets', to: '/vocab/sets', labelKey: 'vocab.nav.sets', icon: 'sets', tone: 'practice' },
  { key: 'nav.vocab.assign', to: '/vocab/assign', labelKey: 'vocab.nav.assign', icon: 'assign', tone: 'practice' },
  { key: 'nav.grading', to: '/grading', labelKey: 'nav.grading', icon: 'grading', tone: 'assess' },
  { key: 'nav.review', to: '/review', labelKey: 'nav.review', icon: 'review', tone: 'assess' },
  { key: 'nav.reports', to: '/reports', labelKey: 'nav.reports', icon: 'reports', tone: 'insights' },
  { key: 'nav.vocab.library', to: '/vocab/library', labelKey: 'vocab.nav.library', icon: 'library', tone: 'practice', group: 'vocab' },
  // 2026-10-26（AC-69.11 修订）：用户要求「我的词单」与「我的词库」并列、紧跟其后，
  // 不再是只从词库工具条进的隐藏页。学生侧栏此时 5 项。
  { key: 'nav.vocab.lists', to: '/vocab/lists', labelKey: 'vocab.nav.lists', icon: 'lists', tone: 'practice', group: 'vocab' },
  { key: 'nav.vocab.review', to: '/vocab/review', labelKey: 'vocab.nav.review', icon: 'vocabReview', tone: 'practice', group: 'vocab' },
  // 2026-10-26（AC-72.8）：删 `nav.feedback` 目录项。它原来指向 `/feedback` 列表页，
  // 而那份列表和首页是同一份数据的两个视图；现在「看评语」从首页 / 作业页的行内直接进详情，
  // 侧栏不再留一个重复入口。`/feedback/:offeringId` 路由仍然存在。
  { key: 'nav.vocab.books', to: '/admin/vocab/books', labelKey: 'vocab.books.adminNav', icon: 'library' },
  { key: 'nav.ai', to: '/admin/ai', labelKey: 'nav.aiSettings', icon: 'settings' },
]

/**
 * 按后端给的 key 集合过滤出侧栏项。**不认识角色**——角色判断在 `RoleNavMatrix`。
 *
 * 011 / T563（AC-91.1 / 91.5）：同一 `group` 的项折叠成一项「背单词」，落点 `/vocab/review`。
 * 折叠条件是「组里至少有一项被授权」；组内三项都关闭（vocab 模块关闭）时整项不出现（AC-91.4）。
 *
 * @param features `GET /api/features` 的返回值；未加载时传空对象即可（侧栏不渲染）。
 */
export function navSpecs(features: Record<string, boolean>): NavSpec[] {
  const visible = NAV_CATALOG.filter((item) => features[item.key] === true)
  const folded = new Set<string>()
  const out: NavSpec[] = []
  for (const item of visible) {
    if (!item.group) {
      out.push(item)
      continue
    }
    if (folded.has(item.group)) continue
    folded.add(item.group)
    const members = visible.filter((other) => other.group === item.group)
    // 落点固定为「今日复习」：进入背单词默认落在要背的那一屏（AC-91.2 / AC-91.5）。
    const landing = members.find((member) => member.to === '/vocab/review') ?? item
    out.push({
      key: landing.key,
      to: '/vocab/review',
      labelKey: 'nav.vocab',
      icon: 'vocabReview',
      tone: landing.tone,
      group: item.group,
      activePaths: members.map((member) => member.to),
    })
  }
  return out
}

/**
 * 当前地址是否算选中该项（AC-91.2 / AC-91.3）。
 *
 * 默认按 `to` 精确匹配（`end` 项必须完全相等），折叠项按 `activePaths` 任一前缀匹配——
 * 所以 `/vocab/review/session/:id` 也高亮「背单词」。
 */
export function navItemActive(item: NavSpec, pathname: string): boolean {
  const paths = item.activePaths ?? [item.to]
  return paths.some((path) => (path === '/' ? pathname === '/' : pathname === path || pathname.startsWith(`${path}/`)))
}
