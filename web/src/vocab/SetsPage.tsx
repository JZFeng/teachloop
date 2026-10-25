import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, DataTable, ErrorState, Loading, PageHeader, useConfirm, useListState, useToast } from '../ui'
import type { Column } from '../ui'
import './vocab.css'

interface SetView {
  id: string
  name: string
  language: string
  description?: string
  active: boolean
  updatedAt: string
  wordCount: number
  assignedClasses: number
}

interface AssignmentView {
  id: string
  setId: string
  setName: string
  dueAt: string | null
  createdAt: string
  note?: string
  classCount: number
  recipientCount: number
  claimedCount: number
}

type Tab = 'sets' | 'records'

export default function VocabSetsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const [tab, setTab] = useState<Tab>('sets')
  const [sets, setSets] = useState<SetView[] | null>(null)
  const [records, setRecords] = useState<AssignmentView[] | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  // T444 / AC-68.3：排序、页码、每页条数写进 URL；两个页签共用，切页签时回到第 1 页
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: tab === 'sets' ? 'updatedAt' : 'createdAt', dir: 'desc' },
    10,
  )

  const switchTab = (next: Tab) => {
    setTab(next)
    setSelected([])
    setPage(1)
  }

  const load = useCallback(async () => {
    setError(null)
    try {
      if (tab === 'sets') setSets(await api.get<SetView[]>('/api/vocab/sets'))
      else setRecords(await api.get<AssignmentView[]>('/api/vocab/assignments'))
    } catch (loadError) {
      setError(loadError as Error)
    }
  }, [tab])

  useEffect(() => { void load() }, [load, reloadKey])

  const refresh = () => setReloadKey((value) => value + 1)

  /** 启用/停用合并成一个切换按钮（2026-10-24 教师要求）：批量动作是可逆的，不再二次确认。 */
  const batch = async (action: 'ACTIVATE' | 'DEACTIVATE' | 'DELETE') => {
    const ids = selected
    if (ids.length === 0) return
    if (action === 'DELETE') {
      const ok = await confirm({
        title: t('vocab.sets.deleteTitle'),
        content: t('vocab.sets.batchBody', { count: ids.length }),
        okText: t('common.confirm'),
        cancelText: t('common.cancel'),
        danger: true,
      })
      if (!ok) return
    }
    await api.post('/api/vocab/sets/batch', { ids, action })
    toast.success(t('vocab.sets.batchDone', { count: ids.length }))
    setSelected([])
    refresh()
  }

  /** 复制的是一条「学生链接」：学生自己打开进预习页，老师打开会被后端拒绝（403）。 */
  const copyLink = async (id: string) => {
    try {
      if (!navigator.clipboard) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(`${window.location.origin}/vocab/assignments/${id}`)
      toast.success(t('vocab.sets.copyDone'))
    } catch {
      toast.error(t('vocab.sets.copyFailed'))
    }
  }

  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (tab === 'sets' && sets === null) return <Loading label={t('common.loading')} />
  if (tab === 'records' && records === null) return <Loading label={t('common.loading')} />

  const selectedSets = (sets ?? []).filter((set) => selected.includes(set.id))
  /** 切换方向以「选中项是否全部启用」判定：全部启用时点一下是停用，否则是启用。 */
  const allSelectedActive = selectedSets.length > 0 && selectedSets.every((set) => set.active)

  const setColumns: Column<SetView>[] = [
    { key: 'name', title: t('vocab.sets.name'), value: (set) => set.name, sortable: true },
    { key: 'wordCount', title: t('vocab.sets.wordCount'), value: (set) => set.wordCount, sortable: true },
    {
      key: 'active',
      title: t('vocab.sets.status'),
      value: (set) => (set.active ? 1 : 0),
      sortable: true,
      render: (set) => <span className="chip">{set.active ? t('vocab.sets.active') : t('vocab.sets.inactive')}</span>,
    },
    { key: 'assignedClasses', title: t('vocab.sets.assignedClasses'), value: (set) => set.assignedClasses, sortable: true },
    {
      key: 'updatedAt',
      title: t('vocab.sets.updatedAt'),
      value: (set) => set.updatedAt,
      sortable: true,
      render: (set) => new Date(set.updatedAt).toLocaleString(),
    },
    {
      key: 'actions',
      title: t('vocab.sets.actions'),
      value: () => '',
      render: (set) => (
        <Button size="small" onClick={() => navigate(`/vocab/sets/${set.id}`)}>{t('vocab.sets.update')}</Button>
      ),
    },
  ]

  const recordColumns: Column<AssignmentView>[] = [
    { key: 'setName', title: t('vocab.sets.recordSet'), value: (record) => record.setName, sortable: true },
    { key: 'classCount', title: t('vocab.sets.recordClasses'), value: (record) => record.classCount, sortable: true },
    {
      key: 'createdAt',
      title: t('vocab.sets.recordSentAt'),
      value: (record) => record.createdAt,
      sortable: true,
      render: (record) => new Date(record.createdAt).toLocaleString(),
    },
    {
      key: 'dueAt',
      title: t('vocab.sets.recordDue'),
      value: (record) => record.dueAt ?? '',
      sortable: true,
      render: (record) => (record.dueAt ? new Date(record.dueAt).toLocaleString() : t('vocab.sets.noDue')),
    },
    {
      key: 'claimed',
      title: t('vocab.sets.recordClaimed'),
      value: (record) => record.claimedCount,
      sortable: true,
      render: (record) => `${record.claimedCount} / ${record.recipientCount}`,
    },
    {
      key: 'actions',
      title: t('vocab.sets.actions'),
      value: () => '',
      render: (record) => (
        <Button size="small" title={t('vocab.sets.copyHint')} onClick={() => void copyLink(record.id)}>
          {t('vocab.sets.copyLink')}
        </Button>
      ),
    },
  ]

  return (
    <>
      <PageHeader title={t('vocab.sets.title')} actions={tab === 'sets' ? (
        <Button variant="primary" onClick={() => navigate('/vocab/sets/new')}>
          {t('vocab.sets.newSet')}
        </Button>
      ) : undefined} />

      <div className="row" style={{ gap: 8, marginBottom: 12 }}>
        <Button size="small" variant={tab === 'sets' ? 'primary' : 'default'}
          onClick={() => switchTab('sets')}>
          {t('vocab.sets.tabSets')}
        </Button>
        <Button size="small" variant={tab === 'records' ? 'primary' : 'default'}
          onClick={() => switchTab('records')}>
          {t('vocab.sets.tabRecords')}
        </Button>
      </div>

      {tab === 'sets' ? (
        <DataTable
          rows={sets ?? []}
          columns={setColumns}
          rowKey={(set) => set.id}
          sort={sort}
          page={page}
          pageSize={pageSize}
          onSortChange={setSort}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
          selection={{ selectedKeys: selected, onChange: setSelected }}
          batchBar={(
            <>
              <span>{t('vocab.sets.selected', { count: selected.length })}</span>
              <Button size="small" onClick={() => void batch(allSelectedActive ? 'DEACTIVATE' : 'ACTIVATE')}>
                {allSelectedActive ? t('vocab.sets.deactivate') : t('vocab.sets.activate')}
              </Button>
              <Button size="small" variant="danger" onClick={() => void batch('DELETE')}>{t('vocab.sets.delete')}</Button>
            </>
          )}
          empty={t('vocab.sets.empty')}
        />
      ) : (
        <DataTable
          rows={records ?? []}
          columns={recordColumns}
          rowKey={(record) => record.id}
          sort={sort}
          page={page}
          pageSize={pageSize}
          onSortChange={setSort}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
          empty={t('vocab.sets.recordsEmpty')}
        />
      )}
    </>
  )
}
