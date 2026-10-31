import { createContext, useContext, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * 011 / T596（AC-91.6）：Tab 行右端的投放槽。
 *
 * 问题的形状：三个 Tab 页的标题已经由 `VocabShell` 的页签承担，页面不再画自己的 `h1`；
 * 但页级动作（今日复习的 ⓘ、我的词单的「新建词单」）仍然属于页面——它们要读页面的数据
 * （ⓘ 里的三个数、快捷键表），壳层拿不到。
 *
 * 所以壳层在 Tab 行末尾留一个空 `div`，把 DOM 节点放进 context；页面用
 * `<VocabTabExtra>{...}</VocabTabExtra>` 把自己那块 portal 进去。
 * 为什么用 portal 而不是把内容 setState 到壳层：后者要把 JSX 存进 state，
 * 每次渲染都是新节点，轻则多一次渲染，重则死循环；portal 只搬 DOM，不搬状态。
 */
export const VocabTabSlotContext = createContext<HTMLElement | null>(null)

export function VocabTabExtra({ children }: { children: ReactNode }) {
  const slot = useContext(VocabTabSlotContext)
  // 首帧槽还没挂上（ref 回调在 commit 阶段才跑），这里什么都不渲染，下一帧补上。
  return slot ? createPortal(children, slot) : null
}
