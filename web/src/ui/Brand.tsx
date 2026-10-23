import { useTranslation } from 'react-i18next'

/**
 * 品牌组件：全站唯一出口。三种形态分别对应三个位置，不允许页面自己拼 logo。
 *
 *   mark   只有图形标，用于 favicon / 极小尺寸 / 装饰
 *   text   图形标 + 文本字标，用于侧栏（17px 下位图字标会糊，文本更稳）
 *   lockup 整标（亮色用位图、暗色用「mark + 文本」），只用于登录页这类大字位
 *
 * 为什么暗色不用位图整标：原图字标是深海军蓝，放在暗底上只有 2.14:1；
 * 硬做反色位图会在缩放时发毛。暗色改用令牌色的文本字标，任意尺寸都清晰。
 */
export type BrandVariant = 'mark' | 'text' | 'lockup'
export type BrandSize = 'sm' | 'md' | 'lg' | 'xl'

export interface BrandProps {
  variant?: BrandVariant
  size?: BrandSize
  /** 侧栏可带一行小字；传 true 用 i18n 的 app.tagline，传字符串则覆盖。 */
  tagline?: boolean | string
  className?: string
}

export function Brand({ variant = 'text', size = 'md', tagline = false, className }: BrandProps) {
  const { t } = useTranslation()
  const cls = ['tl-brand', `tl-brand-${variant}`, `tl-brand-${size}`, className].filter(Boolean).join(' ')
  const taglineText = tagline === true ? t('app.tagline') : tagline || ''

  if (variant === 'mark') {
    return <img className={cls} src="/brand/logo-mark-wide-512.png" alt="TeachLoop" />
  }

  if (variant === 'lockup') {
    return (
      <span className={cls}>
        <span className="tl-sr-only">TeachLoop</span>
        <img className="tl-brand-lockup-img" src="/brand/logo-lockup-960.png" alt="" aria-hidden="true" />
        <span className="tl-brand-lockup-dark" aria-hidden="true">
          <img className="tl-brand-mark" src="/brand/logo-mark-wide-512.png" alt="" />
          <span className="tl-brand-word">
            Teach<span className="tl-brand-word-l">L</span>
            <span className="tl-brand-word-oop">oop</span>
          </span>
        </span>
      </span>
    )
  }

  return (
    <span className={cls}>
      <span className="tl-sr-only">TeachLoop</span>
      <img className="tl-brand-mark" src="/brand/logo-mark-wide-512.png" alt="" aria-hidden="true" />
      <span className="tl-brand-copy">
        <span className="tl-brand-word" aria-hidden="true">
          Teach<span className="tl-brand-word-l">L</span>
          <span className="tl-brand-word-oop">oop</span>
        </span>
        {taglineText && <span className="tl-brand-tagline">{taglineText}</span>}
      </span>
    </span>
  )
}
