const loopbackOrigin = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?\/?$/i;

export function localPortalUrl(localOrigin: string | undefined, entry: "venta" | "#/login"): string | undefined {
  const host = localOrigin?.match(loopbackOrigin)?.[1];
  return host ? `http://${host}:${entry === "venta" ? "5173/" : "5175/#/login"}` : undefined;
}

/** Portal URLs are deployment configuration, never derived from visitor data. */
export function resolvePortalUrl(configured: string | undefined, localOrigin: string | undefined, entry: "venta" | "#/login"): string | undefined {
  const value = configured?.trim();
  if (!value) return localPortalUrl(localOrigin, entry);
  if (/[\s\\\u0000-\u001f\u007f]/.test(value)) return undefined;
  if (/^\/(?!\/)/.test(value)) return value;
  try {
    const url = new URL(value);
    if (url.username || url.password || !/^https?:\/\//i.test(value)) return undefined;
    if (url.protocol === "https:" || url.protocol === "http:" && loopbackOrigin.test(url.origin) && /^http:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:[/?#]|$)/i.test(value)) return url.href;
  } catch { /* Invalid configuration does not create a login link. */ }
  return undefined;
}
