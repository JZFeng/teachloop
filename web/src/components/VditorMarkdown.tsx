import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
// 外壳、放大态与源码视图的样式都在 markdown.css：显式依赖它，避免以后别的页面不再引 Markdown.tsx 时掉样式。
import './markdown.css'
import {
  CodeOutlined,
  CompressOutlined,
  EditOutlined,
  ExpandAltOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  useEscapeClose,
} from '../ui'

/** `rich` = Vditor 的即时渲染（Typora 同款：光标行露 Markdown 标记）；`src` = 纯 Markdown 源码。 */
type ViewMode = 'rich' | 'src'

export interface VditorMarkdownProps {
  id?: string
  value: string
  onChange: (next: string) => void
  /** 常规态高度按行数折算；放大态永远铺满。 */
  rows?: number
  placeholder?: string
  disabled?: boolean
  footer?: ReactNode
  expandable?: boolean
  /** 放大态顶部显示的名字（题目要求 / 评分标准）。 */
  heading?: string
}

/** 只留写作业真正用得到的按钮：上传、表情、导出、全屏这些都关掉（全屏走我们自己的放大态）。 */
const TOOLBAR = [
  'headings',
  'bold',
  'italic',
  'strike',
  '|',
  'list',
  'ordered-list',
  'check',
  '|',
  'quote',
  'line',
  'code',
  'inline-code',
  '|',
  'link',
  'table',
  '|',
  'undo',
  'redo',
]

/**
 * 源码视图：非受控 textarea。
 *
 * 为什么不用 Vditor 自带的 SV（源码 + 预览）面板：它的撤销栈按 800ms 批量入栈（`undoDelay`），
 * 输入后立刻按 Cmd+Z 没有反应；而且 SV 会再显示一个预览面板，而富文本视图本身已经是预览了。
 * 非受控 textarea 用浏览器原生撤销，Cmd+Z 立即生效；外部改值（导入 .md）时只回灌一次。
 */
