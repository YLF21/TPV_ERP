// Real loopback HTTP transport; synthetic data only, no database or application session.
const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const path = require('node:path');

const documentId = '33333333-3333-3333-3333-333333333333';
const eventId = '44444444-4444-4444-4444-444444444444';
const cutoff = '2026-09-10T12:34:56.123456789Z';
const revision = '9007199254740993';
const expectedScope = {
  companyId: '11111111-1111-1111-1111-111111111111',
  storeId: '22222222-2222-2222-2222-222222222222',
  dateFrom: '2026-08-01', dateTo: '2026-08-31', createdBefore: cutoff,
};

async function run(shell, processRemoteSigned) {
  const requests = [];
  const failures = [];
  const server = http.createServer(async (request, response) => {
    // Clients intentionally abort rejected/oversized responses.
    response.on('error', () => {});
    try {
      assert.equal(request.method, 'POST');
      assert.match(request.headers['content-type'], /^application\/json; charset=utf-8$/i);
      assert.equal(request.headers.accept, 'application/json');
      const token = /^Bearer FIXTURE-([a-z0-9-]+)$/.exec(request.headers.authorization || '');
      assert.ok(token, 'Only synthetic fixture credentials are accepted');
      const mode = token[1];
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const bytes = Buffer.concat(chunks);
      assert.equal(Number(request.headers['content-length']), bytes.length);
      const body = JSON.parse(bytes.toString('utf8'));
      requests.push({ mode, url: request.url });
      const send = (value) => response.end(JSON.stringify(value));
      response.setHeader('Content-Type', 'application/json; charset=utf-8');
      if (/^http-(401|403|404|500)$/.test(mode)) {
        response.statusCode = Number(mode.slice(5));
        return send({ secret: 'RESPONSE_BODY_MUST_NOT_ESCAPE' });
      }
      if (mode === 'redirect') {
        response.writeHead(302, { Location: '/must-not-follow' });
        return response.end();
      }
      if (mode === 'invalid-json') return response.end('RESPONSE_BODY_MUST_NOT_ESCAPE');
      if (mode === 'oversize-known') {
        response.setHeader('Content-Length', 4194305);
        return response.end(' '.repeat(4194305));
      }
      if (mode === 'oversize-chunked') {
        response.setHeader('Transfer-Encoding', 'chunked');
        response.write(' '.repeat(2097152));
        return response.end(' '.repeat(2097153));
      }
      if (mode === 'disconnect') return request.socket.destroy();
      const action = request.url.slice('/api/v1/sync/document-recovery/'.length);
      assert.equal(request.url, '/api/v1/sync/document-recovery/' + action);
      assert.deepEqual(body.scope, expectedScope);
      if (action === 'preview') {
        assert.equal(body.afterId, null);
        const scope = { ...expectedScope };
        if (mode === 'wrong-scope') scope.storeId = documentId;
        return send({ scope, documents: [{ documentId, number: 'T-001-客',
          type: 'TICKET', status: 'PAGADO', date: '2026-08-01',
          total: '9007199254740993.01', currency: 'EUR', problem: null }],
          nextAfterId: null, hasMore: false });
      }
      const receipt = { documentId, eventId, sourceRevision: revision, outboxStatus: 'PENDIENTE' };
      if (action === 'prepare') {
        assert.deepEqual(body.documentIds, [documentId]);
        assert.equal(body.reason, 'Synthetic HTTP test');
        if (mode === 'numeric-revision') receipt.sourceRevision = 1;
        return send({ scope: expectedScope, status: 'ENQUEUED', documents: [receipt] });
      }
      assert.equal(action, 'verify');
      assert.deepEqual(body.documents, [receipt]);
      const pending = mode === 'pending';
      const missingCustomer = mode === 'missing-customer';
      return send({ scope: expectedScope, complete: !pending && !missingCustomer, documents: [{
        documentId, eventId: mode === 'wrong-event' ? documentId : eventId,
        sourceRevision: revision, projected: !pending, customerLinked: missingCustomer ? false : null,
        status: pending ? 'MISSING' : missingCustomer ? 'CUSTOMER_BINDING_MISSING' : 'PROJECTED',
      }] });
    } catch (error) {
      failures.push(error.message);
      response.statusCode = 400;
      response.end('{}');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    await new Promise((resolve, reject) => {
      // Explicit opt-in only: affects this test child, never the stored machine/user policy.
      const policyArgs = processRemoteSigned ? ['-ExecutionPolicy', 'RemoteSigned'] : [];
      // Node otherwise forwards PS7 module paths to Windows PowerShell 5.1, unlike a direct PS launch.
      // Let each child discover its own standard modules; do not change the parent environment.
      const childEnv = { ...process.env };
      for (const key of Object.keys(childEnv)) {
        if (key.toLowerCase() === 'psmodulepath') delete childEnv[key];
      }
      const child = spawn(shell, ['-NoProfile', '-NonInteractive', ...policyArgs, '-File',
        path.join(__dirname, 'document-sync-recovery.Http.Tests.ps1'), '-FixtureOrigin', url],
      { windowsHide: true, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'] });
      let output = '';
      child.stdout.on('data', chunk => { output += chunk; });
      child.stderr.on('data', chunk => { output += chunk; });
      const timer = setTimeout(() => { child.kill(); reject(new Error('Fixture client exceeded 45 seconds')); }, 45000);
      child.once('error', error => { clearTimeout(timer); reject(error); });
      child.once('exit', code => {
        clearTimeout(timer);
        if (code !== 0) return reject(new Error(`PowerShell fixture failed (${code}): ${output}`));
        process.stdout.write(output);
        resolve();
      });
    });
    assert.deepEqual(failures, []);
    assert.equal(requests.length, 19, 'No automatic retry, redirect or unexpected extra request');
    assert.equal(requests.filter(row => row.mode === 'success').length, 5);
    for (const mode of ['pending', 'missing-customer', 'wrong-event', 'wrong-scope', 'numeric-revision',
      'http-401', 'http-403', 'http-404', 'http-500', 'redirect', 'invalid-json',
      'oversize-known', 'oversize-chunked', 'disconnect']) {
      // Five successful calls plus fourteen deliberate variants = nineteen requests.
      assert.equal(requests.filter(row => row.mode === mode).length, 1, mode);
    }
    assert.ok(requests.every(row => row.url.startsWith('/api/v1/sync/document-recovery/')));
    console.log(`${path.basename(shell)}: HTTP request contract and bounded request counts passed`);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

(async () => {
  const args = process.argv.slice(2);
  const processRemoteSigned = args.includes('--process-remote-signed');
  const shells = args.filter(arg => arg !== '--process-remote-signed');
  if (!shells.length || shells.some(arg => arg.startsWith('-'))) {
    throw new Error('Pass the PowerShell executable(s); --process-remote-signed requires explicit authorization');
  }
  for (const shell of shells) await run(shell, processRemoteSigned);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
