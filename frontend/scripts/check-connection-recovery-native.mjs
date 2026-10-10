// Isolated Windows/Electron smoke test. It uses no real store credentials or database.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const electron = require('electron');
const { createWindowsProfileCrypto } = require('../desktop/windows-profile-crypto.cjs');
const { verifyBootstrap } = require('../desktop/terminal-linking.cjs');
const root = path.resolve(import.meta.dirname, '..');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'espos-native-recovery-'));
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const installationId = crypto.randomUUID(), requestId = crypto.randomUUID();
function proof(challenge) {
  return { protocolVersion: 1, installationId, installationReference: 'TEST ONLY', storeName: 'Isolated test',
    publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'), challenge,
    signature: crypto.sign('RSA-SHA256', Buffer.from(`TPV-TERMINAL-LINKING-V1\n${challenge}\n${installationId}`), privateKey).toString('base64') };
}
const identity = { installationId, storeId: crypto.randomUUID(), terminalId: crypto.randomUUID(), bindingId: crypto.randomUUID(),
  terminalCode: '001', terminalCredential: crypto.randomBytes(32).toString('base64url'), storeName: 'Isolated test',
  companyName: 'ISOLATED TEST', keyFingerprint: verifyBootstrap(proof('nonce'), 'nonce').keyFingerprint };
