const LOOPBACK_HOSTNAMES = new Set(["127.0.0.1", "localhost", "::1"]);

function originFromFrame(frame) {
  const candidate = frame?.url || frame?.origin || "";
  try {
    return new URL(candidate).origin;
  } catch {
    return "";
  }
}

function isLocalTrustedOrigin(value) {
  let parsed;
  try {
    parsed = new URL(String(value || ""));
  } catch {
    return false;
  }
  return parsed.protocol === "http:"
    && LOOPBACK_HOSTNAMES.has(parsed.hostname.toLowerCase())
    && !parsed.username
    && !parsed.password
    && !parsed.search
    && !parsed.hash;
}

function isAuthorizedIpcEvent(event, expectedWindow, trustedOriginValue) {
  if (!isLocalTrustedOrigin(trustedOriginValue)) return false;
  const authorization = typeof expectedWindow === "function" ? expectedWindow() : expectedWindow;
  const predicate = authorization && typeof authorization === "object"
    ? authorization.predicate
    : null;
  const windows = resolveExpectedWindows(authorization);
  return windows.some((candidate) => {
    if (!candidate || candidate.isDestroyed?.()) return false;
    if (typeof predicate === "function" && !predicate(candidate, event)) return false;
    const webContents = candidate.webContents;
    if (!webContents || event?.sender !== webContents) return false;
    if (!event?.senderFrame || event.senderFrame !== webContents.mainFrame) return false;
    return originFromFrame(event.senderFrame) === trustedOriginValue;
  });
}

function resolveExpectedWindows(expectedWindow) {
  const resolved = typeof expectedWindow === "function" ? expectedWindow() : expectedWindow;
  if (Array.isArray(resolved)) return resolved;
  if (resolved instanceof Set) return [...resolved];
  if (resolved && typeof resolved === "object" && Array.isArray(resolved.windows)) {
    return resolved.windows;
  }
  return resolved ? [resolved] : [];
}

function unauthorizedIpcResult() {
  return {
    ok: false,
    code: "IPC_UNAUTHORIZED",
    message: "Solicitud IPC no autorizada"
  };
}

function createPrivilegedIpcRegistrar({ ipcMain, getTrustedOrigin }) {
  if (!ipcMain?.handle) throw new Error("ipcMain.handle es obligatorio");
  return function registerPrivilegedHandler(channel, expectedWindow, handler) {
    if (typeof channel !== "string" || !channel) throw new Error("Canal IPC inválido");
    if (typeof handler !== "function") throw new Error(`Handler IPC inválido: ${channel}`);
    ipcMain.handle(channel, async (event, ...args) => {
      if (!isAuthorizedIpcEvent(event, expectedWindow, getTrustedOrigin())) return unauthorizedIpcResult();
      return handler(event, ...args);
    });
  };
}

module.exports = {
  LOOPBACK_HOSTNAMES,
  originFromFrame,
  isLocalTrustedOrigin,
  isAuthorizedIpcEvent,
  resolveExpectedWindows,
  createPrivilegedIpcRegistrar,
  unauthorizedIpcResult
};
