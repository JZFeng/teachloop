/*
 * 只读 Markdown 的解析器：纯数据进、纯数据出，不碰 React、不产生 HTML 字符串。
 *
 * 为什么要有这一层：学生在作业页看到的「题目要求」与「评分标准」都是老师用 Markdown
 * 写的（Vditor IR 产出的就是 Markdown），原来的解析直接产出 React 元素，导致
 * ①无法单测；②遇到真实文档里的写法就退化。2026-10-24 学生的真实作业暴露了三处：
 *   - `***Instructions***`（粗+斜体）被读成「*Instructions」多一个星号；
 *   - 列表项之间空一行就断成多个 `<ol>`，每段都从「1.」重新编号；
 *   - 2 空格缩进的兄弟项被当成子列表，6 空格的 a./b. 子项反而没挂到父项下。
 * 所以解析结果改成可序列化的 Block/Inline 树：`Markdown.tsx` 只负责把树画成元素，
 * 解析规则由 `web/tests/markdown-parser.test.mjs` 用真实作业文本守住。
 *
 * 安全边界：本文件只产出文本与结构，链接、图片、原生 HTML 标签一律当普通文字，
 * 因此渲染层不需要 dangerouslySetInnerHTML，也没有转义漏网的可能。
 *
 * 块级语法：ATX 标题、段落、有序/无序/字母列表（可嵌套）、引用、分隔线、围栏代码、表格。
 * 行内语法：`code`、***粗斜***（以及 **粗** / *斜* / __ / _）。
 * 字母列表（`a.` / `A.`）是 CommonMark 之外的扩展：老师的文档从 Word 转过来时很常见，
 * 渲染成 `<ol type="a">`。数字列表的起始值保留（`3. foo` → start=3）。
 */

export type MdInline =
  | { type: 'text'; value: string }
  | { type: 'code'; value: string }
  | { type: 'strong'; children: MdInline[] }
  | { type: 'em'; children: MdInline[] }
  | { type: 'br' }

export interface MdListItem {
  blocks: MdBlock[]
}

export type MdBlock =
  | { type: 'heading'; level: number; children: MdInline[] }
  | { type: 'paragraph'; children: MdInline[] }
  | {
      type: 'list'
      ordered: boolean
      /** 字母列表的大小写；数字/无序列表为 undefined。 */
      alpha?: 'a' | 'A'
      start: number
      items: MdListItem[]
    }
  | { type: 'quote'; children: MdInline[] }
  | { type: 'code'; value: string }
  | { type: 'hr' }
  | { type: 'table'; header: MdInline[][]; rows: MdInline[][][] }

const FENCE = /^\s{0,3}```/
const HEADING = /^\s{0,3}(#{1,4})\s+(.*?)\s*$/
const HR = /^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/
const QUOTE = /^\s{0,3}>\s?(.*)$/
const MARKER = /^( *)([-*+]|\d{1,3}[.)]|[A-Za-z][.)])( +)(.*)$/

/** `#` 也渲染成 h2：组件嵌在页面里，页面自己已经有 h1，标题整体下移并封顶 h4。 */
export const MAX_HEADING_LEVEL = 4

interface Marker {
  kind: 'bullet' | 'ordered' | 'alpha'
  alphaCase: 'a' | 'A'
  indent: number
  /** 标记本身（`-`、`1.`、`a)`）。 */
  marker: string
  /** 标记后的空格数。 */
  gap: number
  start: number
  rest: string
}

function expandLeadingTabs(line: string): string {
  return line.replace(/^\t+/, (tabs) => ' '.repeat(tabs.length * 4))
}

function indentWidth(line: string): number {
  return /^ */.exec(line)?.[0].length ?? 0
}

function matchMarker(line: string): Marker | null {
  const matched = MARKER.exec(line)
  if (!matched) return null
  const [, spaces, marker, gap, rest] = matched
  const base = { indent: spaces.length, marker, gap: gap.length, rest }
  if (marker === '-' || marker === '*' || marker === '+') {
    return { ...base, kind: 'bullet', alphaCase: 'a', start: 1 }
  }
  if (/^\d/.test(marker)) {
    return { ...base, kind: 'ordered', alphaCase: 'a', start: Number.parseInt(marker, 10) }
  }
  const letter = marker[0]
  return { ...base, kind: 'alpha', alphaCase: letter === letter.toUpperCase() ? 'A' : 'a', start: 0 }
}

