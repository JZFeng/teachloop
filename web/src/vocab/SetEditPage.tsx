import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Input, Loading, PageHeader, TextArea, useToast } from '../ui'
import './vocab.css'

interface SetView {
  id: string
  name: string
  language: string
  description?: string
  active: boolean
  wordCount: number
}

interface SetWord {
  id: string
  position: number
  form: string
  band?: number | null
}

interface Preview {
  distinctWords: string[]
  skipped: Array<{ line: number; text: string; reason: string }>
  cleanups: Array<{ line: number; rule: string }>
}

export default function VocabSetEditPage() {
  const { id } = useParams()
  const isNew = !id
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()

  const [setId, setSetId] = useState<string | undefined>(id)
  const [name, setName] = useState('')
  const [language, setLanguage] = useState('en')
  const [description, setDescription] = useState('')
  const [active, setActive] = useState(true)
  const [words, setWords] = useState<SetWord[]>([])
  const [loading, setLoading] = useState(!isNew)
  const [error, setError] = useState<Error | null>(null)
  const [text, setText] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    if (!setId) return
    setLoading(true)
    setError(null)
    try {
      const detail = await api.get<{ set: SetView; words: SetWord[] }>(`/api/vocab/sets/${setId}`)
      setName(detail.set.name)
      setLanguage(detail.set.language)
      setDescription(detail.set.description ?? '')
      setActive(detail.set.active)
      setWords(detail.words)
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [setId])

  useEffect(() => { void load() }, [load])

  const save = async () => {
    if (!name.trim()) {
      toast.error(t('vocab.sets.nameRequired'))
      return
    }
    setBusy(true)
    try {
      const body = { name: name.trim(), language, description, active }
      const saved = setId
        ? await api.put<SetView>(`/api/vocab/sets/${setId}`, body)
        : await api.post<SetView>('/api/vocab/sets', body)
      setSetId(saved.id)
      setName(saved.name)
      toast.success(t('vocab.sets.savedToast'))
      if (isNew) navigate(`/vocab/sets/${saved.id}`, { replace: true })
      else await load()
    } catch (saveError) {
      toast.error((saveError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const runPreview = async () => {
    if (!text.trim()) return
    try {
      setPreview(await api.post<Preview>('/api/vocab/import/preview', { text, language }))
    } catch (previewError) {
      toast.error((previewError as Error).message)
    }
  }

  const addWords = async () => {
    if (!setId || !preview) return
    setBusy(true)
    try {
      const result = await api.post<{ added: number; skipped: number }>(
        `/api/vocab/sets/${setId}/words`, { words: preview.distinctWords })
      toast.success(t('vocab.sets.addDone', { added: result.added, skipped: result.skipped }))
      setText('')
      setPreview(null)
      await load()
    } catch (addError) {
      toast.error((addError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const removeWord = async (wordId: string) => {
    if (!setId) return
    await api.del(`/api/vocab/sets/${setId}/words/${wordId}`)
    await load()
  }

  const moveWord = async (index: number, offset: number) => {
    if (!setId) return
    const target = index + offset
    if (target < 0 || target >= words.length) return
    const reordered = [...words]
    ;[reordered[index], reordered[target]] = [reordered[target], reordered[index]]
    setWords(reordered)
    await api.put(`/api/vocab/sets/${setId}/words/order`,
      { wordIds: reordered.map((word) => word.id) })
  }

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={() => void load()} />

  return (
    <>
      <PageHeader title={isNew ? t('vocab.sets.newSet') : t('vocab.sets.editTitle')} />

      <div className="card" style={{ maxWidth: 820 }}>
        <label className="row" style={{ gap: 8, marginBottom: 10 }}>
          <span style={{ width: 80 }}>{t('vocab.sets.name')}</span>
          <Input value={name} onChange={(event) => setName(event.target.value)} maxLength={100} />
        </label>
        <label className="row" style={{ gap: 8, marginBottom: 10 }}>
          <span style={{ width: 80 }}>{t('vocab.sets.language')}</span>
          <Input value={language} onChange={(event) => setLanguage(event.target.value)} style={{ width: 100 }} />
          <label className="row" style={{ gap: 4 }}>
            <input type="checkbox" checked={active} onChange={() => setActive((value) => !value)} />
            {t('vocab.sets.active')}
          </label>
        </label>
        <label className="row" style={{ gap: 8, marginBottom: 14, alignItems: 'flex-start' }}>
          <span style={{ width: 80 }}>{t('vocab.sets.description')}</span>
          <TextArea rows={3} value={description} onChange={(event) => setDescription(event.target.value)} />
        </label>

        <div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => navigate('/vocab/sets')}>{t('vocab.sets.cancel')}</Button>
          <Button variant="primary" busy={busy} onClick={() => void save()}>{t('vocab.sets.save')}</Button>
        </div>
      </div>

      <div className="card" style={{ maxWidth: 820, marginTop: 12 }}>
        <h3 style={{ marginTop: 0 }}>{t('vocab.sets.words')}</h3>
        {!setId ? (
          <p className="muted">{t('vocab.sets.saveFirst')}</p>
        ) : (
          <>
            <div className="row" style={{ gap: 8, alignItems: 'flex-start' }}>
              <TextArea rows={5} value={text} placeholder={t('vocab.sets.pastePlaceholder')}
                onChange={(event) => setText(event.target.value)} />
              <div className="row" style={{ gap: 8, flexDirection: 'column' }}>
                <Button size="small" onClick={() => void runPreview()}>{t('vocab.sets.preview')}</Button>
                <Button size="small" variant="primary" disabled={!preview || preview.distinctWords.length === 0}
                  busy={busy} onClick={() => void addWords()}>{t('vocab.sets.addToSet')}</Button>
              </div>
            </div>
            {preview && (
              <div className="sm muted" style={{ marginTop: 8 }}>
                {t('vocab.sets.previewSummary', {
                  words: preview.distinctWords.length,
                  skipped: preview.skipped.length,
                  cleanups: preview.cleanups.length,
                })}
              </div>
            )}

            {words.length === 0 ? <Empty>{t('vocab.sets.wordsEmpty')}</Empty> : (
              <table className="vocab-table" style={{ marginTop: 12 }}>
                <thead>
                  <tr>
                    <th>{t('vocab.sets.word')}</th>
                    <th>{t('vocab.sets.band')}</th>
                    <th>{t('vocab.sets.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {words.map((word, index) => (
                    <tr key={word.id}>
                      <td>{word.form}</td>
                      <td>{word.band ? t('vocab.detail.familyBand', { band: word.band }) : '—'}</td>
                      <td>
                        <span className="row" style={{ gap: 6 }}>
                          <Button size="small" disabled={index === 0}
                            onClick={() => void moveWord(index, -1)}>{t('vocab.library.moveUp')}</Button>
                          <Button size="small" disabled={index === words.length - 1}
                            onClick={() => void moveWord(index, 1)}>{t('vocab.library.moveDown')}</Button>
                          <Button size="small" variant="danger"
                            onClick={() => void removeWord(word.id)}>{t('vocab.sets.remove')}</Button>
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <p className="muted sm">{t('vocab.sets.removeHint')}</p>
          </>
        )}
      </div>
    </>
  )
}
