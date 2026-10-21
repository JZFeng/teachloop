import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { Alert, Button, ErrorState, Field, Input, Loading, PageHeader, TextArea } from '../ui'
import { errorMessage, fromLocalInput, TeacherPage, useApiData } from './common'


/**
 * T240 / D-14 严格版：新建作业。
 * 作业自带内容——标题、题目要求、总分、评分标准——不引用试卷，也没有部分、题号、标准答案、
 * 时长与计时模式。提交时只发作业自身的字段，kind 由后端按「无试卷」派生为 ASSIGNMENT。
 */
export default function AssignmentCreatePage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()

  const { data, loading, error, reload } = useApiData<ClassView[]>(
    () => api.get<ClassView[]>('/api/classes'),
    [],
  )

  const [title, setTitle] = useState('')
  const [instructions, setInstructions] = useState('')
  const [totalPoints, setTotalPoints] = useState('')
  // 评分标准默认两行：内容要点 / 语言准确性；教师可以直接在 Markdown 里补分值与细则。
  const [rubricMarkdown, setRubricMarkdown] = useState(t('teacher.assignmentCreate.defaultRubric'))
  const [selectedClassIds, setSelectedClassIds] = useState<string[]>([])
  const [dueAt, setDueAt] = useState('')
  const [graceHours, setGraceHours] = useState('3')
  const [maxAttempts, setMaxAttempts] = useState('3')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const classes = data ?? []

  const toggleClass = (classId: string) => {
    setSelectedClassIds((current) =>
      current.includes(classId) ? current.filter((id) => id !== classId) : [...current, classId],
    )
  }

  const submit = async () => {
    setFormError(null)
    if (title.trim() === '') {
      setFormError(t('teacher.assignmentCreate.errors.title'))
      return
    }
    if (instructions.trim() === '') {
      setFormError(t('teacher.assignmentCreate.errors.instructions'))
      return
    }
    const points = Number(totalPoints)
    if (!Number.isFinite(points) || points < 1 || points > 1000) {
      setFormError(t('teacher.assignmentCreate.errors.totalPoints'))
      return
    }
    if (selectedClassIds.length === 0) {
      setFormError(t('teacher.assignmentCreate.errors.classes'))
      return
    }
    const dueIso = fromLocalInput(dueAt)
    if (!dueIso) {
      setFormError(t('teacher.assignmentCreate.errors.dueAt'))
      return
    }
    const grace = Number(graceHours)
    if (!Number.isInteger(grace) || grace < 0) {
      setFormError(t('teacher.assignmentCreate.errors.graceHours'))
      return
    }
    const attempts = Number(maxAttempts)
    if (!Number.isInteger(attempts) || attempts < 1) {
      setFormError(t('teacher.assignmentCreate.errors.maxAttempts'))
      return
    }

    setBusy(true)
    try {
      const created = await api.post<OfferingView>('/api/offerings', {
        title: title.trim(),
        instructions: instructions.trim(),
        totalPoints: points,
        rubricMarkdown,
        dueAt: dueIso,
        graceHours: grace,
        maxAttempts: attempts,
        classes: selectedClassIds.map((classId) => ({ classId })),
        publish: true,
      })
      navigate(`/grading/${created.id}`)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/assignments">{t('teacher.assignments.title')}</Link> /{' '}
        <b>{t('teacher.assignmentCreate.title')}</b>
      </div>
      <PageHeader
        title={t('teacher.assignmentCreate.title')}
        sub={t('teacher.assignmentCreate.sub')}
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <div className="card" style={{ maxWidth: 960 }}>
          <Field
            label={t('teacher.assignmentCreate.fieldTitle')}
            required
            hint={t('teacher.assignmentCreate.titleHint')}
            htmlFor="assign-title"
          >
            <Input
              id="assign-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t('teacher.assignmentCreate.titlePlaceholder')}
            />
          </Field>

          <Field
            label={t('teacher.assignmentCreate.fieldInstructions')}
            required
            hint={t('teacher.assignmentCreate.instructionsHint')}
            htmlFor="assign-instructions"
          >
            <TextArea
              id="assign-instructions"
              rows={6}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
              placeholder={t('teacher.assignmentCreate.instructionsPlaceholder')}
            />
          </Field>

          <Field
            label={t('teacher.assignmentCreate.fieldTotalPoints')}
            required
            hint={t('teacher.assignmentCreate.totalPointsHint')}
            htmlFor="assign-total"
          >
            <Input
              id="assign-total"
              type="number"
              min={1}
              max={1000}
              value={totalPoints}
              onChange={(e) => setTotalPoints(e.target.value)}
              placeholder={t('teacher.assignmentCreate.totalPointsPlaceholder')}
            />
          </Field>

          <Field
            label={t('teacher.assignmentCreate.fieldRubric')}
            hint={t('teacher.assignmentCreate.rubricHint')}
            htmlFor="assign-rubric"
          >
            <TextArea
              id="assign-rubric"
              rows={6}
              value={rubricMarkdown}
              onChange={(e) => setRubricMarkdown(e.target.value)}
            />
          </Field>

          <Field label={t('teacher.assignmentCreate.classes')} required hint={t('teacher.assignmentCreate.classesHint')}>
            {classes.length === 0 ? (
              <p className="muted">
                {t('teacher.assignmentCreate.noClasses')}{' '}
                <Link to="/classes">{t('teacher.assignmentCreate.goClasses')}</Link>
              </p>
            ) : (
              <div className="chips" style={{ marginTop: 6, marginBottom: 4 }}>
                {classes.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    className={`chip${selectedClassIds.includes(c.id) ? ' on' : ''}`}
                    aria-pressed={selectedClassIds.includes(c.id)}
                    onClick={() => toggleClass(c.id)}
                  >
                    {c.name} ({c.memberCount})
                  </button>
                ))}
              </div>
            )}
          </Field>

          <div className="form-grid">
            <Field label={t('teacher.assignmentCreate.dueAt')} required htmlFor="assign-due">
              <input
                id="assign-due"
                type="datetime-local"
                value={dueAt}
                onChange={(e) => setDueAt(e.target.value)}
              />
            </Field>
            <Field
              label={t('teacher.assignmentCreate.graceHours')}
              hint={t('teacher.assignmentCreate.graceHint')}
              htmlFor="assign-grace"
            >
              <Input
                id="assign-grace"
                type="number"
                min={0}
                value={graceHours}
                onChange={(e) => setGraceHours(e.target.value)}
              />
            </Field>
            <Field
              label={t('teacher.assignmentCreate.maxAttempts')}
              hint={t('teacher.assignmentCreate.maxAttemptsHint')}
              htmlFor="assign-attempts"
            >
              <Input
                id="assign-attempts"
                type="number"
                min={1}
                value={maxAttempts}
                onChange={(e) => setMaxAttempts(e.target.value)}
              />
            </Field>
          </div>

          {formError && <Alert type="error" showIcon message={formError} style={{ marginTop: 12 }} />}

          <div className="row" style={{ marginTop: 14 }}>
            <Button variant="primary" busy={busy} onClick={() => void submit()}>
              {busy
                ? t('teacher.assignmentCreate.publishing')
                : t('teacher.assignmentCreate.publish')}
            </Button>
          </div>
          <div className="hint" style={{ marginTop: 10 }}>
            {t('teacher.assignmentCreate.publishHint')}
          </div>
        </div>
      )}
    </TeacherPage>
  )
}
