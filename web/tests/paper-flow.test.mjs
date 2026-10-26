/*
 * 试卷向导纯逻辑的回归测试（D-34 / AC-4.24–4.29）。
 *
 * 为什么值得单测：这些函数决定老师看到的数字与「能不能发布」——目标总分不一致要拦、
 * 缺答案要标、评分项列不能出现 `—`、旧草稿不能把四步页面渲染崩。一条判错就是一个假数字。
 *
 * 运行：cd web && pnpm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  applyMetaHints,
  blankDraft,
  compareTarget,
  deleteSummary,
  draftFromDetail,
  essayRubricCheck,
  essayTabs,
  findMetaProblem,
  metaPayload,
  needsReplaceConfirm,
  normalizeDraft,
  previewRows,
  previewSummary,
  publishCheck,
  rubricBlocks,
  rubricGate,
  stageIndex,
  toNumberOrNull,
} from '../src/teacher/paperFlow.ts'
// 页面里的胶水：先解析成摘要，再交给 paperFlow 判定。这里照抄两行，测的就是老师真正走的路径。
import { summarizeRubric } from '../src/components/rubric.ts'

/** 与真实卷同形状的详情：听力 2 题（1 缺答案）+ 写作 1 题（有 rubric）。 */
const DETAIL = {
  paper: {
    id: 'p1',
    title: '高一英语 10 月月考',
    totalPoints: 25,
    version: 1,
    status: 'DRAFT',
    defaultDurationMinutes: 120,
    authorName: '李璐',
    reviewerName: null,
    targetTotalPoints: 25,
  },
  sections: [
    {
      id: 's1',
      title: '第一部分 听力',
      position: 1,
      pointsPerItem: 1.5,
      items: [
        { id: 'i1', number: 1, questionType: 'SINGLE_CHOICE', stem: 'What film…', options: 'A. …', answerKey: 'A', points: 1.5 },
        { id: 'i2', number: 2, questionType: 'SINGLE_CHOICE', stem: 'What does…', options: 'A. …', answerKey: '', points: 1.5 },
      ],
    },
    {
      id: 's2',
      title: '第四部分 写作',
      position: 2,
      pointsPerItem: 22,
      items: [
        { id: 'i3', number: 4, questionType: 'ESSAY', stem: '应用文写作', rubricMarkdown: '- 要点（22 分）', points: 22 },
      ],
    },
  ],
  criteriaNames: [],
  rubricMarkdown: null,
  gradingPromptTemplate: null,
  essayContentPercent: 80,
  contentMarkdown: '# 试卷原文',
}

test('previewRows：写作题不算缺答案，空答案的客观题算', () => {
  const rows = previewRows(DETAIL)
  assert.equal(rows.length, 3)
  assert.equal(rows[0].missing, false)
  assert.equal(rows[1].missing, true)
  assert.equal(rows[2].essay, true)
  assert.equal(rows[2].missing, false)
  assert.equal(rows[0].sectionTitle, '第一部分 听力')
})

test('previewSummary：题数 / 合计 / 缺答案 / 题号断档', () => {
  const summary = previewSummary(DETAIL)
  assert.equal(summary.itemCount, 3)
  assert.equal(summary.itemsTotal, 25)
  assert.equal(summary.missingCount, 1)
  // 1、2、4 → 缺 3
  assert.deepEqual(summary.gaps, [3])
})

test('previewSummary：连续题号没有断档', () => {
  const detail = {
    ...DETAIL,
    sections: [{ ...DETAIL.sections[0], items: DETAIL.sections[0].items.slice(0, 2) }],
  }
  assert.deepEqual(previewSummary(detail).gaps, [])
})

test('essayTabs：只取写作题、按题号升序', () => {
  const tabs = essayTabs(DETAIL)
  assert.equal(tabs.length, 1)
  assert.deepEqual(
    { number: tabs[0].number, points: tabs[0].points, sectionTitle: tabs[0].sectionTitle },
    { number: 4, points: 22, sectionTitle: '第四部分 写作' },
  )
  assert.equal(essayTabs(null).length, 0)
})

test('rubricBlocks：没有写作题给「无」，有 rubric 就按题号分块', () => {
  assert.deepEqual(rubricBlocks([], '无'), [{ number: null, text: '无', filled: false }])
  const blocks = rubricBlocks(
    [
      { number: 4, points: 15, rubricMarkdown: '- 要点（15 分）', sectionTitle: '写作' },
      { number: 5, points: 15, rubricMarkdown: '   ', sectionTitle: '写作' },
    ],
    '无',
  )
  assert.equal(blocks.length, 2)
  assert.equal(blocks[0].number, 4)
  assert.equal(blocks[0].filled, true)
  // 列表记号不显示（老师写的是 Markdown，表格里要读的是评分项本身）
  assert.equal(blocks[0].text, '要点（15 分）')
  // 嵌套/重复的列表记号也一起剥（Vditor 里连打两次列表会出现 `- - `）
  assert.equal(rubricBlocks([{ number: 5, points: 1, rubricMarkdown: '- - 嵌套（1 分）', sectionTitle: 'x' }], '无')[0].text, '嵌套（1 分）')
  assert.equal(blocks[1].text, '无')
  assert.equal(blocks[1].filled, false)
})

