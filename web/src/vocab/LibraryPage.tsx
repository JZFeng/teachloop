import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { isAIConfigured } from '../api/ai'
import { api } from '../api/client'
import {
  Button,
  DataTable,
  ErrorState,
  Input,
  Loading,
  Modal,
  PageHeader,
  StatCard,
  StarOutlined,
  useConfirm,
  useToast,
} from '../ui'
import type { Column, SortState } from '../ui'
import AddToListsModal from './AddToListsModal'
import { fetchLists } from './lists'
import type { VocabList } from './lists'
import './vocab.css'

type ColumnKey = 'form' | 'meaning' | 'sources' | 'state' | 'due' | 'actions'

interface SourceView {
  kind: string
  batchId?: string
  assignmentId?: string
  label?: string
  words?: number
}

interface WordItem {
  id: string
  form: string
  language: string
  content: { senses?: Array<{ pos?: string; zh?: string; en?: string }> } | null
  contentStatus: string
  generating: boolean
  favorite: boolean
  tags: string[]
  paused: boolean
  state: string
  dueAt: string | null
  sources: SourceView[]
}

interface WordPage {
  items: WordItem[]
  total: number
  page: number
  size: number
}

interface Stats {
  total: number
  dueToday: number
  newWords: number
  mastered: number
  paused: number
  favorite: number
}

interface PendingAssignment {
  id: string
  setName: string
  dueAt: string | null
  note?: string
}

interface Prefs {
  order?: string[]
  hidden?: string[]
}

const DEFAULT_ORDER: ColumnKey[] = ['form', 'meaning', 'sources', 'state', 'due', 'actions']
const ALL_COLUMNS: ColumnKey[] = DEFAULT_ORDER
const PAGE_SIZES = [10, 20, 50, 100]

function sourceValue(source: SourceView): string {
  if (source.kind === 'IMPORT' && source.batchId) return `IMPORT:${source.batchId}`
  if (source.kind === 'ASSIGNMENT' && source.assignmentId) return `ASSIGNMENT:${source.assignmentId}`
  return source.kind
}

function normalizePrefs(prefs: Prefs | null | undefined): Prefs {
  const order = (prefs?.order ?? []).filter((key): key is ColumnKey => ALL_COLUMNS.includes(key as ColumnKey))
  return {
    order: order.length === ALL_COLUMNS.length ? order : DEFAULT_ORDER,
    hidden: (prefs?.hidden ?? []).filter((key) => ALL_COLUMNS.includes(key as ColumnKey)),
  }
}

