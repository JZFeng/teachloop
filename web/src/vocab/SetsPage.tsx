import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, useConfirm, useToast } from '../ui'
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
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

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
    const ids = [...selected]
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
    setSelected(new Set())
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

  const allSelected = (sets?.length ?? 0) > 0 && sets!.every((set) => selected.has(set.id))
  const selectedSets = (sets ?? []).filter((set) => selected.has(set.id))
  /** 切换方向以「选中项是否全部启用」判定：全部启用时点一下是停用，否则是启用。 */
  const allSelectedActive = selectedSets.length > 0 && selectedSets.every((set) => set.active)

  return (
    <>
      <PageHeader title={t('vocab.sets.title')} actions={tab === 'sets' ? (
        <Button variant="primary" onClick={() => navigate('/vocab/sets/new')}>
          {t('vocab.sets.newSet')}
        </Button>
      ) : undefined} />

      <div className="row" style={{ gap: 8, marginBottom: 12 }}>
        <Button size="small" variant={tab === 'sets' ? 'primary' : 'default'}
          onClick={() => { setTab('sets'); setSelected(new Set()) }}>
          {t('vocab.sets.tabSets')}
        </Button>
        <Button size="small" variant={tab === 'records' ? 'primary' : 'default'}
          onClick={() => { setTab('records'); setSelected(new Set()) }}>
          {t('vocab.sets.tabRecords')}
        </Button>
      </div>

      {tab === 'sets' ? (
        <>
          {selected.size > 0 && (
            <div className="vocab-batch">
              <span>{t('vocab.sets.selected', { count: selected.size })}</span>
              <Button size="small"
                onClick={() => void batch(allSelectedActive ? 'DEACTIVATE' : 'ACTIVATE')}>
                {allSelectedActive ? t('vocab.sets.deactivate') : t('vocab.sets.activate')}
              </Button>
              <Button size="small" variant="danger" onClick={() => void batch('DELETE')}>{t('vocab.sets.delete')}</Button>
            </div>
          )}

          {(sets?.length ?? 0) === 0 ? <Empty>{t('vocab.sets.empty')}</Empty> : (
            <div className="card" style={{ padding: 0, overflow: 'auto' }}>
              <table className="vocab-table">
                <thead>
                  <tr>
                    <th style={{ width: 34 }}>
                      <input type="checkbox" aria-label={t('vocab.sets.selectAll')} checked={allSelected}
                        onChange={() => setSelected(allSelected ? new Set() : new Set(sets!.map((set) => set.id)))} />
                    </th>
                    <th>{t('vocab.sets.name')}</th>
                    <th>{t('vocab.sets.wordCount')}</th>
                    <th>{t('vocab.sets.status')}</th>
                    <th>{t('vocab.sets.assignedClasses')}</th>
                    <th>{t('vocab.sets.updatedAt')}</th>
                    <th>{t('vocab.sets.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {sets!.map((set) => (
                    <tr key={set.id} className={selected.has(set.id) ? 'is-selected' : undefined}>
                      <td>
                        <input type="checkbox" aria-label={set.name} checked={selected.has(set.id)}
                          onChange={() => {
                            const next = new Set(selected)
                            if (next.has(set.id)) next.delete(set.id)
                            else next.add(set.id)
                            setSelected(next)
                          }} />
                      </td>
                      <td>
                        <span className="vocab-word" role="link" tabIndex={0}
                          onClick={() => navigate(`/vocab/sets/${set.id}`)}
                          onKeyDown={(event) => { if (event.key === 'Enter') navigate(`/vocab/sets/${set.id}`) }}>
                          {set.name}
                        </span>
                      </td>
                      <td>{set.wordCount}</td>
                      <td><span className="chip">{set.active ? t('vocab.sets.active') : t('vocab.sets.inactive')}</span></td>
                      <td>{set.assignedClasses}</td>
                      <td>{new Date(set.updatedAt).toLocaleString()}</td>
                      <td>
                        <Button size="small" onClick={() => navigate(`/vocab/sets/${set.id}`)}>
                          {t('vocab.sets.update')}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        (records?.length ?? 0) === 0 ? <Empty>{t('vocab.sets.recordsEmpty')}</Empty> : (
          <div className="card" style={{ padding: 0, overflow: 'auto' }}>
            <table className="vocab-table">
              <thead>
                <tr>
                  <th>{t('vocab.sets.recordSet')}</th>
                  <th>{t('vocab.sets.recordClasses')}</th>
                  <th>{t('vocab.sets.recordSentAt')}</th>
                  <th>{t('vocab.sets.recordDue')}</th>
                  <th>{t('vocab.sets.recordClaimed')}</th>
                  <th>{t('vocab.sets.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {records!.map((record) => (
                  <tr key={record.id}>
                    <td>{record.setName}</td>
                    <td>{record.classCount}</td>
                    <td>{new Date(record.createdAt).toLocaleString()}</td>
                    <td>{record.dueAt
                      ? new Date(record.dueAt).toLocaleString()
                      : t('vocab.sets.noDue')}</td>
                    <td>{record.claimedCount} / {record.recipientCount}</td>
                    <td>
                      <Button size="small" title={t('vocab.sets.copyHint')}
                        onClick={() => void copyLink(record.id)}>
                        {t('vocab.sets.copyLink')}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </>
  )
}
