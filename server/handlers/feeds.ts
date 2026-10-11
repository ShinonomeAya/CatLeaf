import type { Repository } from '../db/repository.js';
import { validateAdminSecret, normalizeFeedUrl, isSameFeedUrl } from '../security.js';

/** 首版个人实例上限（契约 3.2：200 个源） */
const MAX_FEEDS = 200;
const MAX_NAME_LENGTH = 120;
const MAX_CATEGORY_LENGTH = 80;
const MAX_URL_LENGTH = 4096;

/**
 * 字段长度与格式校验；不合法返回可直接返回的响应，合法返回 null。
 * id 只允许安全的非空字符，避免塞进 URL 路径后产生歧义。
 */
const validateFeedFields = (
  id: string,
  url: string | undefined,
  category: string | undefined,
  customTitle: string | undefined,
): Response | null => {
  const bad = (error: string, code: string) => Response.json({ error, code }, { status: 400 });

  if (!/^[A-Za-z0-9._~-]{1,128}$/.test(id)) {
    return bad('Feed id must be 1-128 safe characters', 'invalid_id');
  }
  if (url !== undefined && url.length > MAX_URL_LENGTH) {
    return bad(`Feed URL must be at most ${MAX_URL_LENGTH} characters`, 'invalid_url');
  }
  if (customTitle !== undefined && customTitle.length > MAX_NAME_LENGTH) {
    return bad(`Name must be at most ${MAX_NAME_LENGTH} characters`, 'invalid_name');
  }
  if (category !== undefined && category.length > MAX_CATEGORY_LENGTH) {
    return bad(`Category must be at most ${MAX_CATEGORY_LENGTH} characters`, 'invalid_category');
  }
  return null;
};

