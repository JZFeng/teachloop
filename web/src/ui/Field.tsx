import type { ReactNode } from 'react'

/**
 * 表单字段壳：标签 + 必填星号 + 提示 + 错误，四件事一次说清。
 *
 * 为什么不直接用 antd Form.Item：现有 5 屏的表单是受控 state + 手写校验，
 * 迁到 antd Form 会把业务校验逻辑一起重写。先统一「字段长什么样」，
 * 校验仍由页面掌握——这一步降低迁移风险，下一步再考虑迁 Form。
 */
export interface FieldProps {
  label?: ReactNode
  required?: boolean
  hint?: ReactNode
  error?: string | null
  htmlFor?: string
  /**
   * 标签行右侧的动作位（导入 .md、插入模板这类「对这个字段做的事」）。
   * 放在标签行而不是输入框下面：动作属于字段，不属于输入的内容。
   */
  extra?: ReactNode
  children: ReactNode
}

export function Field({ label, required, hint, error, htmlFor, extra, children }: FieldProps) {
  return (
    <div className="tl-field">
      {(label || extra) && (
        <div className="tl-field-head">
          {label && (
            <label className="tl-field-label" htmlFor={htmlFor}>
              {label}
              {required && <span className="tl-field-required" aria-hidden="true">*</span>}
            </label>
          )}
          {extra && <div className="tl-field-extra">{extra}</div>}
        </div>
      )}
      {children}
      {error ? (
        <div className="tl-field-error" role="alert">{error}</div>
      ) : hint ? (
        <div className="tl-field-hint">{hint}</div>
      ) : null}
    </div>
  )
}
