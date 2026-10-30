import type { ApiClient } from '../api/client'
import { createClass, joinClass } from '../api/class.api'
import { createOffering, listSubmissions, publishOffering } from '../api/offering.api'
import { addEssayItem, addSection, createPaper, publishPaper } from '../api/paper.api'
import { startExam, submitEssay } from '../api/exam.api'

/**
 * 场景工厂：把"一串 API 调用"变成一句可读的前置条件。
 *
 * 约定（`docs/process/e2e-playwright.md` §5）：**造数据全部走 API，用例里只留 1–2 个 UI 动作**。
 * 所以这里返回的不是"一堆 id"，而是「已经布置好、学生已交过一份」的完整状态 + 深链 URL，
 * 用例拿它直接 `page.goto`。
 *
 * 隔离：每次调用生成带时间戳的班级名 / 试卷名，避免与 dev 种子数据或其它用例互相污染。
 */
export interface EssayScenario {
  classId: string
  className: string
  paperId: string
  paperTitle: string
  offeringId: string
  /** 学生作答页深链；用例直接 goto，不点菜单。 */
  examUrl: string
  submissionId: string
  studentName: string
  essayText: string
}

function stamp(): string {
  return `${Date.now().toString(36)}-${Math.floor(Math.random() * 1e4).toString(36)}`
}

/**
 * 造一条完整的写作链路：
 * 教师建班 → 教师出卷（1 道 ESSAY）→ 发布 → 学生凭邀请码进班 → 教师布置并发布 →
 * 学生 start → 学生交卷。返回深链与提交 id。
 */
export async function givenSubmittedEssay(input: {
  teacher: ApiClient
  student: ApiClient
  studentName: string
  essayText?: string
}): Promise<EssayScenario> {
  const suffix = stamp()
  const className = `E2E 班级 ${suffix}`
  const essayText = input.essayText ?? `E2E 写作内容 ${suffix}`

  const created = await createClass(input.teacher, className)

  const paperTitle = `E2E 写作卷 ${suffix}`
  const paper = await createPaper(input.teacher, paperTitle)
  const section = await addSection(input.teacher, paper.id, '写作', 10)
  await addEssayItem(input.teacher, paper.id, {
    number: 1,
    sectionId: section.id,
    stem: '请以 My Weekend 为题写一段不少于 40 词的短文。',
    points: 10,
  })
  await publishPaper(input.teacher, paper.id)

  await joinClass(input.student, created.inviteCode)

  const offering = await createOffering(input.teacher, { paperId: paper.id, classIds: [created.id] })
  await publishOffering(input.teacher, offering.id)

  await startExam(input.student, offering.id)
  const submitted = await submitEssay(input.student, offering.id, essayText)

  return {
    classId: created.id,
    className,
    paperId: paper.id,
    paperTitle,
    offeringId: offering.id,
    examUrl: `/exams/${offering.id}`,
    submissionId: submitted.submissionId,
    studentName: input.studentName,
    essayText,
  }
}

/** 教师侧核对：提交表里应该有这名学生、且不是 active（已交卷）。 */
export async function findSubmission(teacher: ApiClient, offeringId: string, studentName: string) {
  const rows = await listSubmissions(teacher, offeringId)
  return rows.find((row) => row.studentName.includes(studentName))
}
