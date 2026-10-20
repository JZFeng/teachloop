import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { SortState } from './DataTable'

/** T183：列表的排序 + 分页写入 URL，刷新后保持一致。 */
export function useListState(defaultSort?: SortState, defaultPageSize = 10) {
  const [params, setParams] = useSearchParams()
  const sort: SortState | undefined = params.get('sort')
    ? { key: params.get('sort') as string, dir: params.get('dir') === 'desc' ? 'desc' : 'asc' }
    : defaultSort
  const page = Number(params.get('page') ?? '1') || 1
  const pageSize = Number(params.get('pageSize') ?? String(defaultPageSize)) || defaultPageSize

  const setSort = useCallback((next: SortState) => {
    setParams((p) => {
      const n = new URLSearchParams(p)
      n.set('sort', next.key)
      n.set('dir', next.dir)
      n.delete('page')
      return n
    })
  }, [setParams])

  const setPage = useCallback((next: number) => {
    setParams((p) => {
      const n = new URLSearchParams(p)
      n.set('page', String(Math.max(1, next)))
      return n
    })
  }, [setParams])

  return { sort, page, pageSize, setSort, setPage }
}
