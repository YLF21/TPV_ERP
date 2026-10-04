const crypto = require('node:crypto');
const os = require('node:os');
const { validateBackendUrl, isLoopbackHostname } = require('./backend-config.cjs');

const SIGNING_PREFIX = 'TPV-TERMINAL-LINKING-V1';
const STATUSES = new Set(['PENDING', 'ACTIVE', 'DISABLED', 'RELEASED', 'CANCELLED', 'EXPIRED']);
const DEFINITIVE_REQUEST_REJECTIONS = new Set([
  'WORKSTATION_OCCUPIED', 'WORKSTATION_QUOTA_REACHED', 'WORKSTATION_OUT_OF_QUOTA',
  'TERMINAL_NAME_EXISTS', 'DEVICE_ALREADY_BOUND'
]);

function error(code, message) { const result = new Error(message); result.code = code; return result; }
function candidateUrl(value) {
  if (typeof value !== 'string' || value.length > 512) throw error('INVALID_ADDRESS', 'Dirección del backend no válida');
  let parsed;
  try { parsed = new URL(value.trim()); } catch { throw error('INVALID_ADDRESS', 'Dirección del backend no válida'); }
  if (parsed.hostname.toLowerCase() === 'localhost') parsed.hostname = '127.0.0.1';
  return validateBackendUrl(parsed.toString(), { allowedHosts: [parsed.hostname] });
}
function strictString(value, name, max = 100) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\x00-\x1f\x7f]/.test(value)) {
    throw error('INVALID_INPUT', `${name} no válido`);
  }
  return value.trim();
}
function digestKey(publicKey) { return crypto.createHash('sha256').update(Buffer.from(publicKey, 'base64')).digest('hex'); }
function verifyBootstrap(server, challenge) {
  if (!server || server.protocolVersion !== 1 || server.challenge !== challenge) throw error('INVALID_BOOTSTRAP', 'Respuesta de instalación no válida');
  const installationId = strictString(server.installationId, 'installationId', 100);
  if (typeof server.publicKey !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(server.publicKey) || server.publicKey.length > 8192
      || typeof server.signature !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(server.signature)) {
    throw error('INVALID_BOOTSTRAP', 'Firma de instalación no válida');
  }
  try {
    const der = Buffer.from(server.publicKey, 'base64');
    const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    if (key.asymmetricKeyType !== 'rsa' || (key.asymmetricKeyDetails?.modulusLength ?? 0) < 2048) throw new Error('RSA débil');
    const payload = `${SIGNING_PREFIX}\n${challenge}\n${installationId}`;
    if (!crypto.verify('RSA-SHA256', Buffer.from(payload, 'utf8'), key, Buffer.from(server.signature, 'base64'))) throw new Error('Firma incorrecta');
    return { ...server, installationId, keyFingerprint: digestKey(server.publicKey) };
  } catch { throw error('INVALID_BOOTSTRAP', 'No se pudo verificar la firma de la instalación'); }
}
function verifyPin(server, known) {
  if (!known?.installationId) return false;
  if (known.installationId !== server.installationId) return false;
  if (known.keyFingerprint && known.keyFingerprint !== server.keyFingerprint) throw error('INSTALLATION_KEY_CHANGED', 'La clave de la instalación ha cambiado');
  return true;
}
function validateLink(link, expected) {
  if (!link || link.requestId !== expected.requestId || link.installationId !== expected.installationId || !STATUSES.has(link.status)) {
    throw error('INVALID_LINK_RESPONSE', 'Estado de vinculación no válido');
  }
  if (link.terminalCode && link.terminalCode !== expected.code) throw error('INVALID_LINK_RESPONSE', 'Código de terminal inesperado');
  return link;
}
async function readTextLimited(response, maxBytes = 65536) {
  if (!response.body?.getReader) {
    const value = await response.text();
    if (Buffer.byteLength(value) > maxBytes) throw error('INVALID_RESPONSE', 'Respuesta del backend demasiado grande');
    return value;
  }
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maxBytes) { await reader.cancel(); throw error('INVALID_RESPONSE', 'Respuesta del backend demasiado grande'); }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks).toString('utf8');
}
function createTerminalLinking({ request = fetch, storage, config, discover = async () => [], deviceName = os.hostname(), restart = () => {}, getRuntimeBackendUrl = () => null, getRuntimeBindingId = () => null, getLegacyBackendScope = getRuntimeBackendUrl }) {
  if (!storage || !config) throw new Error('Storage y config obligatorios');
  async function api(url, method, route, body, token) {
    const response = await request(`${url}/api/v1${route}`, {
      method, redirect: 'manual', signal: AbortSignal.timeout(8000),
      headers: { accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    if (!response.ok) {
      let code = `HTTP_${response.status}`;
      try {
        const problem = JSON.parse(await readTextLimited(response, 8192));
        if (typeof problem?.code === 'string' && /^[A-Z][A-Z0-9_]{2,80}$/.test(problem.code)) code = problem.code;
      } catch {}
      const failed = error(code, `El backend rechazó la solicitud (${code})`);
      failed.httpStatus = response.status;
      throw failed;
    }
    if (!(response.headers.get('content-type') || '').toLowerCase().includes('application/json')) throw error('INVALID_RESPONSE', 'Respuesta del backend no válida');
    const text = await readTextLimited(response);
    try { return JSON.parse(text); } catch { throw error('INVALID_RESPONSE', 'JSON del backend no válido'); }
  }
  function known() { return storage.read()?.pending || storage.read()?.identity || null; }
  function linkedIdentity(state) {
    const identity = state?.identity;
    if (!identity?.installationId) return null;
    return { installationId: identity.installationId, bindingId: identity.bindingId,
      terminalId: identity.terminalId, terminalCode: identity.terminalCode,
      ...(identity.terminalName ? { terminalName: identity.terminalName } : {}), storeName: identity.storeName };
  }
  function restartRequired() {
    const configured = config.read().configuration?.backendUrl;
    const runtime = getRuntimeBackendUrl();
    const activeBinding = storage.read()?.identity?.bindingId;
    return Boolean(configured && runtime && configured !== runtime)
      || Boolean(activeBinding && activeBinding !== getRuntimeBindingId());
  }
  async function probeInternal(rawUrl, requireKnown = false) {
    const backendUrl = candidateUrl(rawUrl);
    const challenge = crypto.randomBytes(32).toString('base64url');
    const server = verifyBootstrap(await api(backendUrl, 'GET', `/terminal-linking/bootstrap?challenge=${encodeURIComponent(challenge)}`), challenge);
    const pinned = known();
    const sameInstallation = verifyPin(server, pinned);
    if (requireKnown && !sameInstallation) throw error('NOT_LINKED', 'No hay una vinculación con esta instalación');
    const parsed = new URL(backendUrl);
    return { backendUrl, server, sameInstallation, localServer: isLoopbackHostname(parsed.hostname) };
  }
  async function load() {
    let state = storage.read();
    const current = config.read();
    let verifiedIdentity = null;
    if (state?.identity?.installationId && state?.link?.requestId
        && current.configuration?.backendUrl) {
      try {
        await probeInternal(current.configuration.backendUrl, true);
        const refreshed = validateLink(await api(current.configuration.backendUrl, 'POST', '/terminal-linking/requests/status',
          { requestId: state.link.requestId, credential: state.identity.terminalCredential }),
          { requestId: state.link.requestId, installationId: state.identity.installationId, code: state.identity.terminalCode });
        state = { ...state, link: refreshed };
        if (refreshed.status === 'ACTIVE' && !restartRequired()) verifiedIdentity = state.identity;
      } catch {
        // A cached ACTIVE state is not evidence of current approval while offline.
        state = { ...state, link: null };
      }
    }
    return { configuration: current.configuration && { ...current.configuration,
      ...(known()?.installationId ? { installationId: known().installationId } : {}) }, configurationError: current.error,
      link: state?.link || null, identity: verifiedIdentity, linkedIdentity: linkedIdentity(state),
      deviceName, restartRequired: restartRequired(),
      ...(state?.identity && !state.identity.installationId ? { legacyIdentity: {
        terminalId: state.identity.terminalId, terminalCode: state.identity.terminalCode,
        storeName: state.identity.storeName
      } } : {}),
      ...(state?.pending ? { pendingRequest: { code: state.pending.code, name: state.pending.name,
        backendUrl: state.pending.backendUrl, mode: state.pending.mode } } : {}) };
  }
  function persistLink(current, pending, link) {
    let identity = current.identity || null;
    if (link.status === 'ACTIVE') {
      identity = {
        terminalId: strictString(link.terminalId, 'Terminal', 100), terminalCredential: pending.credential,
        terminalCode: strictString(link.terminalCode, 'Código', 9), terminalName: link.terminalName || pending.name,
        storeId: strictString(link.storeId, 'Tienda', 100), storeName: strictString(link.storeName, 'Tienda', 100),
        bindingId: strictString(link.bindingId, 'Vínculo', 100),
        installationId: pending.installationId, keyFingerprint: pending.keyFingerprint, deviceId: pending.deviceId,
        ...(pending.legacyBackendScope ? { legacyBackendScope: pending.legacyBackendScope,
          legacyTerminalCode: pending.legacyTerminalCode } : {})
      };
    }
    const finished = ['ACTIVE', 'CANCELLED', 'EXPIRED', 'RELEASED'].includes(link.status);
    storage.write({ ...current, link, identity, pending: finished ? null : pending });
    const needsRestart = restartRequired();
    return { link, ...(identity && link.status === 'ACTIVE' && !needsRestart ? { identity } : {}), restartRequired: needsRestart };
  }
  async function requestLink({ backendUrl, code, name, administrator } = {}) {
    const terminalCode = strictString(code, 'Código', 9);
    if (!/^\d{3,9}$/.test(terminalCode) || Number(terminalCode) < 1
        || String(Number(terminalCode)).padStart(3, '0') !== terminalCode) throw error('INVALID_INPUT', 'Código no válido');
    const terminalName = strictString(name, 'Nombre', 80);
    const current = storage.read() || {};
    const retry = current.pending;
    if (retry && (retry.code !== terminalCode || retry.name !== terminalName || retry.backendUrl !== candidateUrl(backendUrl))) {
      throw error('LINK_PENDING', 'Ya existe una solicitud pendiente');
    }
    const checked = await probeInternal(backendUrl);
    if (checked.localServer && terminalCode !== '001') throw error('SERVER_SLOT_PROTECTED', 'El PC backend utiliza el código 001');
    if (current.identity?.bindingId && checked.sameInstallation) {
      if (!current.link?.requestId) throw error('ALREADY_LINKED', 'El equipo ya está vinculado');
      // Another app may have completed this computer's link while this wizard was open.
      // Verify and reuse its proof for every terminal code; never submit a second adoption.
      const link = validateLink(await api(checked.backendUrl, 'POST', '/terminal-linking/requests/status', {
        requestId: current.link.requestId, credential: current.identity.terminalCredential
      }), { requestId: current.link.requestId, installationId: current.identity.installationId,
        code: current.identity.terminalCode });
      const needsRestart = restartRequired();
      if (link.status !== 'RELEASED' || terminalCode === '001') {
        return { link, linkedIdentity: linkedIdentity(current),
          ...(link.status === 'ACTIVE' && !needsRestart ? { identity: current.identity } : {}), restartRequired: needsRestart };
      }
      // An explicitly released remote workstation can request a new approved binding.
    }
    const legacyServer = terminalCode === '001' && checked.localServer && current.identity?.terminalId
      && !current.identity?.installationId;
    const legacyPos = terminalCode !== '001' && current.identity?.terminalId && !current.identity?.installationId;
    const originalUrl = getRuntimeBackendUrl() || config.read().configuration?.backendUrl;
    if (legacyPos && (!originalUrl || checked.backendUrl !== originalUrl)) {
      throw error('LEGACY_ADOPT_ORIGINAL_ADDRESS', 'Adopte el terminal en su dirección anterior antes de cambiarla');
    }
    if (current.identity && !checked.sameInstallation && !legacyServer && !legacyPos) throw error('OTHER_INSTALLATION', 'Esta PC ya pertenece a otra instalación');
    if (terminalCode === '001' && !checked.localServer) throw error('SERVER_LOCAL_ONLY', 'El terminal 001 debe configurarse en el PC backend');
    if (terminalCode === '001' && current.identity && !['001', 'SERVIDOR'].includes(current.identity.terminalCode)) {
      throw error('INVALID_IDENTITY', 'La identidad actual no corresponde al terminal servidor');
    }
    if (terminalCode === '001' && !current.identity && (!administrator || typeof (administrator.username || administrator.userName) !== 'string' || typeof administrator.password !== 'string')) {
      throw error('SERVER_AUTH_REQUIRED', 'Se requiere administrador para el terminal 001');
    }
    const previousLegacyScope = (legacyServer || legacyPos) ? getLegacyBackendScope() || originalUrl : null;
    await config.commit(checked.backendUrl);
    let adminToken;
    if (terminalCode === '001' && !current.identity) {
      const userName = strictString(administrator.username || administrator.userName, 'Administrador', 100);
      const password = administrator.password;
      if (typeof password !== 'string' || password.length === 0 || password.length > 500) {
        throw error('INVALID_INPUT', 'Contraseña no válida');
      }
      const login = await api(checked.backendUrl, 'POST', '/auth/installation-login', { userName, password });
      adminToken = strictString(login.accessToken, 'Token', 4096);
    }
    const pending = retry || {
      requestId: crypto.randomUUID(), deviceId: current.deviceId || crypto.randomUUID(),
      credential: (terminalCode === '001' || legacyPos) && current.identity
        ? current.identity.terminalCredential : crypto.randomBytes(32).toString('base64url'), code: terminalCode, name: terminalName,
      deviceName: strictString(deviceName, 'Equipo', 100), backendUrl: checked.backendUrl,
      installationId: checked.server.installationId, keyFingerprint: checked.server.keyFingerprint,
      mode: terminalCode === '001' ? (current.identity ? 'SERVER_EXISTING' : 'SERVER_ADMIN') : legacyPos ? 'LEGACY_POS' : 'WORKSTATION',
      ...((legacyServer || legacyPos) ? { legacyBackendScope: previousLegacyScope,
        legacyTerminalCode: current.identity.terminalCode } : {})
    };
    if (!pending.credential) throw error('INVALID_IDENTITY', 'La identidad local carece de credencial');
    if (!retry) storage.write({ ...current, deviceId: pending.deviceId, pending }); // durable before HTTP
    let link;
    try {
      if (terminalCode === '001' && current.identity) {
        link = await api(checked.backendUrl, 'POST', '/terminal-linking/server/adopt', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          deviceName: pending.deviceName, terminalId: current.identity.terminalId, name: pending.name
        });
      } else if (terminalCode === '001') {
        link = await api(checked.backendUrl, 'POST', '/terminals/workstations/server/adopt', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential, deviceName: pending.deviceName,
          name: pending.name
        }, adminToken);
      } else if (legacyPos) {
        link = await api(checked.backendUrl, 'POST', '/terminal-linking/legacy/adopt', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          deviceName: pending.deviceName, terminalId: current.identity.terminalId, name: pending.name
        });
      } else {
        link = await api(checked.backendUrl, 'POST', '/terminal-linking/requests', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          code: pending.code, name: pending.name, deviceName: pending.deviceName
        });
      }
    } catch (failed) {
      if (DEFINITIVE_REQUEST_REJECTIONS.has(failed?.code)) {
        const saved = storage.read();
        if (saved?.pending?.requestId === pending.requestId)
          storage.write({ ...saved, pending: null, link: null });
      }
      throw failed;
    }
    link = validateLink(link, pending);
    return persistLink(storage.read(), pending, link);
  }
  async function updatePending(route) {
    const current = storage.read();
    const pending = current?.pending;
    if (!pending) {
      if (route.endsWith('/status') && current?.link?.requestId && current.identity) {
        const currentUrl = config.read().configuration?.backendUrl;
        if (!currentUrl) throw error('CONFIGURATION_REQUIRED', 'No hay dirección de backend configurada');
        await probeInternal(currentUrl, true);
        const link = validateLink(await api(currentUrl, 'POST', route,
          { requestId: current.link.requestId, credential: current.identity.terminalCredential }),
          { requestId: current.link.requestId, installationId: current.identity.installationId,
            code: current.identity.terminalCode });
        storage.write({ ...current, link });
        const needsRestart = restartRequired();
        return { link, linkedIdentity: linkedIdentity(current),
          ...(link.status === 'ACTIVE' && !needsRestart ? { identity: current.identity } : {}),
          restartRequired: needsRestart };
      }
      throw error('NO_PENDING_LINK', 'No hay solicitud pendiente');
    }
    await probeInternal(pending.backendUrl, true);
    let link;
    try {
      link = validateLink(await api(pending.backendUrl, 'POST', route,
        { requestId: pending.requestId, credential: pending.credential }), pending);
    } catch (failed) {
      if (failed?.httpStatus !== 403) throw failed;
      if (route.endsWith('/cancel')) {
        storage.write({ ...current, pending: null, link: null });
        return { link: null, localOnly: true };
      }
      if (pending.mode === 'SERVER_ADMIN') {
        throw error('SERVER_AUTH_REQUIRED', 'Introduzca de nuevo el administrador para reintentar la solicitud');
      }
      let repeated;
      try { repeated = pending.mode === 'SERVER_EXISTING'
        ? await api(pending.backendUrl, 'POST', '/terminal-linking/server/adopt', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          deviceName: pending.deviceName, terminalId: current.identity?.terminalId, name: pending.name
        })
        : pending.mode === 'LEGACY_POS'
        ? await api(pending.backendUrl, 'POST', '/terminal-linking/legacy/adopt', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          deviceName: pending.deviceName, terminalId: current.identity?.terminalId, name: pending.name
        })
        : await api(pending.backendUrl, 'POST', '/terminal-linking/requests', {
          requestId: pending.requestId, deviceId: pending.deviceId, credential: pending.credential,
          code: pending.code, name: pending.name, deviceName: pending.deviceName
        });
      } catch (retryFailed) {
        if (DEFINITIVE_REQUEST_REJECTIONS.has(retryFailed?.code))
          storage.write({ ...current, pending: null, link: null });
        throw retryFailed;
      }
      link = validateLink(repeated, pending);
      if (route.endsWith('/cancel') && link.status === 'PENDING') {
        link = validateLink(await api(pending.backendUrl, 'POST', route,
          { requestId: pending.requestId, credential: pending.credential }), pending);
      }
    }
    return persistLink(current, pending, link);
  }
  async function saveAddress({ backendUrl } = {}) {
    const checked = await probeInternal(backendUrl, true);
    await config.commit(checked.backendUrl);
    const state = storage.read();
    if (state?.pending) storage.write({ ...state, pending: { ...state.pending, backendUrl: checked.backendUrl } });
    return { restartRequired: true };
  }
  const mutate = (work) => storage.withLock ? storage.withLock(work) : work();
  return {
    load, discover: async () => ({ servers: await discover() }),
    probe: async ({ backendUrl } = {}) => { const { server, sameInstallation, localServer } = await probeInternal(backendUrl); return { server, sameInstallation, localServer }; },
    requestLink: (input) => mutate(() => requestLink(input)),
    refreshLink: () => mutate(() => updatePending('/terminal-linking/requests/status')),
    cancelLink: () => mutate(() => updatePending('/terminal-linking/requests/cancel')),
    saveAddress: (input) => mutate(() => saveAddress(input)),
    restart: async () => { await restart(); return {}; }
  };
}
module.exports = { createTerminalLinking, verifyBootstrap, verifyPin, candidateUrl, readTextLimited, error };
