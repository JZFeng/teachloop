import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

interface ErrorBoundaryProps {
  children: ReactNode
  /** 出错时替换整块内容；外层导航留着，用户不必刷新整页才能回去。 */
  fallback: ReactNode
}

interface ErrorBoundaryState {
  failed: boolean
  message: string
}

/**
 * 页面级错误边界（T325）。
 *
 * <p>起因是一次真实白屏：班级页把后端返回的对象渲染成 React 子节点，React 19 直接卸载整棵树，
 * 用户看到的是「点了成员 → 页面没了」。有边界之后，同样是渲染崩溃，页面只剩这一块提示，
 * 侧栏、返回、重新加载都还在——故障可见，但不是死路。
 *
 * <p>边界按路由 pathname 做 key（见 App.tsx）：换一页自动复位，不会把一次崩溃带进下一页。
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false, message: '' }

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return { failed: true, message: error instanceof Error ? error.message : String(error) }
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.error('render failed', error, info.componentStack)
  }

  render(): ReactNode {
    if (this.state.failed) return this.props.fallback
    return this.props.children
  }
}
