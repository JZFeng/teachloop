import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import {
  Button,
  Empty,
  ErrorState,
  Loading,
  Modal,
  PageHeader,
  TextArea,
  useConfirm,
  useToast,
} from '../ui'
import './vocab.css'

interface Sense {
  pos?: string
  zh?: string
  en?: string
}

interface Example {
  en?: string
  zh?: string
}

interface FamilyMember {
  form: string
  freq: number
  head: boolean
}

interface Family {
  head: string
  band: number
  familyFreq: number
  matchedFolded: boolean
  members: FamilyMember[]
}

interface Source {
  kind: string
  label?: string
}

interface Content {
  senses?: Sense[]
  examples?: Example[]
  analysis?: string
  family?: string[]
  phonetic?: { uk?: string; us?: string }
}

interface Detail {
  inLibrary: boolean
  id: string | null
  form: string
  language: string
  contentStatus: string | null
  favorite: boolean
  paused: boolean
  tags: string[]
  state: string | null
  dueAt: string | null
  content: Content | null
  sources: Source[]
  family: Family | null
}

export default function VocabWordDetailPage() {
  const { form = '' } = useParams()
  const { t } = useTranslation()
  const confirm = useConfirm()
  const toast = useToast()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedbackNote, setFeedbackNote] = useState('')

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    api.get<Detail>(`/api/vocab/words/by-form/${encodeURIComponent(form)}`)
      .then((value) => { if (alive) setDetail(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [form, reloadKey])

  const toggleFavorite = async () => {
    if (!detail?.id) return
    await api.put(`/api/vocab/words/${detail.id}/favorite`, { favorite: !detail.favorite })
    refresh()
  }

  const pause = async () => {
    if (!detail?.id) return
    await api.post(`/api/vocab/words/${detail.id}/pause`)
    toast.success(t('vocab.detail.pausedToast', { word: detail.form }))
    refresh()
  }

  const resume = async () => {
    if (!detail?.id) return
    const ok = await confirm({
      title: t('vocab.detail.resumeTitle'),
      content: t('vocab.detail.resumeBody', { word: detail.form }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
    })
    if (!ok) return
    await api.post(`/api/vocab/words/${detail.id}/resume`)
    toast.success(t('vocab.detail.resumedToast', { word: detail.form }))
    refresh()
  }

  const addToLibrary = async () => {
    const result = await api.post<{ created: number; duplicated: number }>('/api/vocab/words',
      { form: detail?.form ?? form, language: detail?.language ?? 'en' })
    toast.success(result.created > 0
      ? t('vocab.detail.addedToast', { word: detail?.form ?? form })
      : t('vocab.detail.alreadyToast'))
    refresh()
  }

  const submitFeedback = async () => {
    if (!detail?.id) return
    await api.post(`/api/vocab/words/${detail.id}/feedback`, { note: feedbackNote.trim() })
    setFeedbackOpen(false)
    setFeedbackNote('')
    toast.success(t('vocab.detail.feedbackThanks'))
  }

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (!detail) return <Empty>{t('vocab.detail.notFound')}</Empty>

  const content = detail.content
  const actions = (
    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
      {detail.inLibrary ? (
        <>
          <Button size="small" variant={detail.favorite ? 'primary' : 'default'} onClick={() => void toggleFavorite()}>
            {detail.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
          </Button>
          {detail.paused
            ? <Button size="small" onClick={() => void resume()}>{t('vocab.library.resume')}</Button>
            : <Button size="small" onClick={() => void pause()}>{t('vocab.library.pause')}</Button>}
          <Button size="small" onClick={() => setFeedbackOpen(true)}>{t('vocab.detail.feedback')}</Button>
        </>
      ) : (
        <Button size="small" variant="primary" onClick={() => void addToLibrary()}>
          {t('vocab.detail.addToLibrary')}
        </Button>
      )}
    </div>
  )

  return (
    <>
      <PageHeader title={detail.form} sub={detail.inLibrary ? undefined : t('vocab.detail.notInLibraryHint')} actions={actions} />
      <div className="vocab-detail-grid">
        <section className="card">
          <h3>{t('vocab.detail.phonetic')}</h3>
          <div className="row" style={{ gap: 16 }}>
            <span>{t('vocab.detail.phoneticUk')} {content?.phonetic?.uk ?? '—'}</span>
            <span>{t('vocab.detail.phoneticUs')} {content?.phonetic?.us ?? '—'}</span>
          </div>
        </section>

        <section className="card">
          <h3>{t('vocab.detail.senses')}</h3>
          {(content?.senses?.length ?? 0) === 0
            ? <p className="muted">{t('vocab.detail.noContent')}</p>
            : (
              <ul className="vocab-sense">
                {content!.senses!.map((sense, index) => (
                  <li key={index}>
                    {sense.pos && <span className="chip">{sense.pos}</span>} {sense.zh ?? sense.en ?? ''}
                    {sense.en && sense.zh && <div className="muted">{sense.en}</div>}
                  </li>
                ))}
              </ul>
            )}
        </section>

        <section className="card">
          <h3>{t('vocab.detail.examples')}</h3>
          {(content?.examples?.length ?? 0) === 0
            ? <p className="muted">{t('vocab.detail.noContent')}</p>
            : (
              <ul className="vocab-sense">
                {content!.examples!.map((example, index) => (
                  <li key={index}>
                    {example.en}
                    {example.zh && <div className="muted">{example.zh}</div>}
                  </li>
                ))}
              </ul>
            )}
        </section>

        <section className="card">
          <h3>{t('vocab.detail.analysis')}</h3>
          <p>{content?.analysis || t('vocab.detail.noContent')}</p>
        </section>

        <section className="card">
          <h3>{t('vocab.detail.family')}</h3>
          {detail.family ? (
            <>
              <p className="muted">
                {detail.family.band >= 1 && detail.family.band <= 30
                  ? t('vocab.detail.familyBand', { band: detail.family.band })
                  : t('vocab.detail.familySupplement')}
                {' · '}
                {t('vocab.detail.familyFrequency', { freq: detail.family.familyFreq })}
              </p>
              <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                {detail.family.members.map((member) => (
                  <span key={member.form} className="chip">{member.form} · {member.freq}</span>
                ))}
              </div>
            </>
          ) : <p className="muted">{t('vocab.detail.familyMissing')}</p>}
        </section>

        <section className="card">
          <h3>{t('vocab.detail.meta')}</h3>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            {detail.inLibrary && <span className="chip">{t(`vocab.library.state.${detail.paused ? 'PAUSED' : (detail.state ?? 'NEW')}`)}</span>}
            {detail.inLibrary && detail.dueAt && (
              <span className="chip">{t('vocab.detail.nextReview')} {new Date(detail.dueAt).toLocaleDateString()}</span>
            )}
            {detail.tags.map((tag) => <span key={tag} className="chip">{tag}</span>)}
            {detail.inLibrary && (detail.sources.length === 0
              ? <span className="chip">{t('vocab.library.sourceKind.SELF')}</span>
              : detail.sources.map((source, index) => (
                <span key={index} className="chip">
                  {source.label ?? t(`vocab.library.sourceKind.${source.kind}`)}
                </span>
              )))}
          </div>
        </section>
      </div>

      <Modal open={feedbackOpen} title={t('vocab.detail.feedbackTitle')}
        onCancel={() => setFeedbackOpen(false)}
        onOk={() => void submitFeedback()} okText={t('vocab.detail.feedbackSubmit')}
        cancelText={t('common.cancel')}>
        <TextArea rows={4} value={feedbackNote} placeholder={t('vocab.detail.feedbackPlaceholder')}
          onChange={(event) => setFeedbackNote(event.target.value)} />
      </Modal>
    </>
  )
}
