import { useEffect, useRef, useState } from 'react'
import type { ClipboardEvent, KeyboardEvent, MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CompressOutlined,
  ExpandAltOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
} from '../ui'

interface RichTextEditorProps {
  /** 挂载时灌入的 HTML（切走再切回来时恢复草稿）；编辑过程中不再由 React 覆盖 DOM。 */
  initialHtml: string
  disabled?: boolean
  placeholder?: string
  /** 每次输入/格式变化回调：html 用于提交，text 用于字数统计。 */
  onChange: (html: string, text: string) => void
  /**
   * T341：加「放大 / 全屏」。只改摆放（CSS fixed + 铺满右侧内容区），不移动 DOM、
   * 不重建 contentEditable，所以正文、选区和撤销栈都保持原样。
   */
  expandable?: boolean
}

/**
 * 与后端 {@code RichTextSanitizer} 同一份白名单：提交时服务端也只留这些标签、不留任何属性。
 * 编辑时就按同一份规则清理，保证「屏幕上看到的」＝「存进库里的」。
 */
const ALLOWED_TAGS = new Set(['P', 'BR', 'STRONG', 'EM', 'U', 'OL', 'UL', 'LI', 'BLOCKQUOTE', 'H1', 'H2', 'H3'])

/** 富文本编辑内容 → 白名单 HTML：b→strong、i→em、div→p，其余标签只保留文字，属性全丢。 */
export function sanitizeHtml(raw: string): string {
  if (!raw) return ''
  const doc = new DOMParser().parseFromString(raw, 'text/html')
  const out = doc.createElement('div')
  copyAllowed(doc.body, out, doc)
  return out.innerHTML
}

function copyAllowed(source: Node, target: Element, doc: Document): void {
  for (const child of Array.from(source.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      target.appendChild(doc.createTextNode(child.textContent ?? ''))
      continue
    }
    if (!(child instanceof Element)) continue
    const tag = child.tagName
    if (tag === 'BR') {
      target.appendChild(doc.createElement('br'))
      continue
    }
    if (tag === 'DIV') {
      const p = doc.createElement('p')
      copyAllowed(child, p, doc)
      target.appendChild(p)
      continue
    }
    const normalized = tag === 'B' ? 'STRONG' : tag === 'I' ? 'EM' : tag
    if (ALLOWED_TAGS.has(normalized)) {
      const el = doc.createElement(normalized.toLowerCase())
      copyAllowed(child, el, doc)
      target.appendChild(el)
    } else {
      // 未知标签（span/font/table/…）：丢掉壳子，保留里面允许的内容与文字
      copyAllowed(child, target, doc)
    }
  }
}

/**
 * T250/T252：作业页的富文本输入。
 *
 * <p>没有引入第三方编辑器：Quill/TipTap 会带 ~40–150KB 依赖，且 Quill 2 的列表输出是
 * {@code <li data-list="bullet">}，会被后端白名单消毒成纯文本。这里用浏览器原生
 * contentEditable + execCommand，输出就是后端允许的 strong/em/u/ol/ul/h3，粘贴和提交
 * 都按同一份白名单清理，看到什么就存什么。
 *
 * <p>T252：补上 Cmd/Ctrl + B / I / U 快捷键；粘贴自动清掉外部样式（灰底、字号、颜色）；
 * 编辑器底色固定白色、聚焦不再有阴影。
 */
