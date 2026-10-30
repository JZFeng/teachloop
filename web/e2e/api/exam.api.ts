import type { ApiClient } from './client'

/**
 * 学生作答域（`ExamSessionEndpoint.java`）。
 *
 * 顺序不能换：`start` 落起算点 → 才可以 `submit`；直接交卷会因"未开始"被拒。
 * 写作题的正文走 `contentText`（`SubmitRequest`），`answers` 是客观题专用，这里传空数组。
 */
export interface SessionView {
  started: boolean
  submitted: boolean
  remainingSeconds: number
}

export interface SubmitResponse {
  submissionId: string
  attemptNo: number
  totalPoints: number
}

export async function startExam(api: ApiClient, offeringId: string): Promise<SessionView> {
  return api.post<SessionView>(`/api/offerings/${offeringId}/start`)
}

export async function submitEssay(api: ApiClient, offeringId: string, text: string): Promise<SubmitResponse> {
  return api.post<SubmitResponse>(`/api/offerings/${offeringId}/submissions`, {
    contentText: text,
    contentHtml: null,
    answers: [],
  })
}
