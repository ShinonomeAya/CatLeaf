/**
 * API 共同边界可重跑检查（T03）。
 *
 * 需要先启动一个本地实例，例如：
 *   npm run build && npx wrangler pages dev dist --port 8788
 * 然后：
 *   node tests/api-boundary.mjs http://127.0.0.1:8788 <ADMIN_SECRET>
 *
 * 判断的是行为而不是源码字符串：密钥缺失/错误一律不进入业务，非法方法不能
 * 顺手返回列表，响应不含公共缓存指令，也不回传堆栈或密钥本身。
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8788';
const SECRET = process.argv[3];

if (!SECRET) {
  console.error('用法: node tests/api-boundary.mjs <BASE_URL> <ADMIN_SECRET>');
  process.exit(2);
}

let failures = 0;
const results = [];

function check(name, condition, detail = '') {
  const ok = !!condition;
  if (!ok) failures++;
  results.push({ ok, name, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
}

const req = (path, { method = 'GET', secret, headers = {}, body } = {}) =>
  fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(secret ? { 'x-admin-secret': secret } : {}),
      ...headers,
    },
    body,
  });

const text = async (r) => {
  try {
    return await r.text();
  } catch {
    return '';
  }
};

// ── 1. 缺失与错误密钥 ──────────────────────────────────────────────
for (const [label, secret] of [['', undefined], ['错误', 'definitely-wrong-secret']]) {
  for (const path of ['/api/feeds/list', '/api/feeds/list/admin', '/api/feeds/summary']) {
    const r = await req(path, { secret });
    check(`无/错误密钥被拒绝 ${label || '缺失'} ${path}`, r.status === 401, `status=${r.status}`);
  }
}

// ── 2. 正确密钥可读，且响应为私有缓存 ─────────────────────────────
const okList = await req('/api/feeds/list', { secret: SECRET });
check('正确密钥 GET /api/feeds/list 成功', okList.status === 200, `status=${okList.status}`);
check(
  '成功响应为 private, no-store',
  (okList.headers.get('cache-control') || '').includes('no-store') &&
    (okList.headers.get('cache-control') || '').includes('private'),
  `cache-control=${okList.headers.get('cache-control')}`,
);
check(
  '成功响应不含公共 CDN 缓存指令',
  !/public|s-maxage|max-age/.test(okList.headers.get('cache-control') || ''),
  `cache-control=${okList.headers.get('cache-control')}`,
);
check(
  '安全响应头存在',
  okList.headers.get('x-content-type-options') === 'nosniff',
  `x-content-type-options=${okList.headers.get('x-content-type-options')}`,
);

const adminList = await req('/api/feeds/list/admin', { secret: SECRET });
check('正确密钥 GET /api/feeds/list/admin 成功', adminList.status === 200, `status=${adminList.status}`);
const before = await text(adminList);

// ── 3. 非法方法不执行业务 ─────────────────────────────────────────
for (const method of ['PUT', 'DELETE', 'PATCH']) {
  const r = await req('/api/feeds/list', { method, secret: SECRET });
  check(`${method} 被拒绝`, r.status === 405, `status=${r.status}`);
}

const getWrite = await req('/api/feeds/add', { secret: SECRET });
const getWriteBody = await text(getWrite);
check('GET /api/feeds/add 不接受 GET', getWrite.status === 405, `status=${getWrite.status}`);
check(
  'GET /api/feeds/add 未顺带返回源列表',
  !Array.isArray(JSON.parse(getWriteBody || 'null')),
  `body=${getWriteBody.slice(0, 60)}`,
);

const badContentType = await req('/api/feeds/add', {
  method: 'POST',
  secret: SECRET,
  headers: { 'Content-Type': 'text/plain' },
  body: 'not json',
});
check('POST 非 JSON Content-Type 被拒绝', badContentType.status === 400, `status=${badContentType.status}`);

// ── 4. debug 路由停用 ─────────────────────────────────────────────
const debug = await req('/api/debug-feed?id=anything', { secret: SECRET });
check('debug 路由已停用', debug.status === 404, `status=${debug.status}`);

// ── 5. 未授权写入不得改动数据库 ───────────────────────────────────
const attack = await req('/api/feeds/delete', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: 'whatever' }),
});
check('无密钥 POST /api/feeds/delete 被拒绝', attack.status === 401, `status=${attack.status}`);

const after = await text(await req('/api/feeds/list/admin', { secret: SECRET }));
check('未授权写入后源列表未变化', before === after, `before=${before.length}B after=${after.length}B`);

// ── 6. 错误响应不泄露内部信息 ─────────────────────────────────────
// 先 await 出 Response 再读 body：直接 .map(text) 会把 Promise 交给 text()，
// r.text() 抛错被吞成空串，导致下面两条“不含堆栈/不含密钥”在空串上恒为真。
const errResponses = await Promise.all([
  req('/api/feeds/list'),
  req('/api/feeds/add', { secret: SECRET }),
  req('/api/debug-feed?id=x', { secret: SECRET }),
]);
const errBodies = await Promise.all(errResponses.map(text));
const joined = errBodies.join('\n');
check('错误响应不含堆栈', !/\bat\s+\w+.*:\d+:\d+|Error:.*\n\s+at /.test(joined), joined.slice(0, 80));
check('错误响应不含服务端密钥', !joined.includes(SECRET));
check(
  '错误响应都有非空 body（防止在空串上做安全检查）',
  errBodies.every((b) => b.trim().length > 0),
  JSON.stringify(errBodies.map((b) => b.length)),
);
check('错误响应含稳定 code', errBodies.every((b) => /"code"\s*:/.test(b)), joined.slice(0, 120));

// ── 7. CORS 不使用通配符 ──────────────────────────────────────────
const evil = await req('/api/feeds/list', {
  secret: SECRET,
  headers: { Origin: 'https://evil.example' },
});
check(
  '非白名单来源不回显 CORS',
  !evil.headers.get('access-control-allow-origin'),
  `acao=${evil.headers.get('access-control-allow-origin')}`,
);

const preflight = await req('/api/feeds/list', {
  method: 'OPTIONS',
  headers: { Origin: 'https://evil.example' },
});
check('预检不返回业务数据', (await text(preflight)).length === 0, `status=${preflight.status}`);
check(
  '预检不对非白名单来源开放',
  !preflight.headers.get('access-control-allow-origin'),
  `acao=${preflight.headers.get('access-control-allow-origin')}`,
);

console.log(`\n${failures === 0 ? '全部通过' : `${failures} 项失败`} / 共 ${results.length} 项`);
process.exit(failures === 0 ? 0 : 1);