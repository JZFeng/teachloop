import type { ReactNode } from 'react'
import {
  Empty as UiEmpty,
  ErrorState as UiErrorState,
  Loading as UiLoading,
  PageHeader as UiPageHeader,
  StatCard as UiStatCard,
} from '../ui'

/**
 * 兼容层：这五个状态组件现在由 `web/src/ui/` 提供。
 *
 * 保留这个文件是为了让还没迁移的 15 个页面继续 import '../components/States'，
 * 不必在一夜之间改 20 个 import。迁完那批页面后删掉本文件即可。
 * 新代码请直接从 `web/src/ui` 引。
 */

export function Loading({ label }: { label?: string }) {
  return <UiLoading label={label} />
}

export function ErrorState({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return <UiErrorState error={error} onRetry={onRetry} />
}

export function Empty({ children }: { children?: ReactNode }) {
  return <UiEmpty>{children}</UiEmpty>
}

export function PageHeader({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return <UiPageHeader title={title} sub={sub} actions={actions} />
}

/** 旧签名是 `n`，新组件叫 `value`；这里做一次映射，调用点不用改。 */
export function StatCard({ n, label, sub, onClick }: { n: ReactNode; label: string; sub?: string; onClick?: () => void }) {
  return <UiStatCard value={n} label={label} sub={sub} onClick={onClick} />
}
