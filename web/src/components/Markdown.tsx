import { useMemo } from 'react'
import type { ReactNode } from 'react'
import './markdown.css'

export interface MarkdownProps {
  /** Markdown 源文本。只渲染下面这个白名单子集，其余按纯文本。 */
  text: string
  className?: string
}

/*
 * 为什么手写而不用库：仓库明确不引第三方 Markdown 编辑器/渲染器
 * （理由见 `web/src/components/RichTextEditor.tsx` 顶部注释：多一个依赖就多一份
 * XSS 面与升级负担，而这里只需要一个很小的子集）。
 *
 * 安全边界：本文件**不产生任何 HTML 字符串**，全程解析成 React 元素，
 * 因此链接、图片、原生 HTML 标签（<a>/<img>/<script>…）天然只会变成文字，
 * 不需要额外转义，也不使用 dangerouslySetInnerHTML。
 *
 * 支持的子集（块级）：
 *   - ATX 标题 `#`~`####`
 *   - 段落（连续非空行合并，段内换行保留为 <br/>）
 *   - 无序列表 `- ` / `* ` / `+ `、有序列表 `1. ` / `1) `，缩进 2 空格以上算一层子列表
 *   - 引用 `> `
 *   - 分隔线 `---` / `***`（三个以上）
 *   - 围栏代码块 ```（不做语法高亮）
 *   - 表格（表头行 + |---| 分隔行 + 数据行）
 * 行内：`**粗体**`、`__粗体__`、`*斜体*`、`_斜体_`、`` `code` ``。
 */

/** 围栏代码块、标题、分隔线、引用、列表项的行首正则（与 parseBlocks 共用）。 */
const FENCE = /^\s{0,3}```/
const HEADING = /^\s{0,3}(#{1,4})\s+(.*?)\s*$/
const HR = /^\s{0,3}(?:-{3,}|\*{3,})\s*$/
const QUOTE = /^\s{0,3}>\s?(.*)$/
const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/

/**
 * `#` 也渲染成 h2：这个组件会嵌在页面里（页面自己已经有 h1），
 * 所以标题整体下移一级并封顶在 h4，`#`→h2、`##`→h3、`###`/`####`→h4，
 * 保证不会出现第二个 h1 抢页面层级。
 */
const MAX_HEADING_LEVEL = 4

function isTableSeparator(line: string): boolean {
  if (!line.includes('-')) return false
  const cells = splitRow(line)
  if (cells.length === 0) return false
  return cells.every((cell) => /^:?-{1,}:?$/.test(cell.trim()))
}

/** 把 `| a | b |` 拆成 `['a', 'b']`：只去掉最外层的两个竖线，单元格内部原样保留。 */
function splitRow(line: string): string[] {
  let body = line.trim()
  if (body.startsWith('|')) body = body.slice(1)
  if (body.endsWith('|')) body = body.slice(0, -1)
  return body.split('|').map((cell) => cell.trim())
}

/** 这一行是不是「块级语法」的开头：是，就结束当前段落。 */
function startsBlock(line: string, next: string | undefined): boolean {
  if (FENCE.test(line) || HR.test(line) || HEADING.test(line) || QUOTE.test(line) || LIST_ITEM.test(line)) {
    return true
  }
  return line.includes('|') && next !== undefined && isTableSeparator(next)
}

/**
 * 行内解析：返回 ReactNode 数组（文本片段是裸字符串，元素带 key）。
 *
 * 优先级：行内代码 > 粗体 > 斜体。反引号里的内容不再解析，
 * 所以 `**a**` 写在反引号里就是字面量。
 */
