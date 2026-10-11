/**
 * T07 历史的可重跑行为检查（对真实实例）。
 *
 *   node tests/history-behaviour.mjs <BASE_URL> <ADMIN_SECRET>
 *
 * 用临时源跑完整流程，结束时删除该源；只使用虚构数据。
 * 覆盖：实际插入计数、重放不重复计数、guid/link 交叉冲突不虚计、
 * limit 必有界、分页在大量同 pubDate 下不重复不跳行、413/429 可识别。
 */
const BASE = (process.argv[2] || '').replace(/\/$/, '');
const SECRET = process.argv[3];
if (!BASE || !SECRET) {
  console.error('用法: node tests/history-behaviour.mjs <BASE_URL> <ADMIN_SECRET>');
  process.exit(2);
}

const FEED_ID = `t07-behaviour-${Date.now()}`;
const MAX_ITEMS = 100;
const MAX_HISTORY_LIMIT = 200;
const DEFAULT_LIMIT = 100;

let failures = 0;
let total = 0;
const check = (name, ok, detail = '') => {
  total++;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

const api = async (path, { method = 'GET', body } = {}) => {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'x-admin-secret': SECRET, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 保留原文 */
  }
  return { status: res.status, json, text, headers: res.headers };
};

const upsert = (items) => api('/api/history/upsert', { method: 'POST', body: { feedId: FEED_ID, items } });
const history = (limit, offset) => {
  const qs = new URLSearchParams({ id: FEED_ID });
  if (limit !== undefined) qs.set('limit', String(limit));
  if (offset !== undefined) qs.set('offset', String(offset));
  return api(`/api/history/get?${qs}`);
};

/** 造条目：同一 pubDate（甚至为 null）最能暴露分页排序不稳定 */
const item = (n, { guid, link, pubDate = null } = {}) => ({
  guid: guid === undefined ? `g-${n}` : guid,
  link: link === undefined ? `https://example.invalid/a/${n}` : link,
  title: `标题 ${n}`,
  pubDate,
  description: `摘要 ${n}`,
});