function splitRow(line: string): string[] {
  let body = line.trim()
  if (body.startsWith('|')) body = body.slice(1)
  if (body.endsWith('|')) body = body.slice(0, -1)
  return body.split('|').map((cell) => cell.trim())
}

function isTableSeparator(line: string): boolean {
  if (!line.includes('-')) return false
  const cells = splitRow(line)
  if (cells.length === 0) return false
  return cells.every((cell) => /^:?-{1,}:?$/.test(cell.trim()))
}

function isTableStart(lines: string[], index: number): boolean {
  const line = lines[index]
  return line.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1])
}

/**
 * 段内换行：CommonMark 的软换行合并成一个空格；行尾两个以上空格或反斜杠才是硬换行（保留 <br>）。
 * 之前把每个换行都当 <br>，老师从别处粘来的「一行一句、句子没写完就换行」会被拆得七零八落。
 */
function joinLines(lines: string[]): string {
  let text = ''
  lines.forEach((raw, index) => {
    const content = raw.trim()
    if (index === 0) {
      text = content
      return
    }
    // 硬换行由**上一行**的行尾决定：两个以上空格或反斜杠
    const hard = /( {2,}|\\)$/.test(lines[index - 1])
    text += hard ? `\n${content}` : ` ${content}`
  })
  return text
}

/** 这一行会不会开始一个新的块：段落遇到它就收尾。 */
function startsBlock(lines: string[], index: number): boolean {
  const line = lines[index]
  return (
    FENCE.test(line) ||
    HR.test(line) ||
    HEADING.test(line) ||
    QUOTE.test(line) ||
    matchMarker(line) !== null ||
    isTableStart(lines, index)
  )
}

/* ----------------------------- 行内 ----------------------------- */

function canOpenItalic(source: string, index: number, marker: string): boolean {
  const next = source[index + 1]
  if (next === undefined || /\s/.test(next) || next === marker) return false
  if (marker === '_' && index > 0 && /[A-Za-z0-9]/.test(source[index - 1])) return false
  return true
}

/**
 * 找收尾标记。三条硬条件：
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

/** 行内解析：行内代码 > 粗斜 > 粗体 > 斜体。反引号里的内容不再解析。 */
export function parseInline(source: string): MdInline[] {
  const tokens: MdInline[] = []
  let buffer = ''
  let cursor = 0

  const flush = () => {
    if (buffer) {
      tokens.push({ type: 'text', value: buffer })
      buffer = ''
    }
  }

  while (cursor < source.length) {
    const char = source[cursor]

    if (char === '\n') {
      flush()
      tokens.push({ type: 'br' })
      cursor += 1
      continue
    }

    if (char === '`') {
      const close = source.indexOf('`', cursor + 1)
      // 空的一对反引号（``）当文字，不产生空 <code>
      if (close > cursor + 1) {
        flush()
        tokens.push({ type: 'code', value: source.slice(cursor + 1, close) })
        cursor = close + 1
        continue
      }
    }

    if (char === '*' || char === '_') {
      const triple = char.repeat(3)
      if (source.startsWith(triple, cursor)) {
        const close = findCloser(source, cursor + 3, triple, char === '_')
        if (close > cursor + 3) {
          flush()
          tokens.push({
            type: 'strong',
            children: [{ type: 'em', children: parseInline(source.slice(cursor + 3, close)) }],
          })
          cursor = close + 3
          continue
        }
      }

      const doubled = char.repeat(2)
      if (source.startsWith(doubled, cursor)) {
        const close = findCloser(source, cursor + 2, doubled, char === '_')
        if (close > cursor + 2) {
          flush()
          tokens.push({ type: 'strong', children: parseInline(source.slice(cursor + 2, close)) })
          cursor = close + 2
          continue
        }
      }

      if (canOpenItalic(source, cursor, char)) {
        const close = findCloser(source, cursor + 1, char, char === '_')
        if (close > cursor + 1) {
          flush()
          tokens.push({ type: 'em', children: parseInline(source.slice(cursor + 1, close)) })
          cursor = close + 1
          continue
        }
      }
    }

    buffer += char
    cursor += 1
  }

  flush()
  return tokens
}

