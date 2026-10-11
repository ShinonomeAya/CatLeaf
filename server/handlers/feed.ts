import type { Repository } from '../db/repository.js';
import { safeParseUrl } from '../security.js';

const RSS_MAX_BYTES = 5 * 1024 * 1024;
import { secureFetch, FetchBlockedError } from '../http.js';

export async function handleFeed(request: Request, repo: Repository): Promise<Response> {
  if (request.method !== 'GET') {
    return Response.json({ error: 'Method not allowed' }, { status: 405 });
  }

  try {
    const url = new URL(request.url);
    const feedId = url.searchParams.get('id');

    if (!feedId) {
      return Response.json({ error: 'Missing id parameter' }, { status: 400 });
    }

    const feed = await repo.getFeedById(feedId);
    if (!feed || !feed.url) {
      console.error(`[Server Error] ID Not Found or URL missing: ${feedId}`);
      return Response.json({ error: `Feed ID '${feedId}' not found on server` }, { status: 404 });
    }

    const parsedTarget = safeParseUrl(feed.url);
    if (!parsedTarget || !parsedTarget.hostname) {
      console.error(`[Server Error] Invalid target URL for ID: ${feedId}`);
      return Response.json({ error: 'Invalid upstream URL for this feed' }, { status: 502 });
    }

    // CF Workers fetch blocks private IPs automatically — no resolveAndValidateHost needed
    // RSS 响应同样受 5 MiB 上限约束（契约 3.3），并逐跳校验目标
    const response = await secureFetch(parsedTarget.toString(), {
      timeout: 15000,
      maxBytes: RSS_MAX_BYTES,
    });
    console.log(`[Feed Fetch] ID: ${feedId} | Status: ${response.status}`);

    if (!response.ok) {
      const errorText = await response.text();
      return Response.json({
        error: `Upstream error for ID '${feedId}'`,
        status: response.status,
        body: errorText.substring(0, 200),
      }, { status: response.status });
    }

    const body = await response.text();
    const contentType = response.headers.get('content-type') || 'application/xml';

    return new Response(body, {
      status: 200,
      headers: {
        // Cache-Control 由 API 共同边界统一设为 private, no-store
        'Content-Type': contentType,
      },
    });
  } catch (error: unknown) {
    // 细节只进服务端日志；响应不回传 message（契约 3.1：不返回堆栈/SQL/密钥）
    console.error(`[Server Error] [Feed Fetch Error]`, error);
    if (error instanceof FetchBlockedError) {
      return Response.json(
        { error: 'Upstream target not allowed', code: 'target_forbidden', reason: error.message },
        { status: 403 },
      );
    }
    const isTimeout = error instanceof Error && (error.name === 'AbortError' || error.message?.includes('timeout'));
    return Response.json(
      { error: isTimeout ? 'Fetch timeout' : 'Fetch failed', code: isTimeout ? 'timeout' : 'upstream_failed' },
      { status: isTimeout ? 504 : 502 },
    );
  }
}
