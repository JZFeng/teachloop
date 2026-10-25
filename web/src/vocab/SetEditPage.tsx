import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Alert, Button, Empty, ErrorState, Input, Loading, PageHeader, TextArea } from '../ui'
import { SpeakerButton } from './SpeakerButton'
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

interface Notice {
  type: 'success' | 'error'
  message: string
}

export default function VocabSetEditPage() {
  const { id } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()

  const [setId, setSetId] = useState<string | undefined>(id)
  const [name, setName] = useState('')
  const [language, setLanguage] = useState('en')
  const [description, setDescription] = useState('')
  const [active, setActive] = useState(true)
  const [words, setWords] = useState<SetWord[]>([])
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(Boolean(id))
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const load = useCallback(async (targetId?: string) => {
    const lookupId = targetId ?? setId
    if (!lookupId) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const detail = await api.get<{ set: SetView; words: SetWord[] }>(`/api/vocab/sets/${lookupId}`)
      setName(detail.set.name)
      setLanguage(detail.set.language || 'en')
      setDescription(detail.set.description ?? '')
      setActive(detail.set.active)
      setWords(detail.words)
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [setId])

  useEffect(() => { void load(id) }, [id, load])

  const save = async () => {
    setNotice(null)
    if (!name.trim()) {
      setNotice({ type: 'error', message: t('vocab.sets.nameRequired') })
      return
    }
    setBusy(true)
    try {
      // 学习语言不再让老师选（2026-10-24 用户决定）：V1 固定 en，由老师按词判断。
      const body = { name: name.trim(), language, description, active }
      const saved = setId
        ? await api.put<SetView>(`/api/vocab/sets/${setId}`, body)
        : await api.post<SetView>('/api/vocab/sets', body)
      setSetId(saved.id)
      setName(saved.name)
      if (!id) {
        window.history.replaceState(null, '', `/vocab/sets/${saved.id}`)
      }

      // 「保存」同时完成解析与入库（2026-10-24 用户决定：不再有预览/加入按钮）
      if (text.trim()) {
        const preview = await api.post<Preview>('/api/vocab/import/preview',
          { text, language: 'en' })
        const total = preview.distinctWords.length
        if (total === 0) {
          setNotice({ type: 'error', message: t('vocab.sets.noWordsParsed') })
        } else {
          const result = await api.post<{ added: number; skipped: number }>(
            `/api/vocab/sets/${saved.id}/words`, { words: preview.distinctWords })
          setNotice({
            type: result.added === total ? 'success' : 'error',
            message: t('vocab.sets.addResult', { added: result.added, total }),
          })
          setText('')
        }
      } else {
        setNotice({ type: 'success', message: t('vocab.sets.savedNotice') })
      }
      await load(saved.id)
    } catch (saveError) {
      setNotice({ type: 'error', message: (saveError as Error).message })
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
      <PageHeader
        title={setId ? t('vocab.sets.editTitle') : t('vocab.sets.newSet')}
        actions={(
          <Button size="small" onClick={() => navigate('/vocab/sets')}>
            {t('vocab.sets.backToList')}
          </Button>
        )}
      />

      <div className="card" style={{ maxWidth: 860 }}>
        <label className="row" style={{ gap: 8, marginBottom: 10 }}>
          <span style={{ width: 60 }}>{t('vocab.sets.name')}</span>
          <Input value={name} onChange={(event) => setName(event.target.value)} maxLength={100} />
          <label className="row" style={{ gap: 4 }}>
            <input type="checkbox" checked={active} onChange={() => setActive((value) => !value)} />
            {t('vocab.sets.active')}
          </label>
        </label>

        <label className="row" style={{ gap: 8, marginBottom: 14, alignItems: 'flex-start' }}>
          <span style={{ width: 60 }}>{t('vocab.sets.description')}</span>
          <TextArea rows={2} value={description} onChange={(event) => setDescription(event.target.value)} />
        </label>

        {notice && (
          <Alert type={notice.type} showIcon message={notice.message} style={{ marginBottom: 12 }} />
        )}

        <h3>{t('vocab.sets.words')}</h3>
        <TextArea rows={5} value={text} placeholder={t('vocab.sets.bulkPlaceholder')}
          onChange={(event) => setText(event.target.value)} />

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
                  <td>
                    <span className="row" style={{ gap: 6 }}>
                      {word.form}
                      <SpeakerButton form={word.form} compact />
                    </span>
                  </td>
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

        <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          <Button onClick={() => navigate('/vocab/sets')}>{t('vocab.sets.cancel')}</Button>
          <Button variant="primary" busy={busy} onClick={() => void save()}>{t('vocab.sets.save')}</Button>
        </div>
      </div>
    </>
  )
}
