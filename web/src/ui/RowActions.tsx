import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Dropdown } from 'antd'
import type { MenuProps } from 'antd'
import { Button } from './Button'
import { MoreOutlined } from './icons'

/**
 * 表格行内动作（AC-25.1 / AC-25.5）：**至多 1 个一级按钮**，其余收进「⋯」菜单。
 *
 * 为什么不是「一行摆几个按钮」：
 *   1. 行高——4 个按钮在窄列里必然折行，行高翻倍，一屏少看好几行；
 *   2. 判断成本——「详情」与「汇总」并排时，用户要先想「我该点哪个」，而点行本来就进详情；
 *   3. 增长——动作只会越来越多，加宽列只是把折行换成横向滚动条。
 *
 * 约定：与「点行」目的地相同的动作不要做成按钮（AC-25.1）；禁用原因写进可见文案，
 * 不能只靠 `title` 悬停（AC-25.5）。
 */
export interface RowAction {
  key: string
  label: ReactNode
  onClick?: () => void
  disabled?: boolean
  /** 主操作进行中：按钮自己转圈并禁用，防止连点（与 `Button` 的 busy 同义）。 */
  busy?: boolean
  /** 禁用原因：一级按钮走 `title`，菜单项**拼进文案**（AC-25.5）。 */
  reason?: string
  danger?: boolean
}

export interface RowActionsProps {
  /**
   * 行内最重要的那个动作。**刻意不叫 `primary`**。
   *
   * 叫 `primary` 会诱导调用方顺手补一个 `variant: 'primary'`，于是行内实色按钮与页面级
   * 主按钮视觉权重相同——2026-10-26 在 `/vocab/lists` 与批改详情两处实际发生。
   * `Button.tsx:9` 的约定是「primary 主操作，一屏只该有一个」，所以行内动作**永远是描边**，
   * 实色主按钮只留给页面级容器（`PageHeader` actions / 表单底部 / 工具栏）。
   *
   * 这不是审美偏好：一屏出现两个同色实心按钮时，扫视无法判断哪个是页面级主操作。
   */
  main?: RowAction
  items?: RowAction[]
}

export function RowActions({ main, items = [] }: RowActionsProps) {
  const { t } = useTranslation('translation')
  if (!main && items.length === 0) return null

  const moreLabel = t('common.moreActions')
  const menuItems: MenuProps['items'] = items.map((item) => ({
    key: item.key,
    danger: item.danger,
    disabled: item.disabled,
    title: item.disabled ? item.reason : undefined,
    label:
      item.disabled && item.reason ? (
        <span>
          {item.label}
          <span className="muted"> · {item.reason}</span>
        </span>
      ) : (
        item.label
      ),
    onClick: item.disabled ? undefined : item.onClick,
  }))

  return (
    // 行本身是「点行进详情」，动作容器必须拦住冒泡，否则点动作会同时触发行跳转。
    <span className="tl-actions" onClick={(event) => event.stopPropagation()}>
      {main && (
        <Button
          size="small"
          variant="default"
          disabled={main.disabled}
          busy={main.busy}
          title={main.disabled ? main.reason : undefined}
          onClick={main.onClick}
        >
          {main.label}
        </Button>
      )}
      {items.length > 0 && (
        <Dropdown menu={{ items: menuItems }} trigger={['click']} placement="bottomRight">
          {/* 只留 aria-label：title 会弹原生 tooltip，正好盖住刚展开的菜单项。 */}
          <Button size="small" variant="text" icon={<MoreOutlined />} aria-label={moreLabel} />
        </Dropdown>
      )}
    </span>
  )
}
