/**
 * Cloudflare Workers/Pages environment bindings type definition.
 */
export interface Env {
  // D1 database binding (SQLite, preferred)
  DB?: D1Database;

  // Neon PostgreSQL connection string (fallback)
  DATABASE_URL?: string;

  // Admin secret for feed management endpoints
  ADMIN_SECRET?: string;

  // KV namespace for distributed rate limiting
  RATE_LIMIT_KV?: KVNamespace;

  // Max proxied media size in bytes (default 50MB)
  MEDIA_PROXY_MAX_BYTES?: string;

  // Max article HTML size in bytes for extraction (default 5MB)
  ARTICLE_EXTRACT_MAX_BYTES?: string;

  // 允许跨域读取 API 的浏览器来源白名单（逗号分隔）。原生 App 不使用 CORS，
  // 留空即不对任何浏览器来源开放；不使用 '*'。
  ALLOWED_ORIGINS?: string;

  // debug 路由默认关闭，仅显式设为 'true' 时才可用
  ENABLE_DEBUG_ROUTE?: string;
}
