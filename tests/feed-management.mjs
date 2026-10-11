/**
 * T08 订阅增删改行为的可重跑检查（模块级，用内存假仓库）。
 *
 *   node tests/feed-management.mjs
 *
 * 覆盖验收里的负例：重复 id/URL 不覆盖原源、编辑不能换 URL、
 * 非 HTTPS 与非法字段被拒、删除语义、200 源上限。
 * 生产侧另由 tests/api-boundary.mjs 与手工核对覆盖。
 */
import { bundleModules } from './_bundle.mjs';

const { handleFeeds } = await bundleModules([
  { module: 'server/handlers/feeds.ts', names: ['handleFeeds'] },
  { module: 'server/security.ts', names: ['normalizeFeedUrl', 'isSameFeedUrl'] },
]);

const SECRET = 'catleaf-fake-test-secret-not-real';

let failures = 0;
let total = 0;
const check = (name, ok, detail = '') => {
  total++;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

/** 内存假仓库：只实现本任务用到的部分，字段与真实 FeedRow 对齐 */
function makeRepo(seed = []) {
  const feeds = seed.map((f, i) => ({
    id: f.id,
    url: f.url,
    category: f.category ?? '',
    isSub: false,
    customTitle: f.customTitle ?? '',
    displayOrder: f.displayOrder ?? i,
    allowedMediaHosts: null,
  }));
  return {
    feeds,
    async listFeeds() {
      return [...feeds].sort((a, b) => a.displayOrder - b.displayOrder || a.id.localeCompare(b.id));
    },
    async insertFeed(data) {
      const nextOrder = feeds.reduce((m, f) => Math.max(m, f.displayOrder ?? 0), -1) + 1;
      feeds.push({ ...data, displayOrder: nextOrder });
    },
    async updateFeedMeta(id, data) {
      const f = feeds.find((x) => x.id === id);
      if (!f) return 0;
      f.category = data.category;
      f.customTitle = data.customTitle;
      return 1;
    },
    async deleteFeed(id) {
      const i = feeds.findIndex((x) => x.id === id);
      if (i < 0) return 0;
      feeds.splice(i, 1);
      return 1;
    },
    async reorderFeeds() {},
    async getFeedSummaries() {
      return [];
    },
  };
}

const post = (repo, action, body) =>
  handleFeeds(
    new Request(`https://reader.example.test/api/feeds/${action}`, {
      method: 'POST',
      headers: { 'x-admin-secret': SECRET, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    repo,
    action,
    SECRET,
  );

const readJson = async (res) => {
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
};

// ── 1. 新增：有效 HTTPS 源 ────────────────────────────────────────
let repo = makeRepo();
let res = await post(repo, 'add', { id: 'f1', url: 'https://blog.example.org/feed.xml', category: '技术' });
check('新增有效源 → 200', res.status === 200, `status=${res.status}`);
check('新增后源数 +1', repo.feeds.length === 1);
check('新源追加到末尾（displayOrder 递增）', repo.feeds[0].displayOrder === 0);

res = await post(repo, 'add', { id: 'f2', url: 'https://other.example.org/rss' });
check('第二个源 displayOrder=1', res.status === 200 && repo.feeds[1].displayOrder === 1, `order=${repo.feeds[1]?.displayOrder}`);

// ── 2. 重复 id 不覆盖 ────────────────────────────────────────────
res = await post(repo, 'add', { id: 'f1', url: 'https://evil.example.net/other.xml' });
let body = await readJson(res);
check('同 id 重复新增 → 409', res.status === 409, `status=${res.status} ${body.code}`);
check('冲突 code=id_exists', body.code === 'id_exists', body.code);
check('原源 URL 未被覆盖', repo.feeds.find((f) => f.id === 'f1').url === 'https://blog.example.org/feed.xml');
check('源数未变', repo.feeds.length === 2);

// ── 3. 重复规范化 URL 不覆盖 ─────────────────────────────────────
res = await post(repo, 'add', { id: 'f3', url: 'HTTPS://Blog.Example.ORG:443/feed.xml' });
body = await readJson(res);
check('同规范化 URL 不同 id → 409', res.status === 409, `status=${res.status} ${body.code}`);
check('冲突 code=url_exists', body.code === 'url_exists', body.code);
check('源数未变', repo.feeds.length === 2);

// ── 4. 保守规范化：不把不同地址合并 ──────────────────────────────
res = await post(repo, 'add', { id: 'f4', url: 'https://blog.example.org/feed.xml/' });
check('尾斜杠不同视为不同源（允许新增）', res.status === 200, `status=${res.status}`);
res = await post(repo, 'add', { id: 'f5', url: 'https://blog.example.org/feed.xml?token=abc' });
check('带 query 视为不同源（不删 query）', res.status === 200, `status=${res.status}`);

// ── 5. 非 HTTPS 与非法字段 ───────────────────────────────────────
res = await post(repo, 'add', { id: 'f6', url: 'http://blog.example.org/feed.xml' });
body = await readJson(res);
check('http 源被拒 → 400 invalid_url', res.status === 400 && body.code === 'invalid_url', `${res.status} ${body.code}`);

for (const [label, payload, expectCode] of [
  ['含斜杠的 id', { id: 'bad/id', url: 'https://a.example.org/feed' }, 'invalid_id'],
  ['含空格的 id', { id: 'bad id', url: 'https://a.example.org/feed' }, 'invalid_id'],
  ['超长名称', { id: 'ok1', url: 'https://a.example.org/feed', customTitle: 'x'.repeat(121) }, 'invalid_name'],
  ['超长分组', { id: 'ok2', url: 'https://a.example.org/feed', category: 'y'.repeat(81) }, 'invalid_category'],
  ['超长 URL', { id: 'ok3', url: `https://a.example.org/${'z'.repeat(4200)}` }, 'invalid_url'],
]) {
  res = await post(repo, 'add', payload);
  body = await readJson(res);
  check(`${label} → 400 ${expectCode}`, res.status === 400 && body.code === expectCode, `${res.status} ${body.code}`);
}

// ── 6. 编辑：只改名称与分组，不能换 URL ──────────────────────────
res = await post(repo, 'update', { id: 'f1', url: 'https://blog.example.org/feed.xml', category: '阅读', customTitle: '我的源' });
check('编辑名称/分组 → 200', res.status === 200, `status=${res.status}`);
let updated = repo.feeds.find((f) => f.id === 'f1');
check('名称已更新', updated.customTitle === '我的源');
check('分组已更新', updated.category === '阅读');
check('URL 未变', updated.url === 'https://blog.example.org/feed.xml');

res = await post(repo, 'update', { id: 'f1', url: 'https://blog.example.org/new-feed.xml' });
body = await readJson(res);
check('编辑换 URL → 409', res.status === 409, `status=${res.status} ${body.code}`);
check('冲突 code=url_change_requires_new_feed', body.code === 'url_change_requires_new_feed', body.code);
check('URL 仍未被改动', repo.feeds.find((f) => f.id === 'f1').url === 'https://blog.example.org/feed.xml');

// URL 写法不同但同一目标时不应误判为换地址
res = await post(repo, 'update', { id: 'f1', url: 'HTTPS://Blog.Example.ORG:443/feed.xml', customTitle: '改名' });
check('等价的 URL 写法不算换地址', res.status === 200, `status=${res.status}`);

res = await post(repo, 'update', { id: 'nope', url: 'https://x.example.org/feed' });
body = await readJson(res);
check('编辑不存在的源 → 404', res.status === 404 && body.code === 'not_found', `${res.status} ${body.code}`);

// ── 7. 删除 ─────────────────────────────────────────────────────
res = await post(repo, 'delete', { id: 'nope' });
body = await readJson(res);
check('删除不存在的源 → 404', res.status === 404 && body.code === 'not_found', `${res.status} ${body.code}`);

const beforeDelete = repo.feeds.length;
res = await post(repo, 'delete', { id: 'f2' });
check('删除存在的源 → 200', res.status === 200, `status=${res.status}`);
check('删除后源数 -1', repo.feeds.length === beforeDelete - 1);
check('被删源确实不在列表里', !repo.feeds.some((f) => f.id === 'f2'));

// ── 8. 200 源上限 ───────────────────────────────────────────────
repo = makeRepo();
for (let i = 0; i < 200; i++) {
  await post(repo, 'add', { id: `bulk${i}`, url: `https://feed${i}.example.org/rss` });
}
check('可达 200 个源', repo.feeds.length === 200, `feeds=${repo.feeds.length}`);
res = await post(repo, 'add', { id: 'over', url: 'https://over.example.org/rss' });
body = await readJson(res);
check('第 201 个源 → 409 feed_limit_reached', res.status === 409 && body.code === 'feed_limit_reached', `${res.status} ${body.code}`);
check('超限后未插入', repo.feeds.length === 200);

// ── 9. 缺字段 ───────────────────────────────────────────────────
res = await post(makeRepo(), 'add', { id: 'x' });
check('新增缺 URL → 400', res.status === 400);
res = await post(makeRepo(), 'update', { url: 'https://a.example.org/f' });
check('编辑缺 id → 400', res.status === 400);

console.log(`\n${failures === 0 ? '全部通过' : `${failures} 项失败`} / 共 ${total} 项`);
process.exit(failures === 0 ? 0 : 1);