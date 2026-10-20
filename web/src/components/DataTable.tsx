import { useMemo } from 'react'
import type { ReactNode } from 'react'

export type SortDir = 'asc' | 'desc'
export interface SortState { key: string; dir: SortDir }

export interface Column<T> {
  key: string
  title: string
  value: (row: T) => string | number
  render?: (row: T) => ReactNode
  sortable?: boolean
  width?: string
}

export interface DataTableProps<T> {
  rows: T[]
  columns: Column<T>[]
  rowKey: (row: T) => string
  sort?: SortState
  page?: number
  pageSize?: number
  onSortChange?: (sort: SortState) => void
  onPageChange?: (page: number) => void
  onRowClick?: (row: T) => void
  empty?: string
}

const NUMERIC = /^-?\d+(\.\d+)?$/

function compare(a: string | number, b: string | number, dir: SortDir): number {
  const as = String(a)
  const bs = String(b)
  const numeric = NUMERIC.test(as.trim()) && NUMERIC.test(bs.trim())
  const cmp = numeric
    ? Number(as) - Number(bs)
    : as.localeCompare(bs, 'zh-Hans-CN', { numeric: true })
  return dir === 'asc' ? cmp : -cmp
}

/**
 * 全局列表约定（T183）：列排序 + 分页只在客户端做一次，排序/分页状态由调用方
 * 通过 useListState 写进 URL，刷新、前进后退都保持一致。
 */
export function DataTable<T>({
  rows, columns, rowKey, sort, page = 1, pageSize = 10,
  onSortChange, onPageChange, onRowClick, empty = '暂无数据',
}: DataTableProps<T>) {
  const sorted = useMemo(() => {
    if (!sort) return rows
    const col = columns.find((c) => c.key === sort.key)
    if (!col) return rows
    return [...rows].sort((a, b) => compare(col.value(a), col.value(b), sort.dir))
  }, [rows, columns, sort])

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize))
  const current = Math.min(Math.max(1, page), totalPages)
  const visible = sorted.slice((current - 1) * pageSize, current * pageSize)

  const clickSort = (col: Column<T>) => {
    if (!col.sortable || !onSortChange) return
    const dir: SortDir = sort?.key === col.key && sort.dir === 'asc' ? 'desc' : 'asc'
    onSortChange({ key: col.key, dir })
  }

  if (rows.length === 0) {
    return <div className="card"><span className="muted">{empty}</span></div>
  }

  return (
    <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
      <table className="sortable" style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                style={{ width: c.width, textAlign: 'left', padding: '10px 12px', cursor: c.sortable ? 'pointer' : undefined }}
                className={sort?.key === c.key ? sort.dir : undefined}
                onClick={() => clickSort(c)}
                title={c.sortable ? '点击排序' : undefined}
              >
                {c.title}{c.sortable ? (sort?.key === c.key ? (sort.dir === 'asc' ? ' ⇅' : ' ⇅') : ' ⇅') : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visible.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              style={{ cursor: onRowClick ? 'pointer' : undefined }}
            >
              {columns.map((c) => (
                <td key={c.key} style={{ padding: '10px 12px', borderTop: '1px solid var(--line-2)' }}>
                  {c.render ? c.render(row) : c.value(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row" style={{ justifyContent: 'space-between', padding: '10px 12px', borderTop: '1px solid var(--line-2)' }}>
        <span className="small muted">共 {rows.length} 条 · 第 {current}/{totalPages} 页</span>
        <div className="row">
          <button className="btn" disabled={current <= 1} onClick={() => onPageChange?.(current - 1)}>上一页</button>
          <button className="btn" disabled={current >= totalPages} onClick={() => onPageChange?.(current + 1)}>下一页</button>
        </div>
      </div>
    </div>
  )
}
