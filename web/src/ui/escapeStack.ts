/**
 * 弹层「按 Esc 关闭」的栈（T354）。
 *
 * 页面上可能同时存在多层弹层：放大编辑器压着页面、答题页的目录抽屉上方又有提交检查，
 * 一次 Esc 只应该关最上面那层。这里用栈保证后开的先关（LIFO），并且**只有栈顶**
 * 会收到这次键击——即使栈顶拒绝关闭（比如正在提交），键击也不会穿透到下面一层。
 *
 * 纯逻辑，不碰 DOM，`web/tests/escape-stack.test.mjs` 直接测它。
 */

export type DismissHandler = () => void

/** 只需要这几个字段：真实 KeyboardEvent 与测试里造的对象都能传进来。 */
export interface EscapeKeyLike {
  key: string
  /** 别处已经处理过（preventDefault）的键击不再重复处理。 */
  defaultPrevented?: boolean
  /** 输入法组合期间按 Esc 是取消候选词，不是关弹层。 */
  isComposing?: boolean
  /** 旧浏览器在组合期间给 229。 */
  keyCode?: number
}

export interface EscapeStack {
  /** 压入一层；返回幂等的撤销函数。 */
  push(handler: DismissHandler): () => void
  /** 处理一次按键；返回 true 表示这次 Esc 被栈顶接管（不代表一定关闭了）。 */
  handle(event: EscapeKeyLike): boolean
  /** 当前弹层数，用来决定 document 监听器还要不要留着。 */
  readonly size: number
}

export function createEscapeStack(): EscapeStack {
  const handlers: DismissHandler[] = []

  return {
    push(handler) {
      handlers.push(handler)
      let removed = false
      return () => {
        if (removed) return
        removed = true
        // 弹层不一定按顺序卸载，按函数找自己而不是弹栈顶。
        const index = handlers.lastIndexOf(handler)
        if (index >= 0) handlers.splice(index, 1)
      }
    },

    handle(event) {
      if (event.key !== 'Escape') return false
      if (event.defaultPrevented) return false
      if (event.isComposing || event.keyCode === 229) return false
      const top = handlers[handlers.length - 1]
      if (!top) return false
      top()
      // 只要栈顶存在，这次 Esc 就归它：挡住下面几层，避免一次按键关掉两层。
      return true
    },

    get size() {
      return handlers.length
    },
  }
}