test('compareTarget：空目标不校验，容差内算一致', () => {
  assert.equal(compareTarget(null, 150), 'none')
  assert.equal(compareTarget('', 150), 'none')
  assert.equal(compareTarget(150, 150), 'match')
  assert.equal(compareTarget('150.0', 150), 'match')
  assert.equal(compareTarget(150, 142), 'mismatch')
  assert.equal(compareTarget(150, 150.0005), 'match')
})

test('publishCheck：锁定 / 空卷 / 缺答案 / 目标不一致依次拦住', () => {
  const base = { itemCount: 77, actual: 150, target: 150, missingCount: 0, locked: false }
  assert.deepEqual(publishCheck(base), { ok: true, reason: null })
  assert.equal(publishCheck({ ...base, locked: true }).reason, 'locked')
  assert.equal(publishCheck({ ...base, itemCount: 0 }).reason, 'empty')
  assert.equal(publishCheck({ ...base, actual: 0 }).reason, 'empty')
  assert.equal(publishCheck({ ...base, missingCount: 2 }).reason, 'missingAnswers')
  assert.equal(publishCheck({ ...base, target: 142 }).reason, 'mismatch')
  // 目标为空时只校验前面的项
  assert.equal(publishCheck({ ...base, target: null }).ok, true)
})

test('deleteSummary：数出会丢的 rubric，旧评分项金额由总分差推导', () => {
  const summary = deleteSummary(DETAIL)
  assert.equal(summary.sections, 2)
  assert.equal(summary.items, 3)
  assert.equal(summary.rubrics, 1)
  assert.equal(summary.criteriaNames, 0)
  assert.equal(summary.legacyCriteriaPoints, 0)
  assert.equal(needsReplaceConfirm(summary), true)

  const legacy = deleteSummary({
    ...DETAIL,
    criteriaNames: ['内容', '语言'],
    paper: { ...DETAIL.paper, totalPoints: 30 },
  })
  assert.equal(legacy.criteriaNames, 2)
  assert.equal(legacy.legacyCriteriaPoints, 5)
  assert.equal(needsReplaceConfirm(legacy), true)

  assert.equal(needsReplaceConfirm(deleteSummary(null)), false)
})

test('normalizeDraft：v2 草稿可用，旧五步草稿当作没有', () => {
  const good = normalizeDraft({
    paperId: 'p1',
    step: 3,
    title: '月考',
    durationMinutes: '120',
    authorName: '李璐',
    reviewerName: '',
    targetTotalPoints: '150',
    contentMarkdown: '# 原文',
  })
  assert.equal(good.step, 3)
  assert.equal(good.targetTotalPoints, '150')
  assert.equal(good.contentMarkdown, '# 原文')

  // 旧版形状：有 sections、没有 contentMarkdown
  assert.equal(normalizeDraft({ step: 2, sections: [{ key: 'a', items: [] }] }), null)
  assert.equal(normalizeDraft(null), null)
  assert.equal(normalizeDraft('nope'), null)
  assert.equal(normalizeDraft({ step: 99, paperId: null }).step, 1)
})

test('stageIndex：四个阶段，超时不越界', () => {
  assert.equal(stageIndex(0), 0)
  assert.equal(stageIndex(1200), 1)
  assert.equal(stageIndex(2500), 2)
  assert.equal(stageIndex(3600), 3)
  assert.equal(stageIndex(99999), 3)
  assert.equal(stageIndex(-5), 0)
})

test('findMetaProblem：标题 / 时长 / 目标总分', () => {
  const base = { ...blankDraft(), title: '月考' }
  assert.equal(findMetaProblem(base), null)
  assert.equal(findMetaProblem({ ...base, title: '  ' }), 'title')
  assert.equal(findMetaProblem({ ...base, durationMinutes: '0' }), 'duration')
  assert.equal(findMetaProblem({ ...base, durationMinutes: '90.5' }), 'duration')
  assert.equal(findMetaProblem({ ...base, durationMinutes: '90' }), null)
  assert.equal(findMetaProblem({ ...base, targetTotalPoints: '-1' }), 'target')
  assert.equal(findMetaProblem({ ...base, targetTotalPoints: '150' }), null)
})

test('applyMetaHints：只填空白，不覆盖已填的值', () => {
  const draft = { ...blankDraft(), title: '月考', authorName: '王老师' }
  const next = applyMetaHints(draft, { authorName: '李璐', reviewerName: '刘嘉伦', targetTotalPoints: 150 })
  assert.equal(next.authorName, '王老师')
  assert.equal(next.reviewerName, '刘嘉伦')
  assert.equal(next.targetTotalPoints, '150')
  assert.equal(applyMetaHints(draft, null).authorName, '王老师')
})

