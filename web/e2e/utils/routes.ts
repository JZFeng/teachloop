/**
 * 平板守卫覆盖的路由清单。加页面时只改这里。
 *
 * 分两组的原因：未登录页（登录/注册/忘记密码）在已登录状态下会被路由守卫弹走，
 * 必须用干净的 context 跑；其余页面需要 teacher 登录态。
 */
export const PUBLIC_ROUTES = ['/login', '/register', '/forgot'] as const

/** 011 / T567：学生侧三个入口 + 今日页。三者都在平板两档跑布局断言。 */
export const STUDENT_ROUTES = ['/', '/vocab/review', '/vocab/lists', '/vocab/library'] as const

/** 教师可见的业务页；学生端与作答页在阶段 2 随场景工厂一起加（需要先造数据）。 */
export const TEACHER_ROUTES = [
  '/',
  '/classes',
  '/papers',
  '/assignments',
  '/grading',
  '/reports',
  '/vocab/lists',
  '/notifications',
  '/profile',
] as const
