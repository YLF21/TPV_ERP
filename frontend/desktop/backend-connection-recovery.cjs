const crypto = require('node:crypto');
const dns = require('node:dns/promises');
const net = require('node:net');
const { verifyBootstrap, readTextLimited } = require('./terminal-linking.cjs');
const { createFixedBackendTransport } = require('./fixed-backend-transport.cjs');
const { isLoopbackHostname } = require('./backend-config.cjs');

function isPrivateAddress(ip) {
  if (net.isIP(ip) !== 4) return false;
  const parts = ip.split('.').map(Number);
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
    || (parts[0] === 192 && parts[1] === 168);
}
function sameBinding(a, b) {
  return ['installationId', 'storeId', 'terminalId', 'bindingId', 'terminalCode', 'keyFingerprint', 'terminalCredential']
    .every(key => a?.[key] && a[key] === b?.[key]);
}
function createBackendConnectionRecovery({ backendUrl, storage, discover,
  lookup = hostname => dns.lookup(hostname, { family: 4 }),
  createTransport = createFixedBackendTransport, allowCandidate = isPrivateAddress,
  onStatus = () => {}, timeoutMs = 3000, startupTimeoutMs = 15000, monitorMs = 30000 } = {}) {
  const original = new URL(backendUrl); const hostname = original.hostname.replace(/^\[|\]$/g, '');
  const initial = storage.read(); const identity = initial?.identity;
  const eligible = Boolean(sameBinding(identity, identity) && initial?.link?.requestId
    && initial.link.status === 'ACTIVE');
  let selected; let state = 'CHECKING'; let errorCode; let pending; let interval;
  let startupFinished = false; let startupDeadline;
  function status() { return { ok: true, state, ...(selected ? { backendIp: selected.connectIp } : {}), ...(errorCode ? { errorCode } : {}) }; }
  function publish(next, code) { state = next; errorCode = code; onStatus(status()); return status(); }
  async function json(transport, route, body) {
    const response = await transport.request(`${original.origin}/api/v1${route}`, {
      method: body ? 'POST' : 'GET', signal: AbortSignal.timeout(Math.max(1, Math.min(timeoutMs,
        startupDeadline ? startupDeadline - Date.now() : timeoutMs))),
      headers: { accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    if (!response.ok) throw Object.assign(new Error(`HTTP_${response.status}`), { code: `HTTP_${response.status}`, httpStatus: response.status });
    if (!(response.headers.get('content-type') || '').includes('application/json')) throw new Error('INVALID_RESPONSE');
    return JSON.parse(await readTextLimited(response));
  }
  async function verify(transport) {
    assertBinding();
    const challenge = crypto.randomBytes(32).toString('base64url');
    const server = verifyBootstrap(await json(transport, `/terminal-linking/bootstrap?challenge=${challenge}`), challenge);
    if (server.installationId !== identity.installationId || server.keyFingerprint !== identity.keyFingerprint) throw new Error('INSTALLATION_IDENTITY_CHANGED');
    const linked = await json(transport, '/terminal-linking/requests/status', {
      requestId: initial.link.requestId, credential: identity.terminalCredential });
    if (linked.status !== 'ACTIVE') throw new Error('TERMINAL_DISABLED');
    if (linked.requestId !== initial.link.requestId || !['installationId', 'storeId', 'terminalId', 'bindingId', 'terminalCode']
      .every(key => linked[key] === identity[key])) throw new Error('BINDING_CHANGED');
    assertBinding();
  }
  function assertBinding() {
    const latest = storage.read();
    if (!sameBinding(identity, latest?.identity) || latest.link?.requestId !== initial.link.requestId
        || latest.link?.status !== 'ACTIVE') throw new Error('BINDING_CHANGED');
  }
  async function remember(ip) {
    await storage.withLock(() => {
      const latest = storage.read();
      if (!sameBinding(identity, latest?.identity) || latest.link?.status !== 'ACTIVE') throw new Error('BINDING_CHANGED');
      storage.write({ ...latest, connectionLocation: { version: 1, authority: original.origin,
        installationId: identity.installationId, bindingId: identity.bindingId, keyFingerprint: identity.keyFingerprint, ip } });
    }, { timeoutMs: Math.max(1, Math.min(1500, startupDeadline - Date.now())) });
  }
  async function startup() {
    if (startupFinished) return status();
    startupFinished = true;
    if (!eligible) return publish('CONNECTED'); // First linking remains under its existing wizard.
    startupDeadline = Date.now() + startupTimeoutMs;
    const addresses = [];
    const cached = initial.connectionLocation;
    if (cached?.version === 1 && cached.authority === original.origin && cached.installationId === identity.installationId
      && cached.bindingId === identity.bindingId && cached.keyFingerprint === identity.keyFingerprint
      && (allowCandidate(cached.ip) || (isLoopbackHostname(hostname) && cached.ip === hostname))) addresses.push(cached.ip);
    try {
      if (net.isIP(hostname)) addresses.push(hostname);
      else {
        let timer;
        const resolved = await Promise.race([lookup(hostname), new Promise(resolve => {
          timer = setTimeout(() => resolve(null), timeoutMs);
        })]).finally(() => clearTimeout(timer));
        if (resolved?.address && net.isIP(resolved.address)) addresses.push(resolved.address);
      }
    } catch {}
    const attempted = new Set(); let lastError;
    async function attempt(ip) {
      if (!net.isIP(ip) || attempted.has(ip) || Date.now() >= startupDeadline) return false;
      attempted.add(ip);
      let candidate;
      try {
        candidate = createTransport({ backendUrl: original.origin, connectIp: ip });
        selected ||= candidate; // The previous concrete location remains the retry target on startup failure.
        await verify(candidate);
        try { await remember(ip); } catch (failed) {
          // A cache write is optional; changed approval/identity must still fail closed.
          assertBinding();
          if (failed.message === 'BINDING_CHANGED') throw failed;
        }
        if (selected !== candidate) selected.close(); selected = candidate;
        publish('CONNECTED'); return true;
      } catch (failed) {
        lastError = failed;
        if (selected !== candidate) candidate?.close();
        return false;
      }
    }
    for (const ip of new Set(addresses)) if (await attempt(ip)) { startupDeadline = undefined; return status(); }
    if (!isLoopbackHostname(hostname)) {
      try {
        let timer;
        const candidates = await Promise.race([discover(), new Promise(resolve => {
          timer = setTimeout(() => resolve([]), Math.max(1, startupDeadline - Date.now()));
        })]).finally(() => clearTimeout(timer));
        for (const candidate of candidates.slice(0, 16)) {
          if (candidate.installationId && candidate.installationId !== identity.installationId) continue;
          let url; try { url = new URL(candidate.backendUrl); } catch { continue; }
          if (url.protocol !== 'https:' || Number(url.port || 443) !== Number(original.port || 443)) continue;
          for (const ip of (candidate.addresses || []).slice(0, 8)) {
            if (allowCandidate(ip) && await attempt(ip)) { startupDeadline = undefined; return status(); }
            if (attempted.size >= 8) break;
          }
          if (attempted.size >= 8) break;
        }
      } catch (failed) { lastError ||= failed; }
    }
    startupDeadline = undefined;
    return publish('OFFLINE', lastError?.code || lastError?.message || 'BACKEND_UNREACHABLE');
  }
  async function retry(interactive = true) {
    if (!eligible) return status();
    if (pending) return pending;
    pending = (async () => {
      if (interactive) publish('CHECKING');
      try { if (!selected) throw new Error('BACKEND_ADDRESS_UNAVAILABLE'); await verify(selected); return publish('CONNECTED'); }
      catch (failed) { return publish('OFFLINE', failed.code || failed.message || 'BACKEND_UNREACHABLE'); }
      finally { pending = undefined; }
    })();
    return pending;
  }
  function transportFailure() { if (eligible) publish('OFFLINE', 'BACKEND_UNREACHABLE'); }
  function startMonitor() {
    if (!eligible || interval) return;
    interval = setInterval(() => { if (state === 'CONNECTED') void retry(false); }, monitorMs);
    interval.unref?.();
  }
  function close() { clearInterval(interval); selected?.close(); }
  return { startup, status, retry, transportFailure, startMonitor, close,
    canProxy: () => !eligible || state === 'CONNECTED',
    getTransport: () => selected,
    request: (url, options) => {
      if (new URL(url).origin === original.origin) {
        if (selected) return selected.request(url, options);
        if (eligible) return Promise.reject(Object.assign(new Error('BACKEND_ADDRESS_UNAVAILABLE'), { code: 'BACKEND_ADDRESS_UNAVAILABLE' }));
      }
      return fetch(url, options); // Explicit first-link/manual wizard targets retain their existing checks.
    } };
}
module.exports = { createBackendConnectionRecovery, sameBinding, isPrivateAddress };
