#!/usr/bin/env node
/**
 * L03 产出生成：tools/catalog/dispositions.json →
 *   1. docs/catalog/核验台账.md（逐条处置 + 方法 + 平衡校验，可追溯）
 *   2. android-native/app/src/main/assets/catalog/catalog.json（应用最小静态清单）
 *
 * 应用清单只含「可收录（ok / ok_https_upgrade）」与「需配置（needs_config）」：
 * 无效地址不进默认可添加列表（契约 8.6）；待复验/暂不收录只留在台账。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const data = JSON.parse(readFileSync(join(root, 'tools/catalog/dispositions.json'), 'utf-8'));
const latestCheck = data.dispositions.reduce((day, d) => d.checkedAt > day ? d.checkedAt : day, data.checkedAt);

const CATEGORY_NAMES = {
  tech: '科技数码', dev: '编程技术', business: '商业财经', news: '新闻时事',
  productivity: '效率工具', design: '设计创意', culture: '文化阅读', life: '生活方式',
  science: '科学教育', gaming: '游戏娱乐', blog: '个人博客', other: '其他',
};

// 五源简介（人工核对；其余条目不编造描述，留空由界面显示域名+分类）
const DESCRIPTIONS = {
  'https://sspai.com/feed': '高质量数字生活指南，效率工具与数码产品评测。',
  'https://www.ifanr.com/feed': '报道明日产品的科技媒体，覆盖消费电子与出行。',
  'https://www.ithome.com/rss/': '科技资讯快报，数码行业动态与产品发布。',
  'https://www.ruanyifeng.com/blog/atom.xml': '技术随笔与每周科技爱好者周刊，开发者常读。',
  'https://www.theverge.com/rss/index.xml': 'Technology, science, art and culture news from The Verge.',
  'https://daily.juya.uk/rss.xml': '每日汇总 AI 新闻与动态，提供中文早报。',
};

const stableId = (url) => 'c' + crypto.createHash('sha256').update(url).digest('hex').slice(0, 12);
const siteOf = (u) => { try { const x = new URL(u); return `${x.protocol}//${x.host}`; } catch { return ''; } };

const include = data.dispositions.filter((d) => d.status === 'ok' || d.status === 'ok_https_upgrade' || d.status === 'needs_config');
for (const entry of include) {
  const url = new URL(entry.url);
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error(`目录不能预填非HTTPS或带凭据地址：${entry.name}`);
}
const preferred = include.filter((d) => d.preferred);

const entries = include.map((d) => ({
  id: d.catalogId || stableId(d.url),
  name: d.name,
  feedUrl: d.url,
  siteUrl: siteOf(d.url),
  categoryId: d.categoryId,
  language: d.language,
  tags: [],
  plainDescription: DESCRIPTIONS[d.url] || '',
  sourceType: d.sourceType,
  status: d.status === 'ok_https_upgrade' ? 'ok' : d.status,
  checkedAt: d.checkedAt,
  provenance: d.provenance,
  ...(d.aliases.length ? { aliases: d.aliases } : {}),
  ...(d.note || d.schemeChanged ? { note: d.note || '原 http 地址，已实测 HTTPS 替代' } : {}),
}));

// 原五源顺序保持，用户批准的新精选追加在末尾。
const PREFERRED_ORDER = ['少数派', '爱范儿', 'IT之家', '阮一峰的网络日志', 'The Verge', '橘鸦AI早报'];
const preferredIds = preferred
  .slice()
  .sort((a, b) => PREFERRED_ORDER.indexOf(a.name) - PREFERRED_ORDER.indexOf(b.name))
  .map((d) => d.catalogId || stableId(d.url));

const catalog = {
  catalogVersion: `2026-10-09.2`,
  checkedAt: latestCheck,
  snapshot: 'top-rss-list@3892c30181036eeee6e67f0750fad0457bfb59c7',
  categories: Object.entries(CATEGORY_NAMES).map(([id, name]) => ({ id, name })),
  preferredIds,
  entries,
};

mkdirSync(join(root, 'android-native/app/src/main/assets/catalog'), { recursive: true });
writeFileSync(join(root, 'android-native/app/src/main/assets/catalog/catalog.json'), JSON.stringify(catalog, null, 1));

// ── 台账 ────────────────────────────────────────────────────────
const counts = {};
for (const d of data.dispositions) counts[d.status] = (counts[d.status] || 0) + 1;
const STATUS_LABEL = {
  ok: '可收录', ok_https_upgrade: '可收录（HTTPS 替代）', needs_config: '需配置（第三方生成源）',
  recheck: '待复验', merged: '合并', skip: '暂不收录',
};
const lines = [];
lines.push('# L03 全量候选核验台账');
lines.push('');
lines.push(`- 全量核验日期：${data.checkedAt}；最近增量复验：${latestCheck}（工作机受控限流：并发 4、间隔 300ms、超时 10s、1 MiB 上限、网络层失败仅 1 次重试、无代理）`);
lines.push(`- 候选快照：[top-rss-list@3892c301](https://github.com/weekend-project-space/top-rss-list/blob/3892c30181036eeee6e67f0750fad0457bfb59c7/README.md) ${data.snapshotCount} 条（本地存档 tools/catalog/snapshot-top-rss-list-3892c301.md）`);
lines.push(`- 首选五源缺项补齐：${data.fiveAdded.length ? data.fiveAdded.join('、') : '无（快照已含）'}`);
lines.push(`- 用户增量源：${data.dispositions.filter((d) => d.provenance.startsWith('user-added-')).map((d) => d.name).join('、') || '无'}；首次引导保留原五源，发现精选按用户确认追加。`);
lines.push(`- 处置合计 ${data.dispositions.length} 条 + 快照内同 URL 合并说明；平衡校验由 verify-catalog.mjs 断言（候选数 = 处置数 + 合并数）`);
lines.push(`- 处置分布：${Object.entries(counts).map(([k, v]) => `${STATUS_LABEL[k] || k} ${v}`).join('，')}`);
lines.push('- 应用静态清单只收录 可收录/需配置 两类（待复验不冒充可用、不进默认可添加列表）；清单与台账由同一 dispositions.json 生成。');
lines.push('- 分类为内容发现用途的初版归类（12 类映射 PRD），可在后续版本修订；不自动订阅任何条目。');
lines.push('');
lines.push('| 名称 | 地址 | 分类 | 语言 | 来源 | 处置 | 说明 |');
lines.push('| --- | --- | --- | --- | --- | --- | --- |');
for (const d of data.dispositions) {
  const url = d.schemeChanged ? `${d.url}（原 ${d.originalUrl}）` : d.url;
  const alias = d.aliases.length ? `；同名条目：${d.aliases.join('/')}` : '';
  lines.push(`| ${d.name} | ${url} | ${CATEGORY_NAMES[d.categoryId]} | ${d.language} | ${d.sourceType === 'third_party' ? '第三方生成' : '原站'} | ${STATUS_LABEL[d.status] || d.status} | ${d.reason}${alias} |`);
}
mkdirSync(join(root, 'docs/catalog'), { recursive: true });
writeFileSync(join(root, 'docs/catalog/核验台账.md'), lines.join('\n'));

console.log(`entries=${entries.length} preferred=${preferredIds.length} ledger=${data.dispositions.length}`);
console.log('counts=', JSON.stringify(counts));
const catDist = {};
for (const d of include) catDist[CATEGORY_NAMES[d.categoryId]] = (catDist[CATEGORY_NAMES[d.categoryId]] || 0) + 1;
console.log('category dist=', JSON.stringify(catDist));
