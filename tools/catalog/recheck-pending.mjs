#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { isIP } from 'node:net';

const input = new URL('./dispositions.json', import.meta.url);
const output = new URL(process.argv[2] || '../../docs/validation/2026-09-27-catalog-recheck.json', import.meta.url);
const entries = JSON.parse(readFileSync(input, 'utf8')).dispositions.filter((x) => x.status === 'recheck');
const queue = [...entries];
const results = [];

function allowed(raw) {
  const u = new URL(raw);
  const host = u.hostname.toLowerCase();
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password ||
      host === 'localhost' || host.endsWith('.local') ||
      (isIP(host) === 4 && /^(10\.|127\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)/.test(host))) {
    throw new Error('blocked target');
  }
  return u;
}

async function once(raw) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10_000);
  try {
    let url = raw;
    for (let hop = 0; hop <= 3; hop++) {
      const target = allowed(url);
      const res = await fetch(target, {
        signal: ctrl.signal, redirect: 'manual',
        headers: { 'User-Agent': 'CatLeafCatalogRecheck/1.0 (personal RSS verification)' },
      });
      if (res.status >= 300 && res.status < 400) {
        const location = res.headers.get('location');
        if (!location || hop === 3) return { http: res.status, finalUrl: url, feedLike: false, reason: 'redirect limit' };
        url = new URL(location, target).href;
        continue;
      }
      if (!res.ok) return { http: res.status, finalUrl: url, feedLike: false, reason: 'HTTP error' };
      const reader = res.body.getReader();
      const chunks = [];
      let size = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 1024 * 1024) {
          await reader.cancel();
          return { http: res.status, finalUrl: url, feedLike: false, reason: 'over 1 MiB' };
        }
        chunks.push(value);
      }
      const text = new TextDecoder().decode(Buffer.concat(chunks));
      const root = /<\s*(rss|feed|rdf:RDF)(?:\s|>)/i.test(text.slice(0, 2000));
      const item = /<\s*(item|entry)(?:\s|>)/i.test(text);
      return { http: res.status, finalUrl: url, feedLike: root && item,
        reason: root && item ? 'feed-shaped response; parser confirmation still required' : 'no feed root and entry pair' };
    }
  } catch (e) {
    return { http: 0, finalUrl: raw, feedLike: false, reason: e.name === 'AbortError' ? 'timeout' : String(e.message || e) };
  } finally {
    clearTimeout(timer);
  }
}

async function worker() {
  while (queue.length) {
    const entry = queue.shift();
    let result = await once(entry.url);
    if (result.http === 0 && result.reason !== 'blocked target') result = await once(entry.url);
    results.push({ name: entry.name, originalUrl: entry.url, ...result });
    if (results.length % 10 === 0) process.stderr.write(`${results.length}/${entries.length}\n`);
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
}

await Promise.all(Array.from({ length: 4 }, worker));
results.sort((a, b) => a.originalUrl.localeCompare(b.originalUrl));
writeFileSync(output, JSON.stringify({ checkedAt: new Date().toISOString(), inputCount: entries.length, results }, null, 2));
const candidates = results.filter((x) => x.feedLike);
process.stdout.write(JSON.stringify({ checked: results.length, feedShaped: candidates.length,
  candidates: candidates.map((x) => x.originalUrl), output: output.pathname }) + '\n');
