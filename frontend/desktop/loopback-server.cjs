const fs = require("node:fs");
const http = require("node:http");
const https = require("node:https");
const path = require("node:path");
const { validateBackendUrl } = require("./backend-config.cjs");

const DEFAULT_MAX_REQUEST_BYTES = 50 * 1024 * 1024;
const DEFAULT_TIMEOUT_MS = 120_000;
const HOP_BY_HOP_HEADERS = new Set([
  "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
  "te", "trailer", "transfer-encoding", "upgrade"
]);

function securityHeaders(contentType) {
  return {
    "cache-control": "no-store",
    "content-security-policy": "default-src 'self'; connect-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'",
    "content-type": contentType,
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer"
  };
}

function mimeType(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  return ({
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
    ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2",
    ".txt": "text/plain; charset=utf-8", ".map": "application/json"
  })[extension] || "application/octet-stream";
}

function safeStaticPath(staticRoot, requestPath) {
  let decoded;
  try {
    decoded = decodeURIComponent(requestPath);
  } catch {
    return null;
  }
  if (decoded.includes("\0") || decoded.includes("\\")) return null;
  const target = path.resolve(staticRoot, `.${decoded}`);
  const relative = path.relative(staticRoot, target);
  return relative.startsWith("..") || path.isAbsolute(relative) ? null : target;
}

function requestSizeExceeded(req, maxBytes) {
  const contentLength = Number(req.headers["content-length"]);
  return Number.isFinite(contentLength) && contentLength > maxBytes;
}

function writeError(response, statusCode, message) {
  if (response.headersSent) {
    response.destroy();
    return;
  }
  const body = JSON.stringify({ error: message });
  response.writeHead(statusCode, {
    ...securityHeaders("application/json; charset=utf-8"),
    "content-length": Buffer.byteLength(body)
  });
  response.end(body);
}

function createDesktopServer({
  staticRoot,
  backendUrl,
  backendAllowedHosts = [],
  maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES,
  timeoutMs = DEFAULT_TIMEOUT_MS
} = {}) {
  const root = path.resolve(staticRoot || "");
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error(`No existe la carpeta dist de la aplicación: ${root}`);
  }
  const rootReal = fs.realpathSync(root);
  const backend = new URL(validateBackendUrl(backendUrl, { allowedHosts: backendAllowedHosts }));
  const transport = backend.protocol === "https:" ? https : http;
  let server;

  async function serveStatic(request, response, pathname) {
    if (!["GET", "HEAD"].includes(request.method)) {
      writeError(response, 405, "Método no permitido");
      return;
    }
    let target = safeStaticPath(root, pathname);
    if (!target) {
      writeError(response, 400, "Ruta no válida");
      return;
    }
    try {
      if (fs.existsSync(target) && fs.statSync(target).isDirectory()) target = path.join(target, "index.html");
      if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
        if (path.extname(pathname)) {
          writeError(response, 404, "Recurso no encontrado");
          return;
        }
        target = path.join(root, "index.html");
      }
      const realTarget = fs.realpathSync(target);
      const targetRelative = path.relative(rootReal, realTarget);
      if (targetRelative.startsWith("..") || path.isAbsolute(targetRelative)) {
        writeError(response, 400, "Ruta no válida");
        return;
      }
      const stat = fs.statSync(realTarget);
      response.writeHead(200, {
        ...securityHeaders(mimeType(target)),
        "content-length": stat.size
      });
      if (request.method === "HEAD") {
        response.end();
        return;
      }
      fs.createReadStream(realTarget).on("error", () => response.destroy()).pipe(response);
    } catch {
      writeError(response, 404, "Recurso no encontrado");
    }
  }

  function proxy(request, response, requestUrl) {
    if (requestSizeExceeded(request, maxRequestBytes)) {
      writeError(response, 413, "La solicitud supera el límite permitido");
      request.resume();
      return;
    }
    const headers = { ...request.headers };
    for (const header of HOP_BY_HOP_HEADERS) delete headers[header];
    headers.host = backend.host;
    headers.origin = backend.origin;
    delete headers.referer;
    const targetPath = `${backend.pathname.replace(/\/$/, "")}${requestUrl.pathname}${requestUrl.search}`;
    const upstream = transport.request({
      protocol: backend.protocol,
      hostname: backend.hostname,
      port: backend.port || undefined,
      method: request.method,
      path: targetPath,
      headers,
      timeout: timeoutMs,
      rejectUnauthorized: backend.protocol === "https:"
    }, (upstreamResponse) => {
      const responseHeaders = {};
      for (const [header, value] of Object.entries(upstreamResponse.headers)) {
        if (!HOP_BY_HOP_HEADERS.has(header)) responseHeaders[header] = value;
      }
      Object.assign(responseHeaders, securityHeaders(responseHeaders["content-type"] || "application/octet-stream"));
      response.writeHead(upstreamResponse.statusCode || 502, responseHeaders);
      upstreamResponse.pipe(response);
    });
    upstream.on("timeout", () => upstream.destroy(new Error("Backend timeout")));
    upstream.on("error", () => writeError(response, 502, "No se pudo conectar con el backend"));
    request.on("aborted", () => upstream.destroy());
    let bytes = 0;
    request.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > maxRequestBytes) {
        request.destroy();
        upstream.destroy();
      }
    });
    request.pipe(upstream);
  }

  server = http.createServer((request, response) => {
    response.setTimeout(timeoutMs, () => response.destroy());
    if (/%2e|%5c/i.test(request.url || "")) {
      writeError(response, 400, "Ruta no válida");
      return;
    }
    let requestUrl;
    try {
      requestUrl = new URL(request.url || "/", "http://127.0.0.1");
    } catch {
      writeError(response, 400, "URL no válida");
      return;
    }
    if (requestUrl.pathname === "/api/v1" || requestUrl.pathname.startsWith("/api/v1/")) {
      proxy(request, response, requestUrl);
      return;
    }
    void serveStatic(request, response, requestUrl.pathname);
  });
  server.requestTimeout = timeoutMs;
  server.headersTimeout = Math.min(timeoutMs, 30_000);

  return {
    server,
    async start() {
      await new Promise((resolve, reject) => {
        const onError = (error) => { server.off("listening", onListening); reject(error); };
        const onListening = () => { server.off("error", onError); resolve(); };
        server.once("error", onError);
        server.once("listening", onListening);
        server.listen(0, "127.0.0.1");
      });
      const address = server.address();
      return `http://127.0.0.1:${address.port}`;
    },
    async close() {
      if (!server.listening) return;
      await new Promise((resolve) => server.close(() => resolve()));
    }
  };
}

module.exports = { createDesktopServer, mimeType, safeStaticPath };