try {
  // ── 准备：登记临时源 ──────────────────────────────────────────
  const added = await api('/api/feeds/add', {
    method: 'POST',
    body: { id: FEED_ID, url: 'https://example.invalid/feed.xml', category: 'T07', isSub: false },
  });
  check('临时源登记成功', added.status === 200, `status=${added.status}`);

  // ── 1. 首次写入的 added 是实际插入量 ───────────────────────────
  let res = await upsert([item(1), item(2), item(3)]);
  check('首次写入 added=3', res.status === 200 && res.json.added === 3, JSON.stringify(res.json));

  // ── 2. 重放同一批：不重复计数、总数不变 ────────────────────────
  const before = (await history()).json;
  res = await upsert([item(1), item(2), item(3)]);
  check('重放 added=0', res.status === 200 && res.json.added === 0, JSON.stringify(res.json));
  const after = (await history()).json;
  check('重放后总数不变', before.total === after.total, `${before.total} -> ${after.total}`);

  // ── 3. guid 不同但 link 相同：唯一约束冲突，added 必须为 0 ─────
  //    旧实现按 guid 查不到就计数，实际插入被 link 唯一索引丢弃 → 虚报。
  res = await upsert([{ ...item(4), guid: 'g-brand-new', link: 'https://example.invalid/a/1' }]);
  check('link 冲突时 added=0（不虚计）', res.status === 200 && res.json.added === 0, JSON.stringify(res.json));
  const afterConflict = (await history()).json;
  check('冲突写入不改变总数', afterConflict.total === after.total, `${after.total} -> ${afterConflict.total}`);

  // ── 4. 同批内重复条目也不重复计数 ─────────────────────────────
  res = await upsert([{ ...item(9) }, { ...item(9) }]);
  check('同批重复只计一次', res.status === 200 && res.json.added === 1, JSON.stringify(res.json));

  // ── 5. limit 有界：写入 201 条后 limit=999 应被夹到 200 ────────
  const bulk = Array.from({ length: MAX_ITEMS }, (_, i) => item(1000 + i));
  const bulk2 = Array.from({ length: MAX_ITEMS }, (_, i) => item(2000 + i));
  const bulk3 = Array.from({ length: 1 }, (_, i) => item(3000 + i));
  const r1 = await upsert(bulk);
  const r2 = await upsert(bulk2);
  const r3 = await upsert(bulk3);
  check('三批写入成功', [r1, r2, r3].every((r) => r.status === 200), `${r1.json?.added}/${r2.json?.added}/${r3.json?.added}`);

  const all = await history();
  const expectedTotal = all.json.total;
  const big = await history(999);
  check(
    `limit=999 被夹到 ${MAX_HISTORY_LIMIT}`,
    big.status === 200 && big.json.items.length === MAX_HISTORY_LIMIT,
    `items=${big.json.items.length}, total=${big.json.total}`,
  );

  const overBatch = await upsert(Array.from({ length: MAX_ITEMS + 1 }, (_, i) => item(5000 + i)));
  check('单批超过 100 条 → 413', overBatch.status === 413, `status=${overBatch.status} ${overBatch.text.slice(0, 60)}`);

  // limit 是“给了值就越界夹取、没给才用默认”。0 属于越界 → 夹到下限 1，
  // 关键是不能像旧实现那样“无 LIMIT 取全表”。
  const zeroLimit = await history(0);
  check(
    'limit=0 夹到下限 1（不再取全表）',
    zeroLimit.status === 200 && zeroLimit.json.items.length === 1,
    `items=${zeroLimit.json.items.length}, total=${zeroLimit.json.total}`,
  );

  // 缺省 limit 才是默认 100；此时总数已超过 100，必须正好返回 100
  const defaultLimit = await history();
  check(
    '缺省 limit 返回默认 100',
    defaultLimit.status === 200 && defaultLimit.json.items.length === DEFAULT_LIMIT,
    `items=${defaultLimit.json.items.length}, total=${defaultLimit.json.total}`,
  );

  const negOffset = await history(10, -5);
  check('负 offset 被夹为 0', negOffset.status === 200, `status=${negOffset.status}`);

  // ── 6. 分页稳定：大量同 pubDate（全为 null）下不重复、不跳行 ──
  const pageSize = 50;
  const seen = [];
  for (let offset = 0; offset < expectedTotal; offset += pageSize) {
    const page = await history(pageSize, offset);
    if (page.status !== 200 || page.json.items.length === 0) break;
    seen.push(...page.json.items.map((i) => i.guid));
  }
  const unique = new Set(seen);
  check('分页遍历条数与总数一致', seen.length === expectedTotal, `${seen.length} vs ${expectedTotal}`);
  check('分页无重复条目', unique.size === seen.length, `unique=${unique.size}, seen=${seen.length}`);

  // ── 7. 越界与非法源 ───────────────────────────────────────────
  const missingFeed = await api('/api/history/upsert', {
    method: 'POST',
    body: { feedId: 'does-not-exist-t07', items: [item(1)] },
  });
  check('不存在的源不能写入 → 404', missingFeed.status === 404, `status=${missingFeed.status}`);

  const badBody = await api('/api/history/upsert', { method: 'POST', body: { feedId: FEED_ID } });
  check('缺 items 数组 → 400', badBody.status === 400, `status=${badBody.status}`);

  // ── 8. 429 可识别（带稳定 code 与 Retry-After）────────────────
  let sawRateLimit = null;
  for (let i = 0; i < 40 && !sawRateLimit; i++) {
    const r = await upsert([item(9000 + i)]);
    if (r.status === 429) sawRateLimit = r;
  }
  check(
    '超过 30 次/分钟 → 429 且带 code/Retry-After',
    sawRateLimit !== null && sawRateLimit.json?.code === 'rate_limited' && !!sawRateLimit.headers.get('retry-after'),
    sawRateLimit ? `${JSON.stringify(sawRateLimit.json)} retry-after=${sawRateLimit.headers.get('retry-after')}` : '未触发',
  );
} finally {
  const del = await api('/api/feeds/delete', { method: 'POST', body: { id: FEED_ID } });
  console.log(`\n清理临时源 ${FEED_ID}: ${del.status}`);
}

console.log(`\n${failures === 0 ? '全部通过' : `${failures} 项失败`} / 共 ${total} 项`);
process.exit(failures === 0 ? 0 : 1);