/**
 * 词卡密度两版 mock 的可复核验证 + 截图脚本。
 *
 *   cd web && NODE_PATH=$PWD/node_modules node ../docs/mock/vocab-card-density-2026-10-31/verify.cjs
 *
 * 做三件事：
 *   1. 量化：v0（现状单列基线）/ v1 / v2 在 1440×900 与 834×1194 下的高度、每行词数、换行数、最小列宽；
 *   2. 断言：每行词数、834 无横向溢出、核心理解在 1440×900 首屏内、无 console 报错、tab 切换生效；
 *   3. 截图：shots/{v1,v2}-{1440,834}.png（viewport 截图，不是整页，用来证明首屏）。
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('@playwright/test');

const DIR = __dirname;
const FILE = 'file://' + path.join(DIR, 'index.html');
const SHOTS = path.join(DIR, 'shots');

const failures = [];
function check(name, ok, detail) {
  const line = `${ok ? 'PASS' : 'FAIL'}  ${name}${detail === undefined ? '' : '  → ' + JSON.stringify(detail)}`;
  console.log(line);
  if (!ok) failures.push(line);
}

/**
 * 每个 item 的「单行实际占用宽度」与列宽余量（slack = 列宽 - 占用）。
 * 词 / 词性用实测盒子宽度；释义用 Range 实测字形宽度（不能取盒子宽度：盒子被 1fr 撑满，会变成循环论证）。
 *   slack < 0  → 该格换行（行高翻倍）
 *   slack ≈ 0  → 恰好单行放下，换字体或系统字号就会换行
 */
async function itemWidths(page, group) {
  return page.evaluate((group) => {
    const grid = document.querySelector('.morph-grid[data-group="' + group + '"]');
    const cell = Math.round(grid.querySelector('.morph-item').getBoundingClientRect().width * 10) / 10;
    const items = Array.from(grid.querySelectorAll('.morph-item')).map((el) => {
      const word = el.querySelector('b').getBoundingClientRect().width;
      const pos = el.querySelector('.pos').getBoundingClientRect().width;
      const gloss = el.querySelector('.gloss');
      const gap = parseFloat(window.getComputedStyle(el).columnGap) || 0;
      let glossWidth = 0;
      if (gloss) {
        const range = document.createRange();
        range.selectNodeContents(gloss);
        glossWidth = Math.max.apply(null, Array.from(range.getClientRects()).map((rect) => rect.width));
      }
      const used = word + gap + pos + (gloss ? gap + glossWidth : 0);
      return {
        text: el.textContent.replace(/\s+/g, ' ').trim(),
        used: Math.round(used * 10) / 10,
        slack: Math.round((cell - used) * 10) / 10,
        wrapped: el.getBoundingClientRect().height > parseFloat(window.getComputedStyle(el).lineHeight) * 1.45,
      };
    });
    const tightest = items.reduce((a, b) => (a.slack <= b.slack ? a : b));
    return { cell, tightest, wrapped: items.filter((item) => item.wrapped).length, total: items.length, items };
  }, group);
}

async function collect(page, version) {
  await page.evaluate((v) => { document.body.setAttribute('data-version', v); }, version);
  const metrics = await page.evaluate(() => window.mockMetrics());
  const widths = {};
  for (const group of ['derivatives', 'inflections', 'family']) {
    widths[group] = await itemWidths(page, group);
  }
  return { metrics, widths };
}

