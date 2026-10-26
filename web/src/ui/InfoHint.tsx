import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Popover } from 'antd'
import { InfoCircleOutlined } from './icons'

/**
 * 标签旁的 ⓘ：把长解释收进气泡，页面上只留「这是什么」（2026-10-25 用户要求）。
 *
 * 触发只开 hover + focus，**故意不加 click**：三个都开时，鼠标移到图标上（hover 打开）
 * 再点一下会把刚打开的气泡关掉——实测过一次，快点的用户看不到内容。只留 hover/focus 时，
 * 点一下按钮会先拿到焦点（桌面与 iOS/Android 的按钮都会），气泡同样会开，触屏也能用。
 *
 * 使用边界：气泡里只放**页面上看不出来**的规则（什么时候开始批改、宽限期影响什么）。
 * 「学生端看到的就是这个标题」这类自解释句属于噪音，直接删，不要折进气泡。
 */
export function InfoHint({ children, label }: { children: ReactNode; label?: string }) {
  const { t } = useTranslation('translation')
  const text = label ?? t('common.moreInfo')
  return (
    <Popover
      content={<div className="tl-info-body">{children}</div>}
      trigger={['hover', 'focus']}
      placement="top"
    >
      <button type="button" className="tl-info-btn" aria-label={text} title={text}>
        <InfoCircleOutlined aria-hidden="true" />
      </button>
    </Popover>
  )
}
