import type { ApiClient } from './client'

/**
 * 班级域（`ClassEndpoint.java`）。只包"测试要用到的那几步"，不做通用 CRUD。
 *
 * 为什么账号不在这里建：注册链路要收激活邮件（`docs/process/e2e-playwright.md` §2.3），
 * 测试账号走 dev 种子或 CLI `--create-user`，不在用例里造。
 */
export interface ClassView {
  id: string
  name: string
  inviteCode: string
  memberCount: number
}

/** 教师建班（`ClassEndpoint.java:49`），返回值里的 `inviteCode` 给测试学生进班用。 */
export async function createClass(api: ApiClient, name: string): Promise<ClassView> {
  return api.post<ClassView>('/api/classes', { name })
}

/** 学生用邀请码进班（`ClassEndpoint.java:66`）。 */
export async function joinClass(api: ApiClient, inviteCode: string): Promise<void> {
  await api.post('/api/classes/join', { inviteCode })
}