function SourceTextarea({
  value,
  onChange,
  height,
  fill,
  placeholder,
  disabled,
}: {
  value: string
  onChange: (next: string) => void
  height: number
  fill: boolean
  placeholder?: string
  disabled: boolean
}) {
  const ref = useRef<HTMLTextAreaElement | null>(null)
  /**
   * 挂载时的内容只取一次，之后 `defaultValue` 永远不变。
   *
   * 为什么不能直接 `defaultValue={value}`：父组件每敲一个字符就重渲染一次，React 会把新的
   * `defaultValue` 写回 DOM；textarea 的 defaultValue 变更会打断浏览器的输入合并，撤销就退化成
   * 「一次退一个字符」（实测：裸 textarea 一次撤销整段，加了动态 defaultValue 只退一个字符）。
   */
  const initialValueRef = useRef(value)

  // 外部改值（导入 .md、清空表单）：只在内容不一致时写回；这是程序化写入，撤销栈会被清掉，可接受。
  useEffect(() => {
    const element = ref.current
    if (!element) return
    if (element.value !== value) element.value = value
  }, [value])

  // 进入源码视图自动聚焦，光标放到末尾（从富文本切过来时最自然的落点）。
  useEffect(() => {
    const element = ref.current
    if (!element || disabled) return
    element.focus()
    const end = element.value.length
    element.setSelectionRange(end, end)
    // 只在挂载时做一次；之后用户自己点哪就是哪。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <textarea
      ref={ref}
      className={fill ? 'md-source-input md-source-input--fill' : 'md-source-input'}
      style={fill ? undefined : { height }}
      defaultValue={initialValueRef.current}
      placeholder={placeholder}
      readOnly={disabled}
      spellCheck={false}
      onChange={(event) => onChange(event.currentTarget.value)}
    />
  )
}

/**
 * 把 Vditor 接成受控组件（T339）。
 *
 * 两种视图，按钮只写动作、不写术语：
 *   - 默认富文本：Vditor IR，Typora 同款即时渲染；
 *   - 点「源码」：纯 Markdown textarea，全宽、等宽字体、无预览、无工具条；按钮变成「返回编辑」。
 *
 * 受控约定：`value` 永远来自父组件，输入只通过 `onChange` 上抛；外部改值用 `setValue` / textarea 回灌，
 * 靠 `emittedRef` 区分「自己改的」与「外面改的」，避免光标乱跳。
 *
 * 资源：`cdn: '/vditor'` 指向 `public/vditor/dist/`（由 scripts/copy-vditor.mjs 生成），不走 unpkg。
 */
export function VditorMarkdown({
  id,
  value,
  onChange,
  rows = 8,
  placeholder,
  disabled = false,
  footer,
  expandable = false,
  heading,
}: VditorMarkdownProps) {
  const { t, i18n } = useTranslation()
  const [view, setView] = useState<ViewMode>('rich')
  const [expanded, setExpanded] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)
  const [ready, setReady] = useState(false)
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme === 'dark')

  const hostRef = useRef<HTMLDivElement | null>(null)
  const overlayRef = useRef<HTMLDivElement | null>(null)
  const expandButtonRef = useRef<HTMLButtonElement | null>(null)
  const vditorRef = useRef<Vditor | null>(null)
  /** after 回调（内部实例真正建好）之后才允许 focus/setValue/setTheme，否则会读 undefined。 */
  const readyRef = useRef(false)
  const restoreFocusRef = useRef(false)
  /** 最后一次由本组件交给父组件的 Markdown：用来区分外部改值与自身输入。 */
  const emittedRef = useRef(value)
  const valueRef = useRef(value)
  const onChangeRef = useRef(onChange)

  const lang = i18n.language.startsWith('zh') ? 'zh_CN' : 'en_US'
  const inlineHeight = Math.max(240, rows * 26 + 96)

  useEffect(() => {
    valueRef.current = value
  }, [value])

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  // 跟随全站主题（ThemeProvider 写在 html[data-theme] 上）。
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setDark(document.documentElement.dataset.theme === 'dark')
    })
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => observer.disconnect()
  }, [])

  // 只在富文本视图创建 Vditor；切到源码视图时销毁它（源码是独立的 textarea）。
  // 换放大态也要重建：Vditor 没有公开的 resize，height 只能初始化时给。
  useEffect(() => {
    if (view !== 'rich') return
    const host = hostRef.current
    if (!host) return
    setReady(false)
    readyRef.current = false
    const editor = new Vditor(host, {
      mode: 'ir',
      lang,
      cdn: '/vditor',
      cache: { enable: false },
      value: valueRef.current,
      placeholder: placeholder ?? t('editor.markdownPlaceholder'),
      height: expanded ? '100%' : inlineHeight,
      width: '100%',
      theme: dark ? 'dark' : 'classic',
      icon: 'ant',
      // 默认 800ms 批量入栈会让「输入后立刻 Cmd+Z」没反应；压到 200ms。
      undoDelay: 200,
      toolbar: TOOLBAR,
      toolbarConfig: { hide: false, pin: true },
      counter: { enable: false },
      outline: { enable: false, position: 'left' },
      preview: { delay: 120, hljs: { enable: false } },
      link: { isOpen: false },
      upload: {
        url: '',
        // 作业输入不接图片上传：粘贴截图会被拦下，避免把 base64 塞进 rubric。
        validate: () => t('editor.imageUnsupported'),
      },
      input: (next) => {
        if (next === emittedRef.current) return
        emittedRef.current = next
        onChangeRef.current(next)
      },
      after: () => {
        readyRef.current = true
        setReady(true)
      },
    })
    vditorRef.current = editor
    return () => {
      vditorRef.current = null
      readyRef.current = false
      setReady(false)
      try {
        editor.destroy()
      } catch {
        /* Vditor 在 after 之前卸载时可能抛错，忽略即可 */
      }
    }
    // value / onChange 走 ref，不参与重建：否则每个按键都会销毁重建编辑器。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, expanded, lang, inlineHeight])

  // 主题切换不重建编辑器，只换 Vditor 的暗色皮肤。
  // 必须等 after 回调（内部 vditor 实例建好）再切：初始化前调 setTheme 会读 undefined.options。
  useEffect(() => {
    const editor = vditorRef.current
    if (!editor || !ready) return
    editor.setTheme(dark ? 'dark' : 'classic', dark ? 'dark' : 'light')
  }, [dark, ready])

  // 只读态：Vditor 的 disabled/enable 只有在 after 之后调用才安全。
  useEffect(() => {
    const editor = vditorRef.current
    if (!editor || !ready) return
    if (disabled) editor.disabled()
    else editor.enable()
  }, [disabled, ready])

  // 外部改值（导入 .md、清空表单）回灌；自己输入触发的重渲染不会被覆盖。
  useEffect(() => {
    const editor = vditorRef.current
    if (!editor || !ready) return
    if (value === emittedRef.current) return
    emittedRef.current = value
    editor.setValue(value)
  }, [value, ready])

  // 放大期间锁页面滚动、Esc 收起；原生全屏由浏览器管，这里只管遮罩。
  // T354：Esc 走组件层的弹层栈；焦点在 Vditor 自己的面板里或浏览器全屏时，这层只拦截不关闭。
  useEscapeClose(expanded, closeExpanded, {
    canClose: () => {
      if (document.fullscreenElement) return false
      const active = document.activeElement as HTMLElement | null
      return !active?.closest('.vditor-panel')
    },
  })
  useEffect(() => {
    if (!expanded) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const syncFullscreen = () => setFullscreen(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', syncFullscreen)
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('fullscreenchange', syncFullscreen)
      setFullscreen(false)
      if (document.fullscreenElement) {
        const exit = document.exitFullscreen?.()
        if (exit) void exit.catch(() => undefined)
      }
    }
  }, [expanded])

  // 富文本放大后把焦点放回编辑器；源码视图由 SourceTextarea 自己聚焦。
  useEffect(() => {
    if (expanded && view === 'rich' && readyRef.current) vditorRef.current?.focus()
  }, [expanded, view])

  // 收起时把焦点还给「放大」按钮。
  useEffect(() => {
    if (expanded) return
    if (restoreFocusRef.current) {
      restoreFocusRef.current = false
      expandButtonRef.current?.focus()
    }
  }, [expanded])

  function openExpanded() {
    if (!expandable || expanded) return
    ;(document.activeElement as HTMLElement | null)?.blur()
    setExpanded(true)
  }

  function closeExpanded() {
    if (!expanded) return
    restoreFocusRef.current = true
    setExpanded(false)
  }

  function toggleFullscreen() {
    const element = overlayRef.current
    if (!element) return
    if (document.fullscreenElement) {
      const exit = document.exitFullscreen?.()
      if (exit) void exit.catch(() => undefined)
      return
    }
    const request = element.requestFullscreen?.()
    if (request) void request.catch(() => undefined)
  }

  const sourceToggle = (
    <button
      type="button"
      className="md-tool md-tool-text md-source-toggle"
      title={view === 'rich' ? t('editor.sourceHint') : t('editor.backToEdit')}
      aria-pressed={view === 'src'}
      onClick={() => setView(view === 'rich' ? 'src' : 'rich')}
    >
      {view === 'rich' ? <CodeOutlined aria-hidden="true" /> : <EditOutlined aria-hidden="true" />}
      <span>{view === 'rich' ? t('editor.source') : t('editor.backToEdit')}</span>
    </button>
  )

  const expandButton = expandable ? (
    <button
      type="button"
      ref={expandButtonRef}
      className="md-tool md-expand"
      title={t('editor.expand')}
      aria-label={t('editor.expand')}
      onClick={openExpanded}
    >
      <ExpandAltOutlined aria-hidden="true" />
    </button>
  ) : null

  const editorBody = view === 'rich' ? (
    <div ref={hostRef} id={id} className="vditor-host" />
  ) : (
    <SourceTextarea
      value={value}
      onChange={onChange}
      height={inlineHeight}
      fill={expanded}
      placeholder={placeholder ?? t('editor.markdownPlaceholder')}
      disabled={disabled}
    />
  )

  if (expanded) {
    return createPortal(
      <div className="md-overlay" role="dialog" aria-modal="true" aria-label={heading ?? t('editor.toolbar')}>
        <div className="md-overlay-panel" ref={overlayRef}>
          <div className="md-overlay-head">
            {heading ? <div className="md-overlay-title">{heading}</div> : null}
            {sourceToggle}
            <div className="md-overlay-grow" />
            <button
              type="button"
              className="md-tool md-tool-text"
              title={fullscreen ? t('editor.exitFullscreen') : t('editor.fullscreen')}
              aria-label={fullscreen ? t('editor.exitFullscreen') : t('editor.fullscreen')}
              aria-pressed={fullscreen}
              onClick={toggleFullscreen}
            >
              {fullscreen ? <FullscreenExitOutlined aria-hidden="true" /> : <FullscreenOutlined aria-hidden="true" />}
              <span>{fullscreen ? t('editor.exitFullscreen') : t('editor.fullscreen')}</span>
            </button>
            <button
              type="button"
              className="md-tool md-tool-text"
              title={t('editor.collapse')}
              aria-label={t('editor.collapse')}
              onClick={closeExpanded}
            >
              <CompressOutlined aria-hidden="true" />
              <span>{t('editor.collapse')}</span>
            </button>
          </div>
          <div className="md-overlay-body">
            <div className="md-pane">{editorBody}</div>
          </div>
          {footer ? <div className="md-footer md-footer-fill">{footer}</div> : null}
        </div>
      </div>,
      document.body,
    )
  }

  return (
    <div className="md-editor md-editor--vditor">
      <div className="md-toolbar">
        {sourceToggle}
        <div className="md-actions">{expandButton}</div>
      </div>
      {editorBody}
      {footer ? <div className="md-footer">{footer}</div> : null}
    </div>
  )
}

export default VditorMarkdown
