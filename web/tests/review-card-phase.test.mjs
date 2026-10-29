/**
 * 复习卡三态判定（2026-10-29 用户报 Bug）。
 *
 * 真实场景：`/vocab/review/session/:id` 的卡片只剩一行 `struggle` + `英 — 美 —`，
 * 中间大片空白，翻面也什么都没有。原因是内容包换版（v2.3 → v2.5）后，`vocab_words`
 * 还写着 `content_status = 'READY'`，前端只看状态就渲染了卡面。
 * 这里把「状态 + 内容」的判定钉死，页面只负责渲染。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { reviewCardPhase } from '../src/vocab/reviewCardState.ts'

test('READY 且有内容：正常渲染卡面', () => {
  assert.equal(reviewCardPhase('READY', { senses: [{ zh: '奋斗；挣扎' }] }), 'ready')
})

test('READY 但内容解析不到（内容包换版/提示词升级）：按准备中处理，不渲染空卡', () => {
  assert.equal(reviewCardPhase('READY', null), 'preparing')
  assert.equal(reviewCardPhase('READY', undefined), 'preparing')
})

test('PENDING / 空状态：准备中，等按需生成', () => {
  assert.equal(reviewCardPhase('PENDING', null), 'preparing')
  assert.equal(reviewCardPhase(undefined, null), 'preparing')
  assert.equal(reviewCardPhase(null, undefined), 'preparing')
})

test('FAILED：走失败态，「重试」按钮才有地方出现', () => {
  assert.equal(reviewCardPhase('FAILED', null), 'failed')
  assert.equal(reviewCardPhase('FAILED', { senses: [] }), 'failed')
})
