import { api } from '../api/client'

/** US-87：小测的接口形状（后端 `QuizEndpoint` 的 record 一一对应）。 */
export type QuizKind = 'TODAY' | 'SESSION' | 'RETRY'
export type QuizStatus = 'IN_PROGRESS' | 'SUBMITTED'
export type QuizQuestionType = 'SINGLE_CHOICE' | 'FILL_BLANK'

export interface QuizOption {
  key: string
  text: string
}

export interface QuizItem {
  id: string
  position: number
  type: QuizQuestionType
  stem: string
  options: QuizOption[]
  /** 未交卷时后端恒为 null（答案不下发） */
  given: string | null
  correct: boolean | null
  /** 交卷后才有值：选择题是正确选项文本，填空是句中的词形 */
  answer: string | null
}

export interface Quiz {
  id: string
  kind: QuizKind
  status: QuizStatus
  itemCount: number
  correctCount: number | null
  createdAt: string
  submittedAt: string | null
  sourceSessionId: string | null
  items: QuizItem[]
}

export function getQuiz(id: string): Promise<Quiz> {
  return api.get<Quiz>(`/api/vocab/quizzes/${id}`)
}

export function createQuiz(kind: 'TODAY' | 'SESSION', sessionId?: string): Promise<Quiz> {
  return api.post<Quiz>('/api/vocab/quizzes', sessionId ? { kind, sessionId } : { kind })
}

export function submitQuiz(id: string, answers: Array<{ itemId: string; given: string }>): Promise<Quiz> {
  return api.post<Quiz>(`/api/vocab/quizzes/${id}/submit`, { answers })
}

export function retryWrong(id: string): Promise<Quiz> {
  return api.post<Quiz>(`/api/vocab/quizzes/${id}/retry`, {})
}