function parseInline(source: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let textStart = 0
  let cursor = 0
  let seq = 0

  const push = (node: ReactNode, from: number, to: number) => {
    if (from > textStart) nodes.push(source.slice(textStart, from))
    nodes.push(node)
    textStart = to
    cursor = to
  }

  while (cursor < source.length) {
    const char = source[cursor]

    if (char === '\n') {
      seq += 1
      push(<br key={`${keyPrefix}-br-${seq}`} />, cursor, cursor + 1)
      continue
    }

    if (char === '`') {
      const close = source.indexOf('`', cursor + 1)
      // close > cursor + 1：空的一对反引号（``）当文字，不产生空 <code>
      if (close > cursor + 1) {
        seq += 1
        push(
          <code key={`${keyPrefix}-code-${seq}`}>{source.slice(cursor + 1, close)}</code>,
          cursor,
          close + 1,
        )
        continue
      }
    }

    if (char === '*' || char === '_') {
      const doubled = source.startsWith(char + char, cursor)
      if (doubled) {
        const close = findCloser(source, cursor + 2, char + char, char === '_')
        if (close > 0) {
          seq += 1
          push(
            <strong key={`${keyPrefix}-strong-${seq}`}>
              {parseInline(source.slice(cursor + 2, close), `${keyPrefix}-s${seq}`)}
            </strong>,
            cursor,
            close + 2,
          )
          continue
        }
        // 找不到配对：把第一个字符当普通文字，避免和后面的单个 * 抢配对
        cursor += 1
        continue
      }
      if (canOpenItalic(source, cursor, char)) {
        const close = findCloser(source, cursor + 1, char, char === '_')
        if (close > 0) {
          seq += 1
          push(
            <em key={`${keyPrefix}-em-${seq}`}>
              {parseInline(source.slice(cursor + 1, close), `${keyPrefix}-e${seq}`)}
            </em>,
            cursor,
            close + 1,
          )
          continue
        }
      }
    }

    cursor += 1
  }

  if (textStart < source.length) nodes.push(source.slice(textStart))
  return nodes
}

/**
 * 找收尾标记的位置。三条硬条件：
 *   1. 中间必须有内容，且内容不能以空白结尾（`** bold **` 不算粗体）；
 *   2. 单个标记不能被更长的标记吃掉（`*a **b** c*` 的收尾在最后一个 `*`）；
 *   3. 下划线强调不能落在单词内部（`snake_case_name` 不能变成斜体）。
 */
function findCloser(source: string, from: number, marker: string, wordBoundary: boolean): number {
  let cursor = from
  while (cursor < source.length) {
    const at = source.indexOf(marker, cursor)
    if (at === -1) return -1
    const hasContent = at > from && !/\s/.test(source[at - 1])
    const isolated = marker.length > 1 || (source[at - 1] !== marker && source[at + 1] !== marker)
    const boundary = !wordBoundary || at + 1 >= source.length || !/[A-Za-z0-9]/.test(source[at + 1])
    if (hasContent && isolated && boundary) return at
    cursor = at + marker.length
  }
  return -1
}

function canOpenItalic(source: string, index: number, marker: string): boolean {
  const next = source[index + 1]
  if (next === undefined || /\s/.test(next) || next === marker) return false
  if (marker === '_' && index > 0 && /[A-Za-z0-9]/.test(source[index - 1])) return false
  return true
}