export function RichTextEditor({
  initialHtml,
  disabled,
  placeholder,
  onChange,
  expandable = false,
}: RichTextEditorProps) {
  const { t } = useTranslation('translation')
  const bodyRef = useRef<HTMLDivElement>(null)
  const [empty, setEmpty] = useState(!initialHtml.trim())
  const [expanded, setExpanded] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const expandButtonRef = useRef<HTMLButtonElement | null>(null)
  const restoreFocusRef = useRef(false)

  // 放大期间锁页面滚动；Esc 收起（原生全屏时先由浏览器退出全屏）。
  useEffect(() => {
    if (!expanded) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape' || document.fullscreenElement) return
      closeExpanded()
    }
    const syncFullscreen = () => setFullscreen(Boolean(document.fullscreenElement))
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('fullscreenchange', syncFullscreen)
    bodyRef.current?.focus()
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('fullscreenchange', syncFullscreen)
      setFullscreen(false)
      if (document.fullscreenElement) {
        const exit = document.exitFullscreen?.()
        if (exit) void exit.catch(() => undefined)
      }
    }
  }, [expanded])

  // 收起后把焦点还给「放大」按钮。
  useEffect(() => {
    if (expanded) return
    if (restoreFocusRef.current) {
      restoreFocusRef.current = false
      expandButtonRef.current?.focus()
    }
  }, [expanded])

  function openExpanded() {
    if (!expandable || expanded) return
    setExpanded(true)
  }

  function closeExpanded() {
    if (!expanded) return
    restoreFocusRef.current = true
    setExpanded(false)
  }

  function toggleFullscreen() {
    const element = wrapRef.current
    if (!element) return
    if (document.fullscreenElement) {
      const exit = document.exitFullscreen?.()
      if (exit) void exit.catch(() => undefined)
      return
    }
    const request = element.requestFullscreen?.()
    if (request) void request.catch(() => undefined)
  }

  // 只在挂载时写一次 innerHTML：之后光标和选区完全交给浏览器，React 不参与。
  useEffect(() => {
    const el = bodyRef.current
    if (!el) return
    // execCommand 默认可能产出 <b>/<i> 或带 style 的 <span>；先关掉「用 CSS 表达格式」。
    document.execCommand('styleWithCSS', false, 'false')
    if (initialHtml) el.innerHTML = sanitizeHtml(initialHtml)
    setEmpty(!el.innerText.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const sync = () => {
    const el = bodyRef.current
    if (!el) return
    const text = el.innerText
    setEmpty(!text.trim())
    onChange(sanitizeHtml(el.innerHTML), text)
  }

  const exec = (command: string, value?: string) => {
    if (disabled) return
    bodyRef.current?.focus()
    document.execCommand(command, false, value)
    sync()
  }

  /** Cmd/Ctrl + B/I/U：浏览器原生也会处理，这里显式兜住，避免不同浏览器/输入法下失效。 */
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (disabled) return
    const mod = event.metaKey || event.ctrlKey
    if (!mod || event.altKey) return
    const key = event.key.toLowerCase()
    const command = key === 'b' ? 'bold' : key === 'i' ? 'italic' : key === 'u' ? 'underline' : null
    if (!command) return
    event.preventDefault()
    exec(command)
  }

  /** 粘贴：把外部 HTML 按白名单洗一遍再插入；没有 HTML 就插纯文本。 */
  const onPaste = (event: ClipboardEvent<HTMLDivElement>) => {
    if (disabled) return
    const html = event.clipboardData.getData('text/html')
    const text = event.clipboardData.getData('text/plain')
    if (!html && !text) return
    event.preventDefault()
    const clean = sanitizeHtml(html)
    if (clean.trim()) {
      document.execCommand('insertHTML', false, clean)
    } else if (text) {
      document.execCommand('insertText', false, text)
    }
    sync()
  }

  // 点工具条不能把可编辑区的选区弄丢，所以 mousedown 一律阻止默认行为。
  const keepSelection = (event: MouseEvent) => event.preventDefault()

  return (
    <div ref={wrapRef} className={`rich-editor${expanded ? ' rich-editor--expanded' : ''}`}>
      <div className="rich-toolbar" role="toolbar" aria-label={t('editor.toolbar')}>
        <button type="button" title={t('editor.bold')} aria-label={t('editor.bold')}
          onMouseDown={keepSelection} onClick={() => exec('bold')}><b>B</b></button>
        <button type="button" title={t('editor.italic')} aria-label={t('editor.italic')}
          onMouseDown={keepSelection} onClick={() => exec('italic')}><i>I</i></button>
        <button type="button" title={t('editor.underline')} aria-label={t('editor.underline')}
          onMouseDown={keepSelection} onClick={() => exec('underline')}><u>U</u></button>
        <span className="sep" aria-hidden="true" />
        <button type="button" title={t('editor.unorderedList')} aria-label={t('editor.unorderedList')}
          onMouseDown={keepSelection} onClick={() => exec('insertUnorderedList')}>•</button>
        <button type="button" title={t('editor.orderedList')} aria-label={t('editor.orderedList')}
          onMouseDown={keepSelection} onClick={() => exec('insertOrderedList')}>1.</button>
        <button type="button" title={t('editor.heading')} aria-label={t('editor.heading')}
          onMouseDown={keepSelection} onClick={() => exec('formatBlock', 'h3')}>H</button>
        <button type="button" title={t('editor.paragraph')} aria-label={t('editor.paragraph')}
          onMouseDown={keepSelection} onClick={() => exec('formatBlock', 'p')}>¶</button>
        <span className="rich-toolbar-spacer" aria-hidden="true" />
        {expanded ? (
          <>
            <button
              type="button"
              className="rich-toolbar-action"
              title={fullscreen ? t('editor.exitFullscreen') : t('editor.fullscreen')}
              aria-label={fullscreen ? t('editor.exitFullscreen') : t('editor.fullscreen')}
              aria-pressed={fullscreen}
              onClick={toggleFullscreen}
            >
              {fullscreen ? <FullscreenExitOutlined aria-hidden="true" /> : <FullscreenOutlined aria-hidden="true" />}
            </button>
            <button
              type="button"
              className="rich-toolbar-action"
              title={t('editor.collapse')}
              aria-label={t('editor.collapse')}
              onClick={closeExpanded}
            >
              <CompressOutlined aria-hidden="true" />
            </button>
          </>
        ) : expandable ? (
          <button
            type="button"
            ref={expandButtonRef}
            className="rich-toolbar-action"
            title={t('editor.expand')}
            aria-label={t('editor.expand')}
            onClick={openExpanded}
          >
            <ExpandAltOutlined aria-hidden="true" />
          </button>
        ) : null}
      </div>
      <div className="rich-body-wrap">
        <div
          ref={bodyRef}
          className="rich-body"
          contentEditable={!disabled}
          suppressContentEditableWarning
          onInput={sync}
          onBlur={sync}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          role="textbox"
          aria-multiline="true"
          aria-label={placeholder ?? t('editor.placeholder')}
        />
        {empty && !disabled && <div className="rich-placeholder">{placeholder ?? t('editor.placeholder')}</div>}
      </div>
    </div>
  )
}
