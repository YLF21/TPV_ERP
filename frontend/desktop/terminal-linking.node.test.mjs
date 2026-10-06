import { test } from 'vitest';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { createTerminalLinking, verifyBootstrap, candidateUrl } = require('./terminal-linking.cjs');

const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const encodedKey = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
function bootstrap(challenge, installationId = 'installation-a') {
  return {
    protocolVersion: 1, installationId, installationReference: 'TEST', storeId: 'store', storeName: 'Store', companyName: 'Empresa Real SL',
    publicKey: encodedKey, challenge,
    signature: crypto.sign('RSA-SHA256', Buffer.from(`TPV-TERMINAL-LINKING-V1\n${challenge}\n${installationId}`), privateKey).toString('base64'),
    maxWindows: 2, slots: []
  };
}
function json(body) { return { ok: true, headers: new Headers({ 'content-type': 'application/json' }), text: async () => JSON.stringify(body) }; }

test('challenge signature and URL security', () => {
  const value = bootstrap('nonce');
  assert.equal(verifyBootstrap(value, 'nonce').installationId, 'installation-a');
  assert.throws(() => verifyBootstrap(value, 'different'), /Respuesta/);
  assert.throws(() => verifyBootstrap({ ...value, installationId: 'other' }, 'nonce'), /firma/);
  assert.throws(() => candidateUrl('http://192.168.1.10:8080'), /HTTPS/);
  assert.throws(() => candidateUrl('https://user:pass@example.test'), /credenciales/);
});

test('proof is durable before POST, refresh uses proof and pins installation', async () => {
  let state = null;
  let committed = null;
  const posts = [];
  const storage = { read: () => state, write: (next) => { state = structuredClone(next); } };
  const config = { read: () => ({ configuration: committed && { backendUrl: committed } }), commit: async (url) => { committed = url; } };
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    const body = JSON.parse(options.body);
    assert.equal(state.pending.credential, body.credential);
    assert.equal(state.pending.requestId, body.requestId);
    posts.push({ url, body });
    if (url.endsWith('/requests')) return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '002', status: 'PENDING' });
    return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE',
      terminalId: 'terminal', bindingId: 'binding', storeId: 'store', storeName: 'Store' });
  };
  const bridge = createTerminalLinking({ request, storage, config, deviceName: 'PC', getRuntimeBindingId: () => 'binding' });
  const requested = await bridge.requestLink({ backendUrl: 'https://backend.example', code: '002', name: 'Caja 2' });
  assert.equal(requested.link.status, 'PENDING');
  assert.equal(committed, 'https://backend.example');
  const active = await bridge.refreshLink();
  assert.equal(active.identity.terminalCredential, posts[0].body.credential);
  assert.equal(active.identity.companyName, 'Empresa Real SL');
  assert.equal(state.identity.companyName, 'Empresa Real SL');
  assert.equal(state.pending, null);
  const same = await bridge.probe({ backendUrl: 'https://new.example' });
  assert.equal(same.sameInstallation, true);
  await bridge.saveAddress({ backendUrl: 'https://new.example' });
  assert.equal(committed, 'https://new.example');
  const otherRequest = async (url, options) => options.method === 'GET'
    ? json(bootstrap(new URL(url).searchParams.get('challenge'), 'other-installation')) : request(url, options);
  const otherBridge = createTerminalLinking({ request: otherRequest, storage, config });
  assert.equal((await otherBridge.probe({ backendUrl: 'https://other.example' })).sameInstallation, false);
  await assert.rejects(otherBridge.saveAddress({ backendUrl: 'https://other.example' }), /vinculación/);
});

test('server adoption reuses the old credential and marks legacy outbox scope only after proof', async () => {
  let state = { identity: { terminalId: 'server-id', terminalCode: 'SERVIDOR', terminalCredential: 'old-credential', storeName: 'Store' } };
  const storage = { read: () => state, write: (next) => { state = structuredClone(next); } };
  let legacyConfigUrl = 'http://localhost:8080';
  const config = { read: () => ({ configuration: { backendUrl: legacyConfigUrl } }),
    commit: async (value) => { legacyConfigUrl = value; } };
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    assert.ok(url.endsWith('/terminal-linking/server/adopt') || url.endsWith('/terminal-linking/requests/status'));
    const body = JSON.parse(options.body);
    assert.equal(body.credential, 'old-credential');
    if (url.endsWith('/adopt')) assert.equal(state.pending.credential, 'old-credential');
    return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '001', status: 'ACTIVE',
      terminalId: 'server-id', bindingId: 'binding', storeId: 'store', storeName: 'Store' });
  };
  const bridge = createTerminalLinking({ request, storage, config, getRuntimeBackendUrl: () => 'http://127.0.0.1:8080',
    getLegacyBackendScope: () => legacyConfigUrl });
  const result = await bridge.requestLink({ backendUrl: 'http://127.0.0.1:8080', code: '001', name: 'Servidor' });
  assert.equal(result.identity, undefined); // Credential is hidden until the new binding scope is loaded on restart.
  assert.equal(state.identity.terminalCredential, 'old-credential');
  assert.equal(state.identity.legacyBackendScope, 'http://localhost:8080');
  assert.equal(state.identity.legacyTerminalCode, 'SERVIDOR');
  assert.equal(result.restartRequired, true);
  assert.equal((await bridge.refreshLink()).identity, undefined);
});