(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const result = {};

  for (const bp of [{ w: 1440, h: 900, tag: '1440' }, { w: 834, h: 1194, tag: '834' }]) {
    for (const version of ['v1', 'v2']) {
      const page = await browser.newPage({ viewport: { width: bp.w, height: bp.h } });
      const errors = [];
      page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
      page.on('pageerror', (err) => errors.push(String(err)));
      await page.goto(FILE + '#' + version);
      await page.waitForTimeout(120);

      // tab 选中态必须跟 #hash 一致
      const activeTab = await page.evaluate(() => document.querySelector('.proto-tab.is-on').getAttribute('data-version'));
      check(`${bp.tag} ${version} tab 选中态与 hash 一致`, activeTab === version, activeTab);

      const { metrics, widths } = await collect(page, version);
      result[version + '-' + bp.tag] = { metrics, widths, errors };

      const rows = (g) => metrics.groups[g].map((row) => row.items);
      const expected = version === 'v1'
        ? { derivatives: [3, 1], inflections: [3], family: [3, 3, 2] }
        : { derivatives: [2, 2], inflections: [2, 1], family: [3, 3, 2] };
      for (const group of ['derivatives', 'inflections', 'family']) {
        check(`${bp.tag} ${version} ${group} 每行词数 = ${JSON.stringify(rows(group))}`,
          JSON.stringify(rows(group)) === JSON.stringify(expected[group]), expected[group]);
      }
      check(`${bp.tag} ${version} 无横向溢出（scrollWidth - innerWidth = ${metrics.overflowPx}px）`, metrics.overflowPx <= 2, metrics.overflowPx);
      check(`${bp.tag} ${version} 无 console 报错`, errors.length === 0, errors);

      // 页头结构必须与真实实现一致：返回行 → 「查词 ⓘ」→ 卡片列（反馈图标单独一行、卡上方右侧）
      const head = await page.evaluate(() => {
        const back = document.querySelector('.back-btn').getBoundingClientRect();
        const title = document.querySelector('.page-title').getBoundingClientRect();
        const card = document.getElementById('wordCard').getBoundingClientRect();
        const icon = document.getElementById('feedbackBtn').getBoundingClientRect();
        const padRight = parseFloat(getComputedStyle(document.getElementById('wordCard')).paddingRight);
        return {
          backBottom: Math.round(back.bottom), titleTop: Math.round(title.top), titleBottom: Math.round(title.bottom),
          cardTop: Math.round(card.top), iconBottom: Math.round(icon.bottom),
          iconRightGap: Math.round((card.right - padRight - 1) - icon.right),
        };
      });
      check(`${bp.tag} ${version} 返回行在「查词」标题上方`, head.backBottom <= head.titleTop + 1, head);
      check(`${bp.tag} ${version} 标题行在卡片上方`, head.titleBottom <= head.cardTop, head);
      check(`${bp.tag} ${version} 反馈图标在卡片上方且与卡内右边对齐（偏差 ${head.iconRightGap}px）`,
        head.iconBottom <= head.cardTop && Math.abs(head.iconRightGap) <= 2, head);

      // ⓘ 气泡能开能关
      const info = await page.evaluate(() => {
        const btn = document.getElementById('infoBtn');
        const pop = document.getElementById('infoPop');
        btn.click();
        const opened = !pop.hidden;
        document.body.click();
        return { opened, closed: pop.hidden, text: pop.textContent.trim() };
      });
      check(`${bp.tag} ${version} ⓘ 气泡可开可关（${info.text}）`, info.opened && info.closed, info);

      // 换行项数按本 mock 已记录的实测值断言（834 下真实卡片内边距 40 + 标签列 64 会把 3 列挤到换行，
      // 这是「按真实版式量出来的结论」，不是渲染 bug；v1 在 834 反而比 v2 更矮更散，见 README §3）
      const WRAP_EXPECT = {
        '1440': { v1: { derivatives: 0, inflections: 0, family: 0 }, v2: { derivatives: 0, inflections: 0, family: 0 } },
        '834': { v1: { derivatives: 3, inflections: 0, family: 3 }, v2: { derivatives: 0, inflections: 0, family: 3 } },
      };
      const wrappedOf = (group) => metrics.groups[group].reduce((sum, row) => sum + row.wrapped, 0);
      for (const group of ['derivatives', 'inflections', 'family']) {
        const expectWrap = WRAP_EXPECT[bp.tag][version][group];
        check(`${bp.tag} ${version} ${group} 换行项数 = ${wrappedOf(group)}（记录值 ${expectWrap}）`,
          wrappedOf(group) === expectWrap, { cell: widths[group].cell });
      }

      if (bp.tag === '1440') {
        check('1440 ' + version + ' 核心理解在 900 首屏内（标题 top = ' + metrics.coreTitleTopInViewport + 'px）',
          metrics.coreTitleAboveFold, metrics.coreTitleTopInViewport);
      }

      await page.screenshot({ path: path.join(SHOTS, `${version}-${bp.tag}.png`) });
      await page.close();
    }
  }

  // v0 现状基线（单列）：只在 1440 下量化，不出图、不在切换器里出现
  const page0 = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page0.goto(FILE + '#v1');
  const v0 = await collect(page0, 'v0');
  result['v0-1440'] = { metrics: v0.metrics, widths: v0.widths };
  // 切回 v1，防止 #hash 与 body 不一致影响后续判断
  await collect(page0, 'v1');
  await page0.close();

  // 真实页面没有预览条：再量一遍首屏（预览条 1440 下 67px、834 下 85px）
  for (const bp of [{ w: 1440, h: 900, tag: '1440' }, { w: 834, h: 1194, tag: '834' }]) {
    const pageShot = await browser.newPage({ viewport: { width: bp.w, height: bp.h } });
    await pageShot.goto(FILE + '#v1');
    await pageShot.evaluate(() => document.body.classList.add('shot'));
    for (const version of ['v1', 'v2']) {
      await pageShot.evaluate((v) => { document.body.setAttribute('data-version', v); }, version);
      const m = await pageShot.evaluate(() => window.mockMetrics());
      result['noChrome-' + version + '-' + bp.tag] = m;
      check(`无预览条 ${bp.tag} ${version} 核心理解在首屏内（标题 top = ${m.coreTitleTopInViewport}px / ${m.firstScreenHeight}px）`,
        m.coreTitleAboveFold, m.coreTitleTopInViewport);
    }
    await pageShot.close();
  }

  // 两个版本的 DOM 必须完全一致（差异只允许来自 body[data-version] 上的 CSS）
  const pageDom = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await pageDom.goto(FILE + '#v1');
  const dom = {};
  for (const version of ['v1', 'v2']) {
    await pageDom.evaluate((v) => { document.body.setAttribute('data-version', v); }, version);
    dom[version] = await pageDom.evaluate(() =>
      document.querySelector('main').outerHTML.replace(/\s+/g, ' ').trim());
  }
  check('两版 DOM 完全相同（除密度外无差异）', dom.v1 === dom.v2, { sameLength: dom.v1.length === dom.v2.length });
  await pageDom.close();

  await browser.close();

  const out = path.join(DIR, 'metrics.json');
  fs.writeFileSync(out, JSON.stringify(result, null, 2));
  console.log('\nmetrics →', out);
  console.log(failures.length === 0 ? '\nALL PASS' : '\n' + failures.length + ' FAILED\n' + failures.join('\n'));
  process.exit(failures.length === 0 ? 0 : 1);
})();
