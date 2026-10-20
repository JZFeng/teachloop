import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassView, OfferingView, PaperView } from '../api/types'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import {
  errorMessage,
  fromLocalInput,
  Tag,
  TeacherPage,
  useApiData,
} from './common'

type Kind = 'EXAM' | 'ASSIGNMENT'
type Timing = 'INDIVIDUAL' | 'FIXED_WINDOW'

interface Override {
  dueAt: string
  duration: string
}

/**
 * T201 布置页：试卷 + 类型 + 参与班级（多选）+ 时间窗口 + 按班覆盖 + 发布。
 * 试卷本身不含班级，班级关系只存在于这一次布置里。
 */
export default function OfferingCreatePage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const presetPaperId = params.get('paperId') ?? ''

  const { data, loading, error, reload } = useApiData<{ papers: PaperView[]; classes: ClassView[] }>(
    async () => {
      const [papers, classes] = await Promise.all([
        api.get<PaperView[]>('/api/papers'),
        api.get<ClassView[]>('/api/classes'),
      ])
      return { papers, classes }
    },
    [],
  )

  const [paperId, setPaperId] = useState(presetPaperId)
  const [kind, setKind] = useState<Kind>('EXAM')
  const [selectedClassIds, setSelectedClassIds] = useState<string[]>([])
  const [startAt, setStartAt] = useState('')
  const [dueAt, setDueAt] = useState('')
  const [duration, setDuration] = useState('')
  const [timingMode, setTimingMode] = useState<Timing>('INDIVIDUAL')
  const [overrides, setOverrides] = useState<Record<string, Override>>({})
  const [publish, setPublish] = useState(true)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const papers = data?.papers ?? []
  const classes = data?.classes ?? []
  const paper = papers.find((p) => p.id === paperId) ?? null

  // 时长默认继承试卷的 defaultDurationMinutes，本场可覆盖（AC-26.3）。
  useEffect(() => {
    if (!paper) return
    setDuration(paper.defaultDurationMinutes === null ? '' : String(paper.defaultDurationMinutes))
  }, [paperId, paper])

  const toggleClass = (classId: string) => {
    setSelectedClassIds((current) =>
      current.includes(classId) ? current.filter((id) => id !== classId) : [...current, classId],
    )
  }

  const setOverride = (classId: string, field: keyof Override, value: string) => {
    setOverrides((current) => {
      const existing: Override = current[classId] ?? { dueAt: '', duration: '' }
      return {
        ...current,
        [classId]: { ...existing, [field]: value },
      }
    })
  }

  const submit = async (shouldPublish: boolean) => {
    setFormError(null)
    if (!paper) {
      setFormError(t('teacher.offering.errors.paper'))
      return
    }
    if (selectedClassIds.length === 0) {
      setFormError(t('teacher.offering.errors.classes'))
      return
    }
    const dueIso = fromLocalInput(dueAt)
    const startIso = fromLocalInput(startAt)
    if (!dueIso) {
      setFormError(t('teacher.offering.errors.dueAt'))
      return
    }
    if (startIso && new Date(dueIso) <= new Date(startIso)) {
      setFormError(t('teacher.offering.errors.dueAfterStart'))
      return
    }
    const durationValue = duration.trim() === '' ? null : Number(duration)
    if (durationValue !== null && (!Number.isFinite(durationValue) || durationValue <= 0)) {
      setFormError(t('teacher.offering.errors.duration'))
      return
    }
    if (kind === 'EXAM' && (durationValue === null || durationValue <= 0)) {
      setFormError(t('teacher.offering.errors.examDuration'))
      return
    }
    if (kind === 'EXAM' && timingMode === 'FIXED_WINDOW' && !startIso) {
      setFormError(t('teacher.offering.errors.fixedWindowStart'))
      return
    }

    const classAssignments: Array<{
      classId: string
      dueAtOverride: string | null
      durationOverride: number | null
    }> = []
    for (const classId of selectedClassIds) {
      const override = overrides[classId]
      const overrideDue = fromLocalInput(override?.dueAt ?? '')
      if (overrideDue && startIso && new Date(overrideDue) <= new Date(startIso)) {
        setFormError(t('teacher.offering.errors.overrideDue'))
        return
      }
      const overrideDuration =
        !override || override.duration.trim() === '' ? null : Number(override.duration)
      if (
        overrideDuration !== null &&
        (!Number.isFinite(overrideDuration) || overrideDuration <= 0)
      ) {
        setFormError(t('teacher.offering.errors.overrideDuration'))
        return
      }
      classAssignments.push({
        classId,
        dueAtOverride: overrideDue,
        durationOverride: overrideDuration,
      })
    }

    setBusy(true)
    try {
      const created = await api.post<OfferingView>('/api/offerings', {
        paperId: paper.id,
        kind,
        startAt: startIso,
        dueAt: dueIso,
        durationMinutes: durationValue,
        timingMode,
        classes: classAssignments,
        publish: shouldPublish,
      })
      navigate(`/grading/${created.id}`)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const handleSubmit = (shouldPublish: boolean) => {
    try {
      void submit(shouldPublish)
    } catch (err) {
      setFormError(errorMessage(err))
    }
  }

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/papers">{t('teacher.papers.title')}</Link> /{' '}
        <b>{t('teacher.offering.title')}</b>
      </div>
      <PageHeader title={t('teacher.offering.title')} sub={t('teacher.offering.sub')} />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          {papers.length === 0 ? (
            <Empty>
              {t('teacher.offering.noPapers')}{' '}
              <Link to="/papers/new">{t('teacher.offering.createPaper')}</Link>
            </Empty>
          ) : (
            <div className="card" style={{ maxWidth: 960 }}>
              <h2>
                {paper ? paper.title : t('teacher.offering.selectPaper')}{' '}
                {paper && (
                  <small>
                    {t('teacher.offering.paperMeta', {
                      points: paper.totalPoints,
                      version: paper.version,
                    })}
                  </small>
                )}
              </h2>

              <label className="f">
                <b>
                  {t('teacher.offering.paper')} <i>*</i>
                </b>
                <select value={paperId} onChange={(e) => setPaperId(e.target.value)}>
                  <option value="">{t('teacher.offering.selectPaper')}</option>
                  {papers.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.title} · {p.totalPoints} {t('teacher.common.points')} ·{' '}
                      {t(`teacher.paperStatus.${p.status}`)}
                    </option>
                  ))}
                </select>
                <div className="hint">{t('teacher.offering.paperHint')}</div>
              </label>

              <label className="f">
                <b>{t('teacher.offering.kind')}</b>
                <select value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
                  <option value="EXAM">{t('teacher.kind.EXAM')}</option>
                  <option value="ASSIGNMENT">{t('teacher.kind.ASSIGNMENT')}</option>
                </select>
                <div className="hint">{t('teacher.offering.kindHint')}</div>
              </label>

              <div className="f">
                <b>
                  {t('teacher.offering.classes')} <i>*</i>
                </b>
                {classes.length === 0 ? (
                  <p className="muted">
                    {t('teacher.offering.noClasses')} <Link to="/classes">{t('teacher.offering.goClasses')}</Link>
                  </p>
                ) : (
                  <div className="chips" style={{ marginTop: 6, marginBottom: 4 }}>
                    {classes.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        className={`chip${selectedClassIds.includes(c.id) ? ' on' : ''}`}
                        onClick={() => toggleClass(c.id)}
                      >
                        {c.name} ({c.memberCount})
                      </button>
                    ))}
                  </div>
                )}
                <div className="hint">{t('teacher.offering.classesHint')}</div>
              </div>

              <div className="form-grid">
                <label className="f">
                  <b>{t('teacher.offering.startAt')}</b>
                  <input
                    type="datetime-local"
                    value={startAt}
                    onChange={(e) => setStartAt(e.target.value)}
                  />
                </label>
                <label className="f">
                  <b>
                    {t('teacher.offering.dueAt')} <i>*</i>
                  </b>
                  <input
                    type="datetime-local"
                    value={dueAt}
                    onChange={(e) => setDueAt(e.target.value)}
                  />
                </label>
                <label className="f">
                  <b>{t('teacher.offering.duration')}</b>
                  <input
                    type="number"
                    min={1}
                    value={duration}
                    onChange={(e) => setDuration(e.target.value)}
                    placeholder={t('teacher.offering.durationPlaceholder')}
                  />
                  <div className="hint">{t('teacher.offering.durationHint')}</div>
                </label>
                <label className="f">
                  <b>{t('teacher.offering.timingMode')}</b>
                  <select
                    value={timingMode}
                    onChange={(e) => setTimingMode(e.target.value as Timing)}
                  >
                    <option value="INDIVIDUAL">{t('teacher.timing.INDIVIDUAL')}</option>
                    <option value="FIXED_WINDOW">{t('teacher.timing.FIXED_WINDOW')}</option>
                  </select>
                  <div className="hint">{t('teacher.offering.timingHint')}</div>
                </label>
              </div>

              <h3 style={{ fontSize: 14, margin: '18px 0 8px' }}>
                {t('teacher.offering.overrides')}
              </h3>
              {selectedClassIds.length === 0 ? (
                <p className="muted">{t('teacher.offering.overridesEmpty')}</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>{t('teacher.classes.colName')}</th>
                      <th>{t('teacher.offering.overrideDue')}</th>
                      <th>{t('teacher.offering.overrideDuration')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedClassIds.map((classId) => {
                      const current = classes.find((c) => c.id === classId)
                      const override = overrides[classId]
                      return (
                        <tr key={classId}>
                          <td>{current?.name ?? classId}</td>
                          <td>
                            <input
                              type="datetime-local"
                              value={override?.dueAt ?? ''}
                              onChange={(e) => setOverride(classId, 'dueAt', e.target.value)}
                            />
                            {!override?.dueAt && (
                              <span className="flag"> {t('teacher.offering.inherit')}</span>
                            )}
                          </td>
                          <td>
                            <input
                              type="number"
                              min={1}
                              placeholder={t('teacher.offering.inherit')}
                              value={override?.duration ?? ''}
                              onChange={(e) => setOverride(classId, 'duration', e.target.value)}
                            />
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}

              <div className="row" style={{ marginTop: 16 }}>
                <button
                  type="button"
                  className={`switch${publish ? ' on' : ''}`}
                  role="switch"
                  aria-checked={publish}
                  onClick={() => setPublish((v) => !v)}
                >
                  <i />
                  <span>{t('teacher.offering.publishSwitch')}</span>
                </button>
                {publish ? <Tag tone="green">{t('teacher.common.yes')}</Tag> : <Tag tone="gray">{t('teacher.common.no')}</Tag>}
              </div>
              <div className="hint" style={{ marginTop: 6 }}>
                {t('teacher.offering.publishHint')}
              </div>

              {formError && (
                <div className="inline-note" style={{ borderLeftColor: 'var(--red)', marginTop: 12 }}>
                  {formError}
                </div>
              )}

              <div className="row" style={{ marginTop: 14 }}>
                <button
                  type="button"
                  className="btn pri"
                  disabled={busy}
                  onClick={() => handleSubmit(publish)}
                >
                  {busy
                    ? t('teacher.common.saving')
                    : publish
                      ? t('teacher.offering.publish')
                      : t('teacher.offering.saveDraft')}
                </button>
                <button
                  type="button"
                  className="btn"
                  disabled={busy}
                  onClick={() => handleSubmit(false)}
                >
                  {t('teacher.offering.saveDraftOnly')}
                </button>
              </div>
              <div className="hint" style={{ marginTop: 10 }}>
                {t('teacher.offering.snapshotHint')}
              </div>
            </div>
          )}
        </>
      )}
    </TeacherPage>
  )
}
