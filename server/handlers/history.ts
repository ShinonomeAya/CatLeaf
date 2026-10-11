import type { Repository, HistoryItem } from '../db/repository.js';
import type { RateLimiter } from '../rate-limit.js';
import { normalizeClientIp } from '../security.js';

interface HistoryConfig {
  maxItems: number;       // Max items per upsert request（契约 3.2：100 条/批）
  maxRequests: number;    // Max upsert requests per window (default 30)
  windowMs: number;       // Rate limit window in ms (default 60000)
  retentionDays: number;  // History retention in days (default 60)
}

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 200;

/** 解析并夹取整数参数：非法值退回默认值，越界夹到边界 */
const clampInt = (raw: string | null, fallback: number, min: number, max: number): number => {
  const parsed = Number.parseInt(raw ?? '', 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(Math.max(parsed, min), max);
};

const DEFAULT_CONFIG: HistoryConfig = {
  maxItems: 100,
  maxRequests: 30,
  windowMs: 60_000,
  retentionDays: 60,
};

export async function handleHistory(
  request: Request,
  repo: Repository,
  rateLimiter: RateLimiter,
  config: Partial<HistoryConfig> = {},
): Promise<Response> {
  const cfg = { ...DEFAULT_CONFIG, ...config };

  try {
    const url = new URL(request.url);
    const action = url.searchParams.get('action');

    // ── GET: Get history items ──
    if (request.method === 'GET' || action === 'get') {
      const feedId = url.searchParams.get('id');
      // 契约 3.2：limit 默认 100、范围 1–200；offset 非负整数。
      // 原实现用 `|| 0` 兜底 → limit=0 时仓库不加 LIMIT，等于把整表读出来。
      const limit = clampInt(url.searchParams.get('limit'), DEFAULT_LIMIT, 1, MAX_LIMIT);
      const offset = clampInt(url.searchParams.get('offset'), 0, 0, Number.MAX_SAFE_INTEGER);

      if (!feedId) {
        return Response.json({ error: 'Missing id parameter' }, { status: 400 });
      }

      const total = await repo.getHistoryCount(feedId);
      if (total === 0) {
        return Response.json({ feedId, items: [], lastUpdated: null, total: 0 });
      }

      const rows = await repo.getHistory(feedId, limit, offset);

      const items = rows.map(row => ({
        title: row.title,
        pubDate: row.pubDate,
        link: row.link,
        guid: row.guid,
        author: row.author,
        description: row.description,
        content: row.content,
        thumbnail: row.thumbnail ? JSON.parse(row.thumbnail) : null,
        enclosure: row.enclosure ? JSON.parse(row.enclosure) : null,
        feedTitle: row.feedTitle,
      }));

      const lastUpdated = rows.length > 0 ? new Date(rows[0].lastUpdated).getTime() : null;

      return Response.json({ feedId, items, lastUpdated, total });
    }

    // ── POST: Upsert history items ──
    if (request.method === 'POST') {
      const body = await request.json() as { feedId?: string; items?: HistoryItem[] };
      const { feedId, items } = body;

      if (!feedId || !Array.isArray(items)) {
        return Response.json({ error: 'Missing feedId or items array' }, { status: 400 });
      }

      if (items.length > cfg.maxItems) {
        return Response.json({ error: 'Too many items in a single request' }, { status: 413 });
      }

      const clientIp = normalizeClientIp(request.headers);
      const limited = await rateLimiter.check(`history:${clientIp}`, cfg.maxRequests, cfg.windowMs);
      if (limited) {
        // KV 计数是非原子的：并发下可能少算，因此这是**软限额**，不是硬配额，
        // 只用于压住刷量，不能当作精确额度（契约 3.2 明确禁止把它当硬额度）。
        const retryAfterSeconds = Math.ceil(cfg.windowMs / 1000);
        return Response.json(
          { error: 'Too many history upsert requests', code: 'rate_limited', retryAfterSeconds },
          { status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } },
        );
      }

      const exists = await repo.feedExists(feedId);
      if (!exists) {
        return Response.json({ error: `Feed ID '${feedId}' not found` }, { status: 404 });
      }

      const expiredCount = await repo.deleteExpiredHistory(feedId, cfg.retentionDays);
      const { added: addedCount } = await repo.upsertHistoryItems(feedId, items);
      const total = await repo.getHistoryCount(feedId);

      console.log(`[History] Feed "${feedId}": +${addedCount} new, ${total} total`);

      return Response.json({ success: true, added: addedCount, total, expired: expiredCount });
    }

    return Response.json({ error: 'Method not allowed' }, { status: 405 });
  } catch (error: unknown) {
    console.error('[Server Error] [API Error]', error);
    console.error('[Server Error] [History]', error);
    return Response.json({ error: 'Internal server error', code: 'internal_error' }, { status: 500 });
  }
}
