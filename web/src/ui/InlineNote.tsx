import type { CSSProperties, ReactNode } from 'react'

export type NoteTone = 'info' | 'success' | 'warning' | 'error'

const TONE_CLASS: Record<NoteTone, string> = {
  info: '',
  success: 'ok',
  warning: 'warn',
  error: 'err',
}

/**
 * 行内提示条：错误 / 警告 / 成功一律上色（2026-10-25 用户要求「错误与警告消息要 color coded」）。
 *
 * 灰字只用来讲「这里是什么、下一步怎么做」；只要有件坏事发生了、或者有件事必须由人处理，
 * 就用 `tone="error"` / `tone="warning"`，别再用 `className="muted"`。颜色只能来自
 * `styles/mockup.css` 的 `.inline-note.err/.warn/.ok`（check-ui 第 1、2 条规则禁止页面写颜色）。
 */
export function InlineNote({
  tone = 'info',
  children,
  className,
  style,
}: {
  tone?: NoteTone
  children: ReactNode
  className?: string
  style?: CSSProperties
}) {
  const cls = ['inline-note', TONE_CLASS[tone], className].filter(Boolean).join(' ')
  return (
    <div className={cls} style={style} role={tone === 'error' ? 'alert' : undefined}>
      {children}
    </div>
  )
}
