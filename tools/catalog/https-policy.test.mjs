import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, mkdirSync, copyFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fetchOnce } from './verify-catalog.mjs';

let calls = [];
const response = (body, status = 200, headers = {}) => new Response(body, { status, headers });
const fake = async (url, options) => {
  calls.push(String(url));
  assert.equal(options.redirect, 'manual');
  assert.equal(Object.keys(options.headers).length, 1);
  return response('<rss><channel><item><title>Test</title></item></channel></rss>');
};
for (const url of ['http://example.com/rss', 'https://user:password@example.com/rss', 'https://127.0.0.1/rss', 'https://10.0.0.1/rss', 'https://[::1]/rss']) {
  assert.equal((await fetchOnce(url, fake)).ok, false);
}
assert.equal(calls.length, 0);
const downgraded = await fetchOnce('https://example.com/rss', async () => {
  calls.push('redirect'); return response(null, 302, { Location: 'http://example.com/rss' });
});
assert.equal(downgraded.ok, false);
assert.equal(calls.length, 1);
calls = [];
const redirected = await fetchOnce('https://example.com/rss', async (url, options) => {
  if (!calls.length) { calls.push(String(url)); return response(null, 301, { Location: '/feed' }); }
  return fake(url, options);
});
assert.equal(redirected.ok, true);
assert.equal(redirected.finalUrl, 'https://example.com/feed');
assert.equal((await fetchOnce('https://example.com/rss', async () => response('x'.repeat(1024 * 1024 + 1)))).error, 'over 1 MiB');

const catalog = JSON.parse(readFileSync(new URL('../../android-native/app/src/main/assets/catalog/catalog.json', import.meta.url), 'utf8'));
assert(catalog.entries.every(e => new URL(e.feedUrl).protocol === 'https:' && !new URL(e.feedUrl).username && !new URL(e.feedUrl).password));
assert.equal(new Set(catalog.entries.map(e => e.id)).size, catalog.entries.length);
assert.equal(catalog.entries.find(e => e.id === catalog.preferredIds.at(-1)).feedUrl, 'https://daily.juya.uk/rss.xml');
const isolated = mkdtempSync(join(tmpdir(), 'catleaf-catalog-https-'));
try {
  const folder = join(isolated, 'tools/catalog');
  mkdirSync(folder, { recursive: true });
  copyFileSync(new URL('./build-outputs.mjs', import.meta.url), join(folder, 'build-outputs.mjs'));
  for (const url of ['http://example.com/feed', 'https://user:password@example.com/feed']) {
    writeFileSync(join(folder, 'dispositions.json'), JSON.stringify({ checkedAt: '2026-10-09', dispositions: [{ name: 'Invalid', url, checkedAt: '2026-10-09', status: 'ok' }] }));
    assert.throws(() => execFileSync(process.execPath, [join(folder, 'build-outputs.mjs')], { stdio: 'pipe' }), e => String(e.stderr).includes('目录不能预填非HTTPS或带凭据地址'));
    assert.equal(existsSync(join(isolated, 'android-native/app/src/main/assets/catalog/catalog.json')), false);
  }
} finally {
  assert.equal(dirname(resolve(isolated)), resolve(tmpdir()));
  assert(basename(isolated).startsWith('catleaf-catalog-https-'));
  rmSync(isolated, { recursive: true });
}
console.log('HTTPS/credentials/private targets, redirect downgrade, bounded stream and catalog invariants passed');
