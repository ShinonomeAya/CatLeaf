#!/usr/bin/env node
/**
 * L03（C03）：全量候选核验与版本化目录。
 *
 * 输入：tools/catalog/snapshot-top-rss-list-3892c301.md（已入库的 340 条快照，可追溯）
 * 输出：
 *   - docs/catalog/核验台账.md（逐条处置，含失败原因，可追溯）
 *   - android-native/app/src/main/assets/catalog/catalog.json（应用静态清单：仅 可收录+需配置）
 *
 * 核验纪律（契约 8.6 / 计划 L03）：
 *   - 每源受控限流（并发 4、间隔 300ms、超时 10s、1 MiB 上限、仅 1 次重试），在工作机执行；
 *   - 失败不无限重试、不使用公共代理；HTTP 候选实测 HTTPS 替代，不做字符串替换；
 *   - RSSHub/anyfeeder 等第三方生成源单独标识（sourceType），处置为「需配置」；
 *   - 同名不同频道不误并：去重只按规范化 URL；同名同域名不同路径记别名/频道注记；
 *   - 候选数 = 所有处置数之和，不允许遗漏。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { applyOverrides } from './overrides.mjs';
import { classifyGeneratedFeedCategory, classifyNameCategory } from './category-classifier.mjs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const SNAPSHOT = join(root, 'tools/catalog/snapshot-top-rss-list-3892c301.md');

// ── 解析快照 ────────────────────────────────────────────────────
function parseSnapshot() {
  const text = readFileSync(SNAPSHOT, 'utf-8');
  const rows = [];
  for (const line of text.split('\n')) {
    // 行格式：| 名称 | [url](url) | [查看](...) |
    const m = line.match(/^\|\s*([^|]*?)\s*\|\s*\[\s*(https?:\/\/[^\]\s]+?)\s*\]\s*\(/);
    if (!m) continue;
    const name = m[1].trim();
    const url = m[2].trim();
    if (!url.startsWith('http')) continue;
    rows.push({ name, url });
  }
  return rows;
}

// ── 分类（12 固定 ID，映射 PRD 中文名；关键词规则 + 逐条 overrides）──
export const CATEGORIES = [
  ['tech', '科技数码'], ['dev', '编程技术'], ['business', '商业财经'],
  ['news', '新闻时事'], ['productivity', '效率工具'], ['design', '设计创意'],
  ['culture', '文化阅读'], ['life', '生活方式'], ['science', '科学教育'],
  ['gaming', '游戏娱乐'], ['blog', '个人博客'], ['other', '其他'],
];

// 规则按顺序命中；域名精确/后缀优先于名称关键词
const DOMAIN_RULES = [
  [/^(github\.io$|\.github\.io$|blog\.|www\.blog)/, 'blog'],
  [/meituan|juejin|cnblogs|oschina|segmentfault|v2ex|coolshell|codingnow|ruanyifeng|programthink|vmlog|taoshu|liqi|deeplearn|python|rust|golang|java/, 'dev'],
  [/sspai|appinn|少数派|小众/, 'productivity'],
  [/ifanr|ithome|36kr|geekpark|leiphone|qbitai|jiemian|tech|digitaling|tmtpost|pingwest|zaike/, 'tech'],
  [/solidot|hackernews|hn/, 'tech'],
  [/caixin|finance|money|wallstreetcn|jiemian|yicai|eeo|21jingji|ftchinese/, 'business'],
  [/news|xinwen|thepaper|infzm|zaobao|bbc|reuters|nytimes|dw|voachinese|rfi|rfa/, 'news'],
  [/gcores|gamersky|vgtime|game|indienova|chuapp/, 'gaming'],
  [/douban|zhihu|dushu|book|read|poetry|wenxue/, 'culture'],
  [/dribbble|uisdc|design|shejipc|logonews/, 'design'],
  [/songshuhui|guokr|sciencenet|nature|science/, 'science'],
  [/douban|xiachufang|meishij|food|travel|qyer|mafengwo/, 'life'],
];

// 逐条 overrides：域名优先（含同名不同频道情形的人工核对结果）
const OVERRIDES = {
  'www.zhihu.com': 'culture',        // 知乎每日精选：综合问答精选
  'rsshub.app': null,                // RSSHub 路径各异，按 path 细分
  'plink.anyfeeder.com': null,       // anyfeeder 按 path 细分
  'www.ruanyifeng.com': 'dev',
  'feeds.feedburner.com': null,
  'feeds2.feedburner.com': null,
  'blog.codingnow.com': 'dev',
  'coolshell.cn': 'dev',
  'www.sspai.com': 'productivity',
  'www.ifanr.com': 'tech',
  'tech.meituan.com': 'dev',
  'www.solidot.org': 'tech',
  'www.gcores.com': 'gaming',
  'www.geekpark.net': 'tech',
  'www.bmpi.dev': 'blog',
  'feed.appinn.com': 'productivity',
};

function hostOf(url) { try { return new URL(url).host.toLowerCase(); } catch { return ''; } }

function classify(name, url) {
  const host = hostOf(url);
  if (OVERRIDES[host] != null) return OVERRIDES[host];
  const generated = classifyGeneratedFeedCategory(name, url);
  if (generated != null) return generated;
  for (const [re, cat] of DOMAIN_RULES) if (re.test(host)) return cat;
  return classifyNameCategory(name) ?? 'other';
}

// ── 受控抓取 ────────────────────────────────────────────────────
const TIMEOUT_MS = 10_000;
const MAX_BYTES = 1_024 * 1024;

export async function fetchOnce(url, fetcher = fetch) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    let target = new URL(url);
    for (let hop = 0; hop <= 3; hop++) {
      if (target.protocol !== 'https:' || target.username || target.password) throw new Error('blocked HTTPS target');
      const host = target.hostname.toLowerCase();
      const [a, b] = host.split('.').map(Number);
      if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal') || host.includes(':') ||
          (isIP(host) === 4 && (a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254) ||
          (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || (a === 198 && (b === 18 || b === 19))))) throw new Error('blocked private target');
      const res = await fetcher(target, {
        signal: controller.signal, redirect: 'manual',
        headers: { 'User-Agent': 'CatLeafCatalogCheck/1.0 (personal RSS reader verification)' },
      });
      if (res.status >= 300 && res.status < 400) {
        const location = res.headers.get('location');
        await res.body?.cancel();
        if (!location || hop === 3) throw new Error('redirect limit');
        target = new URL(location, target);
        continue;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of res.body ?? []) {
        size += chunk.length;
        if (size > MAX_BYTES) throw new Error('over 1 MiB');
        chunks.push(chunk);
      }
      const text = new TextDecoder('utf-8', { fatal: false }).decode(Buffer.concat(chunks));
      return { ok: res.ok, status: res.status, finalUrl: target.href, text };
    }
  } catch (e) {
    return { ok: false, status: 0, finalUrl: url, text: '', error: e.name === 'AbortError' ? 'timeout' : String(e.message || e) };
  } finally {
    clearTimeout(timer);
  }
}

function looksLikeFeed(text) {
  const head = text.slice(0, 2000).toLowerCase();
  const hasRoot = /<rss[\s>]|<feed[\s>]|<rdf[\s>]/.test(head);
  const hasItems = /<item[\s>]|<entry[\s>]/.test(text.toLowerCase());
  return hasRoot && hasItems;
}

function isThirdParty(url) {
  const h = hostOf(url);
  return h === 'rsshub.app' || h.endsWith('.anyfeeder.com') || h.endsWith('.feedburner.com') ||
    h === 'feedx.net' || h.endsWith('.rssbrew.com') || h === 'rsshub.js.cool';
}

async function verifyAll(entries) {
  const results = new Map();
  const queue = [...entries];
  const DELAY_MS = 300;
  let done = 0;
  async function worker() {
    while (queue.length) {
      const entry = queue.shift();
      const target = entry.url.replace(/^http:\/\//, 'https://');
      let r = await fetchOnce(target);
      if (!r.ok && !r.status && r.error && !r._retried) {
        r = await fetchOnce(target); // 仅一次重试（网络层失败）
        r._retried = true;
      }
      results.set(entry.url, r);
      done++;
      if (done % 25 === 0) console.error(`  … ${done}/${entries.length}`);
      await new Promise((res) => setTimeout(res, DELAY_MS));
    }
  }
  await Promise.all(Array.from({ length: 4 }, worker));
  return results;
}

// ── 主流程 ──────────────────────────────────────────────────────
async function main() {
  const snapshot = parseSnapshot();
  console.error(`快照解析：${snapshot.length} 条候选`);

  // 五源首选（ADR 003）：快照缺项则补
  const FIVE = [
    { name: '少数派', url: 'https://sspai.com/feed', categoryId: 'productivity', language: 'zh', description: '高质量数字生活指南，效率工具与数码产品评测。', siteUrl: 'https://sspai.com' },
    { name: '爱范儿', url: 'https://www.ifanr.com/feed', categoryId: 'tech', language: 'zh', description: '报道明日产品的科技媒体，覆盖消费电子与出行。', siteUrl: 'https://www.ifanr.com' },
    { name: 'IT之家', url: 'https://www.ithome.com/rss/', categoryId: 'tech', language: 'zh', description: '科技资讯快报，数码行业动态与产品发布。', siteUrl: 'https://www.ithome.com' },
    { name: '阮一峰的网络日志', url: 'https://www.ruanyifeng.com/blog/atom.xml', categoryId: 'dev', language: 'zh', description: '技术随笔与每周科技爱好者周刊，开发者常读。', siteUrl: 'https://www.ruanyifeng.com/blog/' },
    { name: 'The Verge', url: 'https://www.theverge.com/rss/index.xml', categoryId: 'tech', language: 'en', description: 'Technology, science, art and culture news from The Verge.', siteUrl: 'https://www.theverge.com' },
  ];

  // 去重：规范化 URL（小写 host、去默认端口、去尾斜杠——仅用于判重，不改目录地址）
  const norm = (u) => { try { const x = new URL(u); return `${x.host.toLowerCase()}${x.pathname.replace(/\/$/, '')}${x.search}`; } catch { return u; } };
  const seen = new Map();
  for (const e of snapshot) {
    const k = norm(e.url);
    if (!seen.has(k)) seen.set(k, []);
    seen.get(k).push(e);
  }

  // 合并五源缺项
  const fiveAdded = [];
  for (const f of FIVE) {
    if (![...seen.keys()].some((k) => k.includes(hostOf(f.url)))) {
      seen.set(norm(f.url), [{ name: f.name, url: f.url, _preferred: true }]);
      fiveAdded.push(f.name);
    }
  }
  console.error(`五源缺项补齐：${fiveAdded.length ? fiveAdded.join('、') : '无（快照已含全部五源）'}`);

  const candidates = [];
  for (const [k, group] of seen) {
    const primary = group[0];
    candidates.push({
      name: primary.name || hostOf(primary.url),
      url: primary.url,
      aliases: group.slice(1).map((g) => g.name).filter((n) => n && n !== primary.name),
      duplicatesInSnapshot: group.length,
      preferred: Boolean(primary._preferred) || FIVE.some((f) => norm(f.url) === k),
    });
  }
  console.error(`去重后待核验：${candidates.length} 条（重复合并 ${snapshot.length - candidates.length + fiveAdded.length} 条）`);

  console.error('开始受控核验（并发 4，间隔 300ms）…');
  const fetched = await verifyAll(candidates);

  const checkedAt = new Date().toISOString().slice(0, 10);
  const dispositions = [];
  for (const c of candidates) {
    const r = fetched.get(c.url);
    const third = isThirdParty(c.url);
    let status, reason;
    if (c.url.startsWith('http://') && (!r?.ok || !looksLikeFeed(r.text))) {
      status = 'recheck';
      reason = `HTTPS替代未核实（${r?.error || r?.status}），不向App预填HTTP地址`;
    } else if (third) {
      status = 'needs_config';
      reason = '第三方生成源（RSSHub/anyfeeder/feedburner）：需要可用的生成实例，不默认使用陌生公共代理';
    } else if (r && r.ok && looksLikeFeed(r.text)) {
      status = 'ok';
      reason = `${new Date().toISOString().slice(0, 10)} 核验：可解析订阅内容`;
    } else if (r && r.ok) {
      status = 'recheck';
      reason = `HTTP ${r.status} 但未解析出订阅条目（如空文档/网页），不冒充可用`;
    } else {
      status = 'recheck';
      reason = `本次核验失败（${r ? (r.error || `HTTP ${r.status}`) : '未请求'}）；不代表永久失效，未冒充可用`;
    }
    if (c.url.startsWith('http://') && (status === 'ok' || status === 'needs_config')) {
      c.verifiedUrl = c.url.replace(/^http:\/\//, 'https://');
      if (status === 'ok') status = 'ok_https_upgrade';
      reason += '；已实测HTTPS替代';
    }
    dispositions.push({
      name: c.name, url: c.verifiedUrl || c.url, originalUrl: c.url,
      schemeChanged: Boolean(c.verifiedUrl),
      categoryId: classify(c.name, c.verifiedUrl || c.url),
      language: /[\u4e00-\u9fff]/.test(c.name) ? 'zh' : (hostOf(c.url).endsWith('.cn') ? 'zh' : 'en'),
      sourceType: third ? 'third_party' : 'origin',
      status, reason,
      aliases: c.aliases,
      duplicateCount: c.duplicatesInSnapshot,
      preferred: c.preferred,
      checkedAt,
      provenance: c.preferred ? 'preferred-five' : 'top-rss-list@3892c301',
    });
  }

  applyOverrides(dispositions);

  // ── 汇总 ──────────────────────────────────────────────────────
  const counts = {};
  for (const d of dispositions) counts[d.status] = (counts[d.status] || 0) + 1;
  const catCounts = {};
  for (const d of dispositions.filter((x) => x.status === 'ok' || x.status === 'ok_https_upgrade')) {
    catCounts[d.categoryId] = (catCounts[d.categoryId] || 0) + 1;
  }
  console.error('处置汇总：', JSON.stringify(counts));
  console.error('可收录分类分布：', JSON.stringify(catCounts));
  console.error(`候选 ${snapshot.length} + 补五源 ${fiveAdded.length} = ${snapshot.length + fiveAdded.length}；处置合计 ${dispositions.length}（含快照内同 URL 合并）`);

  // 校验：候选数 = 处置数（含合并说明）
  const totalDispositions = dispositions.length;
  const mergedDuplicates = dispositions.reduce((s, d) => s + (d.duplicateCount - 1), 0);
  if (snapshot.length + fiveAdded.length !== totalDispositions + mergedDuplicates) {
    throw new Error('候选数与处置数不平：' + JSON.stringify({ snapshot: snapshot.length, fiveAdded: fiveAdded.length, totalDispositions, mergedDuplicates }));
  }

  writeFileSync(join(root, 'tools/catalog/dispositions.json'), JSON.stringify({ checkedAt, snapshotCount: snapshot.length, fiveAdded, dispositions }, null, 2));
  console.error('已写出 tools/catalog/dispositions.json');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
