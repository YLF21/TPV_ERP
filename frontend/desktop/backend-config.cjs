const fs = require("node:fs");
const path = require("node:path");

const DEFAULT_BACKEND_URL = "http://127.0.0.1:8080";
const MAX_CONFIG_FILE_BYTES = 8 * 1024;
const LOOPBACK_HOSTNAMES = new Set(["127.0.0.1", "localhost", "::1"]);
const PRODUCTION_CONFIG_RELATIVE_PATH = path.win32.join("TPV ERP", "desktop", "backend-config.json");

function productionBackendConfigPath(programData = "C:\\ProgramData") {
  return path.win32.join(programData, PRODUCTION_CONFIG_RELATIVE_PATH);
}

function assertRegularConfigPath(configPath) {
  const resolved = path.resolve(configPath);
  let current = resolved;
  while (true) {
    let stat;
    try {
      stat = fs.lstatSync(current);
    } catch (error) {
      if (error?.code === "ENOENT") {
        current = path.dirname(current);
        if (current === path.dirname(current)) break;
        continue;
      }
      throw new Error("La configuración del backend no es válida");
    }
    if (stat.isSymbolicLink() || (stat.isDirectory() && current === resolved) || (!stat.isDirectory() && current !== resolved) || (current === resolved && !stat.isFile())) {
      throw new Error("La configuración del backend debe ser un fichero regular sin enlace simbólico");
    }
    if (current === path.dirname(current)) break;
    current = path.dirname(current);
  }
}

function normalizeAllowedHosts(value) {
  if (Array.isArray(value)) return value;
  if (typeof value === "string") return value.split(",");
  return [];
}

function normalizedHost(value) {
  return String(value || "").trim().toLowerCase().replace(/^\[|\]$/g, "");
}

function isLoopbackHostname(hostname) {
  return LOOPBACK_HOSTNAMES.has(normalizedHost(hostname));
}

function validateBackendUrl(value, { allowedHosts = [] } = {}) {
  const candidate = String(value || "").trim();
  if (!candidate) throw new Error("La URL del backend no puede estar vacía");
  let parsed;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error("La URL del backend no es válida");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("El backend solo puede usar HTTP o HTTPS");
  }
  if (!parsed.hostname || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error("La URL del backend no puede incluir credenciales, consulta ni fragmento");
  }
  if (parsed.pathname !== "/" && parsed.pathname !== "") {
    throw new Error("La URL del backend debe apuntar a su origen, sin ruta adicional");
  }
  if (parsed.protocol === "http:" && !isLoopbackHostname(parsed.hostname)) {
    throw new Error("El backend remoto debe usar HTTPS");
  }
  if (parsed.protocol === "https:" && !isLoopbackHostname(parsed.hostname)) {
    const allowlist = normalizeAllowedHosts(allowedHosts).map(normalizedHost).filter(Boolean);
    if (!allowlist.includes(normalizedHost(parsed.hostname))) {
      throw new Error("El host del backend remoto no está en la allowlist configurada");
    }
  }
  parsed.pathname = "";
  return parsed.toString().replace(/\/$/, "");
}

function readBackendConfig(configPath) {
  if (!configPath) return { backendUrl: null, allowedHosts: [] };
  let stat;
  try {
    stat = fs.lstatSync(configPath);
  } catch (error) {
    if (error?.code === "ENOENT") return { backendUrl: null, allowedHosts: [] };
    throw new Error("La configuración del backend no es válida");
  }
  assertRegularConfigPath(configPath);
  if (!stat.isFile() || stat.size > MAX_CONFIG_FILE_BYTES) {
    throw new Error("La configuración del backend no es válida");
  }
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(configPath, "utf8"));
  } catch {
    throw new Error("La configuración del backend no contiene JSON válido");
  }
  return {
    backendUrl: parsed?.backendUrl ?? parsed?.url ?? null,
    allowedHosts: normalizeAllowedHosts(parsed?.allowedHosts ?? parsed?.allowlist)
  };
}

function resolveBackendUrl({
  envValue,
  configPath,
  defaultUrl = DEFAULT_BACKEND_URL,
  allowedHosts,
  envAllowedHosts,
  useEnvironment = true
} = {}) {
  return resolveBackendConfig({ envValue, configPath, defaultUrl, allowedHosts, envAllowedHosts, useEnvironment }).backendUrl;
}

function resolveBackendConfig({
  envValue,
  configPath,
  defaultUrl = DEFAULT_BACKEND_URL,
  allowedHosts,
  envAllowedHosts,
  useEnvironment = true
} = {}) {
  const config = readBackendConfig(configPath);
  const configured = envValue ?? config.backendUrl;
  const configuredAllowlist = allowedHosts
    ?? envAllowedHosts
    ?? (useEnvironment ? process.env.TPV_DESKTOP_BACKEND_ALLOWED_HOSTS : undefined)
    ?? config.allowedHosts;
  return {
    backendUrl: validateBackendUrl(configured || defaultUrl, { allowedHosts: configuredAllowlist }),
    allowedHosts: normalizeAllowedHosts(configuredAllowlist).map(normalizedHost).filter(Boolean)
  };
}

module.exports = {
  DEFAULT_BACKEND_URL,
  validateBackendUrl,
  resolveBackendUrl,
  resolveBackendConfig,
  readBackendConfig,
  productionBackendConfigPath,
  isLoopbackHostname,
  normalizeAllowedHosts
};
