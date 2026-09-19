const dns = require("node:dns/promises");
const net = require("node:net");
const os = require("node:os");

const BACKEND_ADDRESS_PATH = "/__tpv/backend-address";

function normalizeAddress(value) {
  let address = String(value || "").toLowerCase().replace(/^\[|\]$/g, "").split("%")[0];
  if (net.isIP(address) === 6) {
    address = new URL(`http://[${address}]`).hostname.slice(1, -1);
    const mapped = address.match(/^::ffff:([\da-f]+):([\da-f]+)$/);
    if (mapped) {
      const high = parseInt(mapped[1], 16);
      const low = parseInt(mapped[2], 16);
      address = `${high >> 8}.${high & 255}.${low >> 8}.${low & 255}`;
    }
  }
  return net.isIP(address) ? address : null;
}

function isLoopback(address) {
  return address === "::1" || address?.startsWith("127.");
}

function createBackendAddressResolver(backendUrl, {
  lookup = dns.lookup,
  networkInterfaces = os.networkInterfaces,
  directConnection = false,
  timeoutMs = 1500,
  cacheMs = 15_000,
  now = Date.now
} = {}) {
  let hostname;
  try {
    const parsed = new URL(backendUrl);
    if (["http:", "https:"].includes(parsed.protocol)) {
      hostname = parsed.hostname.replace(/^\[|\]$/g, "");
    }
  } catch {
    // No configured target is preferable to displaying the renderer's address.
  }
  let cachedAddress;
  let expiresAt = 0;
  let pending;

  async function resolveAddress() {
    if (!hostname) return null;
    const literal = normalizeAddress(hostname);
    if (literal) return literal;
    if (expiresAt > now()) return cachedAddress;
    if (pending) return pending;
    pending = (async () => {
      let timeout;
      try {
        const result = await Promise.race([
          Promise.resolve().then(() => lookup(hostname)),
          new Promise((resolve) => { timeout = setTimeout(() => resolve(null), timeoutMs); })
        ]);
        cachedAddress = normalizeAddress(result?.address);
      } catch {
        cachedAddress = null;
      } finally {
        clearTimeout(timeout);
        expiresAt = now() + cacheMs;
        pending = undefined;
      }
      return cachedAddress;
    })();
    return pending;
  }

  return async function backendAddress(clientAddress, serverAddress) {
    const target = await resolveAddress();
    if (!target) return { backendLabel: null };
    // An absolute renderer API URL addresses loopback on the browser's own PC.
    if (directConnection && isLoopback(target)) return { backendLabel: "LOCAL" };
    const interfaces = new Set(Object.values(networkInterfaces())
      .flatMap((entries) => entries || [])
      .map((entry) => normalizeAddress(entry.address))
      .filter(Boolean));
    const client = normalizeAddress(clientAddress);
    const server = normalizeAddress(serverAddress);
    const clientIsLocal = isLoopback(client) || interfaces.has(client);
    const targetIsLocal = isLoopback(target) || interfaces.has(target);
    if ((clientIsLocal && targetIsLocal) || target === client) {
      return { backendLabel: "LOCAL" };
    }
    // A remote browser reaches a loopback backend through the dev server. Show
    // that server's LAN address, never imply the backend runs on the browser PC.
    if (isLoopback(target)) {
      return { backendLabel: server && !isLoopback(server) ? server : null };
    }
    return { backendLabel: target };
  };
}

module.exports = { BACKEND_ADDRESS_PATH, createBackendAddressResolver };
