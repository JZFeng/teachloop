import { useMemo } from 'react'
import type { ReactNode } from 'react'
import { Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useTranslation } from 'react-i18next'

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
 * 全局列表约定（T183 起沿用）：排序 + 分页在客户端做一次，状态由调用方
 * 通过 `useListState` 写进 URL，刷新/前进后退都保持一致。
 *
 * 实现从手写 `<table>` 换成 antd Table：表头排序、分页器、空态、行悬浮都交给它，
 * 但**排序与分页仍由调用方控制**，避免行为悄悄变化。
 */
export function DataTable<T>({
  rows, columns, rowKey, sort, page = 1, pageSize = 10,
  onSortChange, onPageChange, onRowClick, empty,
}: DataTableProps<T>) {
  const { t } = useTranslation('translation')

  const sorted = useMemo(() => {
    if (!sort) return rows
    const col = columns.find((c) => c.key === sort.key)
    if (!col) return rows
    return [...rows].sort((a, b) => compare(col.value(a), col.value(b), sort.dir))
  }, [rows, columns, sort])

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize))
  const current = Math.min(Math.max(1, page), totalPages)
  const visible = sorted.slice((current - 1) * pageSize, current * pageSize)

  const antColumns: ColumnsType<T> = columns.map((c) => ({
    key: c.key,
    title: c.title,
    width: c.width,
    sorter: Boolean(c.sortable),
    sortOrder: sort?.key === c.key ? (sort.dir === 'asc' ? 'ascend' : 'descend') : null,
    render: (_value: unknown, row: T) => (c.render ? c.render(row) : c.value(row)),
  }))

  return (
    <div className="tl-table-card">
      <Table<T>
        size="middle"
        columns={antColumns}
        dataSource={visible}
        rowKey={rowKey}
        pagination={{
          current,
          pageSize,
          total: sorted.length,
          showSizeChanger: false,
          showTotal: (total, range) => t('common.pageInfo', { total, page: current, pages: totalPages }) || `${range}`,
          onChange: (next) => onPageChange?.(next),
        }}
        onChange={(_pagination, _filters, sorter) => {
          if (!onSortChange) return
          const single = Array.isArray(sorter) ? sorter[0] : sorter
          const key = String(single?.columnKey ?? '')
          if (!key) return
          const dir: SortDir = single?.order === 'descend' ? 'desc' : 'asc'
          onSortChange({ key, dir })
        }}
        onRow={(row) => ({
          onClick: onRowClick ? () => onRowClick(row) : undefined,
          style: onRowClick ? { cursor: 'pointer' } : undefined,
        })}
        locale={{ emptyText: empty ?? t('common.empty') }}
      />
    </div>
  )
}
