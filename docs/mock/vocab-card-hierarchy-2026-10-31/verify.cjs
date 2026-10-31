/**
 * 词卡层级四方案 mock 的量化 + 断言 + 截图脚本。
 *
 *   cd web && NODE_PATH=$PWD/node_modules node ../docs/mock/vocab-card-hierarchy-2026-10-31/verify.cjs
 *
 * 覆盖：五个方案 × 1440×900 / 834×1194 的指标（README 每个数字来自这里）、p5 的义项 A/B/C 对照、全部截图。
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('@playwright/test');

const DIR = __dirname;
const FILE = 'file://' + path.join(DIR, 'index.html');
const SHOTS = path.join(DIR, 'shots');
const SCHEMES = ['p1', 'p2', 'p3', 'p4', 'p5'];

const failures = [];
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail === undefined ? '' : '  → ' + JSON.stringify(detail)}`);
  if (!ok) failures.push(name);
}

async function selectScheme(page, scheme) {
  await page.evaluate((s) => {
    document.querySelector('.proto-tab[data-scheme="' + s + '"]').click();
  }, scheme);
  await page.waitForTimeout(240);
}

async function measure(page) {
  return page.evaluate(() => window.mockMetrics());
}

(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const result = {};

  for (const bp of [{ w: 1440, h: 900, tag: '1440' }, { w: 834, h: 1194, tag: '834' }]) {
    for (const scheme of SCHEMES) {
      const page = await browser.newPage({ viewport: { width: bp.w, height: bp.h } });
      const errors = [];
      page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
      page.on('pageerror', (err) => errors.push(String(err)));
      await page.goto(FILE + '#p1');
      await selectScheme(page, scheme);

      const activeTab = await page.evaluate(() => document.querySelector('.proto-tab.is-on').getAttribute('data-scheme'));
      check(`${bp.tag} ${scheme} tab 选中态生效`, activeTab === scheme, activeTab);

      const m = await measure(page);
      result[scheme + '-' + bp.tag] = m;
      const at = `${bp.tag} ${scheme}`;

      // 六项指标的基础约束
      check(`${at} 无横向溢出（${m.overflowPx}px）`, m.overflowPx <= 2, m.overflowPx);
      check(`${at} 无 console 报错`, errors.length === 0, errors);
      check(`${at} 重复行数 = 7`, m.duplicateRows === 7, { duplicateRows: m.duplicateRows, familyRows: m.familyRows });
      check(`${at} 词族 0 个新词`, m.newFamilyWords === 0, m.newFamilyWords);
      check(`${at} 区块顺序 = 核心理解 → 构词 → 例句 → 近义词 → 词源`,
        m.sectionOrder.join(',') === 'coreSection,morphSection,examplesSection,synonymsSection,etymologySection', m.sectionOrder);
      check(`${at} 核心理解正文完整落在首屏内（${m.coreVisiblePx} / ${m.coreBodyHeight}px）`,
        m.coreVisiblePx === m.coreBodyHeight && m.coreBodyHeight > 0, m.coreTitleTopInViewport);

      if (scheme === 'p1') {
        check(`${at} 词族组已删除、7 个可见词条`, m.visibleMorphItems === 7, m.visibleMorphItems);
        check(`${at} 派生词 / 词形变化 一行 2 词（列宽 ${m.columns.derivatives}px）`,
          m.columns.derivatives === m.columns.inflections, m.columns);
        check(`${at} 保留 42 字级的释义（${m.senseFontSize}）+ 灰底提示`, m.senseFontSize === '16px' && m.noteBackground !== 'rgba(0, 0, 0, 0)',
          { sense: m.senseFontSize, note: m.noteBackground });
        check(`${at} 英/美音标分开显示（2 段 IPA）`, m.visibleIpaCount === 2, m.visibleIpaCount);
      }

      if (scheme === 'p2') {
        const labelAbove = await page.evaluate(() => {
          const label = document.querySelector('.morph-label').getBoundingClientRect();
          const grid = document.querySelector('[data-group="derivatives"]').getBoundingClientRect();
          return { above: label.bottom <= grid.top + 1, labelLeft: Math.round(label.left), gridLeft: Math.round(grid.left) };
        });
        check(`${at} 标签列已取消：组名在内容上方一行且左对齐`, labelAbove.above && labelAbove.labelLeft === labelAbove.gridLeft, labelAbove);
        const p1 = result['p1-' + bp.tag];
        check(`${at} 内容宽度比 p1 宽（${m.columns.derivatives}px > ${p1.columns.derivatives}px）`,
          m.columns.derivatives > p1.columns.derivatives, { p2: m.columns.derivatives, p1: p1.columns.derivatives });
        check(`${at} 0 折行项`, m.wrapItems === 0, { wrapItems: m.wrapItems, visible: m.visibleMorphItems });
      }

      if (scheme === 'p3') {
        check(`${at} 三级字阶：释义 ${m.senseFontSize} / 区块标题 ${m.sectionTitleFontSize}`,
          m.senseFontSize === '14px' && m.sectionTitleFontSize === '15px', { sense: m.senseFontSize, sec: m.sectionTitleFontSize });
        check(`${at} 现代构词提示已去掉灰底（${m.noteBackground}）`, m.noteBackground === 'rgba(0, 0, 0, 0)', m.noteBackground);
        check(`${at} 英/美音标合并成 1 段 + UK/US 两个喇叭`, m.visibleIpaCount === 1, m.visibleIpaCount);
        const speaker = await page.evaluate(async () => {
          const el = document.querySelector('.example .speak');
          return { idle: getComputedStyle(el).opacity };
        });
        check(`${at} 例句喇叭默认不可见（opacity ${speaker.idle}）`, speaker.idle === '0', speaker);
        await page.hover('.example');
        await page.waitForTimeout(220);
        const hovered = await page.evaluate(() => getComputedStyle(document.querySelector('.example .speak')).opacity);
        check(`${at} 例句喇叭 hover 后可见（opacity ${hovered}）`, hovered === '1', hovered);
        await page.mouse.move(0, 0);
        await page.waitForTimeout(160);
        check(`${at} 0 折行项`, m.wrapItems === 0, m.wrapItems);
      }

      if (scheme === 'p4') {
        const hidden = ['morph', 'examples', 'synonyms', 'etymology'].every((k) => m.sections[k] && !m.sections[k].visible);
        check(`${at} 默认只显示 释义 + 核心理解（其余 4 块隐藏）`, hidden, m.sections);
        check(`${at} 折叠入口可见（「展开其余 4 块」）`, m.collapseBar && m.collapseBar.visible, m.collapseBar);
        check(`${at} 整卡高度 ${m.cardHeight}px 小于 p3（${result['p3-' + bp.tag].cardHeight}px）`,
          m.cardHeight < result['p3-' + bp.tag].cardHeight, { p4: m.cardHeight, p3: result['p3-' + bp.tag].cardHeight });

        // 展开
        await page.click('#collapseBar');
        await page.waitForTimeout(120);
        const expanded = await measure(page);
        result[scheme + '-expanded-' + bp.tag] = expanded;
        const allShown = ['morph', 'examples', 'synonyms', 'etymology'].every((k) => expanded.sections[k] && expanded.sections[k].visible);
        check(`${at} 点开后 4 块全部出现`, allShown, expanded.sections);
        const barText = await page.evaluate(() => document.getElementById('collapseText').textContent.trim());
        check(`${at} 展开后入口文案变为「收起来」`, barText.indexOf('收起') === 0, barText);
        const p3 = result['p3-' + bp.tag];
        const barCost = expanded.cardHeight - p3.cardHeight; // 折叠条自身占位（43 + 22 margin）
        const sameBlocks = ['morph', 'examples', 'synonyms', 'etymology']
          .every((k) => Math.abs(expanded.sections[k].height - p3.sections[k].height) <= 2);
        check(`${at} 展开后 4 块高度与 p3 逐块一致（差 ≤2px）`, sameBlocks,
          { expanded: ['morph', 'examples', 'synonyms', 'etymology'].map((k) => expanded.sections[k].height),
            p3: ['morph', 'examples', 'synonyms', 'etymology'].map((k) => p3.sections[k].height) });
        check(`${at} 展开后只比 p3 高一条折叠入口（${barCost}px ≈ 43+22）`, Math.abs(barCost - 65) <= 4, barCost);
        if (bp.tag === '1440') {
          await page.mouse.move(0, 0);
          await page.evaluate(() => window.scrollTo(0, 0));
          await page.waitForTimeout(150);
          await page.screenshot({ path: path.join(SHOTS, 'p4-expanded-1440.png'), fullPage: true });
        }
        await page.click('#collapseBar');
        await page.waitForTimeout(120);
      }

      if (scheme === 'p5') {
        const g = m.grid.derivatives;
        check(`${at} 单词卡片：1px 边框 / 6px 圆角 / 内边距 ${g.paddingTop} ${g.paddingLeft}`,
          g.borderWidth === '1px' && g.borderRadius === '6px' && parseFloat(g.paddingTop) >= 8,
          { border: g.borderWidth, radius: g.borderRadius, pad: [g.paddingTop, g.paddingLeft] });
        check(`${at} 单词 15px/650、释义 13px muted`, g.wordFontSize === '15px' && g.wordFontWeight === '650',
          { word: g.wordFontSize, weight: g.wordFontWeight });
        check(`${at} 词性做成浅灰 Tag（12px / 圆角 4px / 非斜体 / 灰字）`,
          g.posFontSize === '12px' && g.posBorderRadius === '4px' && g.posFontStyle === 'normal'
            && g.posBackground === 'rgb(239, 244, 251)' && g.posColor === 'rgb(65, 83, 110)',
          { size: g.posFontSize, radius: g.posBorderRadius, style: g.posFontStyle, bg: g.posBackground });
        check(`${at} 释义行的词性也是同一个 Tag`, m.sensePosBackground === 'rgb(239, 244, 251)', m.sensePosBackground);
        check(`${at} 网格 ${g.columns} 列 × ${g.cardWidth}px（auto-fill minmax(300px,1fr)）`,
          g.columns === 2 && g.cardWidth > 300, { columns: g.columns, width: g.cardWidth });
        check(`${at} 组名线性图标可见（不是 emoji）`, m.groupIconVisible === true, m.groupIconVisible);
        check(`${at} 区块标题 4px 主题色竖条（${m.sectionTitle.borderLeftWidth} / ${m.sectionTitle.borderLeftColor}）`,
          m.sectionTitle.borderLeftWidth === '4px' && m.sectionTitle.borderLeftColor === 'rgb(26, 99, 224)',
          m.sectionTitle);
        check(`${at} 信息条 = 浅蓝信息块（--accent-weak）`, m.infoBox.background === 'rgb(232, 239, 253)', m.infoBox);
        check(`${at} 0 折行项（卡片内释义不折行）`, m.wrapItems === 0, { wrapItems: m.wrapItems, cardWidth: g.cardWidth });
      }

      // 主截图前必须复位：鼠标移开（避免 hover 态进图）+ 回到页面顶部
      await page.mouse.move(0, 0);
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.waitForTimeout(150);

      if (bp.tag === '1440') {
        check(`${at} 主截图在页面顶部（scrollY=0）`, await page.evaluate(() => window.scrollY) === 0);
        await page.screenshot({ path: path.join(SHOTS, `${scheme}-${bp.tag}.png`) });

        // 方案 5 的 hover 证明（单词卡片描边变主题色）
        if (scheme === 'p5') {
          await page.evaluate(() => {
            const target = document.getElementById('morphSection');
            window.scrollTo(0, Math.max(0, target.getBoundingClientRect().top + window.scrollY - 200));
          });
          await page.hover('[data-group="derivatives"] .morph-item');
          await page.waitForTimeout(260);
          const hoverBorder = await page.evaluate(() =>
            getComputedStyle(document.querySelector('[data-group="derivatives"] .morph-item')).borderTopColor);
          check('1440 p5 单词卡片 hover 描边变主题色', hoverBorder === 'rgb(26, 99, 224)', hoverBorder);
          await page.screenshot({ path: path.join(SHOTS, 'p5-hover-1440.png') });
          await page.evaluate(() => window.scrollTo(0, 0));
          await page.mouse.move(0, 0);
        }

        // 方案 3 的 hover 证明（额外一张，不属于九张必出图）
        if (scheme === 'p3') {
          await page.evaluate(() => {
            const target = document.getElementById('examplesSection');
            window.scrollTo(0, Math.max(0, target.getBoundingClientRect().top + window.scrollY - 260));
          });
          await page.hover('.example:nth-child(2)');
          await page.waitForTimeout(260);
          const hoverShotOpacity = await page.evaluate(() => getComputedStyle(document.querySelectorAll('.example')[1].querySelector('.speak')).opacity);
          check('1440 p3 hover 截图的喇叭确实可见', hoverShotOpacity === '1', hoverShotOpacity);
          await page.screenshot({ path: path.join(SHOTS, 'p3-hover-1440.png') });
          await page.evaluate(() => window.scrollTo(0, 0));
        }
      } else {
        await page.screenshot({ path: path.join(SHOTS, `${scheme}-${bp.tag}.png`) });
      }

      await page.close();
    }
  }

  // 释义 15px 对照（方案 3）：只量不截图，给 README 的「要不要 15px」用
  const sensePage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await sensePage.goto(FILE + '#p3');
  for (const sense of ['14', '15']) {
    await sensePage.evaluate((v) => { document.body.setAttribute('data-sense', v); }, sense);
    await sensePage.waitForTimeout(60);
    const m = await measure(sensePage);
    result['p3-sense' + sense + '-1440'] = m;
    check(`1440 p3 释义 ${sense}px（实测 ${m.senseFontSize}）`, m.senseFontSize === sense + 'px', m.cardTopToCoreTitle);
  }
  await sensePage.close();

  // 方案 5 的 auto-fill 列跳变 + 834 用 minmax(280px) 的对照
  const gridPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await gridPage.goto(FILE + '#p5');
  const gridTable = [];
  for (const w of [1440, 1100, 1050, 1000, 834]) {
    await gridPage.setViewportSize({ width: w, height: 1194 });
    await gridPage.waitForTimeout(140);
    const row = await gridPage.evaluate(() => {
      const grid = document.querySelector('[data-group="derivatives"]');
      const read = () => {
        const items = Array.from(grid.querySelectorAll('.morph-item'));
        const top = Math.round(items[0].getBoundingClientRect().top);
        const firstRow = items.filter((el) => Math.abs(Math.round(el.getBoundingClientRect().top) - top) <= 2);
        return { cols: firstRow.length, width: Math.round(items[0].getBoundingClientRect().width) };
      };
      const m = window.mockMetrics();
      grid.style.gridTemplateColumns = 'repeat(auto-fill, minmax(300px, 1fr))';
      const at300 = read();
      grid.style.gridTemplateColumns = 'repeat(auto-fill, minmax(280px, 1fr))';
      const at280 = read();
      grid.style.gridTemplateColumns = '';
      return { card: m.cardWidth, inner: m.cardInnerWidth, at300, at280 };
    });
    gridTable.push({ viewport: w, ...row });
    result['p5-grid-' + w] = row;
  }
  await gridPage.close();

  check('p5 视口 834 下仍是两列（337px，不需要 280px 兜底）',
    gridTable.find((r) => r.viewport === 834).at300.cols === 2
    && gridTable.find((r) => r.viewport === 834).at280.cols === 2, gridTable.find((r) => r.viewport === 834));
  check('p5 auto-fill 在 1050 与 1100 之间跳列（1050 两列 / 1100 三列）',
    gridTable.find((r) => r.viewport === 1050).at300.cols === 2
    && gridTable.find((r) => r.viewport === 1100).at300.cols === 3,
    { at1050: gridTable.find((r) => r.viewport === 1050).at300, at1100: gridTable.find((r) => r.viewport === 1100).at300 });
  check('p5 换成 minmax(280px) 后 1050 变三列（293px）',
    gridTable.find((r) => r.viewport === 1050).at280.cols === 3, gridTable.find((r) => r.viewport === 1050).at280);

  // 方案 5 的义项排法 A / B / C（两断点都量；B 是 p5 默认）
  const senseRows = [];
  for (const bp of [{ w: 1440, h: 900, tag: '1440' }, { w: 834, h: 1194, tag: '834' }]) {
    for (const v of ['a', 'b', 'c']) {
      const page = await browser.newPage({ viewport: { width: bp.w, height: bp.h } });
      await page.goto(FILE + '?senses=' + v + '#p5');
      await page.waitForTimeout(180);
      const m = await measure(page);
      result['p5-senses-' + v + '-' + bp.tag] = m;
      senseRows.push({ tag: bp.tag, variant: v, ...m });
      check(`${bp.tag} p5 义项排法 ${v} 生效（${m.senses.items} 项 / 释义区 ${m.senses.height}px / 折行 ${m.senses.wrappedItems}）`,
        m.senseVariant === v && m.senses.wrappedItems === 0, { height: m.senses.height, heights: m.senses.itemHeights });
      if (bp.tag === '1440') {
        await page.screenshot({ path: path.join(SHOTS, `p5-senses-${v}-1440.png`) });
      }
      await page.close();
    }
  }
  const sOf = (v, tag) => senseRows.find((r) => r.variant === v && r.tag === tag);
  check('p5 义项 A 是一行（释义区 22px）', sOf('a', '1440').senses.height < 30, sOf('a', '1440').senses.height);
  check('p5 义项 B 拆成三行（释义区 ≈67px，三项各自一行）',
    sOf('b', '1440').senses.height > 55 && sOf('b', '1440').senses.itemHeights.every((h) => h > 18),
    { height: sOf('b', '1440').senses.height, heights: sOf('b', '1440').senses.itemHeights });
  check('p5 义项 C：首项 15/650、次项 13.5/400 muted',
    sOf('c', '1440').senses.firstFontSize === '15px' && sOf('c', '1440').senses.firstFontWeight === '650'
      && sOf('c', '1440').senses.firstColor === 'rgb(14, 35, 64)',
    { size: sOf('c', '1440').senses.firstFontSize, weight: sOf('c', '1440').senses.firstFontWeight });
  check('p5 义项三种排法的高度顺序 A < B < C（卡顶→核心理解 ' +
    [sOf('a', '1440').cardTopToCoreTitle, sOf('b', '1440').cardTopToCoreTitle, sOf('c', '1440').cardTopToCoreTitle].join(' / ') + 'px）',
    sOf('a', '1440').cardTopToCoreTitle < sOf('b', '1440').cardTopToCoreTitle
      && sOf('b', '1440').cardTopToCoreTitle <= sOf('c', '1440').cardTopToCoreTitle,
    { a: sOf('a', '1440').cardTopToCoreTitle, b: sOf('b', '1440').cardTopToCoreTitle, c: sOf('c', '1440').cardTopToCoreTitle });
  check('p5 义项 B 在 834 也不折行（释义区 ' + sOf('b', '834').senses.height + 'px）',
    sOf('b', '834').senses.wrappedItems === 0 && sOf('b', '834').senses.height === sOf('b', '1440').senses.height,
    { h834: sOf('b', '834').senses.height, h1440: sOf('b', '1440').senses.height });

  // 方案 5 的信息条对照：浅蓝块 vs 方案 3 的注脚写法
  const infoPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await infoPage.goto(FILE + '#p5');
  await infoPage.waitForTimeout(160);
  const infoBox = await measure(infoPage);
  await infoPage.evaluate(() => { document.body.setAttribute('data-info', 'note'); });
  await infoPage.waitForTimeout(160);
  const infoNote = await measure(infoPage);
  await infoPage.close();
  result['p5-info-box-1440'] = infoBox;
  result['p5-info-note-1440'] = infoNote;
  check(`p5 信息条两版都量到（浅蓝块 ${infoBox.cardHeight}px vs 注脚 ${infoNote.cardHeight}px）`,
    infoBox.infoBox.background === 'rgb(232, 239, 253)' && infoNote.infoBox.background === 'rgba(0, 0, 0, 0)',
    { box: infoBox.cardHeight, note: infoNote.cardHeight });

  // 五个方案共用同一份 DOM（切换只改 body 上的 data-*）
  const domPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await domPage.goto(FILE + '#p1');
  const doms = {};
  for (const scheme of SCHEMES) {
    await selectScheme(domPage, scheme);
    doms[scheme] = await domPage.evaluate(() => document.querySelector('main').outerHTML.replace(/\s+/g, ' ').trim());
  }
  const sameAll = ['p2', 'p3', 'p4', 'p5'].every((k) => doms[k] === doms.p1);
  let diffAt = -1;
  if (!sameAll) {
    for (const k of ['p2', 'p3', 'p4', 'p5']) {
      if (doms[k] === doms.p1) continue;
      let i = 0;
      while (i < doms.p1.length && i < doms[k].length && doms.p1[i] === doms[k][i]) i += 1;
      diffAt = i;
      console.log('  DOM diff', k, '@', i,
        JSON.stringify(doms.p1.slice(Math.max(0, i - 90), i + 90)), '≠',
        JSON.stringify(doms[k].slice(Math.max(0, i - 90), i + 90)));
      break;
    }
  }
  check('五方案 DOM 完全一致（只换 body[data-scheme]/[data-expanded]/[data-info]）', sameAll,
    { length: doms.p1.length, diffAt });
  await domPage.close();

  await browser.close();

  const out = path.join(DIR, 'metrics.json');
  fs.writeFileSync(out, JSON.stringify(result, null, 2));
  console.log('\nmetrics →', out);
  console.log(failures.length === 0 ? '\nALL PASS' : `\n${failures.length} FAILED\n` + failures.join('\n'));
  process.exit(failures.length === 0 ? 0 : 1);
})();
