/**
 * 学生词单（US-69 / T451）：契约见 `docs/decisions/020-vocab-word-lists.md` §6。
 *
 * 词单只引用词条：词条一份、进度一份。所以这里只做三件事——读词单、读词单里的词、
 * 把「未暂停的词 id」交给既有的自由复习会话接口（不新开会话机制）。
 */
import { api } from '../api/client'

/** 单学生词单上限（AC-69.4，后端 `teachloop.vocab.lists.max-per-student`，默认 100）。 */
export const LIST_MAX = 100

/** 词单词表一页的条数（后端 `size` 上限 200）。 */
export const LIST_WORDS_PAGE = 200

/** 一轮复习最多 1000 词（与 `ReviewSessionEndpoint.MAX_WORDS` 同口径）。 */
export const SESSION_MAX = 1000

export interface VocabList {
  id: string
  name: string
  description?: string | null
  wordCount: number
  position?: number | null
  updatedAt?: string | null
  /** 非 null = 这份词单是某本系统词书的副本（013 / FR-247），列表里显示「词书副本」标签。 */
  bookId?: string | null
}

/**
 * 词单里的一行。后端契约给的是 `state`；暂停（`vocab_words.removed`）在查询接口里是
 * 独立的布尔字段，这里两种都认，避免后端字段名差一个字就把暂停词算进复习范围。
 */
export interface ListWord {
  id: string
  form: string
  band?: number | string | null
  state?: string | null
  paused?: boolean | null
  dueAt?: string | null
  favorite?: boolean | null
  position?: number | null
  addedAt?: string | null
  /** 词被软删（AC-55）后引用仍在：行保留、不参与复习 */
  deleted?: boolean | null
}

export interface ListWordPage {
  items: ListWord[]
  total: number
  page: number
  size: number
}

/** `GET /lists/{id}/review-words`：复习范围的轻量 id 数组 + 详情页的四个计数。 */
export interface ReviewWords {
  wordIds: string[]
  reviewable: number
  total: number
  paused: number
  deleted: number
  sessionLimit: number
}

export interface ListAddResult {
  added: number
  duplicated: number
}

export function isPausedWord(word: ListWord | null | undefined): boolean {
  if (!word) return false
  return word.paused === true || word.state === 'PAUSED'
}

export async function fetchLists(): Promise<VocabList[]> {
  return (await api.get<VocabList[]>('/api/vocab/lists')) ?? []
}

export async function fetchList(listId: string): Promise<VocabList> {
  return api.get<VocabList>(`/api/vocab/lists/${listId}`)
}

/**
 * 复习范围一次拿全（未暂停、未软删，按加入顺序）。不要去分页拼 id：那样要么漏词，
 * 要么按词单大小做 N 次往返；这里直接给 id 数组与 `sessionLimit`。
 */
export async function fetchReviewWords(listId: string): Promise<ReviewWords> {
  const value = await api.get<Partial<ReviewWords>>(`/api/vocab/lists/${listId}/review-words`)
  const wordIds = Array.isArray(value?.wordIds) ? value.wordIds as string[] : []
  return {
    wordIds,
    reviewable: typeof value?.reviewable === 'number' ? value.reviewable : wordIds.length,
    total: typeof value?.total === 'number' ? value.total : wordIds.length,
    paused: typeof value?.paused === 'number' ? value.paused : 0,
    deleted: typeof value?.deleted === 'number' ? value.deleted : 0,
    sessionLimit: typeof value?.sessionLimit === 'number' ? value.sessionLimit : SESSION_MAX,
  }
}

export async function fetchListWords(
  listId: string,
  page = 1,
  size = LIST_WORDS_PAGE,
): Promise<ListWordPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return api.get<ListWordPage>(`/api/vocab/lists/${listId}/words?${query.toString()}`)
}

/**
 * 列表页每一行「开始复习（N）」的 N：走词库查询的 `?list=` 维度取未暂停总数（`size=1`）。
 * 取不到时返回 `null`，由调用方退回词数——不要在这里编一个数字。
 */
export async function countReviewableWords(listId: string): Promise<number | null> {
  const query = new URLSearchParams({
    list: listId, paused: 'false', page: '1', size: '1',
  })
  try {
    const value = await api.get<{ total: number }>(`/api/vocab/words?${query.toString()}`)
    return typeof value.total === 'number' ? value.total : null
  } catch {
    return null
  }
}

/** 复用既有自由复习会话：`fromStart:false` = 从上次停留的位置继续（AC-61.4）。 */
export async function createReviewSession(wordIds: string[]): Promise<string> {
  const session = await api.post<{ id: string }>('/api/vocab/review-sessions',
    { wordIds, fromStart: false })
  return session.id
}

/**
 * 加词。用 `forms` 而不是 `wordIds`：不在词库的词由后端一步入库再引用（AC-69.5），
 * 前端不需要先判断「这个词在不在词库」。
 */
export async function addFormsToList(listId: string, forms: string[]): Promise<ListAddResult> {
  const result = await api.post<Partial<ListAddResult>>(`/api/vocab/lists/${listId}/words`, { forms })
  return { added: result?.added ?? 0, duplicated: result?.duplicated ?? 0 }
}

/** 移出单个词：只删引用，不动词条与进度（AC-69.6，不二次确认）。 */
export async function removeWordFromList(listId: string, wordId: string): Promise<void> {
  await api.del(`/api/vocab/lists/${listId}/words/${wordId}`)
}

/** 粘贴的文本 → 词形数组：逗号/分号（含全角）、空格、换行都当分隔符。 */
export function splitForms(text: string): string[] {
  const seen = new Set<string>()
  const forms: string[] = []
  text.split(/[\s,，;；、\n\r\t]+/).forEach((raw) => {
    const form = raw.trim()
    if (!form) return
    const key = form.toLowerCase()
    if (seen.has(key)) return
    seen.add(key)
    forms.push(form)
  })
  return forms
}

/**
 * 「加入词单」弹层要知道这个词已在哪些词单里（AC-69.2：已在词单的行置灰并标「已在词单」）。
 *
 * 契约里没有「这个词属于哪些词单」的直接字段，所以按词单逐份用 `GET /words?list=&q=`
 * 反查，并对返回行做**词形精确比对**（`q` 是 contains 匹配，避免 bravery 命中 brave）。
 * 查询失败的行按「不在词单」处理：宁可让用户多看到一份可选项，也不要把弹层卡死。
 *
 * 后端若在 `GET /lists?wordId=` 或词条详情里直接给 memberships，这里可以整段删掉。
 */
export async function findMemberships(
  form: string,
  lists: VocabList[],
): Promise<Set<string>> {
  const target = form.trim().toLowerCase()
  const found = new Set<string>()
  if (!target) return found
  await Promise.all(lists.filter((list) => (list.wordCount ?? 0) > 0).map(async (list) => {
    const query = new URLSearchParams({ list: list.id, q: form.trim(), page: '1', size: '100' })
    try {
      const value = await api.get<{ items?: ListWord[] }>(`/api/vocab/words?${query.toString()}`)
      const hit = (value.items ?? []).some((item) => (item.form ?? '').toLowerCase() === target)
      if (hit) found.add(list.id)
    } catch {
      // 单份反查失败不阻塞弹层
    }
  }))
  return found
}
