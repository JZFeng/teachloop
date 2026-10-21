import type { ReactNode } from 'react'
import { Button as AntButton } from 'antd'
import type { ButtonProps as AntButtonProps } from 'antd'

/**
 * 按钮唯一入口。页面不再写 `className="btn pri"`。
 *
 * 变体映射（保持语义，不暴露 antd 的类型名，方便以后换实现）：
 *   primary  主操作，一屏只该有一个     → type="primary"
 *   default  次要操作                   → 默认
 *   text     表格行内、工具条上的轻操作 → type="text"
 *   danger   删除/驳回                  → danger
 */
export type ButtonVariant = 'primary' | 'default' | 'text' | 'danger'

export interface ButtonProps extends Omit<AntButtonProps, 'type' | 'variant'> {
  variant?: ButtonVariant
  /** 加载态：按钮自己禁用并显示转圈，避免用户连点两次「发布」。 */
  busy?: boolean
}

export function Button({ variant = 'default', busy, disabled, children, ...rest }: ButtonProps) {
  const type = variant === 'primary' ? 'primary' : variant === 'text' ? 'text' : 'default'
  return (
    <AntButton
      {...rest}
      type={type}
      danger={variant === 'danger'}
      loading={busy ?? rest.loading}
      disabled={disabled || busy}
    >
      {children}
    </AntButton>
  )
}
