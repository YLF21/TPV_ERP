import { afterEach, describe, expect, it, vi } from 'vitest';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { createBackendConnectionRecovery, isPrivateAddress } = require('./backend-connection-recovery.cjs');
const { verifyBootstrap } = require('./terminal-linking.cjs');
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const installationId = crypto.randomUUID();
function bootstrap(challenge, id = installationId, key = privateKey) {
  return { protocolVersion: 1, installationId: id, publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
    challenge, signature: crypto.sign('RSA-SHA256', Buffer.from(`TPV-TERMINAL-LINKING-V1\n${challenge}\n${id}`), key).toString('base64') };
}
const identity = { installationId, storeId: crypto.randomUUID(), terminalId: crypto.randomUUID(), bindingId: crypto.randomUUID(),
  terminalCode: '002', terminalCredential: 'fixture-credential', keyFingerprint: verifyBootstrap(bootstrap('test'), 'test').keyFingerprint };
const services = [];
afterEach(() => { services.splice(0).forEach(service => service.close()); vi.useRealTimers(); });
function setup(options = {}) {
  let state = { identity: { ...identity }, link: { status: 'ACTIVE', requestId: crypto.randomUUID() } };
  const storage = { read: () => structuredClone(state), write: next => { state = structuredClone(next); },
    withLock: async action => action() };
  const requests = [];
  const reachable = new Set(['192.168.31.47']);
  let serverInstallationId = installationId;
  let linkOverrides = {};
  const transports = [];
  const createTransport = vi.fn(({ backendUrl, connectIp }) => {
    const value = { connectIp, close: vi.fn(), request: vi.fn(async (url, requestOptions) => {
      requests.push({ connectIp, url, body: requestOptions.body });
      if (!reachable.has(connectIp)) throw Object.assign(new Error('refused'), { code: 'ECONNREFUSED' });
      const parsed = new URL(url);
      const body = parsed.pathname.endsWith('/bootstrap') ? bootstrap(parsed.searchParams.get('challenge'), serverInstallationId)
        : { ...state.link, ...state.identity, ...linkOverrides };
      return new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } });
    }) };
    transports.push(value); return value;
  });
  const discover = vi.fn(async () => [{ backendUrl: 'https://192.168.31.47:8443', installationId, addresses: ['192.168.31.47'] }]);
  const lookup = vi.fn(async () => ({ address: '192.168.31.46' }));
  const service = createBackendConnectionRecovery({ backendUrl: 'https://backend.example.test:8443', storage,
    discover, lookup, createTransport, ...options });
  services.push(service);
  return { service, storage, requests, reachable, discover, lookup, createTransport, transports,
    setInstallation: id => { serverInstallationId = id; }, setLink: value => { linkOverrides = value; },
    get state() { return state; }, set state(value) { state = value; } };
}

