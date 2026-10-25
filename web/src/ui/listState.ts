/**
 * 列表状态（T441）：排序 / 页码 / 每页条数在 URL 里的读写法，纯函数，可单测。
 *
 * 约定（AC-24.4 / AC-68.3）：
 *   - 写入参数名固定 `sort` / `dir` / `page` / `size`；
 *   - 读取时兼容旧参数 `pageSize`（历史链接不失效）；
 *   - 默认每页 10 条（AC-24.3，2026-10-25 用户决定）。
 */

export type SortDir = 'asc' | 'desc'
export interface SortState { key: string; dir: SortDir }

/** 默认每页条数：10（AC-24.3 修订版）。 */
export const DEFAULT_PAGE_SIZE = 10
/** 页脚可选的每页条数。 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100]

export interface ListStateSnapshot {
  sort?: SortState
  page: number
  pageSize: number
}

function parsePageSize(params: URLSearchParams, fallback: number): number {
  const raw = params.get('size') ?? params.get('pageSize')
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : fallback
}

/** 从查询串读出排序、页码与每页条数；缺省值由调用方给。 */
export function readListState(
  search: string,
  defaultSort?: SortState,
  defaultPageSize: number = DEFAULT_PAGE_SIZE,
): ListStateSnapshot {
  const params = new URLSearchParams(search)
  const key = params.get('sort')
  const sort: SortState | undefined = key
    ? { key, dir: params.get('dir') === 'desc' ? 'desc' : 'asc' }
    : defaultSort
  const page = Math.max(1, Math.trunc(Number(params.get('page') ?? '1')) || 1)
  return { sort, page, pageSize: parsePageSize(params, defaultPageSize) }
}

/** 换排序：写 `sort`/`dir`，回到第 1 页（换了排序还停在第 5 页只会让人困惑）。 */
export function withSort(search: string, sort: SortState): string {
  const next = new URLSearchParams(search)
  next.set('sort', sort.key)
  next.set('dir', sort.dir)
  next.delete('page')
  return next.toString()
}

/** 换页：只动 `page`。 */
export function withPage(search: string, page: number): string {
  const next = new URLSearchParams(search)
  next.set('page', String(Math.max(1, Math.trunc(page) || 1)))
  return next.toString()
}

/** 换每页条数：写 `size`，清掉旧参数 `pageSize` 与页码。 */
export function withPageSize(search: string, pageSize: number): string {
  const next = new URLSearchParams(search)
  next.set('size', String(pageSize))
  next.delete('pageSize')
  next.delete('page')
  return next.toString()
}