test('the backend PC cannot claim a non-server workstation code', async () => {
  const bridge = createTerminalLinking({
    storage: { read: () => null, write: () => { throw new Error('unexpected write'); } },
    config: { read: () => ({ configuration: null }), commit: async () => { throw new Error('unexpected commit'); } },
    request: async (url, options) => {
      assert.equal(options.method, 'GET');
      return json(bootstrap(new URL(url).searchParams.get('challenge')));
    }
  });
  await assert.rejects(bridge.requestLink({ backendUrl: 'http://127.0.0.1:8080', code: '002', name: 'Caja' }),
    error => error.code === 'SERVER_SLOT_PROTECTED');
});

test('missing server request is reissued with the original proof after status denial', async () => {
  let state = { pending: { requestId: 'request', deviceId: 'device', credential: 'proof', code: '002', name: 'Caja',
    deviceName: 'PC', backendUrl: 'https://backend.example', installationId: 'installation-a',
    keyFingerprint: crypto.createHash('sha256').update(Buffer.from(encodedKey, 'base64')).digest('hex'), mode: 'WORKSTATION' } };
  const storage = { read: () => state, write: (next) => { state = structuredClone(next); } };
  const config = { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }), commit: async () => {} };
  let posted = 0;
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    const body = JSON.parse(options.body);
    assert.equal(body.requestId, 'request'); assert.equal(body.credential, 'proof');
    if (url.endsWith('/status')) return { ok: false, status: 403 };
    posted++;
    return json({ requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'PENDING' });
  };
  const bridge = createTerminalLinking({ request, storage, config });
  assert.equal((await bridge.refreshLink()).link.status, 'PENDING');
  assert.equal(posted, 1);
  assert.equal(state.pending.credential, 'proof');
});