describe('startup recovery and fixed runtime destination', () => {
  it('discovers a moved server, verifies proof before credential and only caches location', async () => {
    const value = setup();
    expect((await value.service.startup()).state).toBe('CONNECTED');
    expect(value.service.status().backendIp).toBe('192.168.31.47');
    expect(value.state.identity).toEqual(identity);
    expect(value.state.connectionLocation).toMatchObject({ authority: 'https://backend.example.test:8443', ip: '192.168.31.47' });
    const newServer = value.requests.filter(item => item.connectIp === '192.168.31.47');
    expect(newServer[0].body).toBeUndefined();
    expect(JSON.parse(newServer[1].body).credential).toBe(identity.terminalCredential);
    expect(value.transports[0].close).toHaveBeenCalledOnce();
  });
  it('retry and monitor never resolve DNS, discover or change selected IP', async () => {
    vi.useFakeTimers();
    const value = setup({ monitorMs: 100 });
    await value.service.startup();
    value.reachable.clear(); value.service.transportFailure();
    expect((await value.service.retry()).state).toBe('OFFLINE');
    value.reachable.add('192.168.31.47');
    expect((await value.service.retry()).state).toBe('CONNECTED');
    const factories = value.createTransport.mock.calls.length;
    value.service.startMonitor(); await vi.advanceTimersByTimeAsync(200);
    expect(value.lookup).toHaveBeenCalledOnce(); expect(value.discover).toHaveBeenCalledOnce();
    expect(value.createTransport).toHaveBeenCalledTimes(factories);
    expect(value.service.status().backendIp).toBe('192.168.31.47');
  });
  it('does not reveal credentials to another signed installation', async () => {
    const value = setup(); value.setInstallation(crypto.randomUUID());
    expect((await value.service.startup()).state).toBe('OFFLINE');
    expect(value.requests.every(item => item.body === undefined)).toBe(true);
    expect(value.service.canProxy()).toBe(false);
  });
  it.each(['storeId', 'terminalId', 'bindingId', 'terminalCode', 'requestId'])('rejects mismatched %s in approval', async key => {
    const value = setup(); value.setLink({ [key]: 'different' });
    expect((await value.service.startup()).errorCode).toBe('BINDING_CHANGED');
    expect(value.service.canProxy()).toBe(false);
  });
  it('blocks revoked terminal without changing persisted approval', async () => {
    const value = setup(); value.setLink({ status: 'REVOKED' });
    expect((await value.service.startup()).errorCode).toBe('TERMINAL_DISABLED');
    expect(value.state.link.status).toBe('ACTIVE');
  });
  it('rechecks shared binding before retry credentials', async () => {
    const value = setup(); await value.service.startup();
    value.state = { ...value.state, identity: { ...identity, bindingId: crypto.randomUUID() } };
    const previous = value.requests.length;
    expect((await value.service.retry()).errorCode).toBe('BINDING_CHANGED');
    expect(value.requests).toHaveLength(previous);
  });
  it('uses verified cache before DNS, and tolerates optional cache write failure', async () => {
    const value = setup();
    value.state.connectionLocation = { version: 1, authority: 'https://backend.example.test:8443', ...identity, ip: '192.168.31.47' };
    // Startup captures state once; create another service against the updated shared state.
    value.storage.write = () => { throw new Error('disk full'); };
    const service = createBackendConnectionRecovery({ backendUrl: 'https://backend.example.test:8443', storage: value.storage,
      createTransport: value.createTransport, lookup: value.lookup, discover: value.discover });
    services.push(service);
    expect((await service.startup()).state).toBe('CONNECTED');
    expect(value.discover).not.toHaveBeenCalled();
  });
  it('leaves unlinked or legacy setup under its wizard', async () => {
    const storage = { read: () => ({ identity: { terminalCode: '001' } }) };
    const discover = vi.fn(); const createTransport = vi.fn();
    const service = createBackendConnectionRecovery({ backendUrl: 'http://127.0.0.1:8080', storage, discover, createTransport });
    services.push(service);
    expect((await service.startup()).state).toBe('CONNECTED');
    expect(service.canProxy()).toBe(true); expect(createTransport).not.toHaveBeenCalled(); expect(discover).not.toHaveBeenCalled();
  });
  it('bounds a silent discovery and does not fall back to arbitrary addresses', async () => {
    const value = setup({ startupTimeoutMs: 40, timeoutMs: 10, discover: () => new Promise(() => {}) });
    const start = Date.now();
    expect((await value.service.startup()).state).toBe('OFFLINE');
    expect(Date.now() - start).toBeLessThan(1000);
    expect(value.service.status().backendIp).toBe('192.168.31.46');
  });
  it('fails closed without a startup IP instead of resolving DNS from linked.load', async () => {
    const fallback = vi.spyOn(globalThis, 'fetch');
    const value = setup({ lookup: async () => { throw new Error('DNS unavailable'); }, discover: async () => [] });
    expect((await value.service.startup()).state).toBe('OFFLINE');
    await expect(value.service.request('https://backend.example.test:8443/api/v1/terminal-linking/bootstrap')).rejects.toThrow('BACKEND_ADDRESS_UNAVAILABLE');
    expect(fallback).not.toHaveBeenCalled(); fallback.mockRestore();
  });
  it('requires private IPv4 for untrusted discovery candidates', () => {
    expect(['10.0.2.2', '172.16.0.2', '172.31.2.2', '192.168.31.46'].every(isPrivateAddress)).toBe(true);
    expect(['127.0.0.1', '8.8.8.8', '169.254.0.1', '172.32.0.2', '::1', 'invalid'].some(isPrivateAddress)).toBe(false);
  });
});
