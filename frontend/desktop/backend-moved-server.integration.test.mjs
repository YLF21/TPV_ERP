import { test, expect } from 'vitest';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createBackendConnectionRecovery } = require('./backend-connection-recovery.cjs');
const { createFixedBackendTransport } = require('./fixed-backend-transport.cjs');
const { createDesktopServer } = require('./loopback-server.cjs');
const { verifyBootstrap } = require('./terminal-linking.cjs');
const cert = fs.readFileSync(new URL('../test-fixtures/backend-transport/test-cert.pem', import.meta.url));
const key = fs.readFileSync(new URL('../test-fixtures/backend-transport/test-key.pem', import.meta.url));
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const installationId = crypto.randomUUID();
const requestId = crypto.randomUUID();
const binding = { installationId, storeId: crypto.randomUUID(), terminalId: crypto.randomUUID(),
  bindingId: crypto.randomUUID(), terminalCode: '002', terminalCredential: 'fixture-proof' };

function bootstrap(challenge) {
  return { protocolVersion: 1, installationId, challenge,
    publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
    signature: crypto.sign('RSA-SHA256', Buffer.from(`TPV-TERMINAL-LINKING-V1\n${challenge}\n${installationId}`),
      privateKey).toString('base64') };
}
binding.keyFingerprint = verifyBootstrap(bootstrap('fixture'), 'fixture').keyFingerprint;

async function startBackend(ip, port, requests) {
  const server = https.createServer({ cert, key }, async (request, response) => {
    try {
      let body = '';
      for await (const chunk of request) body += chunk.toString('utf8');
      requests.push({ ip, method: request.method, path: request.url, host: request.headers.host, body });
      const route = new URL(request.url, 'https://backend.example.test');
      let result;
      if (request.method === 'GET' && route.pathname === '/api/v1/terminal-linking/bootstrap') {
        result = bootstrap(route.searchParams.get('challenge'));
      } else if (request.method === 'POST' && route.pathname === '/api/v1/terminal-linking/requests/status') {
        const proof = JSON.parse(body);
        result = proof.requestId === requestId && proof.credential === binding.terminalCredential
          ? { ...binding, requestId, status: 'ACTIVE' } : null;
      } else if (request.method === 'GET' && route.pathname === '/api/v1/operational') {
        result = { servingIp: ip, authority: request.headers.host };
      }
      response.writeHead(result ? 200 : 403, { 'content-type': 'application/json' });
      response.end(JSON.stringify(result || { code: 'DENIED' }));
    } catch {
      response.writeHead(500, { 'content-type': 'application/json' });
      response.end('{}');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, ip, () => { server.off('error', reject); resolve(); });
  });
  return { port: server.address().port, async close() {
    if (!server.listening) return;
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  } };
}

test('a moved backend is rediscovered only at a new startup and keeps TLS authority and binding', async () => {
  const cleanup = [];
  const requests = [];
  const staticRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-moved-backend-'));
  fs.writeFileSync(path.join(staticRoot, 'index.html'), 'desktop fixture');
  cleanup.push(async () => {
    const resolved = fs.realpathSync(staticRoot);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(resolved), /^tpv-moved-backend-/);
    fs.rmSync(resolved, { recursive: true, force: true });
  });
  try {
    const firstBackend = await startBackend('127.0.0.3', 0, requests);
    cleanup.push(() => firstBackend.close());
    const backendUrl = `https://backend.example.test:${firstBackend.port}`;
    const persisted = { identity: { ...binding }, link: { requestId, status: 'ACTIVE' } };
    let state = structuredClone(persisted);
    const storage = { read: () => structuredClone(state), write: next => { state = structuredClone(next); },
      withLock: async action => action() };
    let discoveredIp = '127.0.0.3';
    let discoveries = 0;
    const discover = async () => {
      discoveries++;
      return [{ backendUrl, installationId, addresses: [discoveredIp] }];
    };
    const options = { backendUrl, storage, discover, lookup: async () => ({ address: '127.0.0.2' }),
      allowCandidate: ip => ip.startsWith('127.0.0.'), // Test-only loopback addresses.
      createTransport: input => createFixedBackendTransport({ ...input, ca: cert }),
      timeoutMs: 500, startupTimeoutMs: 3000 };
    const first = createBackendConnectionRecovery(options);
    cleanup.push(() => first.close());
    expect(await first.startup()).toMatchObject({ state: 'CONNECTED', backendIp: '127.0.0.3' });
    expect(discoveries).toBe(1);
    expect(state.connectionLocation).toMatchObject({ authority: backendUrl, ip: '127.0.0.3',
      installationId, bindingId: binding.bindingId });
    expect(state.identity).toEqual(persisted.identity);
    const firstProxy = createDesktopServer({ staticRoot, backendUrl, backendAllowedHosts: ['backend.example.test'],
      backendTransport: first.getTransport(), canProxy: () => first.canProxy(),
      onTransportFailure: () => first.transportFailure(), timeoutMs: 1000 });
    cleanup.push(() => firstProxy.close());
    const firstOrigin = await firstProxy.start();
    expect(await (await fetch(`${firstOrigin}/api/v1/operational`)).json()).toEqual({ servingIp: '127.0.0.3',
      authority: `backend.example.test:${firstBackend.port}` });

    await firstBackend.close();
    const secondBackend = await startBackend('127.0.0.4', firstBackend.port, requests);
    cleanup.push(() => secondBackend.close());
    discoveredIp = '127.0.0.4';
    expect(await first.retry()).toMatchObject({ state: 'OFFLINE', backendIp: '127.0.0.3' });
    expect(first.canProxy()).toBe(false);
    expect((await fetch(`${firstOrigin}/api/v1/operational`)).status).toBe(503);
    expect(discoveries).toBe(1);

    const restarted = createBackendConnectionRecovery(options);
    cleanup.push(() => restarted.close());
    expect(await restarted.startup()).toMatchObject({ state: 'CONNECTED', backendIp: '127.0.0.4' });
    expect(discoveries).toBe(2);
    expect(state.identity).toEqual(persisted.identity);
    expect(state.link).toEqual(persisted.link);
    expect(state.connectionLocation).toMatchObject({ authority: backendUrl, ip: '127.0.0.4',
      installationId, bindingId: binding.bindingId });
    const secondProxy = createDesktopServer({ staticRoot, backendUrl, backendAllowedHosts: ['backend.example.test'],
      backendTransport: restarted.getTransport(), canProxy: () => restarted.canProxy(), timeoutMs: 1000 });
    cleanup.push(() => secondProxy.close());
    const secondOrigin = await secondProxy.start();
    expect(await (await fetch(`${secondOrigin}/api/v1/operational`)).json()).toEqual({ servingIp: '127.0.0.4',
      authority: `backend.example.test:${firstBackend.port}` });
    expect(requests.filter(item => item.path.startsWith('/api/v1/terminal-linking/bootstrap'))).toHaveLength(2);
    expect(requests.filter(item => item.path === '/api/v1/terminal-linking/requests/status')).toHaveLength(2);
    expect(requests.every(item => item.host === `backend.example.test:${firstBackend.port}`)).toBe(true);
  } finally {
    for (const action of cleanup.reverse()) await action();
  }
}, 15000);
