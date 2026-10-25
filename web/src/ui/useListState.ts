import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  DEFAULT_PAGE_SIZE,
  readListState,
  withPage,
  withPageSize,
  withSort,
} from './listState'
import type { SortState } from './listState'

/**
 * 列表的排序 / 页码 / 每页条数写进 URL（T183 起的约定，T441 收敛参数名）。
 *
 * 读写规则在 `listState.ts`（纯函数，`web/tests/list-state.test.mjs` 覆盖）；
 * 这里只负责和 router 的 `useSearchParams` 接起来。
 */
export function useListState(defaultSort?: SortState, defaultPageSize: number = DEFAULT_PAGE_SIZE) {
  const [params, setParams] = useSearchParams()
  const { sort, page, pageSize } = readListState(params.toString(), defaultSort, defaultPageSize)

  const setSort = useCallback((next: SortState) => {
    setParams((prev) => new URLSearchParams(withSort(prev.toString(), next)))
  }, [setParams])

  const setPage = useCallback((next: number) => {
    setParams((prev) => new URLSearchParams(withPage(prev.toString(), next)))
  }, [setParams])

  /** 换每页条数：写 `size` 并回到第 1 页（AC-68.3）。 */
  const setPageSize = useCallback((next: number) => {
    setParams((prev) => new URLSearchParams(withPageSize(prev.toString(), next)))
  }, [setParams])

  return { sort, page, pageSize, setSort, setPage, setPageSize }
}