export default function VocabLibraryPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const confirm = useConfirm()
  const toast = useToast()

  const q = params.get('q') ?? ''
  const page = Math.max(1, Number(params.get('page') ?? 1))
  const size = PAGE_SIZES.includes(Number(params.get('size'))) ? Number(params.get('size')) : 10
  const sort = params.get('sort') ?? 'dueAt'
  const dir = params.get('dir') ?? 'asc'
  const states = params.getAll('state')
  const sourceFilters = params.getAll('source')
  const favorite = params.get('favorite') === 'true'
  const pausedFilter = params.get('paused') === 'true'
  const dueFilter = params.get('due') === 'true'
  const tagFilter = params.get('tag') ?? ''
  // AC-69.8：词单筛选是多值，URL 参数 `list`，与来源/标签/状态/收藏取交集
  const listFilters = params.getAll('list')

  const [search, setSearch] = useState(q)
  const [data, setData] = useState<WordPage | null>(null)
  const [stats, setStats] = useState<Stats | null>(null)
  const [sources, setSources] = useState<SourceView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [selected, setSelected] = useState<string[]>([])
  const [filterOpen, setFilterOpen] = useState(false)
  const [columnsOpen, setColumnsOpen] = useState(false)
  const [prefs, setPrefs] = useState<Prefs>({ order: DEFAULT_ORDER, hidden: [] })
  const [reviewableCount, setReviewableCount] = useState(0)
  const [pending, setPending] = useState<PendingAssignment | null>(null)
  const [startingReview, setStartingReview] = useState(false)
  const [lists, setLists] = useState<VocabList[]>([])
  const [addTarget, setAddTarget] = useState<WordItem | null>(null)
  const [draft, setDraft] = useState({
    states: [] as string[], sources: [] as string[], lists: [] as string[], tag: '', favorite: false, paused: false, due: false,
  })

  const queryString = useMemo(() => {
    const query = new URLSearchParams()
    if (q) query.set('q', q)
    states.forEach((state) => query.append('state', state))
    sourceFilters.forEach((source) => query.append('source', source))
    listFilters.forEach((listId) => query.append('list', listId))
    if (favorite) query.set('favorite', 'true')
    if (pausedFilter) query.set('paused', 'true')
    if (dueFilter) query.set('due', 'true')
    if (tagFilter) query.set('tag', tagFilter)
    query.set('sort', sort)
    query.set('dir', dir)
    query.set('page', String(page))
    query.set('size', String(size))
    return query.toString()
  }, [q, states.join(','), sourceFilters.join(','), listFilters.join(','), favorite, pausedFilter, dueFilter, tagFilter, sort, dir, page, size])

  useEffect(() => setSearch(q), [q])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    api.get<WordPage>(`/api/vocab/words?${queryString}`)
      .then((value) => { if (alive) { setData(value); setSelected([]) } })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [queryString, reloadKey])

  // T426：惰性生成后 PENDING 可能只是「还没轮到」，只有真有生成任务时才轮询（5s），避免空转
  const hasGenerating = (data?.items ?? []).some((word) => word.generating)
  useEffect(() => {
    if (!hasGenerating) return
    const timer = setInterval(() => setReloadKey((value) => value + 1), 5000)
    return () => clearInterval(timer)
  }, [hasGenerating])

  useEffect(() => {
    let alive = true
    api.get<Stats>('/api/vocab/words/stats')
      .then((value) => { if (alive) setStats(value) })
      .catch(() => { /* 统计失败不阻塞列表 */ })
    return () => { alive = false }
  }, [reloadKey])

  useEffect(() => {
    let alive = true
    api.get<SourceView[]>('/api/vocab/sources')
      .then((value) => { if (alive) setSources(value) })
      .catch(() => { if (alive) setSources([]) })
    return () => { alive = false }
  }, [])

  // 词单筛选维度的选项：词单接口未就绪时留空，筛选弹层照常打开（不阻塞词库页）
  useEffect(() => {
    let alive = true
    fetchLists()
      .then((value) => { if (alive) setLists(value) })
      .catch(() => { if (alive) setLists([]) })
    return () => { alive = false }
  }, [reloadKey])

  const reviewableQuery = useMemo(() => {
    const query = new URLSearchParams()
    if (q) query.set('q', q)
    states.forEach((state) => query.append('state', state))
    sourceFilters.forEach((source) => query.append('source', source))
    listFilters.forEach((listId) => query.append('list', listId))
    if (favorite) query.set('favorite', 'true')
    if (dueFilter) query.set('due', 'true')
    if (tagFilter) query.set('tag', tagFilter)
    query.set('paused', 'false')
    // T436：复习顺序跟随列表当前排序，点了哪一个就从哪一个往后
    query.set('sort', sort)
    query.set('dir', dir)
    query.set('size', '20')
    query.set('page', '1')
    return query.toString()
  }, [q, states.join(','), sourceFilters.join(','), listFilters.join(','), favorite, dueFilter, tagFilter, sort, dir])

  useEffect(() => {
    let alive = true
    api.get<WordPage>(`/api/vocab/words?${reviewableQuery}`)
      .then((value) => { if (alive) setReviewableCount(value.total) })
      .catch(() => { if (alive) setReviewableCount(0) })
    return () => { alive = false }
  }, [reviewableQuery, reloadKey])

  useEffect(() => {
    let alive = true
    api.get<PendingAssignment[]>('/api/vocab/assignments/pending')
      .then((value) => { if (alive) setPending(value.length > 0 ? value[0] : null) })
      .catch(() => { if (alive) setPending(null) })
    return () => { alive = false }
  }, [reloadKey])

  const claimPending = async () => {
    if (!pending) return
    try {
      const result = await api.post<{ added: number; duplicated: number }>(
        `/api/vocab/assignments/${pending.id}/claim`, {})
      toast.success(t('vocab.library.claimedToast', {
        added: result.added, duplicated: result.duplicated,
      }))
      setPending(null)
      refresh()
    } catch (claimError) {
      toast.error((claimError as Error).message)
    }
  }

  useEffect(() => {
    let alive = true
    api.get<Prefs>('/api/vocab/prefs')
      .then((value) => { if (alive) setPrefs(normalizePrefs(value)) })
      .catch(() => { /* 用默认列 */ })
    return () => { alive = false }
  }, [])

  const update = useCallback((mutate: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params)
    mutate(next)
    setParams(next, { replace: true })
  }, [params, setParams])

  const toggleMulti = (name: string, value: string) => {
    update((next) => {
      const current = next.getAll(name)
      next.delete(name)
      const values = current.includes(value) ? current.filter((item) => item !== value) : [...current, value]
      values.forEach((item) => next.append(name, item))
      next.set('page', '1')
    })
  }

  const openFilters = () => {
    setDraft({ states, sources: sourceFilters, lists: listFilters, tag: tagFilter, favorite, paused: pausedFilter, due: dueFilter })
    setFilterOpen(true)
  }

  const applyFilters = () => {
    update((next) => {
      ;['state', 'source', 'favorite', 'paused', 'due', 'tag', 'list'].forEach((key) => next.delete(key))
      draft.states.forEach((state) => next.append('state', state))
      draft.sources.forEach((source) => next.append('source', source))
      draft.lists.forEach((listId) => next.append('list', listId))
      if (draft.favorite) next.set('favorite', 'true')
      if (draft.paused) next.set('paused', 'true')
      if (draft.due) next.set('due', 'true')
      if (draft.tag.trim()) next.set('tag', draft.tag.trim())
      next.set('page', '1')
    })
    setFilterOpen(false)
  }

  const clearFilters = () => {
    update((next) => {
      ;['state', 'source', 'favorite', 'paused', 'due', 'tag', 'list'].forEach((key) => next.delete(key))
      next.set('page', '1')
    })
  }

  const filterCount = states.length + sourceFilters.length + listFilters.length + (favorite ? 1 : 0)
    + (pausedFilter ? 1 : 0) + (dueFilter ? 1 : 0) + (tagFilter ? 1 : 0)

  const visibleColumns = useMemo(() => {
    const order = prefs.order?.length ? prefs.order : DEFAULT_ORDER
    const hidden = new Set(prefs.hidden ?? [])
    return order.filter((key) => !hidden.has(key)) as ColumnKey[]
  }, [prefs])

  const savePrefs = (next: Prefs) => {
    const normalized = normalizePrefs(next)
    setPrefs(normalized)
    api.put('/api/vocab/prefs', { tableColumns: normalized }).catch(() => {
      toast.error(t('vocab.library.prefsFailed'))
    })
  }

  const toggleColumn = (key: ColumnKey) => {
    const hidden = new Set(prefs.hidden ?? [])
    if (hidden.has(key)) hidden.delete(key)
    else hidden.add(key)
    savePrefs({ ...prefs, hidden: [...hidden] })
  }

  const moveColumn = (key: ColumnKey, offset: number) => {
    const order = [...(prefs.order ?? DEFAULT_ORDER)]
    const index = order.indexOf(key)
    const target = index + offset
    if (index < 0 || target < 0 || target >= order.length) return
    ;[order[index], order[target]] = [order[target], order[index]]
    savePrefs({ ...prefs, order })
  }

  const refresh = () => setReloadKey((value) => value + 1)

  const pause = async (word: WordItem) => {
    await api.post(`/api/vocab/words/${word.id}/pause`)
    toast.success(t('vocab.library.pausedToast', { word: word.form }))
    refresh()
  }

  const resume = async (word: WordItem) => {
    await api.post(`/api/vocab/words/${word.id}/resume`)
    toast.success(t('vocab.library.resumedToast', { word: word.form }))
    refresh()
  }

  // T436：从词库点一个词 = 从该词开始复习当前筛选结果（与复习页同一张卡）
  const startFreeReview = async (startWordId?: string) => {
    if (startingReview) return
    setStartingReview(true)
    try {
      const ids: string[] = []
      let pageNumber = 1
      let total = reviewableCount
      for (;;) {
        const value = await api.get<WordPage>(`/api/vocab/words?${reviewableQuery}&size=100&page=${pageNumber}`)
        total = value.total
        value.items.forEach((item) => ids.push(item.id))
        if (value.items.length === 0 || ids.length >= Math.min(total, 1000)) break
        pageNumber++
      }
      let scoped = ids.slice(0, 1000)
      if (startWordId) {
        const index = scoped.indexOf(startWordId)
        if (index < 0) {
          // 已暂停的词不进入复习范围（AC-61.1），别悄悄从列表头开始
          toast.info(t('vocab.library.pausedNotReviewable'))
          return
        }
        scoped = scoped.slice(index)
      }
      if (scoped.length === 0) {
        toast.info(t('vocab.freeReview.empty'))
        return
      }
      const session = await api.post<{ id: string }>('/api/vocab/review-sessions',
        { wordIds: scoped, fromStart: !!startWordId })
      navigate(`/vocab/review/session/${session.id}`)
    } catch (startError) {
      toast.error((startError as Error).message)
    } finally {
      setStartingReview(false)
    }
  }

  const retryContent = async (ids: string[]) => {
    if (ids.length === 0) return
    // 背单词内容生成永远要真模型（stub 替身不产出内容），所以这里判 configured 而不是 ready。
    // 探测失败放行（见 api/ai.ts），最终由后端 409 守卫兜底。
    if (!(await isAIConfigured())) {
      toast.error(t('vocab.library.aiNotConfigured'))
      return
    }
    await api.post('/api/vocab/words/retry', { ids })
    toast.success(t('vocab.library.retryDone'))
    refresh()
  }

  const toggleFavorite = async (word: WordItem) => {
    await api.put(`/api/vocab/words/${word.id}/favorite`, { favorite: !word.favorite })
    refresh()
  }

  const remove = async (word: WordItem) => {
    const ok = await confirm({
      title: t('vocab.library.deleteTitle'),
      content: t('vocab.library.deleteBody', { word: word.form }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      danger: true,
    })
    if (!ok) return
    await api.del(`/api/vocab/words/${word.id}`)
    toast.success(t('vocab.library.deletedToast'))
    refresh()
  }

  const batch = async (action: 'PAUSE' | 'DELETE') => {
    const ids = selected
    if (ids.length === 0) return
    const ok = await confirm({
      title: action === 'PAUSE' ? t('vocab.library.batchPauseTitle') : t('vocab.library.batchDeleteTitle'),
      content: t('vocab.library.batchBody', { count: ids.length }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      danger: action === 'DELETE',
    })
    if (!ok) return
    await api.post('/api/vocab/words/batch', { ids, action })
    toast.success(t('vocab.library.batchDone', { count: ids.length }))
    refresh()
  }

  const onSearch = (value: string) => {
    const form = value.trim()
    if (!form) {
      update((next) => { next.delete('q'); next.set('page', '1') })
      return
    }
    navigate(`/vocab/words/by-form/${encodeURIComponent(form)}`)
  }

  // T442：排序交给 DataTable，页面只把「列 → 服务端排序字段」翻译一次
  const changeSort = (next: SortState) => {
    update((prev) => {
      prev.set('sort', next.key === 'meaning' ? 'form' : next.key === 'sources' ? 'createdAt' : next.key)
      prev.set('dir', next.dir)
      prev.set('page', '1')
    })
  }

  const meaningOf = (word: WordItem) => {
    const sense = word.content?.senses?.[0]
    return sense?.zh || sense?.en || '—'
  }

  const stateLabel = (word: WordItem) => word.paused
    ? t('vocab.library.state.PAUSED')
    : t(`vocab.library.state.${word.state}`)

  const columnValue = (column: ColumnKey, word: WordItem): string | number => {
    switch (column) {
      case 'form': return word.form
      case 'meaning': return meaningOf(word)
      case 'sources': return word.sources.map((source) => source.label ?? source.kind).join(' ')
      case 'state': return word.state
      case 'due': return word.dueAt ?? ''
      default: return ''
    }
  }

  const renderCell = (column: ColumnKey, word: WordItem) => {
    switch (column) {
      case 'form':
        return (
          <span
            className="vocab-word"
            role="link"
            tabIndex={0}
            onClick={() => void startFreeReview(word.id)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void startFreeReview(word.id)
            }}
          >
            {word.form}
          </span>
        )
      case 'meaning':
        return meaningOf(word)
      case 'sources':
        return word.sources.length === 0
          ? t('vocab.library.sourceKind.SELF')
          : word.sources.map((source) => source.label ?? t(`vocab.library.sourceKind.${source.kind}`)).join(' · ')
      case 'state':
        return (
          <span className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
            <span className={`chip${word.paused ? ' warn' : ''}`}>{stateLabel(word)}</span>
            {word.contentStatus === 'PENDING' && (
              <span className={`chip ${word.generating ? 'info' : 'warn'}`}>
                {word.generating ? t('vocab.library.contentGenerating') : t('vocab.library.contentIdle')}
              </span>
            )}
            {word.contentStatus === 'FAILED'
              && <span className="chip err">{t('vocab.library.contentFailed')}</span>}
          </span>
        )
      case 'due':
        return word.dueAt ? new Date(word.dueAt).toLocaleDateString() : '—'
      case 'actions':
        return (
          <span className="row" style={{ gap: 6 }}>
            <Button
              size="small"
              variant={word.favorite ? 'primary' : 'default'}
              icon={<StarOutlined />}
              aria-label={word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
              title={word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
              onClick={() => void toggleFavorite(word)}
            />
            <Button size="small" onClick={() => setAddTarget(word)}>{t('vocab.lists.addToLists')}</Button>
            {word.contentStatus === 'FAILED' && (
              <Button size="small" onClick={() => void retryContent([word.id])}>{t('vocab.library.retry')}</Button>
            )}
            {word.paused
              ? <Button size="small" onClick={() => void resume(word)}>{t('vocab.library.resume')}</Button>
              : <Button size="small" onClick={() => void pause(word)}>{t('vocab.library.pause')}</Button>}
            <Button size="small" variant="danger" onClick={() => void remove(word)}>{t('vocab.library.delete')}</Button>
          </span>
        )
      default:
        return null
    }
  }

  const items = data?.items ?? []
  const total = data?.total ?? 0
  // URL 里的 sort 是服务端字段名，表头要高亮的是列：createdAt → sources、dueAt → due
  const sortColumn: ColumnKey = sort === 'createdAt' ? 'sources' : sort === 'dueAt' ? 'due' : (sort as ColumnKey)
  const columns: Column<WordItem>[] = visibleColumns.map((column) => ({
    key: column,
    title: t(`vocab.library.columnsLabel.${column}`),
    sortable: column !== 'actions',
    value: (word) => columnValue(column, word),
    render: (word) => renderCell(column, word),
  }))

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />

  return (
    <>
      <PageHeader title={t('vocab.library.title')} />
      {pending && (
        <div className="vocab-banner">
          <span>
            {t('vocab.library.pendingPrefix')} <strong>{pending.setName}</strong>
            {pending.dueAt ? ` · ${t('vocab.library.pendingDue', {
              date: new Date(pending.dueAt).toLocaleDateString(),
            })}` : ''}
          </span>
          <Button size="small" variant="primary"
            onClick={() => navigate(`/vocab/assignments/${pending.id}`)}>
            {t('vocab.library.pendingClaim')}
          </Button>
        </div>
      )}
      {stats && (
        <div className="row" style={{ gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
          <StatCard value={stats.dueToday} label={t('vocab.library.stats.due')} active={dueFilter}
            onClick={() => update((next) => {
              if (dueFilter) next.delete('due')
              else next.set('due', 'true')
              next.set('page', '1')
            })} />
          <StatCard value={stats.newWords} label={t('vocab.library.stats.new')} active={states.includes('NEW')}
            onClick={() => toggleMulti('state', 'NEW')} />
          <StatCard value={stats.mastered} label={t('vocab.library.stats.mastered')} active={states.includes('GRADUATED')}
            onClick={() => toggleMulti('state', 'GRADUATED')} />
          <StatCard value={stats.paused} label={t('vocab.library.stats.paused')} active={pausedFilter}
            onClick={() => update((next) => {
              if (pausedFilter) next.delete('paused')
              else next.set('paused', 'true')
              next.set('page', '1')
            })} />
          {/* AC-59.12（T512）：收藏是纯标记，入口只放这一张可点卡片；工具条不再加视图切换（AC-59.9） */}
          <StatCard value={stats.favorite} label={t('vocab.library.stats.favorite')} active={favorite}
            onClick={() => update((next) => {
              if (favorite) next.delete('favorite')
              else next.set('favorite', 'true')
              next.set('page', '1')
            })} />
        </div>
      )}

      <div className="vocab-toolbar">
        <Input.Search
          className="vocab-search"
          value={search}
          placeholder={t('vocab.library.searchPlaceholder')}
          allowClear
          onChange={(event) => setSearch(event.target.value)}
          onSearch={onSearch}
        />
        <Button onClick={openFilters}>
          {t('vocab.library.filter')}{filterCount > 0 ? ` · ${filterCount}` : ''}
        </Button>
        <Button onClick={() => setColumnsOpen(true)}>{t('vocab.library.columns')}</Button>
        <Button onClick={clearFilters}>{t('vocab.library.clear')}</Button>
        <Button variant="primary" disabled={reviewableCount === 0 || startingReview} onClick={() => void startFreeReview()}>
          {t('vocab.library.reviewThese', { count: reviewableCount })}
        </Button>
        {/* 2026-10-26 (user request): the "import" button is gone. The add-words page
            already has both tabs (paste / file), so two toolbar buttons led to the
            same page. */}
        <Button onClick={() => navigate('/vocab/add?tab=paste')}>{t('vocab.library.addWords')}</Button>
        {/* AC-69.11：纯文字「词单」，不带数量角标 */}
        <Button onClick={() => navigate('/vocab/lists')}>{t('vocab.lists.toolbarEntry')}</Button>
      </div>

      <DataTable
        rows={items}
        columns={columns}
        rowKey={(word) => word.id}
        sort={{ key: sortColumn, dir: dir === 'desc' ? 'desc' : 'asc' }}
        page={page}
        pageSize={size}
        total={total}
        onSortChange={changeSort}
        onPageChange={(next) => update((prev) => prev.set('page', String(next)))}
        onPageSizeChange={(next) => update((prev) => {
          prev.set('size', String(next))
          prev.set('page', '1')
        })}
        selection={{ selectedKeys: selected, onChange: setSelected }}
        batchBar={(
          <>
            <span>{t('vocab.library.selected', { count: selected.length })}</span>
            <Button size="small" onClick={() => void batch('PAUSE')}>{t('vocab.library.batchPause')}</Button>
            <Button size="small" variant="danger" onClick={() => void batch('DELETE')}>{t('vocab.library.batchDelete')}</Button>
            {items.some((word) => selected.includes(word.id) && word.contentStatus === 'FAILED') && (
              <Button size="small" onClick={() => void retryContent(selected)}>{t('vocab.library.batchRetry')}</Button>
            )}
          </>
        )}
        empty={(
          <>
            <div>{filterCount > 0 || q ? t('vocab.library.noMatch') : t('vocab.library.empty')}</div>
            <div className="row" style={{ gap: 8, justifyContent: 'center', marginTop: 10 }}>
              <Button onClick={() => navigate('/vocab/add?tab=paste')}>{t('vocab.library.addWords')}</Button>
            </div>
          </>
        )}
      />

      <Modal open={filterOpen} title={t('vocab.library.filterTitle')} onCancel={() => setFilterOpen(false)} footer={null}>
        <div className="vocab-filter-group">
          <strong>{t('vocab.library.filterState')}</strong>
          <div className="row">
            {['NEW', 'LEARNING', 'REVIEW', 'GRADUATED'].map((state) => (
              <label key={state} className="row" style={{ gap: 4 }}>
                <input type="checkbox" checked={draft.states.includes(state)}
                  onChange={() => setDraft({
                    ...draft,
                    states: draft.states.includes(state)
                      ? draft.states.filter((item) => item !== state) : [...draft.states, state],
                  })} />
                {t(`vocab.library.state.${state}`)}
              </label>
            ))}
          </div>
        </div>
        <div className="vocab-filter-group">
          <strong>{t('vocab.library.filterSource')}</strong>
          <div className="row">
            {sources.length === 0 && <span className="muted">{t('vocab.library.noSources')}</span>}
            {sources.map((source) => {
              const value = sourceValue(source)
              return (
                <label key={value} className="row" style={{ gap: 4 }}>
                  <input type="checkbox" checked={draft.sources.includes(value)}
                    onChange={() => setDraft({
                      ...draft,
                      sources: draft.sources.includes(value)
                        ? draft.sources.filter((item) => item !== value)
                        : [...draft.sources, value],
                    })} />
                  {source.label ?? t(`vocab.library.sourceKind.${source.kind}`)}{source.words ? ` (${source.words})` : ''}
                </label>
              )
            })}
          </div>
        </div>
        <div className="vocab-filter-group">
          <strong>{t('vocab.lists.filterList')}</strong>
          <div className="row" style={{ flexWrap: 'wrap' }}>
            {lists.length === 0 && <span className="muted">{t('vocab.lists.noListsHint')}</span>}
            {lists.map((list) => (
              <label key={list.id} className="row" style={{ gap: 4 }}>
                <input type="checkbox" checked={draft.lists.includes(list.id)}
                  onChange={() => setDraft({
                    ...draft,
                    lists: draft.lists.includes(list.id)
                      ? draft.lists.filter((item) => item !== list.id)
                      : [...draft.lists, list.id],
                  })} />
                {list.name}
              </label>
            ))}
          </div>
        </div>
        <div className="vocab-filter-group">
          <strong>{t('vocab.library.filterFlags')}</strong>
          <div className="row">
            <label className="row" style={{ gap: 4 }}>
              <input type="checkbox" checked={draft.favorite} onChange={() => setDraft({ ...draft, favorite: !draft.favorite })} />
              {t('vocab.library.filterFavorite')}
            </label>
            <label className="row" style={{ gap: 4 }}>
              <input type="checkbox" checked={draft.paused} onChange={() => setDraft({ ...draft, paused: !draft.paused })} />
              {t('vocab.library.filterPaused')}
            </label>
            <label className="row" style={{ gap: 4 }}>
              <input type="checkbox" checked={draft.due} onChange={() => setDraft({ ...draft, due: !draft.due })} />
              {t('vocab.library.filterDue')}
            </label>
          </div>
        </div>
        <div className="vocab-filter-group">
          <strong>{t('vocab.library.filterTag')}</strong>
          <Input value={draft.tag} placeholder={t('vocab.library.tagPlaceholder')}
            onChange={(event) => setDraft({ ...draft, tag: event.target.value })} />
        </div>
        <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <Button onClick={() => { clearFilters(); setFilterOpen(false) }}>{t('vocab.library.reset')}</Button>
          <Button variant="primary" onClick={applyFilters}>{t('vocab.library.apply')}</Button>
        </div>
      </Modal>

      <Modal open={columnsOpen} title={t('vocab.library.columnsTitle')} onCancel={() => setColumnsOpen(false)} footer={null}>
        <p className="muted">{t('vocab.library.columnsHint')}</p>
        {(prefs.order ?? DEFAULT_ORDER).map((column, index, list) => (
          <div className="vocab-col-row" key={column}>
            <input
              type="checkbox"
              checked={!new Set(prefs.hidden ?? []).has(column)}
              onChange={() => toggleColumn(column as ColumnKey)}
            />
            <span className="grow">{t(`vocab.library.columnsLabel.${column}`)}</span>
            <Button size="small" disabled={index === 0} onClick={() => moveColumn(column as ColumnKey, -1)}>
              {t('vocab.library.moveUp')}
            </Button>
            <Button size="small" disabled={index === list.length - 1} onClick={() => moveColumn(column as ColumnKey, 1)}>
              {t('vocab.library.moveDown')}
            </Button>
          </div>
        ))}
      </Modal>

      <AddToListsModal
        open={addTarget !== null}
        form={addTarget?.form ?? ''}
        inLibrary
        onClose={() => setAddTarget(null)}
        onDone={refresh}
      />
    </>
  )
}