test('offline old address exposes only safe metadata and accepts a pinned new address', async () => {
  let configured = 'https://old.example';
  const keyFingerprint = crypto.createHash('sha256').update(Buffer.from(encodedKey, 'base64')).digest('hex');
  let state = { identity: { installationId: 'installation-a', keyFingerprint, bindingId: 'binding',
    terminalId: 'terminal', terminalCode: '002', terminalName: 'Caja', terminalCredential: 'secret',
    storeName: 'Store', companyName: 'Empresa Real SL' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  const storage = { read: () => state, write: (next) => { state = structuredClone(next); } };
  const config = { read: () => ({ configuration: { backendUrl: configured } }), commit: async (url) => { configured = url; } };
  const request = async (url, options) => {
    if (url.startsWith('https://old.example')) throw new TypeError('offline');
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    return json({ requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'RELEASED' });
  };
  const bridge = createTerminalLinking({ request, storage, config,
    getRuntimeBackendUrl: () => 'https://old.example', getRuntimeBindingId: () => 'binding' });
  const loaded = await bridge.load();
  assert.equal(loaded.identity, null);
  assert.equal(loaded.connectionUnavailable, true);
  assert.deepEqual(loaded.displayContext, { companyName: 'Empresa Real SL', storeName: 'Store', terminalCode: '002', terminalName: 'Caja' });
  assert.deepEqual(loaded.linkedIdentity, { installationId: 'installation-a', bindingId: 'binding',
    terminalId: 'terminal', terminalCode: '002', terminalName: 'Caja', storeName: 'Store', companyName: 'Empresa Real SL' });
  assert.equal(JSON.stringify(loaded).includes('secret'), false);
  assert.equal((await bridge.saveAddress({ backendUrl: 'https://new.example' })).restartRequired, true);
  assert.equal(configured, 'https://new.example');
});

test('an existing encrypted identity gains the company name after verified contact and retains it offline', async () => {
  let state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalName: 'Caja', terminalCredential: 'secret', storeName: 'Store' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  let offline = false;
  const bridge = createTerminalLinking({
    storage: { read: () => state, write: next => { state = structuredClone(next); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => {
      if (offline) throw new TypeError('Network unavailable');
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      return json({ ...state.link });
    }
  });
  const connected = await bridge.load();
  assert.equal(connected.identity.companyName, 'Empresa Real SL');
  assert.equal(connected.connectionUnavailable, false);
  assert.equal(connected.displayContext, undefined);
  assert.equal(state.identity.companyName, 'Empresa Real SL');
  offline = true;
  const disconnected = await bridge.load();
  assert.equal(disconnected.identity, null);
  assert.equal(disconnected.connectionUnavailable, true);
  assert.equal(disconnected.displayContext.companyName, 'Empresa Real SL');
  assert.equal('terminalCredential' in disconnected.displayContext, false);
  assert.equal('terminalId' in disconnected.displayContext, false);
});

test('a cached disabled binding becomes usable when the backend reports it active again', async () => {
  let state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalCredential: 'secret', storeName: 'Store' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'DISABLED' } };
  const bridge = createTerminalLinking({
    storage: { read: () => structuredClone(state), write: next => { state = structuredClone(next); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => options.method === 'GET'
      ? json(bootstrap(new URL(url).searchParams.get('challenge')))
      : json({ requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' })
  });
  const loaded = await bridge.load();
  assert.equal(loaded.identity?.terminalCredential, 'secret');
  assert.equal(loaded.identity?.companyName, 'Empresa Real SL');
  assert.equal(loaded.link.status, 'ACTIVE');
  assert.equal(loaded.connectionUnavailable, false);
});

test.each([
  [' Empresa\tReal\nSL ', 'Empresa Real SL'],
  ['x'.repeat(256), undefined],
  ['Empresa\u0000Real', undefined]
])('company display name %j cannot block an active link', async (companyName, expected) => {
  let state = null;
  let backendUrl = null;
  const bridge = createTerminalLinking({
    storage: { read: () => state, write: next => { state = structuredClone(next); } },
    config: { read: () => ({ configuration: backendUrl && { backendUrl } }), commit: async url => { backendUrl = url; } },
    getRuntimeBindingId: () => 'binding',
    request: async (url, options) => {
      if (options.method === 'GET') return json({ ...bootstrap(new URL(url).searchParams.get('challenge')), companyName });
      const body = JSON.parse(options.body);
      return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE',
        terminalId: 'terminal', bindingId: 'binding', storeId: 'store', storeName: 'Store' });
    }
  });
  const linked = await bridge.requestLink({ backendUrl: 'https://backend.example', code: '002', name: 'Caja' });
  assert.equal(linked.identity.companyName, expected);
  assert.equal(state.identity.companyName, expected);
});

test('load updates only companyName when another app changes pending state during verification', async () => {
  let state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalCredential: 'secret', storeName: 'Store' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  let statusStarted;
  let finishStatus;
  const started = new Promise(resolve => { statusStarted = resolve; });
  const status = new Promise(resolve => { finishStatus = resolve; });
  let lockCount = 0;
  const bridge = createTerminalLinking({
    storage: { read: () => structuredClone(state), write: next => { state = structuredClone(next); },
      withLock: work => { lockCount++; return work(); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => {
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      statusStarted();
      return status;
    }
  });
  const loading = bridge.load();
  await started;
  state = { ...state, pending: { requestId: 'other-request', code: '003', credential: 'other-secret' } };
  finishStatus(json({ ...state.link }));
  const loaded = await loading;
  assert.equal(lockCount, 1);
  assert.equal(state.pending.requestId, 'other-request');
  assert.equal(state.identity.companyName, 'Empresa Real SL');
  assert.equal(loaded.identity.companyName, 'Empresa Real SL');
  assert.equal(loaded.pendingRequest.code, '003');
});

test('load does not return the old identity when another app replaces its binding during verification', async () => {
  let state = { identity: { installationId: 'installation-a', bindingId: 'old-binding', terminalId: 'old-terminal',
    terminalCode: '002', terminalCredential: 'old-secret', storeName: 'Store' },
    link: { requestId: 'old-request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  let statusStarted;
  let finishStatus;
  const started = new Promise(resolve => { statusStarted = resolve; });
  const status = new Promise(resolve => { finishStatus = resolve; });
  const bridge = createTerminalLinking({
    storage: { read: () => structuredClone(state), write: () => { throw new Error('Stale state must not be written'); },
      withLock: work => work() },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'old-binding',
    request: async (url, options) => {
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      statusStarted();
      return status;
    }
  });
  const loading = bridge.load();
  await started;
  state = { identity: { ...state.identity, bindingId: 'new-binding', terminalId: 'new-terminal', terminalCredential: 'new-secret' },
    link: { ...state.link, requestId: 'new-request' } };
  finishStatus(json({ requestId: 'old-request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' }));
  const loaded = await loading;
  assert.equal(loaded.identity, null);
  assert.equal(loaded.connectionUnavailable, false);
  assert.equal(loaded.displayContext, undefined);
  assert.equal(state.identity.companyName, undefined);
});

test('a local storage TypeError does not masquerade as an offline backend', async () => {
  const state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalCredential: 'secret', storeName: 'Store' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  const bridge = createTerminalLinking({
    storage: { read: () => state, withLock: () => { throw new TypeError('Local storage failed'); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => options.method === 'GET'
      ? json(bootstrap(new URL(url).searchParams.get('challenge')))
      : json({ ...state.link })
  });
  const loaded = await bridge.load();
  assert.equal(loaded.identity, null);
  assert.equal(loaded.connectionUnavailable, false);
  assert.equal(loaded.displayContext, undefined);
});

test.each([
  ['server failure', 503, true], ['revoked proof', 403, false], ['client rejection', 400, false]
])('%s does not validate cached identity and only exposes offline display on 5xx', async (_label, status, unavailable) => {
  const state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalCredential: 'secret', storeName: 'Store', companyName: 'Empresa Real SL' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  const bridge = createTerminalLinking({
    storage: { read: () => state, write: () => { throw new Error('Unexpected write'); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => 'https://backend.example', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => options.method === 'GET'
      ? json(bootstrap(new URL(url).searchParams.get('challenge')))
      : { ok: false, status, text: async () => JSON.stringify({ code: 'LINK_PROOF_INVALID' }) }
  });
  const loaded = await bridge.load();
  assert.equal(loaded.identity, null);
  assert.equal(loaded.connectionUnavailable, unavailable);
  assert.equal(loaded.displayContext?.companyName, unavailable ? 'Empresa Real SL' : undefined);
});

test.each(['invalid signature', 'restart required'])('%s suppresses offline display metadata', async (scenario) => {
  const state = { identity: { installationId: 'installation-a', bindingId: 'binding', terminalId: 'terminal',
    terminalCode: '002', terminalCredential: 'secret', storeName: 'Store', companyName: 'Empresa Real SL' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  const bridge = createTerminalLinking({
    storage: { read: () => state, write: () => { throw new Error('Unexpected write'); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://backend.example' } }) },
    getRuntimeBackendUrl: () => scenario === 'restart required' ? 'https://old.example' : 'https://backend.example',
    getRuntimeBindingId: () => 'binding',
    request: async (url, options) => {
      if (scenario === 'restart required') throw new TypeError('Network unavailable');
      const server = bootstrap(new URL(url).searchParams.get('challenge'));
      return json({ ...server, signature: 'ZmFrZQ==' });
    }
  });
  const loaded = await bridge.load();
  assert.equal(loaded.identity, null);
  assert.equal(loaded.displayContext, undefined);
  assert.equal(loaded.connectionUnavailable, scenario === 'restart required');
});

test('two PCs racing for one code leave no blocked pending proof on the loser', async () => {
  let occupied = false;
  const state = [{ value: null }, { value: null }];
  const config = { read: () => ({ configuration: { backendUrl: 'https://shop.example' } }), commit: async () => {} };
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    const body = JSON.parse(options.body);
    if (occupied) return {
      ok: false, status: 409, text: async () => JSON.stringify({ code: 'WORKSTATION_OCCUPIED' })
    };
    occupied = true;
    return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '003', status: 'PENDING' });
  };
  const clients = state.map((cell) => createTerminalLinking({ request, config, storage: {
    read: () => cell.value, write: (next) => { cell.value = structuredClone(next); }
  } }));
  const results = await Promise.allSettled(clients.map((client) =>
    client.requestLink({ backendUrl: 'https://shop.example', code: '003', name: 'Caja' })));
  assert.equal(results.filter(value => value.status === 'fulfilled').length, 1);
  assert.equal(results.filter(value => value.status === 'rejected').length, 1);
  const loser = results.findIndex(value => value.status === 'rejected');
  assert.equal(results[loser].reason.code, 'WORKSTATION_OCCUPIED');
  assert.equal(state[loser].value.pending, null);
  assert.ok(state[1 - loser].value.pending);
});

test('cancel of an absent request clears only local proof without reserving a code', async () => {
  let state = { pending: { requestId: 'request', deviceId: 'device', credential: 'proof', code: '003', name: 'Caja',
    deviceName: 'PC', backendUrl: 'https://shop.example', installationId: 'installation-a',
    keyFingerprint: crypto.createHash('sha256').update(Buffer.from(encodedKey, 'base64')).digest('hex'), mode: 'WORKSTATION' } };
  let submissions = 0;
  const bridge = createTerminalLinking({
    storage: { read: () => state, write: (next) => { state = structuredClone(next); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://shop.example' } }) },
    request: async (url, options) => {
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      if (url.endsWith('/requests')) submissions++;
      return { ok: false, status: 403, text: async () => JSON.stringify({ code: 'LINK_PROOF_INVALID' }) };
    }
  });
  assert.deepEqual(await bridge.cancelLink(), { link: null, localOnly: true });
  assert.equal(submissions, 0);
  assert.equal(state.pending, null);
});

test('two apps in one profile coalesce concurrent server 001 provisioning', async () => {
  let state = null;
  let queue = Promise.resolve();
  const storage = {
    read: () => state,
    write: (next) => { state = structuredClone(next); },
    withLock: (work) => { const turn = queue.then(work); queue = turn.catch(() => {}); return turn; }
  };
  let adoptions = 0;
  let logins = 0;
  let statusChecks = 0;
  let adoptedCredential;
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    if (url.endsWith('/installation-login')) {
      assert.equal(JSON.parse(options.body).password, '  secret-password  ');
      logins++; return json({ accessToken: 'admin-token' });
    }
    if (url.endsWith('/requests/status')) {
      statusChecks++;
      const body = JSON.parse(options.body);
      assert.equal(body.credential, adoptedCredential);
      return json({ ...state.link });
    }
    adoptions++;
    const body = JSON.parse(options.body);
    adoptedCredential = body.credential;
    assert.equal(options.headers.authorization, 'Bearer admin-token');
    assert.equal(state.pending.credential, body.credential);
    return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '001', status: 'ACTIVE',
      terminalId: 'server', bindingId: 'binding', storeId: 'store', storeName: 'Store' });
  };
  const config = { read: () => ({ configuration: { backendUrl: 'http://127.0.0.1:8080' } }), commit: async () => {} };
  const clients = [0, 1].map(() => createTerminalLinking({ request, storage, config,
    getRuntimeBackendUrl: () => 'http://127.0.0.1:8080' }));
  const input = { backendUrl: 'http://127.0.0.1:8080', code: '001', name: 'Servidor',
    administrator: { username: 'admin', password: '  secret-password  ' } };
  const results = await Promise.all(clients.map(client => client.requestLink(input)));
  assert.equal(adoptions, 1);
  assert.equal(logins, 1);
  assert.equal(statusChecks, 1);
  assert.equal(results[0].link.bindingId, 'binding');
  assert.equal(results[1].link.bindingId, 'binding');
  assert.equal(state.identity.terminalCredential, adoptedCredential);
  assert.equal(JSON.stringify(state).includes('secret-password'), false);
});

test.each(['VENTA', 'GESTIÓN'])('linking %s shares code, name and proof with the other open app without another registration', async () => {
  let state = null;
  let backendUrl = null;
  let approved = false;
  let registrations = 0;
  let secondRuntimeBinding;
  const storage = { read: () => structuredClone(state), write: next => { state = structuredClone(next); } };
  const config = { read: () => ({ configuration: backendUrl ? { backendUrl } : null }), commit: async url => { backendUrl = url; } };
  const request = async (url, options) => {
    if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
    const body = JSON.parse(options.body);
    if (url.endsWith('/requests')) registrations++;
    else assert.ok(url.endsWith('/requests/status'), 'Only registration and status are expected');
    assert.equal(body.credential, state.pending?.credential || state.identity.terminalCredential);
    return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '003',
      terminalName: 'Caja compartida', status: approved ? 'ACTIVE' : 'PENDING',
      ...(approved ? { terminalId: 'terminal', bindingId: 'binding', storeId: 'store', storeName: 'Store' } : {}) });
  };
  const first = createTerminalLinking({ storage, config, request, getRuntimeBackendUrl: () => 'https://shop.example' });
  const second = createTerminalLinking({ storage, config, request, getRuntimeBackendUrl: () => 'https://shop.example',
    getRuntimeBindingId: () => secondRuntimeBinding });
  assert.equal((await second.load()).linkedIdentity, null);
  await first.requestLink({ backendUrl: 'https://shop.example', code: '003', name: 'Caja compartida' });
  assert.equal((await second.load()).pendingRequest.code, '003');
  approved = true;
  await first.refreshLink();
  const originalProof = state.identity.terminalCredential;
  const detected = await second.load();
  assert.equal(detected.linkedIdentity.terminalCode, '003');
  assert.equal(detected.linkedIdentity.terminalName, 'Caja compartida');
  assert.equal(detected.link.status, 'ACTIVE');
  assert.equal(detected.identity, null);
  assert.equal(detected.restartRequired, true);
  const staleForm = await second.requestLink({ backendUrl: 'https://shop.example', code: '002', name: 'Ignored old input' });
  assert.equal(staleForm.link.terminalCode, '003');
  assert.equal(staleForm.link.terminalName, 'Caja compartida');
  assert.equal(staleForm.identity, undefined);
  secondRuntimeBinding = 'binding'; // A restart initializes the outbox with the shared binding.
  const loadedAfterRestart = await second.load();
  assert.equal(loadedAfterRestart.identity.terminalCredential, originalProof);
  assert.equal(loadedAfterRestart.identity.terminalName, 'Caja compartida');
  assert.equal(registrations, 1);
});

test('a stale active local state never authorizes reuse of a released shared binding', async () => {
  const state = { identity: { installationId: 'installation-a', terminalId: 'terminal', terminalCode: '001',
    terminalCredential: 'proof', bindingId: 'binding', terminalName: 'Principal', storeName: 'Store' },
    link: { requestId: 'request', installationId: 'installation-a', terminalCode: '001', status: 'ACTIVE' } };
  const client = createTerminalLinking({ storage: { read: () => state, write: () => { throw new Error('Unexpected mutation'); } },
    config: { read: () => ({ configuration: { backendUrl: 'http://127.0.0.1:8080' } }) },
    getRuntimeBackendUrl: () => 'http://127.0.0.1:8080', getRuntimeBindingId: () => 'binding',
    request: async (url, options) => {
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      assert.ok(url.endsWith('/requests/status'));
      return json({ ...state.link, status: 'RELEASED' });
    } });
  const result = await client.requestLink({ backendUrl: 'http://127.0.0.1:8080', code: '001', name: 'Principal' });
  assert.equal(result.link.status, 'RELEASED');
  assert.equal(result.identity, undefined);
  const loaded = await client.load();
  assert.equal(loaded.identity, null);
  assert.equal(loaded.connectionUnavailable, false);
  assert.equal(loaded.displayContext, undefined);
});

test('a released remote workstation may request a new binding with a fresh proof', async () => {
  let state = { deviceId: 'device', identity: { installationId: 'installation-a', terminalId: 'terminal', terminalCode: '002',
    terminalCredential: 'revoked-proof', bindingId: 'old-binding', terminalName: 'Caja', storeName: 'Store' },
    link: { requestId: 'old-request', installationId: 'installation-a', terminalCode: '002', status: 'ACTIVE' } };
  let registrations = 0;
  const client = createTerminalLinking({ storage: { read: () => state, write: next => { state = structuredClone(next); } },
    config: { read: () => ({ configuration: { backendUrl: 'https://shop.example' } }), commit: async () => {} },
    getRuntimeBackendUrl: () => 'https://shop.example', getRuntimeBindingId: () => 'old-binding',
    request: async (url, options) => {
      if (options.method === 'GET') return json(bootstrap(new URL(url).searchParams.get('challenge')));
      if (url.endsWith('/requests/status')) return json({ ...state.link, status: 'RELEASED' });
      assert.ok(url.endsWith('/requests'));
      const body = JSON.parse(options.body);
      assert.notEqual(body.credential, 'revoked-proof');
      assert.notEqual(body.requestId, 'old-request');
      registrations++;
      return json({ requestId: body.requestId, installationId: 'installation-a', terminalCode: '003', status: 'PENDING' });
    } });
  const result = await client.requestLink({ backendUrl: 'https://shop.example', code: '003', name: 'Caja nueva' });
  assert.equal(result.link.status, 'PENDING');
  assert.equal(result.identity, undefined);
  assert.equal(registrations, 1);
});
