import { lazy, Suspense, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { assignableClasses, isAssignableClass } from '../api/types'
import type { ClassView, OfferingView } from '../api/types'
import {
  Alert,
  Button,
  ErrorState,
  Field,
  Input,
  Loading,
  PageHeader,
  UploadOutlined,
  useConfirm,
  useToast,
} from '../ui'
import { errorMessage, fromLocalInput, TeacherPage, useApiData } from './common'
import { RubricPreview } from '../components/RubricPreview'
import { MARKDOWN_IMPORT_MAX_BYTES, readMarkdownFile } from './markdownImport'

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
 *
 * T422：题目要求也加同款导入（两个字段都是 Markdown，只导入一半没道理）；覆盖前先确认，
 * 文件超过 1 MB / 为空 / 读不到各有提示——读取逻辑在 markdownImport.ts，不解析内容。
 */
export default function AssignmentCreatePage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  // 两个字段各一个 input：共用同一个 input 时要记「这次点的是哪个字段」，状态反而更绕。
  const instructionsFileRef = useRef<HTMLInputElement>(null)
  const rubricFileRef = useRef<HTMLInputElement>(null)
  const confirm = useConfirm()
  const toast = useToast()

  const { data, loading, error, reload } = useApiData<ClassView[]>(
    () => api.get<ClassView[]>('/api/classes'),
    [],
  )

  const [title, setTitle] = useState('')
  const [instructions, setInstructions] = useState('')
  const [totalPoints, setTotalPoints] = useState('')
  // 空就是空：不预填任何 rubric。留空无法发布，提示里说清格式与「合计 = 总分」。
  const [rubricMarkdown, setRubricMarkdown] = useState('')
  const [selectedClassIds, setSelectedClassIds] = useState<string[]>([])
  const [dueAt, setDueAt] = useState('')
  const [graceHours, setGraceHours] = useState('3')
  const [maxAttempts, setMaxAttempts] = useState('3')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const classes = data ?? []
  const assignable = assignableClasses(classes)

  // AC-98.7：结课的班点不动——禁用只是视觉，选择状态也不能被它改（服务端另有 409 兜底）。
  const toggleClass = (classId: string) => {
    const target = classes.find((c) => c.id === classId)
    if (!target || !isAssignableClass(target)) return
    setSelectedClassIds((current) =>
      current.includes(classId) ? current.filter((id) => id !== classId) : [...current, classId],
    )
  }

  /**
   * 导入 .md / .txt：只读文件、不解析内容，所以失败面只有三种——太大、空、读不到。
   * 目标字段已有内容时先确认：写入会覆盖，且程序化改值会清掉编辑器的撤销栈。
   */
  const importMarkdown = async (
    file: File | undefined,
    current: string,
    fieldLabel: string,
    apply: (text: string) => void,
  ) => {
    if (!file) return
    const result = await readMarkdownFile(file)
    if (!result.ok) {
      toast.error(
        result.reason === 'tooLarge'
          ? t('teacher.assignmentCreate.importTooLarge', {
              max: MARKDOWN_IMPORT_MAX_BYTES / (1024 * 1024),
            })
          : result.reason === 'empty'
            ? t('teacher.assignmentCreate.importEmpty')
            : t('teacher.assignmentCreate.importUnreadable'),
      )
      return
    }
    if (current.trim() !== '') {
      const ok = await confirm({
        title: t('teacher.assignmentCreate.importOverwriteTitle'),
        content: t('teacher.assignmentCreate.importOverwriteBody', {
          name: file.name,
          field: fieldLabel,
        }),
        okText: t('common.confirm'),
        cancelText: t('common.cancel'),
      })
      if (!ok) return
    }
    apply(result.text)
    toast.success(t('teacher.assignmentCreate.importDone', { name: file.name }))
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
        info={t('teacher.assignmentCreate.sub')}
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <div className="card" style={{ maxWidth: 960 }}>
          <Field
            label={t('teacher.assignmentCreate.fieldTitle')}
            required
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
            htmlFor="assign-instructions"
            extra={(
              <Button
                size="small"
                icon={<UploadOutlined />}
                onClick={() => instructionsFileRef.current?.click()}
              >
                {t('teacher.assignmentCreate.importMarkdown')}
              </Button>
            )}
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
            <input
              ref={instructionsFileRef}
              id="assign-instructions-file"
              type="file"
              accept=".md,.markdown,.txt,text/markdown,text/plain"
              hidden
              onChange={(e) => {
                void importMarkdown(
                  e.target.files?.[0],
                  instructions,
                  t('teacher.assignmentCreate.fieldInstructions'),
                  setInstructions,
                )
                e.target.value = ''
              }}
            />
          </Field>

          <Field
            label={t('teacher.assignmentCreate.fieldTotalPoints')}
            required
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
                onClick={() => rubricFileRef.current?.click()}
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
              ref={rubricFileRef}
              id="assign-rubric-file"
              type="file"
              accept=".md,.markdown,.txt,text/markdown,text/plain"
              hidden
              onChange={(e) => {
                void importMarkdown(
                  e.target.files?.[0],
                  rubricMarkdown,
                  t('teacher.assignmentCreate.fieldRubric'),
                  setRubricMarkdown,
                )
                e.target.value = ''
              }}
            />
          </Field>

          <Field label={t('teacher.assignmentCreate.classes')} required>
            {classes.length === 0 ? (
              <p className="muted">
                {t('teacher.assignmentCreate.noClasses')}{' '}
                <Link to="/classes">{t('teacher.assignmentCreate.goClasses')}</Link>
              </p>
            ) : (
              <div className="chips" style={{ marginTop: 6, marginBottom: 4 }}>
                {classes.map((c) => {
                  // AC-98.7：结课的班留在名单里（让人看见它为什么选不了），但禁用并写明原因。
                  const blocked = !isAssignableClass(c)
                  return (
                    <button
                      key={c.id}
                      type="button"
                      className={`chip${selectedClassIds.includes(c.id) ? ' on' : ''}`}
                      aria-pressed={selectedClassIds.includes(c.id)}
                      disabled={blocked}
                      title={blocked ? t('class.lifecycle.archivedCannotAssign') : undefined}
                      style={blocked ? { opacity: 0.55, cursor: 'not-allowed' } : undefined}
                      onClick={() => toggleClass(c.id)}
                    >
                      {c.name} ({c.memberCount})
                      {blocked && <> · {t('class.lifecycle.archivedSection')}</>}
                    </button>
                  )
                })}
              </div>
            )}
            {assignable.length < classes.length && (
              <div className="hint">{t('class.lifecycle.archivedCannotAssign')}</div>
            )}
          </Field>

          <div className="form-grid">
            <Field
              label={t('teacher.assignmentCreate.dueAt')}
              required
              info={t('teacher.assignmentCreate.publishHint')}
              htmlFor="assign-due"
            >
              <input
                id="assign-due"
                type="datetime-local"
                value={dueAt}
                onChange={(e) => setDueAt(e.target.value)}
              />
            </Field>
            <Field
              label={t('teacher.assignmentCreate.graceHours')}
              info={t('teacher.assignmentCreate.graceHint')}
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
              info={t('teacher.assignmentCreate.maxAttemptsHint')}
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
        </div>
      )}
    </TeacherPage>
  )
}
