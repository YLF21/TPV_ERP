const { isLocalTrustedOrigin } = require("./electron-security.cjs");

function parseRendererUrl(value) {
  let parsed;
  try {
    parsed = new URL(String(value || "").trim());
  } catch {
    throw new Error("TPV_DESKTOP_APP_URL debe ser una URL válida");
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error("TPV_DESKTOP_APP_URL debe usar HTTP o HTTPS");
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error("TPV_DESKTOP_APP_URL no puede incluir credenciales, consulta ni fragmento");
  }
  if (parsed.pathname !== "/" && parsed.pathname !== "") {
    throw new Error("TPV_DESKTOP_APP_URL debe apuntar al origen de la aplicación");
  }
  return parsed;
}

function resolveRendererAppUrl({ isPackaged, envValue, localUrl, allowRemoteDevelopment = false } = {}) {
  if (isPackaged) {
    if (!localUrl) throw new Error("El paquete Electron debe iniciar su servidor local");
    const local = parseRendererUrl(localUrl);
    if (!isLocalTrustedOrigin(local.origin)) {
      throw new Error("El renderer empaquetado debe usar un origen loopback local");
    }
    return local.origin;
  }

  const configured = String(envValue || "").trim();
  if (!configured) {
    if (!localUrl) throw new Error("No se ha configurado la URL del renderer");
    return parseRendererUrl(localUrl).origin;
  }
  const parsed = parseRendererUrl(configured);
  if (!isLocalTrustedOrigin(parsed.origin) && !allowRemoteDevelopment) {
    throw new Error("La URL remota del renderer solo está permitida en desarrollo explícito");
  }
  return parsed.origin;
}

module.exports = { parseRendererUrl, resolveRendererAppUrl };
