import { request, type APIRequestContext } from '@playwright/test'

/**
 * HTTP 传输层：只负责"发请求 + 把失败说清楚"，不含任何业务语义。
 *
 * 两个关键点（都踩过）：
 *   1. **CSRF**：qa 与 prod 打开（`application-qa.yml:13`、`application-prod.yml:17`），dev 关闭。
 *      打开时先 `GET /api/csrf` 拿 Cookie 里的原始 token（`CookieCsrfTokenRepository.withHttpOnlyFalse()`），
 *      再随写请求回传 `X-XSRF-TOKEN`（`SecurityConfig.java:73-77` 特意关掉了 XOR 掩码）。
 *      Playwright 的 request context 自带 cookie jar，所以只需在预热后把 header 补上。
 *   2. **失败可读**：默认的 `expect(res.ok())` 只告诉你 400，不告诉你后端说了什么。
 *      这里把 method + path + 状态码 + 响应体前 300 字一起抛出去。
 */
export interface ApiClientOptions {
  baseURL: string
  /** 登录态文件（`e2e/.auth/*.json`）；不给就是匿名。 */
  storageState?: string
  /** 覆盖 CSRF 开关；默认读 `E2E_CSRF`，dev 不用设。 */
  csrf?: boolean
}

export class ApiClient {
  /** CSRF 打开时要随每个写请求回传的原始 token（Cookie 值本身）。 */
  private csrfToken: string | null = null

  private constructor(
    private readonly context: APIRequestContext,
    private readonly csrf: boolean,
  ) {}

  static async create(options: ApiClientOptions): Promise<ApiClient> {
    const context = await request.newContext({
      baseURL: options.baseURL,
      storageState: options.storageState,
      extraHTTPHeaders: { Accept: 'application/json' },
    })
    const csrf = options.csrf ?? process.env.E2E_CSRF === '1'
    const client = new ApiClient(context, csrf)
    if (csrf) await client.primeCsrf()
    return client
  }

  /** 取一次 CSRF token 并把 header 粘上；qa/prod 的每个身份都要先做。 */
  private async primeCsrf(): Promise<void> {
    const response = await this.context.get('/api/csrf')
    if (!response.ok()) {
      throw new Error(`CSRF 预热失败：GET /api/csrf → ${response.status()}`)
    }
    const state = await this.context.storageState()
    const cookie = state.cookies.find((item) => item.name === 'XSRF-TOKEN')
    if (!cookie) throw new Error('CSRF 预热失败：响应里没有 XSRF-TOKEN Cookie')
    // 逐请求带 header（APIRequestContext 没有 setExtraHTTPHeaders；这是它和 page 的差别）。
    this.csrfToken = decodeURIComponent(cookie.value)
  }

  async get<T>(path: string): Promise<T> {
    return (await this.send('GET', path)).json() as Promise<T>
  }

  async post<T>(path: string, data?: unknown): Promise<T> {
    return (await this.send('POST', path, data)).json() as Promise<T>
  }

  async put<T>(path: string, data?: unknown): Promise<T> {
    return (await this.send('PUT', path, data)).json() as Promise<T>
  }

  private async send(method: string, path: string, data?: unknown) {
    const response = await this.context.fetch(path, {
      method,
      data,
      headers: this.csrfToken ? { 'X-XSRF-TOKEN': this.csrfToken } : undefined,
    })
    if (!response.ok()) {
      const body = await response.text().catch(() => '')
      throw new Error(`${method} ${path} → ${response.status()}\n${body.slice(0, 300)}`)
    }
    return response
  }

  async dispose(): Promise<void> {
    await this.context.dispose()
  }
}
