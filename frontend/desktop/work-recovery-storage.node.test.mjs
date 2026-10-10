import { test, expect, vi } from 'vitest';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createWorkRecoveryStorage, recoveryScope } = require('./work-recovery-storage.cjs');
const IDENTITY = {
  installationId: '11111111-1111-4111-8111-111111111111',
  storeId: '22222222-2222-4222-8222-222222222222',
  terminalId: '33333333-3333-4333-8333-333333333333',
  bindingId: '44444444-4444-4444-8444-444444444444'
};
const MARKER = Buffer.from('test-dpapi-v1:');

function profileCrypto() {
  const key = crypto.randomBytes(32);
  return {
    isEncrypted: (value) => Buffer.isBuffer(value) && value.subarray(0, MARKER.length).equals(MARKER),
    encryptString(text) {
      const iv = crypto.randomBytes(12);
      const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
      const payload = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
      return Buffer.concat([MARKER, iv, cipher.getAuthTag(), payload]);
    },
    decryptString(value) {
      const iv = value.subarray(MARKER.length, MARKER.length + 12);
      const tag = value.subarray(MARKER.length + 12, MARKER.length + 28);
      const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
      decipher.setAuthTag(tag);
      return Buffer.concat([decipher.update(value.subarray(MARKER.length + 28)), decipher.final()]).toString('utf8');
    }
  };
}
function inTempDirectory(run) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-work-recovery-test-'));
  try { return run(directory); }
  finally {
    const resolved = fs.realpathSync(directory);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(resolved), /^tpv-work-recovery-test-/);
    fs.rmSync(resolved, { recursive: true, force: true });
  }
}
function storedFile(directory) {
  const files = fs.readdirSync(path.join(directory, 'work-recovery'));
  expect(files).toHaveLength(1);
  expect(files[0]).toMatch(/^[0-9a-f]{64}\.dpapi$/);
  return path.join(directory, 'work-recovery', files[0]);
}

test('scope requires a linked UUID identity and excludes the backend origin and port', () => {
  const expected = recoveryScope('venta', IDENTITY);
  expect(expected).toBe(`venta:${Object.values(IDENTITY).join(':')}`);
  expect(recoveryScope('venta', { ...IDENTITY, backendUrl: 'https://shop.example:8443' })).toBe(expected);
  expect(recoveryScope('venta', { ...IDENTITY, backendUrl: 'https://shop.example:9443' })).toBe(expected);
  expect(recoveryScope('gestion', IDENTITY)).not.toBe(expected);
  expect(() => recoveryScope('venta', { ...IDENTITY, bindingId: 'not-a-uuid' })).toThrow('WORK_RECOVERY_NOT_LINKED');
  expect(() => recoveryScope('pda', IDENTITY)).toThrow('WORK_RECOVERY_NOT_LINKED');
});

test('encrypted snapshot survives a new adapter and each save replaces its ciphertext', () => inTempDirectory(directory => {
  const cipher = profileCrypto();
  let identity = { ...IDENTITY, backendUrl: 'https://shop.example:8443' };
  const first = createWorkRecoveryStorage({ userDataPath: directory, appKind: 'venta', getIdentity: () => identity,
    profileCrypto: cipher });
  const value = { route: '/sale', cart: [{ sku: 'SKU-1', quantity: 2 }], note: '中文' };
  expect(first.load()).toBeNull();
  first.save(value);
  const target = storedFile(directory);
  const before = fs.readFileSync(target);
  expect(before.includes(Buffer.from('SKU-1'))).toBe(false);
  identity = { ...identity, backendUrl: 'https://shop.example:9443' };
  const reopened = createWorkRecoveryStorage({ userDataPath: directory, appKind: 'venta', getIdentity: () => identity,
    profileCrypto: cipher });
  expect(reopened.load()).toEqual(value);
  reopened.save(value);
  expect(fs.readFileSync(target).equals(before)).toBe(false);
  expect(reopened.load()).toEqual(value);
  reopened.clear();
  expect(reopened.load()).toBeNull();
}));

test('different binding and app scopes cannot read another snapshot', () => inTempDirectory(directory => {
  const cipher = profileCrypto();
  const common = { userDataPath: directory, getIdentity: () => IDENTITY, profileCrypto: cipher };
  const venta = createWorkRecoveryStorage({ ...common, appKind: 'venta' });
  const gestion = createWorkRecoveryStorage({ ...common, appKind: 'gestion' });
  venta.save({ currentScreen: 'sale' });
  expect(gestion.load()).toBeNull();
  const otherBinding = createWorkRecoveryStorage({ ...common, appKind: 'venta',
    getIdentity: () => ({ ...IDENTITY, bindingId: '55555555-5555-4555-8555-555555555555' }) });
  expect(otherBinding.load()).toBeNull();
  expect(venta.load()).toEqual({ currentScreen: 'sale' });
}));

test('nested secrets and values beyond 8 MiB are rejected before replacing a valid snapshot', () => inTempDirectory(directory => {
  const storage = createWorkRecoveryStorage({ userDataPath: directory, appKind: 'venta',
    getIdentity: () => IDENTITY, profileCrypto: profileCrypto() });
  storage.save({ safe: true });
  const target = storedFile(directory);
  const before = fs.readFileSync(target);
  for (const key of ['accessToken', 'refreshToken', 'terminalCredential', 'password']) {
    expect(() => storage.save({ nested: [{ [key]: 'secret' }] })).toThrow('WORK_RECOVERY_SECRET');
  }
  expect(() => storage.save({ content: 'x'.repeat(8 * 1024 * 1024) })).toThrow('WORK_RECOVERY_TOO_LARGE');
  expect(fs.readFileSync(target).equals(before)).toBe(true);
  expect(storage.load()).toEqual({ safe: true });
}));

test('failed atomic rename retains the prior snapshot and removes the temporary file', () => inTempDirectory(directory => {
  const storage = createWorkRecoveryStorage({ userDataPath: directory, appKind: 'venta',
    getIdentity: () => IDENTITY, profileCrypto: profileCrypto() });
  storage.save({ version: 'old' });
  const target = storedFile(directory);
  const before = fs.readFileSync(target);
  const rename = vi.spyOn(fs, 'renameSync').mockImplementationOnce(() => { throw new Error('rename failed'); });
  try { expect(() => storage.save({ version: 'new' })).toThrow('rename failed'); }
  finally { rename.mockRestore(); }
  expect(fs.readFileSync(target).equals(before)).toBe(true);
  expect(storage.load()).toEqual({ version: 'old' });
  expect(fs.readdirSync(path.dirname(target))).toEqual([path.basename(target)]);
}));