/** 把源文本切成块级 React 元素。空行只做分段，不产生任何元素。 */
function parseBlocks(source: string): ReactNode[] {
  const lines = source.split(/\r\n|\r|\n/)
  const blocks: ReactNode[] = []
  let blockSeq = 0
  let line = 0

  const nextKey = () => `md-${blockSeq++}`

  while (line < lines.length) {
    const current = lines[line]
    if (!current.trim()) {
      line += 1
      continue
    }

    if (FENCE.test(current)) {
      const body: string[] = []
      line += 1
      while (line < lines.length && !FENCE.test(lines[line])) {
        body.push(lines[line])
        line += 1
      }
      // 允许文档结尾漏掉收尾围栏
      if (line < lines.length) line += 1
      blocks.push(
        <pre key={nextKey()}>
          <code>{body.join('\n')}</code>
        </pre>,
      )
      continue
    }

    if (HR.test(current)) {
      blocks.push(<hr key={nextKey()} />)
      line += 1
      continue
    }

    const heading = HEADING.exec(current)
    if (heading) {
      const content = heading[2]
      const level = Math.min(heading[1].length + 1, MAX_HEADING_LEVEL)
      const key = nextKey()
      if (level === 2) {
        blocks.push(<h2 key={key}>{parseInline(content, key)}</h2>)
      } else if (level === 3) {
        blocks.push(<h3 key={key}>{parseInline(content, key)}</h3>)
      } else {
        blocks.push(<h4 key={key}>{parseInline(content, key)}</h4>)
      }
      line += 1
      continue
    }

    if (QUOTE.test(current)) {
      const quoted: string[] = []
      while (line < lines.length) {
        const matched = QUOTE.exec(lines[line])
        if (!matched) break
        quoted.push(matched[1])
        line += 1
      }
      const key = nextKey()
      blocks.push(
        <blockquote key={key}>
          <p>{parseInline(quoted.join('\n'), key)}</p>
        </blockquote>,
      )
      continue
    }

    if (current.includes('|') && line + 1 < lines.length && isTableSeparator(lines[line + 1])) {
      const header = splitRow(current)
      line += 2
      const rows: string[][] = []
      while (line < lines.length && lines[line].trim() && lines[line].includes('|')) {
        rows.push(splitRow(lines[line]))
        line += 1
      }
      const key = nextKey()
      blocks.push(
        <table key={key}>
          <thead>
            <tr>
              {header.map((cell, column) => (
                <th key={`${key}-h${column}`}>{parseInline(cell, `${key}-h${column}`)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((cells, row) => (
              <tr key={`${key}-r${row}`}>
                {header.map((_, column) => (
                  <td key={`${key}-r${row}c${column}`}>
                    {parseInline(cells[column] ?? '', `${key}-r${row}c${column}`)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>,
      )
      continue
    }

    const firstItem = LIST_ITEM.exec(current)
    if (firstItem) {
      const ordered = /^\d/.test(firstItem[2])
      interface Entry {
        text: string
        nestedOrdered: boolean
        nested: string[]
      }
      const entries: Entry[] = []
      while (line < lines.length) {
        if (!lines[line].trim()) break
        const matched = LIST_ITEM.exec(lines[line])
        if (!matched) break
        const indent = matched[1].replace(/\t/g, '    ').length
        const rowOrdered = /^\d/.test(matched[2])
        if (indent >= 2) {
          const owner = entries[entries.length - 1]
          if (owner) {
            owner.nested.push(matched[3])
          } else {
            // 缩进但没有父项：当成顶层项收下，不要丢内容
            entries.push({ text: matched[3], nestedOrdered: rowOrdered, nested: [] })
          }
        } else if (rowOrdered === ordered) {
          entries.push({ text: matched[3], nestedOrdered: rowOrdered, nested: [] })
        } else {
          // 同级换了列表类型：结束这一组，交给循环重新开一个列表
          break
        }
        line += 1
      }
      const key = nextKey()
      const items = entries.map((entry, index) => {
        const itemKey = `${key}-li${index}`
        const nested = entry.nested.map((nestedText, nestedIndex) => (
          <li key={`${itemKey}-n${nestedIndex}`}>
            {parseInline(nestedText, `${itemKey}-n${nestedIndex}`)}
          </li>
        ))
        return (
          <li key={itemKey}>
            {parseInline(entry.text, itemKey)}
            {entry.nested.length === 0 ? null : entry.nestedOrdered ? <ol>{nested}</ol> : <ul>{nested}</ul>}
          </li>
        )
      })
      blocks.push(ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>)
      continue
    }

    const paragraph: string[] = []
    while (line < lines.length) {
      const row = lines[line]
      if (!row.trim()) break
      if (paragraph.length > 0 && startsBlock(row, lines[line + 1])) break
      paragraph.push(row.trim())
      line += 1
    }
    const key = nextKey()
    blocks.push(<p key={key}>{parseInline(paragraph.join('\n'), key)}</p>)
  }

  return blocks
}

export function Markdown({ text, className }: MarkdownProps) {
  const blocks = useMemo(() => parseBlocks(text), [text])
  // 空文本渲染 null：空状态（提示文案、占位图）由调用方决定，不要这里塞一个空 div
  if (!text.trim() || blocks.length === 0) return null
  // 调用方可能把 md-body 再传一次（学生端就是这么用的），去重后再挂到 DOM 上
  const classes = Array.from(new Set(['md-body', ...(className ?? '').split(/\s+/).filter(Boolean)]))
  return <div className={classes.join(' ')}>{blocks}</div>
}