const state = { identity, link: { requestId, status: 'ACTIVE', ...identity } };
let server, port; const children = new Set();
async function startBackend() {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    let body;
    if (url.pathname.endsWith('/bootstrap')) body = proof(url.searchParams.get('challenge'));
    else if (url.pathname.endsWith('/requests/status')) body = { ...state.link };
    else if (url.pathname.endsWith('/connectivity')) body = { backendConnected: true, saasConnected: false };
    else body = { ok: true };
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(body));
  });
  await new Promise(resolve => server.listen(port || 0, '127.0.0.1', resolve)); port = server.address().port;
}
async function stopBackend() {
  if (!server?.listening) return;
  await new Promise(resolve => { server.closeAllConnections(); server.close(resolve); });
}
function launch(kind) {
  const folder = path.join(profile, kind); fs.mkdirSync(folder, { recursive: true });
  const rpcFolder = fs.mkdtempSync(path.join(profile, `${kind}-rpc-`));
  fs.writeFileSync(path.join(folder, 'backend-config.json'), JSON.stringify({ backendUrl: `http://127.0.0.1:${port}` }));
  const entry = path.join(profile, `entry-${kind}.cjs`);
  fs.writeFileSync(entry, `
const { app, BrowserWindow, dialog } = require('electron');
const fs = require('node:fs');
app.setPath('userData', ${JSON.stringify(folder)});
app.setPath('appData', ${JSON.stringify(profile)});
app.commandLine.appendSwitch('disable-gpu');
dialog.showErrorBox = (title, message) => {
  require('node:fs').appendFileSync(${JSON.stringify(path.join(profile, 'startup-error.log'))}, title + ': ' + message + '\\n');
  app.exit(1);
};
require('node:fs').appendFileSync(${JSON.stringify(path.join(profile, 'entry.log'))}, 'entry ${kind} packaged=' + app.isPackaged + '\\n');
app.on('ready', () => require('node:fs').appendFileSync(${JSON.stringify(path.join(profile, 'entry.log'))}, 'ready ${kind}\\n'));
app.on('browser-window-created', (_event, window) => window.on('show', () => window.hide()));
require(${JSON.stringify(path.join(root, 'desktop', `main-${kind}.cjs`))});
require('node:fs').appendFileSync(${JSON.stringify(path.join(profile, 'entry.log'))}, 'main loaded ${kind}\\n');
let processing = false;
setInterval(async () => {
  if (processing) return;
  const name = fs.readdirSync(${JSON.stringify(rpcFolder)}).find(name => /^request-\\d+\\.json$/.test(name));
  if (!name) return;
  const file = require('node:path').join(${JSON.stringify(rpcFolder)}, name);
  const call = JSON.parse(fs.readFileSync(file, 'utf8'));
  fs.unlinkSync(file); processing = true;
  try {
    const window = BrowserWindow.getAllWindows()[0];
    if (!window) throw new Error('No window yet');
    if (call.kind === 'key') {
      if (!window.webContents.debugger.isAttached()) window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const space = call.code === 'Space';
      const key = space ? ' ' : 'Enter', code = space ? 'Space' : 'Enter', windowsVirtualKeyCode = space ? 32 : 13;
      await window.webContents.debugger.sendCommand('Input.dispatchKeyEvent', { type: 'keyDown', key, code, windowsVirtualKeyCode,
        text: space ? ' ' : '\\r', unmodifiedText: space ? ' ' : '\\r' });
      await window.webContents.debugger.sendCommand('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode });
      process.stdout.write('RECOVERY_TEST ' + JSON.stringify({ id: call.id, value: true }) + '\\n');
    } else if (call.kind === 'auxiliary') {
      const child = new BrowserWindow({ show: false, webPreferences: { preload: ${JSON.stringify(path.join(root, 'desktop', 'preload.cjs'))}, sandbox: true, nodeIntegration: false, contextIsolation: true } });
      await child.loadURL(window.webContents.getURL());
      const value = await child.webContents.executeJavaScript('window.tpvDesktop.workRecovery.save({ tamper: true })');
      child.destroy(); process.stdout.write('RECOVERY_TEST ' + JSON.stringify({ id: call.id, value }) + '\\n');
    } else {
      const value = await window.webContents.executeJavaScript(call.code);
      process.stdout.write('RECOVERY_TEST ' + JSON.stringify({ id: call.id, value }) + '\\n');
    }
  } catch (error) { process.stdout.write('RECOVERY_TEST ' + JSON.stringify({ id: call.id, error: String(error.message) }) + '\\n'); }
  finally { processing = false; }
}, 25);
`);
  const child = spawn(electron, [entry], { cwd: root, windowsHide: true,
    env: { ...process.env, APPDATA: profile, TPV_DESKTOP_APP_KIND: kind, TPV_DESKTOP_APP_URL: '',
      TPV_DESKTOP_BACKEND_URL: undefined, TPV_DESKTOP_BACKEND_ALLOWED_HOSTS: undefined, TPV_DESKTOP_WINDOW_MODE: 'WINDOWED' },
    stdio: ['ignore', 'pipe', 'pipe'] });
  children.add(child); let serial = 0, buffered = '';
  const requests = new Map();
  child.stdout.on('data', chunk => {
    fs.appendFileSync(path.join(profile, `${kind}-stdout.log`), chunk);
    buffered += chunk.toString();
    let end;
    while ((end = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, end); buffered = buffered.slice(end + 1);
      if (!line.startsWith('RECOVERY_TEST ')) continue;
      const result = JSON.parse(line.slice(14)), request = requests.get(result.id);
      if (request) { requests.delete(result.id); clearTimeout(request.timer); result.error ? request.reject(new Error(result.error)) : request.resolve(result.value); }
    }
  });
  child.stderr.on('data', chunk => fs.appendFileSync(path.join(profile, `${kind}-stderr.log`), chunk));
  const exited = new Promise(resolve => child.on('exit', code => {
    fs.appendFileSync(path.join(profile, `${kind}-exit.log`), String(code));
    children.delete(child);
    for (const request of requests.values()) { clearTimeout(request.timer); request.reject(new Error('Native process exited')); }
    requests.clear(); resolve(code);
  }));
  function call(code, kind = 'evaluate') {
    return new Promise((resolve, reject) => {
      const id = ++serial, timer = setTimeout(() => { requests.delete(id); reject(new Error('Native check timeout')); }, 20000);
      requests.set(id, { resolve, reject, timer });
      const temporary = path.join(rpcFolder, `request-${id}.tmp`);
      fs.writeFileSync(temporary, JSON.stringify({ id, code, kind }));
      fs.renameSync(temporary, path.join(rpcFolder, `request-${id}.json`));
    });
  }
  return { call, exited };
}
async function waitFor(check, label) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    try { if (await check()) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`Native check failed: ${label}`);
}
const report = { profile, checks: [] };
try {
  await startBackend();
  const shared = path.join(profile, 'TPV ERP'); fs.mkdirSync(shared, { recursive: true });
  fs.writeFileSync(path.join(shared, 'desktop-linking.dpapi'), createWindowsProfileCrypto().encryptString(JSON.stringify(state)));
  let venta = launch('venta');
  await waitFor(async () => (await venta.call('window.tpvDesktop.connectionRecovery.status()')).state === 'CONNECTED', 'Venta startup connected');
  assert.equal((await venta.call('window.tpvDesktop.terminalIdentity.load()')).identity.terminalId, identity.terminalId);
  const oldOrigin = await venta.call('location.origin');
  assert.equal((await venta.call(`window.tpvDesktop.workRecovery.save({ sentinel: 'native-test', text: 'x'.repeat(70000) })`)).ok, true);
  assert.equal((await venta.call('', 'auxiliary')).code, 'IPC_UNAUTHORIZED');
  report.checks.push('Venta verified startup, scoped IPC and >64KiB real DPAPI');
  await stopBackend(); await venta.call(`fetch('/api/v1/ping').then(r => r.status)`);
  await waitFor(() => venta.call(`Boolean(document.querySelector('.backend-recovery-dialog'))`), 'lost connection dialog');
  assert.equal((await venta.call('window.tpvDesktop.connectionRecovery.retry()')).state, 'OFFLINE');
  await startBackend(); await venta.call(`document.querySelector('.backend-recovery-retry').focus()`);
  await venta.call('Enter', 'key');
  await waitFor(async () => !(await venta.call(`Boolean(document.querySelector('.backend-recovery-dialog'))`)), 'retry restores UI');
  report.checks.push('Loss modal and retry recovers the same IP without restarting');
  await stopBackend(); await venta.call(`fetch('/api/v1/ping').then(r => r.status)`);
  await waitFor(() => venta.call(`Boolean(document.querySelector('.backend-recovery-dialog'))`), 'loss before close');
  void venta.call(`document.querySelector('.backend-recovery-close').click()`).catch(() => {});
  assert.equal(await venta.exited, 0);
  await startBackend(); venta = launch('venta');
  await waitFor(async () => (await venta.call('window.tpvDesktop.connectionRecovery.status()')).state === 'CONNECTED', 'Venta reopen');
  assert.notEqual(await venta.call('location.origin'), oldOrigin);
  const stored = await venta.call('window.tpvDesktop.workRecovery.load()');
  assert.equal(stored.value.sentinel, 'native-test'); assert.equal(stored.value.text.length, 70000);
  await venta.call('window.tpvDesktop.workRecovery.clear()');
  void venta.call('window.tpvDesktop.closeApplication()').catch(() => {}); await venta.exited;
  report.checks.push('Close/reopen survives changed renderer port and reads encrypted work');
  await stopBackend(); const gestion = launch('gestion');
  await waitFor(() => gestion.call(`Boolean(document.querySelector('.backend-recovery-dialog'))`), 'Gestión offline startup');
  assert.equal((await gestion.call('window.tpvDesktop.workRecovery.load()')).value, null);
  await startBackend(); await gestion.call(`document.querySelector('.backend-recovery-retry').focus()`);
  await gestion.call('Space', 'key');
  await waitFor(async () => !(await gestion.call(`Boolean(document.querySelector('.backend-recovery-dialog'))`)), 'Gestión reactivation');
  assert.equal((await gestion.call('window.tpvDesktop.terminalIdentity.load()')).identity.terminalId, identity.terminalId);
  void gestion.call('window.tpvDesktop.closeApplication()').catch(() => {}); assert.equal(await gestion.exited, 0);
  report.checks.push('Gestión linked offline startup, isolated work scope and retry reactivation');
  report.ok = true;
} catch (error) { report.ok = false; report.error = error.stack; process.exitCode = 1; }
finally {
  for (const child of children) child.kill();
  await stopBackend();
  fs.writeFileSync(path.join(profile, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
