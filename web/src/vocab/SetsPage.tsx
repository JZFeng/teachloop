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

export default function VocabSetsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const [sets, setSets] = useState<SetView[] | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const load = useCallback(async () => {
    setError(null)
    try {
      setSets(await api.get<SetView[]>('/api/vocab/sets'))
    } catch (loadError) {
      setError(loadError as Error)
    }
  }, [])

  useEffect(() => { void load() }, [load, reloadKey])

  const refresh = () => setReloadKey((value) => value + 1)

  const batch = async (action: 'ACTIVATE' | 'DEACTIVATE' | 'DELETE') => {
    const ids = [...selected]
    if (ids.length === 0) return
    const ok = await confirm({
      title: action === 'DELETE' ? t('vocab.sets.deleteTitle') : t('vocab.sets.batchTitle'),
      content: t('vocab.sets.batchBody', { count: ids.length }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      danger: action === 'DELETE',
    })
    if (!ok) return
    await api.post('/api/vocab/sets/batch', { ids, action })
    toast.success(t('vocab.sets.batchDone', { count: ids.length }))
    setSelected(new Set())
    refresh()
  }

  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (sets === null) return <Loading label={t('common.loading')} />

  const allSelected = sets.length > 0 && sets.every((set) => selected.has(set.id))

  return (
    <>
      <PageHeader title={t('vocab.sets.title')} actions={(
        <Button variant="primary" onClick={() => navigate('/vocab/sets/new')}>
          {t('vocab.sets.newSet')}
        </Button>
      )} />

      {selected.size > 0 && (
        <div className="vocab-batch">
          <span>{t('vocab.sets.selected', { count: selected.size })}</span>
          <Button size="small" onClick={() => void batch('ACTIVATE')}>{t('vocab.sets.activate')}</Button>
          <Button size="small" onClick={() => void batch('DEACTIVATE')}>{t('vocab.sets.deactivate')}</Button>
          <Button size="small" variant="danger" onClick={() => void batch('DELETE')}>{t('vocab.sets.delete')}</Button>
        </div>
      )}

      {sets.length === 0 ? <Empty>{t('vocab.sets.empty')}</Empty> : (
        <div className="card" style={{ padding: 0, overflow: 'auto' }}>
          <table className="vocab-table">
            <thead>
              <tr>
                <th style={{ width: 34 }}>
                  <input type="checkbox" aria-label={t('vocab.sets.selectAll')} checked={allSelected}
                    onChange={() => setSelected(allSelected ? new Set() : new Set(sets.map((set) => set.id)))} />
                </th>
                <th>{t('vocab.sets.name')}</th>
                <th>{t('vocab.sets.language')}</th>
                <th>{t('vocab.sets.wordCount')}</th>
                <th>{t('vocab.sets.status')}</th>
                <th>{t('vocab.sets.assignedClasses')}</th>
                <th>{t('vocab.sets.updatedAt')}</th>
                <th>{t('vocab.sets.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {sets.map((set) => (
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
                  <td>{set.language}</td>
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
  )
}
