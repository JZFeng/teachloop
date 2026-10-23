import { useEffect, useRef } from 'react'
import { createEscapeStack } from './escapeStack'

/** 全局只有一份弹层栈：同一次 Esc 只交给最上层。 */
const stack = createEscapeStack()
let listening = false

function onDocumentKeyDown(event: KeyboardEvent) {
  stack.handle(event)
}

function ensureListener() {
  if (listening || typeof document === 'undefined') return
  document.addEventListener('keydown', onDocumentKeyDown)
  listening = true
}

function releaseListenerWhenIdle() {
  if (!listening || stack.size > 0) return
  document.removeEventListener('keydown', onDocumentKeyDown)
  listening = false
}

export interface EscapeCloseOptions {
  /**
   * 返回 false 时这次 Esc 不关闭弹层，但仍然由这层拦住，不会穿透到下面一层。
   * 用来表达「提交中不许关」「原生全屏时先让浏览器退出全屏」。
   */
  canClose?: () => boolean
}

/**
 * 弹层打开时按 Esc 关闭（T354）。
 *
 * 用 document 级监听而不是给弹层元素挂 onKeyDown：焦点可能在弹层里的按钮、输入框，
 * 甚至被编辑器抢走，只有 document 能稳定收到。
 *
 * 多层弹层同时打开时，后开的先关；`canClose` 为 false 的那次键击被吃掉但不关任何层。
 */
export function useEscapeClose(open: boolean, onClose: () => void, options: EscapeCloseOptions = {}) {
  const onCloseRef = useRef(onClose)
  const canCloseRef = useRef(options.canClose)
  // 父组件通常传内联箭头函数，回调用 ref 取最新值，避免每次渲染都重新注册。
  useEffect(() => {
    onCloseRef.current = onClose
    canCloseRef.current = options.canClose
  })

  useEffect(() => {
    if (!open) return
    ensureListener()
    const remove = stack.push(() => {
      const canClose = canCloseRef.current
      if (canClose && !canClose()) return
      onCloseRef.current()
    })
    return () => {
      remove()
      releaseListenerWhenIdle()
    }
  }, [open])
}
