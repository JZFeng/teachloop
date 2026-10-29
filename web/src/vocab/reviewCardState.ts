/**
 * 复习卡的三态判定（2026-10-29 用户报 Bug）。
 *
 * 背景：`content_status` 是库里的存量字段，会过期——内容包换版、提示词升级（v2.3 → v2.5）、
 * delta 被清之后，词行还写着 `READY`，但读路径已经解析不到内容。如果只看状态就渲染卡面，
 * 学生看到的是一张没有音标（`英 — 美 —`）、没有释义和例句的空卡，而且状态是 READY，
 * 按需生成也不会重新入队，页面永远停在空卡上。
 *
 * 所以判定要同时看状态和内容：`READY` **且**有内容才算 ready。
 * 后端 `ContentView.statusFor`（同一天修）也会把这种过期 READY 降级成 PENDING，
 * 这里是前端的第二道闸：即使状态还没刷新到，也不会渲染空卡。
 */
export type ReviewCardPhase = 'ready' | 'preparing' | 'failed'

export function reviewCardPhase(
  contentStatus: string | undefined | null,
  content: unknown,
): ReviewCardPhase {
  if (contentStatus === 'FAILED') {
    return 'failed'
  }
  if (contentStatus === 'READY' && content) {
    return 'ready'
  }
  return 'preparing'
}
