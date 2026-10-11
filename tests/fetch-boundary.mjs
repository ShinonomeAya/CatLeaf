/**
 * T06 抓取边界的可重跑检查。
 *
 *   node tests/fetch-boundary.mjs
 *
 * 两部分：
 * 1) 目标校验器对各类地址变体的判定（纯逻辑）。
 * 2) secureFetch 的逐跳行为：用注入的 fetch 模拟 302 链，验证
 *    “每一跳都校验”“最多 3 跳”“跳转到内网被拒绝”——这些是自动跟随跳转时拦不住的点。
 */
import { bundleModules } from './_bundle.mjs';

const { requireFetchTarget, isForbiddenHost, secureFetch, FetchBlockedError } = await bundleModules([
  { module: 'server/security.ts', names: ['requireFetchTarget', 'isForbiddenHost'] },
  { module: 'server/http.ts', names: ['secureFetch', 'FetchBlockedError'] },
]);

let failures = 0;
let total = 0;
const check = (name, ok, detail = '') => {
  total++;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

// ── 1. 目标校验：应拒绝 ────────────────────────────────────────────
const mustReject = [
  ['非 HTTPS', 'http://blog.example.org/feed.xml'],
  ['带凭据', 'https://user:pw@blog.example.org/feed.xml'],
  ['IPv4 回环', 'https://127.0.0.1/feed'],
  ['IPv4 私网 10', 'https://10.1.2.3/feed'],
  ['IPv4 私网 192.168', 'https://192.168.1.10/feed'],
  ['IPv4 私网 172.16', 'https://172.16.5.4/feed'],
  ['链路本地', 'https://169.254.169.254/latest/meta-data/'],
  ['IPv4 映射', 'https://[::ffff:127.0.0.1]/feed'],
  ['IPv6 回环', 'https://[::1]/feed'],
  ['IPv6 唯一本地', 'https://[fd00::1]/feed'],
  ['IPv6 链路本地', 'https://[fe80::1]/feed'],
  ['localhost', 'https://localhost/feed'],
  ['子域 localhost', 'https://evil.localhost/feed'],
  ['.local', 'https://printer.local/feed'],
  ['.internal', 'https://metadata.internal/feed'],
  ['8 进制回环', 'https://0177.0.0.1/feed'],
  ['十进制回环', 'https://2130706433/feed'],
  ['空值', ''],
];
for (const [label, url] of mustReject) {
  let rejected = false;
  let reason = '';
  try {
    requireFetchTarget(url);
  } catch (e) {
    rejected = e instanceof FetchBlockedError;
    reason = e.message;
  }
  check(`拒绝 ${label}`, rejected, reason);
}

// ── 2. 目标校验：应放行 ────────────────────────────────────────────
const mustPass = [
  'https://blog.example.org/feed.xml',
  'https://example.com:8443/feed',
  'https://sub.domain.example.co.jp/rss',
];
for (const url of mustPass) {
  let passed = false;
  try {
    requireFetchTarget(url);
    passed = true;
  } catch {
    passed = false;
  }
  check(`放行 ${url}`, passed);
}

check('相似但非内网的域名不被误判', isForbiddenHost('127.0.0.1.evil.example') === false);
check('数字开头但非 IP 的主机不被误判', isForbiddenHost('123.example.com') === false);

// ── 3. secureFetch 逐跳校验（注入 fetch，模拟 302 链）───────────────
const realFetch = globalThis.fetch;
/** 用跳转表模拟服务器：[起始路径] → { status, location } */
function stubFetch(routes) {
  const seen = [];
  globalThis.fetch = async (url) => {
    seen.push(url.toString());
    const route = routes.find((r) => r.match === url.toString());
    if (!route) return new Response('body', { status: 200, headers: { 'content-length': '4' } });
    const headers = new Headers();
    if (route.location) headers.set('location', route.location);
    return new Response(null, { status: route.status, headers });
  };
  return seen;
}

try {
  // 3a. 同源 1 跳：应跟随并成功
  let seen = stubFetch([
    { match: 'https://a.example.com/1', status: 302, location: 'https://a.example.com/2' },
  ]);
  let res = await secureFetch('https://a.example.com/1');
  check('同源单跳可跟随', res.status === 200 && seen.length === 2, `requests=${seen.length}`);

  // 3b. 跳转到内网：必须拒绝，且不得真的发出第二次请求
  seen = stubFetch([
    { match: 'https://a.example.com/1', status: 302, location: 'https://169.254.169.254/latest/meta-data/' },
  ]);
  let blocked = false;
  try {
    await secureFetch('https://a.example.com/1');
  } catch (e) {
    blocked = e instanceof FetchBlockedError;
  }
  check('跳转到链路本地地址被拒绝', blocked, `requests=${seen.length}`);
  check('被拒绝的跳转不发出第二次请求', seen.length === 1, `requests=${seen.length}`);

  // 3c. 跳转降级到 http：必须拒绝
  seen = stubFetch([
    { match: 'https://a.example.com/1', status: 302, location: 'http://a.example.com/2' },
  ]);
  blocked = false;
  try {
    await secureFetch('https://a.example.com/1');
  } catch (e) {
    blocked = e instanceof FetchBlockedError;
  }
  check('跳转降级为 http 被拒绝', blocked);

  // 3d. 超过 3 跳：必须拒绝
  seen = stubFetch([
    { match: 'https://a.example.com/1', status: 302, location: 'https://a.example.com/2' },
    { match: 'https://a.example.com/2', status: 302, location: 'https://a.example.com/3' },
    { match: 'https://a.example.com/3', status: 302, location: 'https://a.example.com/4' },
    { match: 'https://a.example.com/4', status: 302, location: 'https://a.example.com/5' },
  ]);
  blocked = false;
  try {
    await secureFetch('https://a.example.com/1');
  } catch (e) {
    blocked = e instanceof FetchBlockedError && /跳转次数超过上限/.test(e.message);
  }
  check('超过 3 跳被拒绝', blocked, `requests=${seen.length}`);
  check('恰好 4 次请求后停止（1 + 3 跳）', seen.length === 4, `requests=${seen.length}`);

  // 3e. 恰好 3 跳应成功
  seen = stubFetch([
    { match: 'https://a.example.com/1', status: 302, location: 'https://a.example.com/2' },
    { match: 'https://a.example.com/2', status: 302, location: 'https://a.example.com/3' },
    { match: 'https://a.example.com/3', status: 302, location: 'https://a.example.com/4' },
  ]);
  res = await secureFetch('https://a.example.com/1');
  check('恰好 3 跳可完成', res.status === 200 && seen.length === 4, `requests=${seen.length}`);

  // 3f. content-length 超上限：拒绝
  globalThis.fetch = async () =>
    new Response('x', { status: 200, headers: { 'content-length': String(6 * 1024 * 1024) } });
  blocked = false;
  try {
    await secureFetch('https://a.example.com/big', { maxBytes: 5 * 1024 * 1024 });
  } catch (e) {
    blocked = e instanceof FetchBlockedError;
  }
  check('声明超过 5 MiB 的响应被拒绝', blocked);

  // 3g. 相对 Location 基于当前地址解析，且跳转目标照样校验
  seen = stubFetch([
    { match: 'https://a.example.com/dir/1', status: 302, location: '/dir/2' },
  ]);
  res = await secureFetch('https://a.example.com/dir/1');
  check('相对 Location 被正确解析', seen[1] === 'https://a.example.com/dir/2', seen[1]);

  // 3h. 起始目标本身非法：不发出任何请求
  seen = stubFetch([]);
  blocked = false;
  try {
    await secureFetch('http://a.example.com/1');
  } catch (e) {
    blocked = e instanceof FetchBlockedError;
  }
  check('起始目标非 HTTPS 时零请求', blocked && seen.length === 0, `requests=${seen.length}`);
} finally {
  globalThis.fetch = realFetch;
}

console.log(`
${failures === 0 ? '全部通过' : `${failures} 项失败`} / 共 ${total} 项`);
process.exit(failures === 0 ? 0 : 1);
