/**
 * Platform-agnostic security functions.
 * No Node.js dependencies (dns, net, http) — works in CF Workers.
 */

/**
 * Safe URL parsing with protocol validation
 */
export const safeParseUrl = (raw: string | null | undefined): URL | null => {
  if (!raw || typeof raw !== 'string') return null;
  if (!/^https?:\/\//i.test(raw.trim())) return null;
  try {
    const parsed = new URL(raw.trim());
    if (parsed.username || parsed.password) return null;
    return parsed;
  } catch {
    return null;
  }
};

/**
 * Check if an IP string looks like a private/loopback/link-local address.
 * Uses regex instead of Node's net.isIP().
 */
const IPV4_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

export const isPrivateIp = (ip: string): boolean => {
  if (!ip || typeof ip !== 'string') return true;

  // IPv6 loopback or unique local
  if (ip === '::1') return true;
  if (ip.startsWith('fc') || ip.startsWith('fd')) return true;

  const m = IPV4_RE.exec(ip);
  if (!m) return true; // Not a valid IPv4 → treat as private (safe default)

  const parts = [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10), parseInt(m[4], 10)];
  if (parts.some(n => n < 0 || n > 255)) return true;

  const [a, b] = parts;
  if (a === 0) return true;
  if (a === 10) return true;
  if (a === 100 && b >= 64 && b <= 127) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 127) return true;
  if (a === 169 && b === 254) return true;
  if (a === 192 && b === 0) return true;
  if (a === 198 && (b === 18 || b === 19)) return true;
  if (a >= 224) return true;

  return false;
};

/**
 * 订阅 URL 的**保守**规范化，只用于去重比较（契约 5.1）。
 *
 * 保守的含义：只做不会改变所指内容的归一——小写 scheme/主机、去掉默认端口、丢掉片段。
 * **不动** path（含尾斜杠）、**不删** query（可能带订阅 token 或影响内容）、
 * 不重写百分号编码。宁可把两个其实相同的地址当成不同，也不要把不同内容合并。
 *
 * 仅接受 HTTPS（契约 5.1 首版只收可访问的 HTTPS RSS/Atom）；无法解析或非 HTTPS 返回 null。
 */
export const normalizeFeedUrl = (raw: string | null | undefined): string | null => {
  const parsed = safeParseUrl(raw);
  if (!parsed || parsed.protocol !== 'https:') return null;
  const port = parsed.port === '' || parsed.port === '443' ? '' : `:${parsed.port}`;
  return `${parsed.protocol}//${parsed.hostname}${port}${parsed.pathname}${parsed.search}`;
};

/** 两个订阅 URL 是否指向同一目标；任一侧无法规范化时退回原始字符串比较 */
export const isSameFeedUrl = (a: string, b: string): boolean => {
  const na = normalizeFeedUrl(a);
  const nb = normalizeFeedUrl(b);
  if (na === null || nb === null) return a.trim() === b.trim();
  return na === nb;
};

/**
 * 主机名是否指向本机 / 内网 / 保留地址。
 *
 * 这里只做**字面量**判断。域名解析到内网地址（DNS rebinding）无法在 Worker 里
 * 用 dns/net 模块检查（契约禁止在 server/ 引入 Node 内置模块），因此那一层依赖
 * 平台出站策略，并且必须用受控测试证明，不能默认它成立（契约 3.3）。
 */
export const isForbiddenHost = (hostname: string): boolean => {
  if (!hostname) return true;
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');

  if (host === 'localhost' || host.endsWith('.localhost')) return true;
  if (host.endsWith('.local') || host.endsWith('.internal')) return true;

  // 字面量 IPv6：回环、唯一本地、链路本地、IPv4 映射
  if (host.includes(':')) {
    return (
      host === '::1' ||
      host === '::' ||
      host.startsWith('fc') ||
      host.startsWith('fd') ||
      host.startsWith('fe80') ||
      host.includes('127.0.0.1') ||
      host.includes('::ffff:')
    );
  }

  // 字面量 IPv4：isPrivateIp 对非 IPv4 字符串返回 true，
  // 所以先确认它确实是点分四段，再交给它判断。
  const m = IPV4_RE.exec(host);
  if (m) return isPrivateIp(host);

  return false;
};

/** 目标被安全策略拒绝（本机/内网/非 HTTPS/跳转超限）。调用方映射为 403。 */
export class FetchBlockedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FetchBlockedError';
  }
}

/**
 * 校验抓取目标：仅 HTTPS、无凭据、非本机/内网；不合法直接抛 [FetchBlockedError]。
 * 每一跳都要调用它（不能只检查第一跳）。
 *
 * 用抛错而不是返回结果联合：本项目的 tsconfig 未开启 strict，判别式收窄不生效，
 * 返回联合会迫使调用方用非空断言；抛错既短又不会漏判。
 */
export const requireFetchTarget = (raw: string | null | undefined): URL => {
  const parsed = safeParseUrl(raw);
  if (!parsed) {
    throw new FetchBlockedError('目标无效或含凭据');
  }
  if (parsed.protocol !== 'https:') {
    throw new FetchBlockedError('仅允许 HTTPS 目标');
  }
  if (isForbiddenHost(parsed.hostname)) {
    throw new FetchBlockedError('目标为本机或内网地址');
  }
  return parsed;
};

/**
 * Infer allowed image hosts based on RSSHub-style routes
 */
export const inferAllowedImageHosts = (feedUrl: string): string[] => {
  const parsed = safeParseUrl(feedUrl);
  if (!parsed) return [];

  const pathname = parsed.pathname || '';
  const hosts = new Set<string>();

  if (parsed.hostname) hosts.add(parsed.hostname.toLowerCase());

  if (pathname.startsWith('/twitter/')) {
    hosts.add('twimg.com');
    hosts.add('pbs.twimg.com');
    hosts.add('abs.twimg.com');
  }

  return Array.from(hosts);
};

/**
 * Normalize client IP from request headers (Web API Headers only)
 */
export const normalizeClientIp = (headers: Headers): string => {
  const forwarded = (headers.get('x-forwarded-for') || '').split(',')[0].trim();
  const raw = forwarded || headers.get('x-real-ip') || headers.get('cf-connecting-ip') || 'unknown';
  if (!raw) return 'unknown';
  return raw.startsWith('::ffff:') ? raw.slice(7) : raw;
};

/**
 * Constant-time string comparison. Length is not hidden (it is a public
 * property of the secret for any fixed input), but the first differing byte
 * position is, so repeated guesses cannot be timed byte by byte.
 */
const timingSafeEqual = (a: string, b: string): boolean => {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
};

/**
 * Validate admin secret from request headers
 */
export const validateAdminSecret = (headers: Headers, adminSecret: string | undefined): boolean => {
  if (!adminSecret) return false;
  const provided = headers.get('x-admin-secret');
  return !!provided && timingSafeEqual(provided, adminSecret);
};