test('metaPayload / toNumberOrNull：空串是 null，非法数字也当 null', () => {
  assert.equal(toNumberOrNull(''), null)
  assert.equal(toNumberOrNull('  '), null)
  assert.equal(toNumberOrNull('150'), 150)
  assert.equal(toNumberOrNull('abc'), null)
  const payload = metaPayload({ ...blankDraft(), authorName: ' 李璐 ', reviewerName: '  ', targetTotalPoints: '150' })
  assert.deepEqual(payload, { authorName: '李璐', reviewerName: null, targetTotalPoints: 150 })
})

test('draftFromDetail：旧卷没有原文 / 没有 meta 时是空串而不是 "null"', () => {
  const legacy = {
    ...DETAIL,
    paper: { ...DETAIL.paper, authorName: null, reviewerName: null, targetTotalPoints: null },
    contentMarkdown: null,
  }
  const draft = draftFromDetail(legacy, 2)
  assert.equal(draft.paperId, 'p1')
  assert.equal(draft.step, 2)
  assert.equal(draft.authorName, '')
  assert.equal(draft.targetTotalPoints, '')
  assert.equal(draft.contentMarkdown, '')
  assert.equal(draft.durationMinutes, '120')
})

/* ---------- 第 3 步评分项体检（2026-10-25：必填拦下一步、合计不一致只提醒） ---------- */

const essayTab = (number, points) => ({ number, points, rubricMarkdown: '', sectionTitle: '第四部分 写作' })

test('essayRubricCheck：完全没填是 empty（唯一的「拦下一步」）', () => {
  const blank = essayTab(76, 15)
  for (const text of ['', '   ', '\n\n']) {
    assert.equal(essayRubricCheck(blank, text, summarizeRubric(text)).verdict, 'empty')
  }
})

test('essayRubricCheck：合计与本题满分一致是 matched，差一分是 mismatch', () => {
  const tab = essayTab(76, 15)
  const ok = '1. 表达理解（5 分）\n2. 给出建议（5 分）\n3. 语法与拼写（5 分）'
  const matched = essayRubricCheck(tab, ok, summarizeRubric(ok))
  assert.equal(matched.verdict, 'matched')
  assert.equal(matched.total, 15)

  const short = '1. 表达理解（5 分）\n2. 给出建议（5 分）'
  const mismatch = essayRubricCheck(tab, short, summarizeRubric(short))
  assert.equal(mismatch.verdict, 'mismatch')
  assert.equal(mismatch.total, 10)
  assert.equal(mismatch.points, 15)
})

test('essayRubricCheck：有文字但没写分值只提醒（后端按均分），不算 mismatch', () => {
  const tab = essayTab(77, 15)
  const text = '1. 表达理解\n2. 给出建议\n3. 语法与拼写'
  const check = essayRubricCheck(tab, text, summarizeRubric(text))
  assert.equal(check.verdict, 'noPoints')
  assert.equal(check.total, 0)
})

test('rubricGate：只有「完全没填」拦人，合计不一致不拦', () => {
  const empty = essayRubricCheck(essayTab(76, 15), '', summarizeRubric(''))
  const matched = essayRubricCheck(essayTab(77, 15), '1. 要点（15 分）', summarizeRubric('1. 要点（15 分）'))
  const gate = rubricGate([empty, matched])
  assert.equal(gate.blocked, true)
  assert.deepEqual(gate.empty, [76])

  const short = '1. 要点（10 分）'
  const mismatch = essayRubricCheck(essayTab(76, 15), short, summarizeRubric(short))
  const soft = rubricGate([mismatch, matched])
  assert.equal(soft.blocked, false)
  assert.deepEqual(soft.mismatch.map((entry) => entry.number), [76])
  assert.deepEqual(soft.noPoints, [])

  const noPoints = essayRubricCheck(essayTab(76, 15), '1. 要点', summarizeRubric('1. 要点'))
  const all = rubricGate([mismatch, noPoints])
  assert.equal(all.blocked, false)
  assert.deepEqual(all.noPoints, [76])
})

test('essayRubricCheck：写了字但一条评分项都没解析出来 = unparsed（等于没填）', () => {
  const tab = essayTab(76, 15)
  // 老师在即时渲染视图里手打的形态：没有 1. / - 标记，「评分标准：」也不是 Markdown 标题。
  const text = '评分标准：\n表达理解(5分)\n给出建议(5分)'
  const check = essayRubricCheck(tab, text, summarizeRubric(text))
  assert.equal(check.verdict, 'unparsed')
  assert.equal(check.total, 0)
})

test('rubricGate：unparsed 和 empty 一起拦下一步，提示分开', () => {
  const blank = '评分标准：\n表达理解(5分)'
  const unparsed = essayRubricCheck(essayTab(76, 15), blank, summarizeRubric(blank))
  const empty = essayRubricCheck(essayTab(77, 15), '', summarizeRubric(''))
  const gate = rubricGate([unparsed, empty])

  assert.equal(gate.blocked, true)
  assert.deepEqual(gate.unparsed, [76])
  assert.deepEqual(gate.empty, [77])
  // 只报「没写分值」的题不拦：它有解析结果，只是没写分值。
  const noPoints = essayRubricCheck(essayTab(78, 15), '1. 要点', summarizeRubric('1. 要点'))
  assert.equal(rubricGate([noPoints]).blocked, false)
})
