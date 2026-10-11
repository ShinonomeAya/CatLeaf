/**
 * HTTP utility functions for Cloudflare Workers/Pages.
 * No Node.js dependencies — uses Web API fetch + AbortController.
 */
import { requireFetchTarget, FetchBlockedError } from './security.js';

// 转出错误类，保持既有的 `from '../http.js'` 导入路径可用
export { FetchBlockedError };

interface SecureFetchOptions {
  timeout?: number;
  headers?: Record<string, string>;
  method?: string;
  /** 最多跟随几次跳转；契约要求最多 3 跳 */
  maxRedirects?: number;
  /** 声明式大小上限：content-length 超限直接拒绝，避免把大响应读进内存 */
  maxBytes?: number;
}

const DEFAULT_MAX_REDIRECTS = 3;
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36';

/**
 * 抓取外部资源：**手动**跟随跳转，每一跳都重新校验目标。
 *
 * 为什么不能交给平台自动跟随：自动跟随会直接把请求发到 302 指向的地址，
 * 若该地址是内网/回环（答辩点）或把 https 降级成 http，就没有任何地方能拦下来。
 * 契约明确要求“每一跳均校验”“最多 3 次受控跳转”，且禁止把
 * “Workers 应该限制内网” 当成完整 SSRF 防护证明。
 *
 * 说明：这里只做字面量主机校验；域名解析到内网地址由平台出站策略兜底，
 * 该边界必须用受控测试证明，见 docs/validation/验收记录.md 的 T06 段落。
 */
export const secureFetch = async (
  targetUrl: string,
  options: SecureFetchOptions = {}
): Promise<Response> => {
  const {
    timeout = 15000,
    headers = {},
    method = 'GET',
    maxRedirects = DEFAULT_MAX_REDIRECTS,
    maxBytes,
  } = options;

  requireFetchTarget(targetUrl);

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeout);

  try {
    // 不手动设置 Host：每跳目标不同，让运行时按当前 URL 生成，避免把上一跳的 Host 带过去
    const normalizedHeaders: Record<string, string> = {
      'User-Agent': USER_AGENT,
      ...headers,
    };

    let current = new URL(targetUrl);
    for (let hop = 0; hop <= maxRedirects; hop++) {
      const response = await fetch(current.toString(), {
        method,
        headers: normalizedHeaders,
        // 手动处理跳转，绝不交给平台自动跟随
        redirect: 'manual',
        signal: controller.signal,
      });

      const isRedirect = response.status >= 300 && response.status < 400;
      const location = response.headers.get('location');
      if (!isRedirect || !location) {
        if (maxBytes !== undefined) {
          const declared = response.headers.get('content-length');
          if (declared && parseInt(declared, 10) > maxBytes) {
            throw new FetchBlockedError('响应超出大小上限');
          }
        }
        return response;
      }

      if (hop === maxRedirects) {
        throw new FetchBlockedError(`跳转次数超过上限（${maxRedirects}）`);
      }

      // 相对 Location 要基于当前地址解析，然后当作全新目标重新校验
      let nextUrl: string;
      try {
        nextUrl = new URL(location, current).toString();
      } catch {
        throw new FetchBlockedError('跳转目标无法解析');
      }

      current = requireFetchTarget(nextUrl);
    }

    throw new FetchBlockedError(`跳转次数超过上限（${maxRedirects}）`);
  } finally {
    clearTimeout(timeoutId);
  }
};

/**
 * Stream response body with a size limit.
 */
export const streamWithSizeLimit = async (
  response: Response,
  maxBytes: number = 50 * 1024 * 1024
): Promise<ReadableStream<Uint8Array>> => {
  const contentLength = response.headers.get('content-length');
  if (contentLength && parseInt(contentLength, 10) > maxBytes) {
    throw new Error('Content exceeds size limit');
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error('Response body is not readable');

  let transferred = 0;

  return new ReadableStream({
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) { controller.close(); return; }
      transferred += value.length;
      if (transferred > maxBytes) {
        controller.error(new Error('Transfer size limit exceeded'));
        reader.cancel();
        return;
      }
      controller.enqueue(value);
    },
    cancel() {
      reader.cancel();
    },
  });
};
