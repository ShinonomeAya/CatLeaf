// Replay with Playwright MCP browser_run_code_unsafe(filename=absolute path).
async (page) => {
  const out = 'F:/sokusai/CatWeb/docs/validation/2026-10-09-toolbar-filter-review';
  const assert = (ok, detail) => { if (!ok) throw Error(detail); };
  await page.goto('http://127.0.0.1:8793/prototypes/2026-10-09-toolbar-filter/index.html');
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true');
  await page.setViewportSize({ width: 1440, height: 1100 });
  for (const id of ['A', 'B', 'C']) {
    const p = page.locator(`#${id} .phone`);
    await p.locator('[data-clear]').click();
    assert(await p.locator('.article').count() === 4, id + ': clear');
    await (id === 'B' ? p.locator('[data-read=unread]') : p.locator('[data-unread]')).click();
    assert(await p.locator('.article').count() === 3, id + ': unread');
    if (id === 'C') {
      await p.locator('[data-pickdate]').click();
      await p.locator('[data-sheet-date=today]').click();
    } else await p.locator('[data-date=today]').click();
    assert(await p.locator('.article').count() === 2, id + ': date AND unread');
    if (id === 'C') {
      await p.locator('[data-pickdate]').click();
      await p.locator('[data-sheet-date=on]').click();
    } else await p.locator('[data-date=on]').click();
    await p.locator('input[type=date]').fill('2026-10-08');
    await p.locator('[data-confirm-date]').click();
    assert(await p.locator('.article').count() === 0, id + ': chosen date AND unread');
    await (id === 'B' ? p.locator('[data-read=all]') : p.locator('[data-unread]')).click();
    assert(await p.locator('.article').count() === 1, id + ': chosen date retained');
    await p.locator('[data-source]').click();
    await p.locator('[data-feed="一个名称很长的示例订阅源"]').click();
    assert((await p.locator('.source-label').textContent()).includes('很长'), id + ': long source');
    await p.locator('[data-action=search]').click();
    await page.keyboard.press('Escape');
    assert(await p.locator('.overlay').isHidden(), id + ': escape');
    assert(await p.locator('[data-action=search]').evaluate(e => e === document.activeElement), id + ': focus return');
    await p.locator('[data-clear]').click();
    await (id === 'B' ? p.locator('[data-read=unread]') : p.locator('[data-unread]')).click();
  }
  const matrix = [];
  for (const width of ['360', '411']) for (const font of ['1', '1.3']) for (const theme of ['light', 'dark']) {
    await page.locator('#width').selectOption(width);
    await page.locator('#font').selectOption(font);
    await page.locator('#theme').selectOption(theme);
    const measurements = await page.locator('.phone').evaluateAll(phones => phones.map(p => {
      const visible = [...p.querySelectorAll('button')].filter(b => b.getClientRects().length && !b.closest('.overlay'));
      const small = visible.filter(b => b.getBoundingClientRect().width < 47.9 || b.getBoundingClientRect().height < 47.9);
      return { id: p.closest('.variant').id, width: p.getBoundingClientRect().width, small: small.map(b => b.textContent.trim()), overflow: p.scrollWidth > p.clientWidth + 1, filterHeight: p.querySelector('.filters').getBoundingClientRect().height };
    }));
    for (const m of measurements) {
      assert(!m.overflow && !m.small.length && Math.abs(m.width - Number(width)) < 1, JSON.stringify(m));
      await page.locator(`#${m.id} .phone`).screenshot({ path: `${out}/${m.id}-${width}-f${font}-${theme}.png` });
    }
    matrix.push({ width, font, theme, measurements });
  }
  await page.locator('#offline').check();
  assert(await page.locator('.notice:visible').count() === 3, 'offline');
  await page.locator('#gallery').screenshot({ path: `${out}/offline-411-f1.3-dark.png` });
  await page.locator('#offline').uncheck();
  await page.locator('#width').selectOption('360');
  await page.locator('#font').selectOption('1');
  await page.locator('#theme').selectOption('light');
  for (const id of ['A', 'B', 'C']) {
    const p = page.locator(`#${id} .phone`);
    await p.locator('[data-source]').click();
    await p.locator('[data-feed="少数派"]').click();
  }
  await page.locator('#gallery').screenshot({ path: 'F:/sokusai/CatWeb/docs/prototypes/2026-10-09-toolbar-filter/compare-light.png' });
  await page.locator('#theme').selectOption('dark');
  await page.locator('#font').selectOption('1.3');
  await page.locator('#gallery').screenshot({ path: 'F:/sokusai/CatWeb/docs/prototypes/2026-10-09-toolbar-filter/compare-dark-large.png' });
  await page.locator('#theme').selectOption('light');
  await page.locator('#font').selectOption('1');
  return { interactions: 'passed', combinations: matrix.length, screenshots: 24, matrix };
}
