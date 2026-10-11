/**
 * T03 共同边界的可重跑检查（模块级）。
 *
 *   node tests/api-boundary-module.mjs
 *
 * 背景：本机 workerd 无法启动（连手写最小配置的 `workerd serve` 都会
 * `std::terminate()`），因此 `wrangler pages dev` 的 HTTP 路径暂时不可用，
 * 见 docs/validation/验收记录.md。本脚本改为直接驱动真实的
 * `functions/_middleware.ts` 与 `server/handlers/feeds.ts` 模块，用真实
 * Request/Response 对象调用，并在 next() 上装探针，验证的是行为而不是源码字符串。
 *
 * 这不代替 HTTP 端到端验收：tests/api-boundary.mjs 仍然是运行时可用后的检查。
 */
import { bundleModules } from './_bundle.mjs';

// 必须单次打包：分开打包会让共享依赖出现两份，类身份不一致（instanceof 跨包失效）
const { onRequest, handleFeeds } = await bundleModules([
  { module: 'functions/_middleware.ts', names: ['onRequest'] },
  { module: 'server/handlers/feeds.ts', names: ['handleFeeds'] },
]);

const SECRET = 'catleaf-fake-test-secret-not-real';
const env = (over = {}) => ({
  ADMIN_SECRET: SECRET,
  ALLOWED_ORIGINS: 'https://app.example.test',
  ENABLE_DEBUG_ROUTE: 'false',
  ...over,
});

let failures = 0;
let total = 0;
const check = (name, ok, detail = '') => {
  total++;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

/** 以探针包装的 next() 调一次中间件；nextCalled 反映 handler 是否被进入 */
async function call(pathname, { method = 'GET', headers = {}, env: e = env(), throwOnNext } = {}) {
  const state = { nextCalled: false };
  const request = new Request(`https://reader.example.test${pathname}`, { method, headers });
  const context = {
    request,
    env: e,
    next: async () => {
      state.nextCalled = true;
      if (throwOnNext) throw new Error('secret-looking-internal-detail: SQLITE_BUSY');
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=999' },
      });
    },
  };
  const response = await onRequest(context);
  const body = await response.text();
  return { status: response.status, headers: response.headers, body, ...state };
}

// ── 1. 未配置服务端密钥 → 503，且不进入业务 ────────────────────────
for (const pathname of ['/api/feeds/list', '/api/feeds/list/admin', '/api/feeds/summary']) {
  const r = await call(pathname, { env: env({ ADMIN_SECRET: undefined }) });
  check(`未配置密钥 → 503 ${pathname}`, r.status === 503, `status=${r.status}`);
  check(`未配置密钥不进入业务 ${pathname}`, r.nextCalled === false);
  check(`503 带稳定 code ${pathname}`, /"code"\s*:\s*"not_configured"/.test(r.body), r.body.slice(0, 70));
}

// ── 2. 缺失 / 错误密钥 → 401，且不进入业务 ────────────────────────
for (const [label, secret] of [['缺失', undefined], ['错误', 'wrong-secret-value']]) {
  for (const pathname of ['/api/feeds/list', '/api/feeds/list/admin', '/api/feeds/summary']) {
    const r = await call(pathname, { headers: secret ? { 'x-admin-secret': secret } : {} });
    check(`${label}密钥 → 401 ${pathname}`, r.status === 401, `status=${r.status}`);
    check(`${label}密钥不进入业务 ${pathname}`, r.nextCalled === false);
  }
}

// ── 3. 正确密钥 → 进入业务，且响应强制私有缓存 ────────────────────
const ok = await call('/api/feeds/list', { headers: { 'x-admin-secret': SECRET } });
check('正确密钥进入业务', ok.status === 200 && ok.nextCalled === true, `status=${ok.status}`);
check(
  'handler 的 public 缓存被覆盖为 private, no-store',
  ok.headers.get('cache-control') === 'private, no-store',
  `cache-control=${ok.headers.get('cache-control')}`,
);
check('安全响应头存在', ok.headers.get('x-content-type-options') === 'nosniff');

// ── 4. 非法方法 → 405，且不进入业务 ──────────────────────────────
for (const method of ['PUT', 'DELETE', 'PATCH']) {
  const r = await call('/api/feeds/list', { method, headers: { 'x-admin-secret': SECRET } });
  check(`${method} → 405`, r.status === 405, `status=${r.status}`);
  check(`${method} 不进入业务`, r.nextCalled === false);
}

