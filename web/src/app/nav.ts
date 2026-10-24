/**
 * 侧栏导航规格（T389）：纯数据，Shell 负责把 icon 与文案换成人话。
 *
 * 单独抽出来的原因只有一个：`web/tests/vocab-nav.test.mjs` 能直接断言
 * 「功能开关关闭时 vocab 项不出现、打开时学生两个/教师两个」，
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
  | 'feedback'
  | 'library'
  | 'vocabReview'
  | 'sets'
  | 'assign'

export interface NavSpec {
  to: string
  labelKey: string
  icon: NavIcon
  end?: boolean
  tone?: NavTone
}

/**
 * 按角色与功能开关给出导航项。
 *
 * - 学生：首页 / 班级 / [我的词库 / 今日复习] / 我的反馈
 * - 教师（含管理员）：首页 / 班级 / 作业 / 试卷 / [词集 / 发词] / 批改 / 复核 / 报表
 * - `vocabEnabled=false` 时 vocab 两项完全不出现（AC-56.3：不放占位空承诺）。
 */
export function navSpecs(role: string, vocabEnabled: boolean): NavSpec[] {
  const studentVocab: NavSpec[] = vocabEnabled
    ? [
        { to: '/vocab/library', labelKey: 'vocab.nav.library', icon: 'library', tone: 'practice' },
        { to: '/vocab/review', labelKey: 'vocab.nav.review', icon: 'vocabReview', tone: 'practice' },
      ]
    : []
  const teacherVocab: NavSpec[] = vocabEnabled
    ? [
        { to: '/vocab/sets', labelKey: 'vocab.nav.sets', icon: 'sets', tone: 'practice' },
        { to: '/vocab/assign', labelKey: 'vocab.nav.assign', icon: 'assign', tone: 'practice' },
      ]
    : []

  if (role === 'STUDENT') {
    return [
      { to: '/', labelKey: 'nav.home', icon: 'home', end: true, tone: 'home' },
      { to: '/classes', labelKey: 'nav.classes', icon: 'classes', tone: 'classes' },
      ...studentVocab,
      { to: '/feedback', labelKey: 'nav.feedback', icon: 'feedback', tone: 'assess' },
    ]
  }

  return [
    { to: '/', labelKey: 'nav.home', icon: 'home', end: true, tone: 'home' },
    { to: '/classes', labelKey: 'nav.classes', icon: 'classes', tone: 'classes' },
    { to: '/assignments', labelKey: 'nav.assignments', icon: 'assignments', tone: 'create' },
    { to: '/papers', labelKey: 'nav.papers', icon: 'papers', tone: 'create' },
    ...teacherVocab,
    { to: '/grading', labelKey: 'nav.grading', icon: 'grading', tone: 'assess' },
    { to: '/review', labelKey: 'nav.review', icon: 'review', tone: 'assess' },
    { to: '/reports', labelKey: 'nav.reports', icon: 'reports', tone: 'insights' },
    ...(role === 'ADMIN'
      ? [{ to: '/admin/ai', labelKey: 'nav.aiSettings', icon: 'ai' as const }]
      : []),
  ]
}
