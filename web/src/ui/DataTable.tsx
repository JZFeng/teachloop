import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from './Button'
import { Empty, Loading } from './States'
import { PAGE_SIZE_OPTIONS } from './listState'
import type { SortState } from './listState'

export type { SortDir, SortState } from './listState'

export interface Column<T> {
  key: string
  title: string
  value: (row: T) => string | number
  render?: (row: T) => ReactNode
  sortable?: boolean
  width?: string
}

/** 选择列：只在有批量动作的页面传（AC-68.4）。 */
export interface TableSelection {
  selectedKeys: string[]
  onChange: (keys: string[]) => void
}

export interface DataTableProps<T> {
  rows: T[]
  columns: Column<T>[]
  rowKey: (row: T) => string
  /** 受控排序；不传则表头不可排。 */
  sort?: SortState
  /** 受控页码（从 1 开始）。 */
  page?: number
  /** 每页条数，默认 10（AC-24.3）。 */
  pageSize?: number
  /** 总数：传了即服务端分页模式——`rows` 是「当前页」，组件不再本地排序与切片。 */
  total?: number
  pageSizeOptions?: number[]
  onSortChange?: (sort: SortState) => void
  onPageChange?: (page: number) => void
  onPageSizeChange?: (pageSize: number) => void
  selection?: TableSelection
  /** 表格上方的常驻工具栏（搜索 / 过滤 / 新建…），由页面提供。 */
  toolbar?: ReactNode
  /** 选中后的批量操作条，由页面提供。 */
  batchBar?: ReactNode
  onRowClick?: (row: T) => void
  empty?: ReactNode
  loading?: boolean
}

const NUMERIC = /^-?\d+(\.\d+)?$/

function compare(a: string | number, b: string | number, dir: 'asc' | 'desc'): number {
  const as = String(a)
  const bs = String(b)
  const numeric = NUMERIC.test(as.trim()) && NUMERIC.test(bs.trim())
  const cmp = numeric
    ? Number(as) - Number(bs)
    : as.localeCompare(bs, 'zh-Hans-CN', { numeric: true })
  return dir === 'asc' ? cmp : -cmp
}

/**
 * 全仓唯一的列表表格（T441 / US-68）。
 *
 * 实现是**手写 `<table>`**，与「我的词库」页原来的表格同一份 DOM 与样式（`ui/ui.css` 的
 * `tl-table*`），不依赖 antd `Table`：样式只有一处真源，词库页改造前后不变。
 *
 * 排序与分页始终由调用方控制（`sort` / `page` / `pageSize` + 回调），状态经
 * `useListState` 写进 URL；传 `total` 时视为服务端分页，组件只渲染当前页。
 */