/* ----------------------------- 块级 ----------------------------- */

function parseBlocks(lines: string[]): MdBlock[] {
  const blocks: MdBlock[] = []
  let line = 0

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
      blocks.push({ type: 'code', value: body.join('\n') })
      continue
    }

    if (HR.test(current)) {
      blocks.push({ type: 'hr' })
      line += 1
      continue
    }

    const heading = HEADING.exec(current)
    if (heading) {
      blocks.push({
        type: 'heading',
        level: Math.min(heading[1].length + 1, MAX_HEADING_LEVEL),
        children: parseInline(heading[2]),
      })
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
      blocks.push({ type: 'quote', children: parseInline(quoted.join('\n')) })
      continue
    }

    if (isTableStart(lines, line)) {
      const header = splitRow(current).map((cell) => parseInline(cell))
      line += 2
      const rows: MdInline[][][] = []
      while (line < lines.length && lines[line].trim() && lines[line].includes('|')) {
        rows.push(splitRow(lines[line]).map((cell) => parseInline(cell)))
        line += 1
      }
      blocks.push({ type: 'table', header, rows })
      continue
    }

    const first = matchMarker(current)
    if (first) {
      const [list, next] = parseList(lines, line, first)
      blocks.push(list)
      line = next
      continue
    }

    const paragraph: string[] = []
    while (line < lines.length && lines[line].trim()) {
      if (paragraph.length > 0 && startsBlock(lines, line)) break
      paragraph.push(lines[line])
      line += 1
    }
    blocks.push({ type: 'paragraph', children: parseInline(joinLines(paragraph)) })
  }

  return blocks
}

/**
 * 解析一组同层列表项。
 *
 * 关键规则（CommonMark 的简化版，也是本次修复的核心）：
 *   - 同一列表里，**空行不打断列表**：下一个非空行还是同层标记，就继续这一组；
 *   - 子层判定看「父项正文的起始列」（indent + 标记宽度 + 空格数），不是固定的 2 空格；
 *     所以 `1. ` 后面缩进 2 空格是兄弟项，缩进 6 空格才是子项；
 *   - 子项内容去掉父项正文起始列后**递归**解析，因此子列表可以再嵌套。
 */
function parseList(lines: string[], start: number, first: Marker): [MdBlock, number] {
  const items: MdListItem[] = []
  let line = start

  while (line < lines.length) {
    let next = line
    while (next < lines.length && !lines[next].trim()) next += 1
    if (next >= lines.length) break

    const marker = matchMarker(lines[next])
    if (!marker || marker.kind !== first.kind || marker.indent !== first.indent) break
    if (marker.kind === 'alpha' && marker.alphaCase !== first.alphaCase) break

    const contentIndent = marker.indent + marker.marker.length + marker.gap
    const body: string[] = [marker.rest]
    let cursor = next + 1

    while (cursor < lines.length) {
      const row = lines[cursor]
      if (!row.trim()) {
        // 空行：后面还有更深缩进（或同层下一项）就还属于这一项，否则这一项结束
        let probe = cursor
        while (probe < lines.length && !lines[probe].trim()) probe += 1
        if (probe < lines.length && indentWidth(lines[probe]) >= contentIndent) {
          body.push('')
          cursor += 1
          continue
        }
        break
      }
      if (indentWidth(row) >= contentIndent) {
        body.push(row.slice(contentIndent))
        cursor += 1
        continue
      }
      // 比正文起始列更浅：新列表项由外层循环接手；否则是懒续行，归入本项
      if (matchMarker(row)) break
      body.push(row.trimStart())
      cursor += 1
    }

    items.push({ blocks: parseBlocks(body) })
    line = cursor
  }

  return [
    {
      type: 'list',
      ordered: first.kind !== 'bullet',
      alpha: first.kind === 'alpha' ? first.alphaCase : undefined,
      start: first.start,
      items,
    },
    line,
  ]
}

export function parseMarkdown(source: string): MdBlock[] {
  const lines = source.split(/\r\n|\r|\n/).map(expandLeadingTabs)
  return parseBlocks(lines)
}
