import { test } from 'vitest';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createWindowsProfileCrypto, MAGIC, isEncrypted } = require('./windows-profile-crypto.cjs');

test('the fixed PowerShell invocation carries data only through stdin and stdout', () => {
  const calls = [];
  const adapter = createWindowsProfileCrypto({ platform: 'win32', systemRoot: 'C:\\Windows',
    run: (file, args, options) => {
      calls.push({ file, args, options });
      return options.input;
    } });
  const secret = `credential-${crypto.randomUUID()}`;
  const encrypted = adapter.encryptString(secret);
  assert.ok(isEncrypted(encrypted));
  assert.ok(encrypted.subarray(0, MAGIC.length).equals(MAGIC));
  assert.equal(adapter.decryptString(encrypted), secret);
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.file, 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
    assert.deepEqual(call.args.slice(0, 3), ['-NoProfile', '-NonInteractive', '-Command']);
    assert.ok(call.args[3].includes('DataProtectionScope]::CurrentUser'));
    assert.ok(!JSON.stringify([call.file, call.args, { ...call.options, input: undefined }]).includes(secret));
    assert.equal(call.options.windowsHide, true);
    assert.equal(call.options.shell, false);
    assert.deepEqual(call.options.stdio, ['pipe', 'pipe', 'pipe']);
    assert.equal(typeof call.options.input, 'string');
  }
  assert.ok(calls[0].args[3].includes('::Protect('));
  assert.ok(calls[1].args[3].includes('::Unprotect('));
});

test('invalid input and command failures expose only a stable error code', () => {
  const fail = createWindowsProfileCrypto({ platform: 'win32', systemRoot: 'C:\\Windows',
    run: () => { throw Object.assign(new Error('secret in stderr'), { stdout: 'secret in stdout' }); } });
  for (const action of [
    () => fail.encryptString('secret'),
    () => fail.encryptString('x'.repeat(64 * 1024 + 1)),
    () => fail.decryptString(Buffer.from('plain text')),
    () => createWindowsProfileCrypto({ platform: 'linux' }).encryptString('secret')
  ]) {
    assert.throws(action, error => error.code === 'SHARED_STORAGE_UNAVAILABLE'
      && !/secret|stdout|stderr/i.test(error.message));
  }
  assert.equal(isEncrypted(Buffer.from('plain text')), false);
});

test('DPAPI factory keeps the 64 KiB default and permits a scoped 8 MiB override', () => {
  const run = (_file, _args, options) => options.input;
  const defaultAdapter = createWindowsProfileCrypto({ platform: 'win32', systemRoot: 'C:\\Windows', run });
  const largeAdapter = createWindowsProfileCrypto({ platform: 'win32', systemRoot: 'C:\\Windows',
    maxPlaintextBytes: 8 * 1024 * 1024, run });
  const text = 'x'.repeat(64 * 1024 + 1);
  assert.throws(() => defaultAdapter.encryptString(text), error => error.code === 'SHARED_STORAGE_UNAVAILABLE');
  assert.equal(largeAdapter.decryptString(largeAdapter.encryptString(text)), text);
  assert.throws(() => largeAdapter.encryptString('x'.repeat(8 * 1024 * 1024 + 1)),
    error => error.code === 'SHARED_STORAGE_UNAVAILABLE');
  assert.throws(() => createWindowsProfileCrypto({ maxPlaintextBytes: 8 * 1024 * 1024 + 1 }),
    error => error.code === 'SHARED_STORAGE_UNAVAILABLE');
});

test.skipIf(process.platform !== 'win32')('Windows CurrentUser DPAPI roundtrip and tamper rejection', () => {
  const adapter = createWindowsProfileCrypto();
  const text = `terminal-${crypto.randomUUID()}-中文`;
  const encrypted = adapter.encryptString(text);
  assert.equal(adapter.decryptString(encrypted), text);
  assert.equal(encrypted.includes(Buffer.from(text, 'utf8')), false);
  const tampered = Buffer.from(encrypted);
  tampered[tampered.length - 1] ^= 1;
  assert.throws(() => adapter.decryptString(tampered), error => error.code === 'SHARED_STORAGE_UNAVAILABLE');
});

test.skipIf(process.platform !== 'win32')('real Windows DPAPI roundtrips a recovery snapshot above 64 KiB', () => {
  const adapter = createWindowsProfileCrypto({ maxPlaintextBytes: 8 * 1024 * 1024 });
  const text = `${crypto.randomUUID()}-${'é中文'.repeat(24 * 1024)}`;
  assert.ok(Buffer.byteLength(text, 'utf8') > 64 * 1024);
  const encrypted = adapter.encryptString(text);
  assert.ok(isEncrypted(encrypted));
  assert.equal(encrypted.includes(Buffer.from(text, 'utf8')), false);
  assert.equal(adapter.decryptString(encrypted), text);
});

test.skipIf(process.platform !== 'win32')('another Windows process reads the shared ciphertext without Electron profile keys', () => {
  const adapter = createWindowsProfileCrypto();
  const expected = `test-terminal-${crypto.randomUUID()}`;
  const encrypted = adapter.encryptString(expected);
  const child = [
    "const fs = require('node:fs');",
    'const { createWindowsProfileCrypto } = require(process.argv[1]);',
    "const ciphertext = Buffer.from(fs.readFileSync(0, 'utf8'), 'base64');",
    'process.stdout.write(createWindowsProfileCrypto().decryptString(ciphertext));'
  ].join('\n');
  const actual = execFileSync(process.execPath, ['-e', child, require.resolve('./windows-profile-crypto.cjs')], {
    input: encrypted.toString('base64'), encoding: 'utf8', windowsHide: true,
    timeout: 15000, maxBuffer: 128 * 1024, stdio: ['pipe', 'pipe', 'pipe']
  });
  assert.equal(actual, expected);
});