export function DataTable<T>({
  rows, columns, rowKey,
  sort, page = 1, pageSize = PAGE_SIZE_OPTIONS[0], total,
  pageSizeOptions, onSortChange, onPageChange, onPageSizeChange,
  selection, toolbar, batchBar, onRowClick, empty, loading,
}: DataTableProps<T>) {
  const { t } = useTranslation('translation')
  const selectAllRef = useRef<HTMLInputElement | null>(null)
  const serverMode = typeof total === 'number'
  const sizes = pageSizeOptions?.length ? pageSizeOptions : PAGE_SIZE_OPTIONS
  // 没给翻页/改条数回调时自己管分页：只读小表（发词预览、班级成员）不用为一张表再接一遍 URL 状态
  const uncontrolled = !serverMode && !onPageChange && !onPageSizeChange
  const [innerPage, setInnerPage] = useState(1)
  const [innerPageSize, setInnerPageSize] = useState(pageSize)

  const sorted = useMemo(() => {
    if (serverMode || !sort) return rows
    const column = columns.find((item) => item.key === sort.key)
    if (!column) return rows
    return [...rows].sort((a, b) => compare(column.value(a), column.value(b), sort.dir))
  }, [rows, columns, sort, serverMode])

  const activePage = uncontrolled ? innerPage : page
  const activePageSize = uncontrolled ? innerPageSize : pageSize
  const totalRows = serverMode ? (total as number) : sorted.length
  const totalPages = Math.max(1, Math.ceil(totalRows / Math.max(1, activePageSize)))
  const current = Math.min(Math.max(1, activePage), totalPages)
  const visible = serverMode ? rows : sorted.slice((current - 1) * activePageSize, current * activePageSize)

  const goToPage = (next: number) => {
    if (uncontrolled) setInnerPage(next)
    onPageChange?.(next)
  }

  const changePageSize = (next: number) => {
    if (uncontrolled) {
      setInnerPageSize(next)
      setInnerPage(1)
    }
    onPageSizeChange?.(next)
  }

  const selected = new Set(selection?.selectedKeys ?? [])
  const pageKeys = visible.map((row) => rowKey(row))
  const allSelected = pageKeys.length > 0 && pageKeys.every((key) => selected.has(key))
  const someSelected = pageKeys.some((key) => selected.has(key))

  useEffect(() => {
    if (selectAllRef.current) selectAllRef.current.indeterminate = someSelected && !allSelected
  }, [someSelected, allSelected])

  const toggleAll = () => {
    if (!selection) return
    const next = new Set(selection.selectedKeys)
    pageKeys.forEach((key) => { if (allSelected) next.delete(key); else next.add(key) })
    selection.onChange([...next])
  }

  const toggleRow = (key: string) => {
    if (!selection) return
    const next = new Set(selection.selectedKeys)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    selection.onChange([...next])
  }

  const clickSort = (column: Column<T>) => {
    if (!column.sortable || !onSortChange) return
    const dir = sort?.key === column.key && sort.dir === 'asc' ? 'desc' : 'asc'
    onSortChange({ key: column.key, dir })
  }

  const shell = (body: ReactNode) => (
    <>
      {toolbar && <div className="tl-table-toolbar">{toolbar}</div>}
      {selection && selection.selectedKeys.length > 0 && batchBar && (
        <div className="tl-table-batch">{batchBar}</div>
      )}
      {body}
    </>
  )

  if (loading) return shell(<Loading />)
  if (rows.length === 0) return shell(<Empty>{empty}</Empty>)

  return shell(
    <div className="tl-table-card">
      <div className="tl-table-scroll">
        <table className="tl-table">
          <thead>
            <tr>
              {selection && (
                <th scope="col" className="tl-table-select">
                  <input
                    ref={selectAllRef}
                    type="checkbox"
                    checked={allSelected}
                    onChange={toggleAll}
                    aria-label={t('common.selectAll')}
                  />
                </th>
              )}
              {columns.map((column) => {
                const active = sort?.key === column.key
                return (
                  <th
                    key={column.key}
                    scope="col"
                    style={column.width ? { width: column.width } : undefined}
                    aria-sort={column.sortable && active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                  >
                    {column.sortable ? (
                      <button
                        type="button"
                        className="tl-table-sort"
                        onClick={() => clickSort(column)}
                        title={t('common.sortHint')}
                      >
                        <span>{column.title}</span>
                        <span
                          className={`tl-table-sort-icon${active ? ` is-${sort.dir}` : ''}`}
                          aria-hidden="true"
                        >
                          {active ? (sort.dir === 'asc' ? '↑' : '↓') : '⇅'}
                        </span>
                      </button>
                    ) : column.title}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {visible.map((row) => {
              const key = rowKey(row)
              return (
                <tr
                  key={key}
                  className={selected.has(key) ? 'is-selected' : undefined}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  onKeyDown={onRowClick ? (event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      onRowClick(row)
                    }
                  } : undefined}
                >
                  {selection && (
                    <td className="tl-table-select" onClick={(event) => event.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={selected.has(key)}
                        onChange={() => toggleRow(key)}
                        aria-label={`${t('common.selectRow')} ${key}`}
                      />
                    </td>
                  )}
                  {columns.map((column) => (
                    <td key={column.key}>{column.render ? column.render(row) : column.value(row)}</td>
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="tl-table-pager">
        <span className="tl-table-pager-info">
          {t('common.pageInfo', { total: totalRows, page: current, pages: totalPages })}
        </span>
        <div className="tl-table-pager-controls">
          <Button size="small" disabled={current <= 1} onClick={() => goToPage(current - 1)}>
            {t('common.prevPage')}
          </Button>
          <Button size="small" disabled={current >= totalPages} onClick={() => goToPage(current + 1)}>
            {t('common.nextPage')}
          </Button>
          <label className="tl-table-size">
            <span>{t('common.pageSize')}</span>
            <select
              value={String(activePageSize)}
              onChange={(event) => changePageSize(Number(event.target.value))}
              aria-label={t('common.pageSize')}
            >
              {sizes.map((size) => <option key={size} value={size}>{size}</option>)}
            </select>
          </label>
        </div>
      </div>
    </div>,
  )
}
