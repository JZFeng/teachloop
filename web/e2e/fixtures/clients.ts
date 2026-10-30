import { ApiClient } from '../api/client'

/**
 * 测试用的 API 客户端工厂与登录态路径。
 *
 * 登录态文件由 `e2e/fixtures/auth.setup.ts` 一次性产出（teacher / student 各一份），
 * 用例只读不写——这样"登录"永远不会成为用例失败的原因。
 */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'
export const TEACHER_STATE = 'e2e/.auth/teacher.json'
export const STUDENT_STATE = 'e2e/.auth/student.json'

export function createTeacherClient(): Promise<ApiClient> {
  return ApiClient.create({ baseURL: BASE_URL, storageState: TEACHER_STATE })
}

export function createStudentClient(): Promise<ApiClient> {
  return ApiClient.create({ baseURL: BASE_URL, storageState: STUDENT_STATE })
}