// ── 5. 预检只答协议，不返回业务数据 ──────────────────────────────
const opt = await call('/api/feeds/list', { method: 'OPTIONS' });
check('OPTIONS → 204', opt.status === 204, `status=${opt.status}`);
check('OPTIONS 不进入业务', opt.nextCalled === false);
check('OPTIONS 无业务数据', opt.body.length === 0, `len=${opt.body.length}`);

const optNoAuth = await call('/api/feeds/add', { method: 'OPTIONS', headers: { Origin: 'https://evil.example' } });
check('非白名单来源预检不回显 CORS', !optNoAuth.headers.get('access-control-allow-origin'), `acao=${optNoAuth.headers.get('access-control-allow-origin')}`);
check('预检不代表放行后续请求', (await call('/api/feeds/add', { method: 'POST', headers: { Origin: 'https://evil.example', 'Content-Type': 'application/json' } })).status === 401);

// ── 6. CORS 只按白名单回显，不用通配符 ────────────────────────────
const evil = await call('/api/feeds/list', { headers: { 'x-admin-secret': SECRET, Origin: 'https://evil.example' } });
check('非白名单来源不回显 CORS', !evil.headers.get('access-control-allow-origin'), `acao=${evil.headers.get('access-control-allow-origin')}`);
const allowed = await call('/api/feeds/list', { headers: { 'x-admin-secret': SECRET, Origin: 'https://app.example.test' } });
check('白名单来源被回显', allowed.headers.get('access-control-allow-origin') === 'https://app.example.test', `acao=${allowed.headers.get('access-control-allow-origin')}`);
check('无 Origin（原生请求）不产生 CORS 头', !ok.headers.get('access-control-allow-origin'));

// ── 7. debug 路由停用 ───────────────────────────────────────────
const dbg = await call('/api/debug-feed?id=x', { headers: { 'x-admin-secret': SECRET } });
check('debug 路由 → 404', dbg.status === 404, `status=${dbg.status}`);
check('debug 路由不进入业务', dbg.nextCalled === false);

// ── 8. 带 body 的 POST 要求 JSON 声明 ───────────────────────────
const badCt = await call('/api/feeds/add', {
  method: 'POST',
  headers: { 'x-admin-secret': SECRET, 'Content-Type': 'text/plain', 'Content-Length': '8' },
});
check('POST 非 JSON → 400', badCt.status === 400, `status=${badCt.status}`);
check('非 JSON 不进入业务', badCt.nextCalled === false);

// ── 9. 错误响应不泄露内部信息 ────────────────────────────────────
const boom = await call('/api/feeds/list', { headers: { 'x-admin-secret': SECRET }, throwOnNext: true });
check('handler 抛错 → 500', boom.status === 500, `status=${boom.status}`);
check('500 不回传内部 message', !boom.body.includes('SQLITE_BUSY'), boom.body.slice(0, 90));
check('500 不含堆栈', !/\bat\s+\S+:\d+:\d+/.test(boom.body));
check('500 不含服务端密钥', !boom.body.includes(SECRET));

// ── 10. GET 写动作不得返回源列表（handler 级守卫）───────────────
// 用会在被读取时抛错的假 repo：只要守卫生效，它就不该被碰到。
for (const action of ['add', 'update', 'delete', 'reorder']) {
  let repoRead = false;
  const repo = {
    listFeeds: async () => {
      repoRead = true;
      throw new Error('repo must not be read for GET on a write action');
    },
    getFeedSummaries: async () => {
      repoRead = true;
      throw new Error('repo must not be read for GET on a write action');
    },
    getFeedById: async () => {
      repoRead = true;
      return null;
    },
  };
  const request = new Request(`https://reader.example.test/api/feeds/${action}`, { method: 'GET' });
  const response = await handleFeeds(request, repo, action, SECRET);
  const body = await response.text();
  check(`GET /api/feeds/${action} → 405`, response.status === 405, `status=${response.status}`);
  check(`GET /api/feeds/${action} 未读取源数据`, repoRead === false);
  check(`GET /api/feeds/${action} 未返回列表`, !Array.isArray(JSON.parse(body || 'null')));
}

console.log(`\n${failures === 0 ? '全部通过' : `${failures} 项失败`} / 共 ${total} 项`);
process.exit(failures === 0 ? 0 : 1);