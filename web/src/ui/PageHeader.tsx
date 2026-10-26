import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { InfoHint } from './InfoHint'

/**
 * 页面标题区：eyebrow / 标题 / 说明 / 操作按钮，四件套统一间距。
 *
 * eyebrow 不逐页传参，而是按路由前缀解析：这样新增页面不会「忘了加品牌层」，
 * 也不会出现同一个模块两种叫法。路由 → 文案的映射在下面这张表里，改一处全站生效。
 * 颜色只表达「这是哪个功能位」（Create 蓝 / Assess 青 / Insights 玫红 / Practice 玫粉），
 * 中性页面（首页、班级、资料、通知）用 --muted-2，不上功能色。
 */
export type PageTone = 'create' | 'assess' | 'insights' | 'practice' | 'neutral'

interface EyebrowRule {
  test: RegExp
  key: string
  tone: PageTone
}

const EYEBROW_RULES: EyebrowRule[] = [
  { test: /^\/vocab\/library$/, key: 'vocabLibrary', tone: 'practice' },
  { test: /^\/vocab\/words(\/|$)/, key: 'vocabLibrary', tone: 'practice' },
  { test: /^\/vocab\/review(\/|$)/, key: 'vocabReview', tone: 'practice' },
  { test: /^\/vocab\/sets(\/|$)/, key: 'vocabSets', tone: 'practice' },
  { test: /^\/vocab\/assign$/, key: 'vocabAssign', tone: 'practice' },
  { test: /^\/vocab\/assignments(\/|$)/, key: 'vocabAssignment', tone: 'practice' },
  { test: /^\/vocab\/add$/, key: 'vocabAdd', tone: 'practice' },
  { test: /^\/assignments\/new$/, key: 'assignmentCreate', tone: 'create' },
  { test: /^\/assignments$/, key: 'assignments', tone: 'create' },
  { test: /^\/papers(\/|$)/, key: 'papers', tone: 'create' },
  { test: /^\/grading(\/|$)/, key: 'grading', tone: 'assess' },
  { test: /^\/review$/, key: 'review', tone: 'assess' },
  { test: /^\/reports$/, key: 'reports', tone: 'insights' },
  { test: /^\/offerings\/[^/]+\/summary$/, key: 'summary', tone: 'insights' },
  { test: /^\/feedback(\/|$)/, key: 'feedback', tone: 'assess' },
  { test: /^\/exams\/[^/]+$/, key: 'exam', tone: 'assess' },
  { test: /^\/classes$/, key: 'classes', tone: 'neutral' },
  { test: /^\/profile$/, key: 'profile', tone: 'neutral' },
  { test: /^\/notifications$/, key: 'notifications', tone: 'neutral' },
  { test: /^\/admin\/ai$/, key: 'adminAi', tone: 'neutral' },
  // 首页刻意没有 eyebrow（AC-72.5 / 决策 027）：它没有功能位，这一行只能重复标题
  // （原来是 `DASHBOARD`，纯英文、零信息，且标题就叫「我的学习」，等于同一个标签写两遍）。
]

export function PageHeader({
  title,
  sub,
  info,
  actions,
  eyebrow,
  tone,
  hideTitle = false,
}: {
  title: ReactNode
  sub?: ReactNode
  /** 标题旁 ⓘ 气泡里的长解释（与 sub 二选一：一屏只说一次，别两处重复）。 */
  info?: ReactNode
  actions?: ReactNode
  /** 覆盖路由推断；只在路由表表达不了时用。 */
  eyebrow?: ReactNode
  tone?: PageTone
  /** 只保留 eyebrow 与操作区（复习卡这类把标题让给内容的页面用）。 */
  hideTitle?: boolean
}) {
  const { pathname } = useLocation()
  const { t } = useTranslation()
  const rule = EYEBROW_RULES.find((item) => item.test.test(pathname))
  const label = eyebrow ?? (rule ? t(`eyebrow.${rule.key}`) : null)
  const resolvedTone = tone ?? rule?.tone ?? 'neutral'

  return (
    <header className="tl-page-head">
      <div className="tl-page-head-text">
        {label && (
          <div className="tl-page-eyebrow" data-tone={resolvedTone}>
            {label}
          </div>
        )}
        {!hideTitle && (
          <div className="tl-page-title-row">
            <h1 className="tl-page-title">{title}</h1>
            {info && <InfoHint>{info}</InfoHint>}
          </div>
        )}
        {sub && <p className="tl-page-sub">{sub}</p>}
      </div>
      {actions && <div className="tl-page-actions">{actions}</div>}
    </header>
  )
}
