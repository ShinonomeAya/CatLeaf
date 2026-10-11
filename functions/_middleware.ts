/**
 * Cloudflare Pages Functions 共同边界：所有 /api/* 的鉴权、方法校验、
 * 私有缓存头与错误边界都在这里，业务 handler 不再各自决定是否保护。
 *
 * 顺序固定为：停用 debug → 跨域预检 → 方法校验 → 认证 → 执行业务。
 * 未通过认证的请求不会进入 handler，因此不会读写 D1。
 */
import type { Env } from '../server/env.js';
import { validateAdminSecret } from '../server/security.js';

const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
};

/** 私有接口不得进入公共 CDN 缓存；覆盖 handler 自己声明的缓存策略 */
const PRIVATE_CACHE = 'private, no-store';

const ALLOWED_METHODS = ['GET', 'POST', 'OPTIONS'];

/** JSON 请求体上限（契约 3.2：JSON 最多 2 MiB） */
const MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;

/** 带请求体的 POST 才要求 JSON 声明；无 body 的 POST（如 admin 列表）不受影响 */
const requiresJsonBody = (method: string, contentLength: string | null): boolean =>
  method === 'POST' && !!contentLength && contentLength !== '0';

const corsHeadersFor = (origin: string | null, env: Env): Record<string, string> => {
  // 原生 App 请求不带 Origin，不需要 CORS。浏览器只按显式白名单回显，
  // 不用 '*'：CORS 不是权限控制，也不能替未授权来源打开读取权限。
  if (!origin) return {};
  const allowed = (env.ALLOWED_ORIGINS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowed.includes(origin)) return {};
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, x-admin-secret',
    'Access-Control-Max-Age': '600',
    Vary: 'Origin',
  };
};

/** 统一附加安全头与私有缓存头，并丢弃上游可能设置的公共缓存指令 */
const finalize = (response: Response, cors: Record<string, string>): Response => {
  const headers = new Headers(response.headers);
  for (const [k, v] of Object.entries(SECURITY_HEADERS)) headers.set(k, v);
  for (const [k, v] of Object.entries(cors)) headers.set(k, v);
  headers.set('Cache-Control', PRIVATE_CACHE);
  headers.delete('CDN-Cache-Control');
  headers.delete('Surrogate-Control');
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
};

const errorJson = (
  status: number,
  code: string,
  error: string,
  cors: Record<string, string>,
  extra: Record<string, unknown> = {},
): Response => finalize(Response.json({ error, code, ...extra }, { status }), cors);

export const onRequest: PagesFunction<Env> = async (context) => {
  const { request, env, next } = context;
  const url = new URL(request.url);

  // 非 API 请求（静态资源）不受 API 边界影响
  if (url.pathname !== '/api' && !url.pathname.startsWith('/api/')) {
    return next();
  }

  const cors = corsHeadersFor(request.headers.get('Origin'), env);

  // 生产默认停用 debug 路由；只有显式开启才放行
  if (url.pathname.startsWith('/api/debug-feed') && env.ENABLE_DEBUG_ROUTE !== 'true') {
    return errorJson(404, 'not_found', 'Not found', cors);
  }

  // 预检只回答协议问题，不返回业务数据，也不代表后续请求被放行
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        ...SECURITY_HEADERS,
        ...cors,
        'Cache-Control': PRIVATE_CACHE,
        'Content-Length': '0',
      },
    });
  }

  if (!ALLOWED_METHODS.includes(request.method)) {
    return errorJson(405, 'method_not_allowed', `Method ${request.method} is not supported`, cors, {
      allow: 'GET, POST, OPTIONS',
    });
  }

  if (!env.ADMIN_SECRET) {
    return errorJson(503, 'not_configured', 'Server is not configured', cors);
  }

  // 先认证，后执行业务
  if (!validateAdminSecret(request.headers, env.ADMIN_SECRET)) {
    return errorJson(401, 'unauthorized', 'Unauthorized', cors);
  }

  const contentLength = request.headers.get('Content-Length');
  if (requiresJsonBody(request.method, contentLength)) {
    const contentType = request.headers.get('Content-Type') || '';
    if (!contentType.toLowerCase().includes('application/json')) {
      return errorJson(400, 'invalid_content_type', 'Content-Type must be application/json', cors);
    }
    // 体积上限在共同边界拦一次，避免把超大 body 读进内存后再由各 handler 判
    if (Number(contentLength) > MAX_JSON_BODY_BYTES) {
      return errorJson(413, 'payload_too_large', 'Request body exceeds 2 MiB', cors);
    }
  }

  try {
    return finalize(await next(), cors);
  } catch (error: unknown) {
    // 只在服务端记录；响应不包含堆栈、SQL、URL 或密钥
    console.error(
      '[API Error]',
      url.pathname,
      request.method,
      error instanceof Error ? error.message : error,
    );
    return errorJson(500, 'internal_error', 'Internal server error', cors);
  }
};