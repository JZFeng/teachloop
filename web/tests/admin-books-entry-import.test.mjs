/*
 * 2026-10-30（Bug）：`?edit=new` 的新建面板只做元信息，词条必须「先保存 → 再跳导入页」两步才能加，
 * 而编辑面板有词条区——两屏不一致。用户要求「new 的时候直接让用户把词条搞进去」。
 *
 * 这里用源码扫描把三件事锁住（成本很低、不依赖浏览器）：
 *   1. 新建面板与编辑面板共用同一个内联词条区（`ImportPastePanel`），新建时预览走草稿端点
 *      `/api/admin/vocab/books/draft/entries/preview`，不再有「跳去导入页」的按钮；
 *   2. 「保存」会把面板里的词条一起提交（先建书/改书，再 `POST .../{id}/entries`）；
 *   3. 原来的独立导入页只留重定向（旧链接不 404），不再自己渲染一套粘贴面板。
 *
 * 说明：这是**结构校验**；真实流程（点一次保存 → 书与词条同时落库）由接口测试
 * `VocabBooksTest.draftPreviewForNewBookPanel` 与浏览器验收覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const PANEL = read('../src/vocab/books/AdminBooksPage.tsx')
const IMPORT_PAGE = read('../src/vocab/books/BookImportPage.tsx')
const PASTE_PANEL = read('../src/vocab/import/ImportPastePanel.tsx')

test('新建面板与编辑面板共用内联词条区：新建走草稿预览端点', () => {
  assert.match(PANEL, /from '\.\.\/import\/ImportPastePanel'/, '面板必须复用加词页那块粘贴/预览组件')
  assert.match(PANEL, /<ImportPastePanel/, '面板里必须渲染 ImportPastePanel')
  assert.match(PANEL, /'\/api\/admin\/vocab\/books\/draft\/entries\/preview'/,
    '书还没建时要走草稿预览端点')
  assert.match(PANEL, /`\/api\/admin\/vocab\/books\/\$\{selected\.id\}\/entries\/preview`/,
    '已存在的书走这本书自己的预览端点')
})

test('「保存」一次完成建书/改书 + 词条导入', () => {
  assert.match(PANEL, /const importEntries = async \(bookId: string\)/, '保存里的导入动作必须抽出来共用')
  assert.match(PANEL, /\/api\/admin\/vocab\/books\/\$\{bookId\}\/entries/, '导入必须打到书的词条端点')
  const save = PANEL.slice(PANEL.indexOf('const save = async ()'), PANEL.indexOf('/** 发布 / 下架'))
  assert.ok(save.length > 0, '找不到 save()')
  assert.match(save, /createAdminBook\(/, '新建路径必须先建书')
  assert.match(save, /importEntries\(created\.id\)/, '新建成功后必须在同一个「保存」里把词条导进去')
  assert.match(save, /importEntries\(updated\.id\)/, '编辑路径同样要能顺手追加词条')
  assert.doesNotMatch(PANEL, /openImport/, '不该再有跳去独立导入页的动作')
})

test('独立导入页只留重定向，不再自带一套粘贴面板', () => {
  assert.match(IMPORT_PAGE, /<Navigate\s+replace/, '导入页应重定向到那本书的面板')
  assert.doesNotMatch(IMPORT_PAGE, /ImportPastePanel/, '导入页不许再渲染第二套粘贴面板')
})

test('粘贴面板支持受控文本：页面才能在自己的「保存」里读到同一段内容', () => {
  assert.match(PASTE_PANEL, /text\?: string/, '缺少受控文本 prop')
  assert.match(PASTE_PANEL, /onTextChange\?: \(value: string\) => void/, '缺少 onTextChange')
  assert.match(PASTE_PANEL, /const value = text \?\? innerText/, '受控/非受控必须共用一份取值')
  assert.match(PASTE_PANEL, /commitPath\?: string/, 'commitPath 必须可选（新建面板由页面自己提交）')
})
