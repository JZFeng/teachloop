import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Input, PageHeader, Popover, TextArea, useToast } from '../ui'
import './vocab.css'

interface SetView {
  id: string
  name: string
  language: string
  wordCount: number
  active: boolean
}

interface ClassView {
  id: string
  name: string
  memberCount: number
}

export default function VocabAssignPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [sets, setSets] = useState<SetView[]>([])
  const [classes, setClasses] = useState<ClassView[]>([])
  const [setQuery, setSetQuery] = useState('')
  const [setId, setSetId] = useState<string>('')
  const [classQuery, setClassQuery] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dueAt, setDueAt] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    Promise.all([
      api.get<SetView[]>('/api/vocab/sets'),
      api.get<ClassView[]>('/api/classes'),
    ]).then(([setList, classList]) => {
      if (!alive) return
      setSets(setList.filter((set) => set.active))
      setClasses(classList)
    }).catch(() => { /* 页面提示留空 */ })
    return () => { alive = false }
  }, [])

  const filteredSets = useMemo(() => {
    const keyword = setQuery.trim().toLowerCase()
    return sets.filter((set) => !keyword || set.name.toLowerCase().includes(keyword))
  }, [sets, setQuery])

  const filteredClasses = useMemo(() => {
    const keyword = classQuery.trim().toLowerCase()
    return classes.filter((item) => !keyword || item.name.toLowerCase().includes(keyword))
  }, [classes, classQuery])

  const selectedSet = sets.find((set) => set.id === setId)
  const selectedStudents = classes
    .filter((item) => selected.has(item.id))
    .reduce((sum, item) => sum + item.memberCount, 0)

  const submit = async () => {
    if (!setId) {
      toast.error(t('vocab.assign.setRequired'))
      return
    }
    if (selected.size === 0) {
      toast.error(t('vocab.assign.classRequired'))
      return
    }
    setBusy(true)
    try {
      await api.post('/api/vocab/assignments', {
        setId,
        classIds: [...selected],
        dueAt: dueAt ? new Date(dueAt).toISOString() : null,
        note: note.trim() || null,
      })
      toast.success(t('vocab.assign.sent'))
      navigate('/vocab/sets')
    } catch (error) {
      toast.error((error as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const info = (
    <div className="sm">
      <p>{t('vocab.assign.infoStudents')}</p>
      <p>{t('vocab.assign.infoSnapshot')}</p>
      <p>{t('vocab.assign.infoRepeat')}</p>
    </div>
  )

  return (
    <>
      <PageHeader title={t('vocab.assign.title')} actions={(
        <Popover content={info} trigger="click">
          <Button size="small">{t('vocab.assign.info')}</Button>
        </Popover>
      )} />

      <div className="card" style={{ maxWidth: 900 }}>
        <h3 style={{ marginTop: 0 }}>{t('vocab.assign.chooseSet')}</h3>
        <Input value={setQuery} placeholder={t('vocab.assign.searchSet')}
          onChange={(event) => setSetQuery(event.target.value)} style={{ maxWidth: 320 }} />
        <div className="row" style={{ gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
          {filteredSets.map((set) => (
            <Button key={set.id} size="small" variant={setId === set.id ? 'primary' : 'default'}
              onClick={() => setSetId(set.id)}>
              {set.name}（{set.wordCount}）
            </Button>
          ))}
          {filteredSets.length === 0 && <span className="muted">{t('vocab.assign.noSets')}</span>}
        </div>
        {selectedSet && (
          <p className="muted sm">{t('vocab.assign.selectedSet', {
            name: selectedSet.name, words: selectedSet.wordCount,
          })}</p>
        )}

        <h3 style={{ marginTop: 20 }}>{t('vocab.assign.chooseClasses')}</h3>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <Input value={classQuery} placeholder={t('vocab.assign.searchClass')}
            onChange={(event) => setClassQuery(event.target.value)} style={{ maxWidth: 240 }} />
          <Button size="small" onClick={() => setSelected(new Set(filteredClasses.map((item) => item.id)))}>
            {t('vocab.assign.selectAll')}
          </Button>
          <Button size="small" onClick={() => setSelected(new Set())}>{t('vocab.assign.clear')}</Button>
          <span className="muted sm">{t('vocab.assign.selection', {
            classes: selected.size, students: selectedStudents,
          })}</span>
        </div>
        <div className="vocab-class-panel">
          {filteredClasses.map((item) => (
            <label key={item.id} className="vocab-class-chip">
              <input type="checkbox" checked={selected.has(item.id)}
                onChange={() => {
                  const next = new Set(selected)
                  if (next.has(item.id)) next.delete(item.id)
                  else next.add(item.id)
                  setSelected(next)
                }} />
              {item.name}（{item.memberCount}）
            </label>
          ))}
          {filteredClasses.length === 0 && <span className="muted">{t('vocab.assign.noClasses')}</span>}
        </div>

        <div className="row" style={{ gap: 12, marginTop: 18, flexWrap: 'wrap' }}>
          <label className="row" style={{ gap: 6 }}>
            {t('vocab.assign.due')}
            <input type="datetime-local" value={dueAt} onChange={(event) => setDueAt(event.target.value)} />
          </label>
        </div>
        <TextArea rows={2} value={note} placeholder={t('vocab.assign.note')}
          onChange={(event) => setNote(event.target.value)} style={{ marginTop: 10 }} />

        <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <Button onClick={() => navigate('/vocab/sets')}>{t('common.cancel')}</Button>
          <Button variant="primary" busy={busy} onClick={() => void submit()}>
            {t('vocab.assign.send')}
          </Button>
        </div>
      </div>
    </>
  )
}
