import { afterEach, describe, expect, it, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import https from 'node:https';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { createFixedBackendTransport } = require('./fixed-backend-transport.cjs');
const { createDesktopServer } = require('./loopback-server.cjs');
const cert = fs.readFileSync(new URL('../test-fixtures/backend-transport/test-cert.pem', import.meta.url));
const key = fs.readFileSync(new URL('../test-fixtures/backend-transport/test-key.pem', import.meta.url));
const cleanup = [];
afterEach(async () => { for (const action of cleanup.splice(0).reverse()) await action(); });
async function backend(handler = (_req, res) => res.end('ok')) {
  const server = https.createServer({ cert, key }, handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  cleanup.push(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return server.address().port;
}
function fixed(port, hostname = 'backend.example.test', trusted = true) {
  const transport = createFixedBackendTransport({ backendUrl: `https://${hostname}:${port}`, connectIp: '127.0.0.1', ...(trusted ? { ca: cert } : {}) });
  cleanup.push(() => transport.close()); return transport;
}
describe('TLS authority independent of the frozen socket IP', () => {
  it.each(['backend.example.test', '192.168.31.46'])('validates %s certificate while connecting to another IP', async hostname => {
    const port = await backend((req, res) => { expect(req.headers.host).toBe(`${hostname}:${port}`); res.end('identity preserved'); });
    const response = await fixed(port, hostname).request(`https://${hostname}:${port}/api/v1/test`);
    expect(await response.text()).toBe('identity preserved');
  });
  it('rejects untrusted CA and incorrect certificate hostname', async () => {
    const port = await backend();
    await expect(fixed(port, 'backend.example.test', false).request(`https://backend.example.test:${port}/`)).rejects.toThrow();
    await expect(fixed(port, 'other.example.test').request(`https://other.example.test:${port}/`)).rejects.toMatchObject({ code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
  });
  it('does not follow redirects or permit another authority to receive a payload', async () => {
    const port = await backend((_req, res) => { res.writeHead(302, { location: 'https://other.example.test/' }); res.end(); });
    const transport = fixed(port);
    expect((await transport.request(`https://backend.example.test:${port}/`)).status).toBe(302);
    await expect(transport.request('https://other.example.test/', { method: 'POST', body: 'secret' })).rejects.toThrow('BACKEND_AUTHORITY_CHANGED');
  });
  it('accepts HTTP only for logical and physical loopback', () => {
    expect(() => createFixedBackendTransport({ backendUrl: 'http://remote.example', connectIp: '127.0.0.1' })).toThrow();
    expect(() => createFixedBackendTransport({ backendUrl: 'http://localhost', connectIp: '192.168.31.46' })).toThrow();
  });
  it('rejects oversized identity responses', async () => {
    const port = await backend((_req, res) => res.end('x'.repeat(65537)));
    await expect(fixed(port).request(`https://backend.example.test:${port}/`)).rejects.toThrow('BACKEND_RESPONSE_TOO_LARGE');
  });
  it('streams operational proxy calls through fixed TLS and reports only transport failure', async () => {
    let calls = 0;
    const port = await backend((req, res) => { calls++; res.writeHead(req.url.endsWith('forbidden') ? 403 : 200); res.end('result'); });
    const transport = fixed(port); const failed = vi.fn(); let permitted = true;
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-fixed-proxy-'));
    fs.writeFileSync(path.join(root, 'index.html'), 'test');
    cleanup.push(() => fs.rmSync(root, { recursive: true, force: true }));
    const desktop = createDesktopServer({ staticRoot: root, backendUrl: transport.backendUrl, backendAllowedHosts: ['backend.example.test'],
      backendTransport: transport, canProxy: () => permitted, onTransportFailure: failed });
    cleanup.push(() => desktop.close()); const origin = await desktop.start();
    expect((await fetch(`${origin}/api/v1/forbidden`)).status).toBe(403); expect(failed).not.toHaveBeenCalled();
    permitted = false;
    expect((await fetch(`${origin}/api/v1/private`, { headers: { authorization: 'Bearer private' } })).status).toBe(503);
    expect(calls).toBe(1);
    permitted = true;
    // Destroy the selected sockets and listener; the proxy must report loss without replay.
    const closeBackend = cleanup.shift(); await closeBackend();
    expect((await fetch(`${origin}/api/v1/failed`)).status).toBe(502); expect(failed).toHaveBeenCalledOnce(); expect(calls).toBe(1);
  });
});
