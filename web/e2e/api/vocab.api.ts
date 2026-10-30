import type { ApiClient } from './client'

/**
 * 背单词域（`teachloop-vocab`）：只包今日页 E2E 需要的那几步。
 *
 * 与班级/试卷一样，账号不在这里建：注册链路要收激活邮件（`docs/process/e2e-playwright.md` §2.3），
 * 这里只负责「给已有学生加词 / 发词」这种数据前置。
 */
export interface PasteResult {
  counts: { total: number; created: number; duplicated: number; failed: number }
  batchId: string
}

/** 学生自己加词（`WordsPasteEndpoint`）：一行一个词，允许带释义，例如 `apple 苹果`。 */
export async function pasteWords(api: ApiClient, text: string, language = 'en'): Promise<PasteResult> {
  return api.post<PasteResult>('/api/vocab/words/paste', { text, language })
}

export interface SetView {
  id: string
  name: string
}

/** 教师建词集（`VocabularySetEndpoint.create`）。 */
export async function createSet(api: ApiClient, name: string, language = 'en'): Promise<SetView> {
  return api.post<SetView>('/api/vocab/sets', { name, language })
}

/** 教师往词集里加词（`POST /api/vocab/sets/{id}/words`）。 */
export async function addWordsToSet(api: ApiClient, setId: string, words: string[]): Promise<unknown> {
  return api.post(`/api/vocab/sets/${setId}/words`, { words })
}

export interface AssignmentView {
  id: string
  setId: string
  recipientCount: number
}

/** 教师发词（`VocabularyAssignmentEndpoint.create`）：`before` 为收件生效时间，空表示立即。 */
export async function assignSet(
  api: ApiClient,
  setId: string,
  classIds: string[],
  dueAt?: string,
): Promise<AssignmentView> {
  return api.post<AssignmentView>('/api/vocab/assignments', {
    setId,
    classIds,
    dueAt: dueAt ?? null,
    note: null,
  })
}

/** 学生领取（`POST /api/vocab/assignments/{id}/claim`）：幂等，返回 added/duplicated。 */
export async function claimAssignment(
  api: ApiClient,
  assignmentId: string,
): Promise<{ assignmentId: string; added: number; duplicated: number }> {
  return api.post(`/api/vocab/assignments/${assignmentId}/claim`)
}

export interface PendingAssignment {
  id: string
  setName: string
  dueAt: string | null
  note: string | null
}

/** 学生待领取列表（今日页之外的另一条入口，E2E 用它确认「发词已到」）。 */
export async function pendingAssignments(api: ApiClient): Promise<PendingAssignment[]> {
  return api.get<PendingAssignment[]>('/api/vocab/assignments/pending')
}
