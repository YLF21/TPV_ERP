import { BACKEND_ADDRESS_PATH, createBackendAddressResolver } from "../desktop/backend-address.cjs";

function configuredBackendUrl(config, proxies) {
  const apiBaseUrl = String(config.env.VITE_TPV_API_BASE_URL ?? "/api/v1");
  if (/^https?:\/\//i.test(apiBaseUrl)) return apiBaseUrl;
  if (!apiBaseUrl.startsWith("/") || apiBaseUrl.startsWith("//")) return undefined;
  const matchingPrefix = Object.keys(proxies || {})
    .filter((prefix) => apiBaseUrl === prefix || apiBaseUrl.startsWith(`${prefix}/`))
    .sort((left, right) => right.length - left.length)[0];
  const proxy = proxies?.[matchingPrefix];
  return typeof proxy === "string" ? proxy : proxy?.target?.toString();
}

export function backendAddressPlugin() {
  function install(server, proxies) {
    const resolveBackendAddress = createBackendAddressResolver(configuredBackendUrl(server.config, proxies), {
      directConnection: /^https?:\/\//i.test(String(server.config.env.VITE_TPV_API_BASE_URL || ""))
    });
    server.middlewares.use((request, response, next) => {
      if (request.url?.split("?")[0] !== BACKEND_ADDRESS_PATH) return next();
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Content-Type", "application/json; charset=utf-8");
      response.setHeader("X-Content-Type-Options", "nosniff");
      if (!["GET", "HEAD"].includes(request.method)) {
        response.statusCode = 405;
        response.end();
        return;
      }
      void resolveBackendAddress(request.socket.remoteAddress, request.socket.localAddress)
        .then((result) => {
          if (!response.destroyed) response.end(request.method === "HEAD" ? undefined : JSON.stringify(result));
        })
        .catch(() => {
          if (response.destroyed) return;
          response.statusCode = 503;
          response.end(JSON.stringify({ backendLabel: null }));
        });
    });
  }
  return {
    name: "tpv-backend-address",
    configureServer(server) { install(server, server.config.server.proxy); },
    configurePreviewServer(server) { install(server, server.config.preview.proxy); }
  };
}
