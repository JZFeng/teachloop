import type { ReactNode } from 'react'
import { Brand } from '../ui'

/**
 * 公共认证页的外壳（注册 / 激活 / 忘记密码）。
 *
 * 与 LoginPage 共用 `.login-page` 底色，但只用一个窄卡片：这三屏不需要左侧品牌卖点区。
 */
export default function AuthLayout({ title, sub, children }: {
  title: string
  sub?: string
  children: ReactNode
}) {
  return (
    <div className="login-page">
      <div className="auth-shell">
        <div className="auth-card">
          <Brand variant="lockup" size="md" />
          <h2 style={{ marginTop: 18 }}>{title}</h2>
          {sub && <p className="sub">{sub}</p>}
          {children}
        </div>
      </div>
    </div>
  )
}
