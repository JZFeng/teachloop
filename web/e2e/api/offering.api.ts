import type { ApiClient } from './client'

/**
 * 布置域（`OfferingEndpoint.java`）。论文（引用试卷）与作业（不引用试卷）走同一个端点，
 * `paperId` 非空即考试；`kind` 只是旧客户端兼容字段，传错会被 400 挡回来。
 */
export interface OfferingView {
  id: string
  paperTitle: string
  classes: Array<{ classId: string; memberCount: number }>
}

/** 每份提交一行（`SittingDtos.OfferingSubmissionView`），字段名与后端记录逐字对应。 */
export interface OfferingSubmissionView {
  submissionId: string
  studentId: string
  studentName: string
  gradingState: string
  totalScore: number
  active: boolean
}

export async function createOffering(
  api: ApiClient,
  input: { paperId: string; classIds: string[]; publish?: boolean },
): Promise<OfferingView> {
  return api.post<OfferingView>('/api/offerings', {
    paperId: input.paperId,
    kind: 'EXAM',
    classIds: input.classIds,
    publish: input.publish ?? false,
  })
}

export async function publishOffering(api: ApiClient, offeringId: string): Promise<void> {
  await api.post(`/api/offerings/${offeringId}/publish`)
}

export async function listSubmissions(api: ApiClient, offeringId: string): Promise<OfferingSubmissionView[]> {
  return api.get<OfferingSubmissionView[]>(`/api/offerings/${offeringId}/submissions`)
}
