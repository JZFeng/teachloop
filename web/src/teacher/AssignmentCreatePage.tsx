import { lazy, Suspense, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { ClassView, OfferingView } from '../api/types'
import { Alert, Button, ErrorState, Field, Input, Loading, PageHeader, TextArea, UploadOutlined } from '../ui'
import { summarizeRubric } from '../components/rubric'
import { errorMessage, fromLocalInput, TeacherPage, useApiData } from './common'

/** T339：Vditor 只在打开这一页时加载（含 Lute 引擎），不要把 600 kB 的编辑器塞进首屏。 */
const VditorMarkdown = lazy(() => import('../components/VditorMarkdown'))


/**
 * T240 / D-14 严格版：新建作业。
 * 作业自带内容——标题、题目要求、总分、评分标准——不引用试卷，也没有部分、题号、标准答案、
 * 时长与计时模式。提交时只发作业自身的字段，kind 由后端按「无试卷」派生为 ASSIGNMENT。
 *
 * T325：评分标准一栏按教师反馈重做——不再预填示例（以前那段英文 2/4/4 rubric 与教师写的
 * 作业毫无关系，还会被误当成 AI 真会看到的东西）、「导入 .md」升成真按钮并挪到标签旁、
 * 两段长文本改成 Markdown 编辑器（AI 读的就是 Markdown，富文本会被后端白名单洗掉结构）。
 */
export default function AssignmentCreatePage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const { data, loading, error, reload } = useApiData<ClassView[]>(
    () => api.get<ClassView[]>('/api/classes'),
    [],
  )

  const [title, setTitle] = useState('')
  const [instructions, setInstructions] = useState('')
  const [totalPoints, setTotalPoints] = useState('')
  // 空就是空：不预填任何 rubric。留空无法发布，提示里说清格式与「合计 = 总分」。
  const [rubricMarkdown, setRubricMarkdown] = useState('')
  const [anchorExamples, setAnchorExamples] = useState('')
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

  const importMarkdown = (file: File | undefined) => {
    if (!file) return
    void file.text().then((text) => {
      setRubricMarkdown(text.replace(/\r\n/g, '\n'))
    })
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
    if (rubricMarkdown.trim() === '') {
      setFormError(t('teacher.assignmentCreate.errors.rubric'))
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
        anchorExamples: anchorExamples.trim() || null,
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
            <Suspense fallback={<div className="md-editor md-editor-loading">{t('editor.loading')}</div>}>
              <VditorMarkdown
                id="assign-instructions"
                rows={6}
                value={instructions}
                onChange={setInstructions}
                placeholder={t('teacher.assignmentCreate.instructionsPlaceholder')}
                expandable
                heading={t('teacher.assignmentCreate.fieldInstructions')}
              />
            </Suspense>
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
            required
            hint={t('teacher.assignmentCreate.rubricHint')}
            htmlFor="assign-rubric"
            extra={(
              <Button
                size="small"
                icon={<UploadOutlined />}
                onClick={() => fileInputRef.current?.click()}
              >
                {t('teacher.assignmentCreate.importMarkdown')}
              </Button>
            )}
          >
            <Suspense fallback={<div className="md-editor md-editor-loading">{t('editor.loading')}</div>}>
              <VditorMarkdown
                id="assign-rubric"
                rows={9}
                value={rubricMarkdown}
                onChange={setRubricMarkdown}
                placeholder={t('teacher.assignmentCreate.rubricPlaceholder')}
                expandable
                heading={t('teacher.assignmentCreate.fieldRubric')}
                footer={(
                  <RubricPreview
                    rubric={rubricMarkdown}
                    totalPoints={Number(totalPoints)}
                  />
                )}
              />
            </Suspense>
            <input
              ref={fileInputRef}
              type="file"
              accept=".md,.markdown,.txt,text/markdown,text/plain"
              hidden
              onChange={(e) => {
                importMarkdown(e.target.files?.[0])
                e.target.value = ''
              }}
            />
          </Field>

          <Field
            label={t('teacher.assignmentCreate.anchorExamples')}
            hint={t('teacher.assignmentCreate.anchorExamplesHint')}
            htmlFor="assign-anchors"
          >
            <TextArea
              id="assign-anchors"
              rows={4}
              value={anchorExamples}
              onChange={(e) => setAnchorExamples(e.target.value)}
              placeholder={t('teacher.assignmentCreate.anchorExamplesPlaceholder')}
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

/**
 * 「AI 将看到」：不再把 rubric 原文再打印一遍，而是按后端同一套规则解析出评分项与合计，
 * 和总分对一下。教师写完立刻知道 AI 会照着哪几条打分、总分对不对得上。
 */
function RubricPreview({ rubric, totalPoints }: { rubric: string; totalPoints: number }) {
  const { t } = useTranslation('translation')
  const summary = summarizeRubric(rubric)
  const expected = Number.isFinite(totalPoints) && totalPoints > 0 ? totalPoints : null
  const matched = expected !== null && !summary.missingPoints && Math.abs(summary.total - expected) < 0.01

  return (
    <div className="rubric-preview">
      <div className="rubric-preview-head">
        <b>{t('teacher.assignmentCreate.aiPreview')}</b>
        {summary.criteria.length > 0 && (
          <span className="small muted">
            {t('teacher.assignmentCreate.aiPreviewSum', {
              total: summary.total,
              expected: expected ?? '—',
            })}
          </span>
        )}
      </div>
      {summary.criteria.length === 0 ? (
        <div className="small muted">{t('teacher.assignmentCreate.aiPreviewEmpty')}</div>
      ) : (
        <>
          <ol className="rubric-preview-list">
            {summary.criteria.map((criterion, index) => (
              <li key={`${criterion.name}-${index}`}>
                <span>{criterion.name}</span>
                <span className="mono">{criterion.points ?? '—'}</span>
              </li>
            ))}
          </ol>
          <div className="small muted">
            {summary.missingPoints
              ? t('teacher.assignmentCreate.aiPreviewMissingPoints')
              : !summary.anyPoints
                ? t('teacher.assignmentCreate.aiPreviewEvenSplit')
                : matched
                  ? t('teacher.assignmentCreate.aiPreviewMatched')
                  : t('teacher.assignmentCreate.aiPreviewMismatch', { expected: expected ?? '—' })}
          </div>
        </>
      )}
    </div>
  )
}
