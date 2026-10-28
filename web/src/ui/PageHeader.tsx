import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { InfoHint } from './InfoHint'

/**
 * 页面标题区：标题 / 说明 / 操作按钮，三件套统一间距。
 *
 * 2026-10-26（决策 028）：全站页头统一成首页的样子——**不再有 eyebrow**。
 * 原来按路由前缀生成的 `CLASSES` / `ASSESS · 复核` / `PRACTICE · 复习` 那一行，
 * 只是把标题换个语言再说一遍；用户要求其它页面都跟首页一样，所以整张路由表删掉。
 *
 * 标题旁一律有 ⓘ（同上，用户要求「每个页面的 header label 都要有 Info icon」）。
 * 逐页文案后续统一补；没补的先用 `common.pageInfoPending` 占位，不编内容。
 */
export function PageHeader({
  title,
  sub,
  info,
  actions,
}: {
  title: ReactNode
  sub?: ReactNode
  /** 标题旁 ⓘ 气泡里的长解释；不传时显示「整理中」占位。 */
  info?: ReactNode
  actions?: ReactNode
}) {
  const { t } = useTranslation()
  const infoContent = info ?? t('common.pageInfoPending')

  return (
    <header className="tl-page-head">
      <div className="tl-page-head-text">
        <div className="tl-page-title-row">
          <h1 className="tl-page-title">{title}</h1>
          <InfoHint>{infoContent}</InfoHint>
        </div>
        {sub && <p className="tl-page-sub">{sub}</p>}
      </div>
      {actions && <div className="tl-page-actions">{actions}</div>}
    </header>
  )
}
