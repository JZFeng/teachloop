/*
 * 「导入 .md / .txt」读取逻辑的单测（web/src/teacher/markdownImport.ts）。
 *
 * 为什么值得测：这段代码替代了原来的 `file.text()` 裸调用，新增的三种失败路径
 * （太大 / 空 / 读不到）如果没被覆盖，页面就会退化成「点了没反应」。
 * 这里不碰 DOM：Node 自带 File，正好能造出正常、超限、空白、读取抛错四种输入。
 *
 * 运行：cd web && pnpm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MARKDOWN_IMPORT_MAX_BYTES,
  readMarkdownFile,
} from '../src/teacher/markdownImport.ts'

const md = (body) => new File([body], 'rubric.md', { type: 'text/markdown' })

test('CRLF 与单独 CR 都归一成 LF，内容不变', async () => {
  const result = await readMarkdownFile(md('a\r\nb\rc\nd'))
  assert.deepEqual(result, { ok: true, text: 'a\nb\nc\nd' })
})

test('超过上限按 tooLarge 拒绝（测试用 3 字节上限，不造 MB 级夹具）', async () => {
  const result = await readMarkdownFile(md('abcdef'), 3)
  assert.deepEqual(result, { ok: false, reason: 'tooLarge' })
})

test('只有空白字符的文件按 empty 拒绝', async () => {
  const result = await readMarkdownFile(md(' \r\n\t '))
  assert.deepEqual(result, { ok: false, reason: 'empty' })
})

test('读取抛错时返回 unreadable，不把异常抛给页面', async () => {
  const broken = { size: 3, text: async () => { throw new Error('boom') } }
  const result = await readMarkdownFile(broken)
  assert.deepEqual(result, { ok: false, reason: 'unreadable' })
})

test('默认上限是 1 MB', () => {
  assert.equal(MARKDOWN_IMPORT_MAX_BYTES, 1024 * 1024)
})
