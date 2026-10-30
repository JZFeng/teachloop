import type { ReactNode } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ViewTabs } from '../ui'
import './vocab.css'

/**
 * 011 / T563（AC-91.1 / 91.2 / 91.5）：背单词模块的页内壳。
 *
 * 侧栏只留一项「背单词」（导航折叠见 `app/nav.ts`），三个页面用页内 Tab 切换：
 * **今日复习 / 我的词单 / 我的词库**（顺序是 2026-10-30 定的：先「要背的」后「管理的」）。
 * 地址保持 `/vocab/review`、`/vocab/lists`、`/vocab/library` 三个独立路由——
 * 每个 Tab 都是真链接，可直接贴给别人、可右键新开（AC-91.2）。
 *
 * 真正复习是全屏会话（`/vocab/review/session/:id`），不经过这个壳（AC-91.3）：
 * 那一屏没有侧栏也没有 Tab，见 `VocabFocusLayout`。
 */
const TABS = [
  { key: 'review', to: '/vocab/review', labelKey: 'vocab.nav.review' },
  { key: 'lists', to: '/vocab/lists', labelKey: 'vocab.nav.lists' },
  { key: 'library', to: '/vocab/library', labelKey: 'vocab.nav.library' },
] as const

export default function VocabShell() {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  // 深链直接打开任一地址时对应 Tab 高亮；子路径（如词单详情）仍高亮所属 Tab。
  const selected = TABS.find((tab) => pathname === tab.to || pathname.startsWith(`${tab.to}/`))?.key
    ?? 'review'

  return (
    <div className="vocab-shell" data-testid="vocab-shell">
      <ViewTabs
        items={TABS.map((tab) => ({ key: tab.key, label: t(tab.labelKey), to: tab.to }))}
        selected={selected}
      />
      <Outlet />
    </div>
  )
}

/**
 * 复习会话的全屏容器（AC-91.3）：没有侧栏、没有 Tab，卡片居中。
 * 宽度 880 是 plan §7 定的「今日页 / 今日复习准备」档；卡片本体仍按 `review-card-column` 的 78% 收敛。
 */
export function VocabFocusLayout({ children }: { children: ReactNode }) {
  return <div className="vocab-focus">{children}</div>
}