export async function handleFeeds(
  request: Request,
  repo: Repository,
  action: string | null,
  adminSecret: string | undefined,
): Promise<Response> {
  try {
    console.log(`[API Feeds] Method: ${request.method}, Action: ${action}`);

    // ── GET: List feeds (public or admin) ──
    if (request.method === 'GET' && action === 'summary') {
      const summaries = await repo.getFeedSummaries();
      return Response.json(summaries);
    }

    // 只有 /api/feeds、/api/feeds/list 与 /api/feeds/list/admin 进入列表分支。
    // 写动作不接受 GET，否则 GET /api/feeds/add 会顺带返回列表。
    if (request.method === 'GET' && action !== null && action !== 'admin') {
      return Response.json(
        { error: `Action '${action}' does not accept GET`, code: 'method_not_allowed' },
        { status: 405 },
      );
    }

    if (request.method === 'GET' || (request.method === 'POST' && action === 'admin')) {
      if (action === 'admin') {
        if (!validateAdminSecret(request.headers, adminSecret)) {
          return Response.json({ error: 'Unauthorized: Invalid Admin Secret' }, { status: 401 });
        }
        const allFeeds = await repo.listFeeds();
        return Response.json(allFeeds);
      }

      // Public list (hides URL)
      const allFeeds = await repo.listFeeds();
      const safeFeeds = allFeeds.map(f => ({
        id: f.id,
        category: f.category,
        isSub: f.isSub || false,
        customTitle: f.customTitle || '',
      }));
      return Response.json(safeFeeds);
    }

    // ── POST: Manage feeds (requires admin secret) ──
    if (request.method === 'POST') {
      if (!adminSecret) {
        return Response.json({ error: 'Admin secret is not configured on server.' }, { status: 503 });
      }
      if (!validateAdminSecret(request.headers, adminSecret)) {
        return Response.json({ error: 'Unauthorized: Invalid Admin Secret' }, { status: 401 });
      }

      const body = await request.json() as { id?: string; url?: string; category?: string; isSub?: boolean; customTitle?: string; allowedMediaHosts?: string[]; ids?: string[] };

      // ── Add Feed：只有“新 id + 新规范化 URL”才添加 ──
      if (action === 'add') {
        const { id, url, category, isSub, customTitle, allowedMediaHosts } = body;
        if (!id || !url) {
          return Response.json({ error: 'Missing ID or URL', code: 'invalid_input' }, { status: 400 });
        }

        const invalid = validateFeedFields(id, url, category, customTitle);
        if (invalid) return invalid;

        const normalized = normalizeFeedUrl(url);
        if (!normalized) {
          return Response.json(
            { error: 'Feed URL must be a valid HTTPS RSS/Atom URL', code: 'invalid_url' },
            { status: 400 },
          );
        }

        const existingFeeds = await repo.listFeeds();

        // 同 id 重复提交不得覆盖已有源（契约 3.2）
        if (existingFeeds.some((f) => f.id === id)) {
          return Response.json(
            { error: `Feed id '${id}' already exists`, code: 'id_exists' },
            { status: 409 },
          );
        }
        // 规范化 URL 重复同样拒绝：重复导入不得悄悄覆盖
        if (existingFeeds.some((f) => isSameFeedUrl(f.url, url))) {
          return Response.json(
            { error: 'A feed with the same URL already exists', code: 'url_exists' },
            { status: 409 },
          );
        }
        if (existingFeeds.length >= MAX_FEEDS) {
          return Response.json(
            {
              error: `Feed limit reached (${MAX_FEEDS}). Remove some feeds first.`,
              code: 'feed_limit_reached',
              limit: MAX_FEEDS,
            },
            { status: 409 },
          );
        }

        await repo.insertFeed({
          id,
          url: url.trim(),
          category: category || '',
          isSub: !!isSub,
          customTitle: customTitle || '',
          allowedMediaHosts: allowedMediaHosts ? JSON.stringify(allowedMediaHosts) : null,
        });
        return Response.json({ success: true });
      }

      // ── Update Feed：只改名称与分组，URL 不同必须走新增 ──
      if (action === 'update') {
        const { id, url, category, customTitle } = body;
        if (!id) {
          return Response.json({ error: 'Missing ID', code: 'invalid_input' }, { status: 400 });
        }

        const invalid = validateFeedFields(id, undefined, category, customTitle);
        if (invalid) return invalid;

        const existingFeeds = await repo.listFeeds();
        const current = existingFeeds.find((f) => f.id === id);
        if (!current) {
          return Response.json({ error: `Feed with id '${id}' not found`, code: 'not_found' }, { status: 404 });
        }

        // 编辑不允许换 URL：旧文章身份会错绑到新源，必须另建源
        if (url !== undefined && url !== current.url && !isSameFeedUrl(url, current.url)) {
          return Response.json(
            {
              error: 'Changing the RSS URL is not allowed; add it as a new feed',
              code: 'url_change_requires_new_feed',
            },
            { status: 409 },
          );
        }

        const changed = await repo.updateFeedMeta(id, {
          category: category ?? current.category,
          customTitle: customTitle ?? current.customTitle,
        });
        if (changed === 0) {
          return Response.json({ error: `Feed with id '${id}' not found`, code: 'not_found' }, { status: 404 });
        }
        return Response.json({ success: true });
      }

      // Delete Feed
      if (action === 'delete') {
        const { id } = body;
        if (!id) {
          return Response.json({ error: 'Missing ID', code: 'invalid_input' }, { status: 400 });
        }
        const deleted = await repo.deleteFeed(id);
        if (deleted === 0) {
          return Response.json({ error: `Feed with id '${id}' not found`, code: 'not_found' }, { status: 404 });
        }
        return Response.json({ success: true });
      }

      // Reorder Feeds
      if (action === 'reorder') {
        const { ids } = body;
        if (!Array.isArray(ids) || ids.length === 0) {
          return Response.json({ error: 'Invalid input: ids must be a non-empty array', code: 'invalid_input' }, { status: 400 });
        }
        const uniqueIds = Array.from(new Set(ids));
        if (uniqueIds.length !== ids.length) {
          return Response.json({ error: 'Duplicate feed ids are not allowed', code: 'invalid_input' }, { status: 400 });
        }
        try {
          await repo.reorderFeeds(uniqueIds);
        } catch (err: unknown) {
          if (err instanceof Error && err.message === 'One or more feeds not found') {
            return Response.json({ error: err.message }, { status: 404 });
          }
          throw err;
        }
        return Response.json({ success: true });
      }

      return Response.json({ error: 'Invalid action parameter', code: 'invalid_action' }, { status: 400 });
    }

    return Response.json({ error: 'Method not allowed', code: 'method_not_allowed' }, { status: 405 });
  } catch (error: unknown) {
    // 细节只进服务端日志；响应不回传 message/堆栈
    console.error('[Server Error] [API Error]', action, error);
    return Response.json({ error: 'Internal server error', code: 'internal_error' }, { status: 500 });
  }
}
