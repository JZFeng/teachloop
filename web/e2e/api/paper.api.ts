import type { ApiClient } from './client'

/**
 * 试卷域（`PaperEndpoint.java`）。考研的是"造一份能发布的最小写作卷"，不是通用编辑器。
 *
 * 发布闸门（`PaperEndpoint.java:800-830` 的注释）：总分 > 0；非 ESSAY 题必须有标准答案；
 * 设了 target_total_points 时合计必须相等。这里只放一道 ESSAY 题，所以天然过闸门。
 */
export interface PaperView {
  id: string
  title: string
}

export interface SectionView {
  id: string
  title: string
}

export async function createPaper(api: ApiClient, title: string): Promise<PaperView> {
  return api.post<PaperView>('/api/papers', { title, defaultDurationMinutes: null })
}

export async function addSection(api: ApiClient, paperId: string, title: string, pointsPerItem?: number): Promise<SectionView> {
  return api.post<SectionView>(`/api/papers/${paperId}/sections`, { title, pointsPerItem: pointsPerItem ?? null })
}

/**
 * 加一道 ESSAY 题（`CreateItem`：number / questionType / stem / points / sectionId）。
 * 主观题不需要 answerKey——发布闸门只检查客观题有没有空答案。
 */
export async function addEssayItem(
  api: ApiClient,
  paperId: string,
  input: { number: number; sectionId: string; stem: string; points: number },
): Promise<void> {
  await api.post(`/api/papers/${paperId}/items`, {
    number: input.number,
    questionType: 'ESSAY',
    stem: input.stem,
    points: input.points,
    sectionId: input.sectionId,
  })
}

export async function publishPaper(api: ApiClient, paperId: string): Promise<void> {
  await api.post(`/api/papers/${paperId}/publish`)
}
