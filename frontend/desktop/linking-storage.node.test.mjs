import { test, vi } from 'vitest';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { createLinkingStorage } = require('./linking-storage.cjs');
const shared = {
  isEncrypted: data => data.subarray(0, 7).toString() === 'SHARED:',
  encryptString: text => Buffer.concat([Buffer.from('SHARED:'), Buffer.from(text).reverse()]),
  decryptString: data => Buffer.from(data.subarray(7)).reverse().toString()
};
function appCrypto(name) {
  const prefix = Buffer.from(`${name}:`);
  return {
    isEncryptionAvailable: () => true,
    encryptString: text => Buffer.concat([prefix, Buffer.from(text).reverse()]),
    decryptString: data => {
      if (!data.subarray(0, prefix.length).equals(prefix)) throw new Error('Another app key');
      return Buffer.from(data.subarray(prefix.length)).reverse().toString();
    }
  };
}

test('a failed disk flush never replaces the committed linking proof', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-storage-'));
  let flush;
  try {
    const secure = { isEncryptionAvailable: () => true,
      encryptString: text => Buffer.from(text), decryptString: data => data.toString('utf8') };
    const storage = createLinkingStorage({ safeStorage: secure, sharedCrypto: shared, target: path.join(dir, 'identity.dpapi') });
    storage.write({ pending: { requestId: 'original' } });
    flush = vi.spyOn(fs, 'fsyncSync').mockImplementation(() => { throw new Error('disk unavailable'); });
    assert.throws(() => storage.write({ pending: { requestId: 'replacement' } }), /disk unavailable/);
    assert.equal(storage.read().pending.requestId, 'original');
    assert.deepEqual(fs.readdirSync(dir), ['identity.dpapi']);
  } finally { flush?.mockRestore(); fs.rmSync(dir, { recursive: true, force: true }); }
});

test('shared encrypted state migrates legacy identity and retains pending proof durably', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-storage-'));
  try {
    const target = path.join(dir, 'shared', 'linking.dpapi');
    const legacyPath = path.join(dir, 'legacy.dpapi');
    const secret = 'a-legacy-credential';
    const secure = {
      isEncryptionAvailable: () => true,
      encryptString: (value) => Buffer.from(value, 'utf8').reverse(),
      decryptString: (value) => Buffer.from(value).reverse().toString('utf8')
    };
    fs.writeFileSync(legacyPath, secure.encryptString(JSON.stringify({ terminalId: 'id', terminalCredential: secret })));
    const storage = createLinkingStorage({ safeStorage: secure, sharedCrypto: shared, target, legacyPaths: [legacyPath] });
    await storage.initialize();
    assert.equal(storage.read().identity.terminalCredential, secret);
    assert.equal(fs.readFileSync(target, 'utf8').includes(secret), false);
    storage.write({ ...storage.read(), pending: { requestId: 'request', credential: 'new-proof' } });
    assert.equal(createLinkingStorage({ sharedCrypto: shared, target }).read().pending.credential, 'new-proof');
    assert.equal(fs.readFileSync(target, 'utf8').includes('new-proof'), false);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('two app instances serialize mutations of the same Windows profile state', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-lock-'));
  try {
    const target = path.join(dir, 'shared.dpapi');
    const secure = { isEncryptionAvailable: () => true,
      encryptString: (text) => Buffer.from(text), decryptString: (data) => data.toString('utf8') };
    const first = createLinkingStorage({ safeStorage: secure, sharedCrypto: shared, target });
    const second = createLinkingStorage({ safeStorage: secure, sharedCrypto: shared, target });
    const order = [];
    await Promise.all([
      first.withLock(async () => { order.push('first-start'); await new Promise(resolve => setTimeout(resolve, 35));
        first.write({ value: 1 }); order.push('first-end'); }),
      second.withLock(async () => { order.push('second-start'); second.write({ value: second.read().value + 1 });
        order.push('second-end'); })
    ]);
    assert.deepEqual(order, ['first-start', 'first-end', 'second-start', 'second-end']);
    assert.equal(first.read().value, 2);
    assert.equal(fs.existsSync(`${target}.lock`), false);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test.each(['gestion', 'venta'])('migration from %s keeps the identity readable by both app profiles', async (owner) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-migration-'));
  try {
    const target = path.join(dir, 'shared.dpapi');
    const before = { identity: { terminalId: 'server', terminalCode: '001', terminalCredential: 'keep-credential', bindingId: 'keep-binding' },
      pending: { requestId: 'keep-request', credential: 'keep-proof' } };
    const ownerCrypto = appCrypto(owner);
    const other = owner === 'gestion' ? 'venta' : 'gestion';
    const original = ownerCrypto.encryptString(JSON.stringify(before));
    fs.writeFileSync(target, original);
    const owningApp = createLinkingStorage({ sharedCrypto: shared, safeStorage: ownerCrypto, target });
    const otherApp = createLinkingStorage({ sharedCrypto: shared, safeStorage: appCrypto(other), target });
    await assert.rejects(otherApp.initialize(), error => error.code === 'LINKING_STORAGE_MIGRATION_REQUIRED');
    assert.deepEqual(fs.readFileSync(target), original);
    assert.deepEqual(owningApp.read(), before);
    assert.deepEqual(fs.readFileSync(target), original); // Reads never migrate outside the lock.
    await owningApp.initialize();
    assert.equal(shared.isEncrypted(fs.readFileSync(target)), true);
    await otherApp.initialize();
    assert.deepEqual(otherApp.read(), before);
    otherApp.write({ ...before, pending: null });
    assert.equal(owningApp.read().pending, null); // Detect another app's replacement despite cache.
    const copy = owningApp.read();
    copy.identity.terminalCredential = 'mutated';
    assert.equal(owningApp.read().identity.terminalCredential, 'keep-credential');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('migration skips an unreadable other profile and stops at the first readable legacy identity', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-legacy-'));
  try {
    const target = path.join(dir, 'shared.dpapi');
    const own = appCrypto('gestion');
    const otherPath = path.join(dir, 'venta.dpapi');
    const ownPath = path.join(dir, 'gestion.dpapi');
    fs.writeFileSync(otherPath, appCrypto('venta').encryptString(JSON.stringify({ terminalId: 'other' })));
    fs.writeFileSync(ownPath, own.encryptString(JSON.stringify({ terminalId: 'preserve' })));
    const storage = createLinkingStorage({ sharedCrypto: shared, safeStorage: own, target,
      legacyPaths: [otherPath, ownPath, dir] }); // Last path must never be inspected once a valid identity exists.
    assert.equal(storage.read(), null);
    assert.equal(fs.existsSync(target), false);
    await storage.initialize();
    assert.equal(storage.read().identity.terminalId, 'preserve');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('failed migration encryption retains the old file and never leaves plaintext or temporary files', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-failure-'));
  try {
    const target = path.join(dir, 'shared.dpapi');
    const own = appCrypto('gestion');
    const original = own.encryptString(JSON.stringify({ identity: { terminalCredential: 'old-proof' } }));
    fs.writeFileSync(target, original);
    const storage = createLinkingStorage({ target, safeStorage: own,
      sharedCrypto: { ...shared, encryptString: () => { throw new Error('DPAPI unavailable'); } } });
    await assert.rejects(storage.initialize(), /DPAPI unavailable/);
    assert.deepEqual(fs.readFileSync(target), original);
    assert.deepEqual(fs.readdirSync(dir), ['shared.dpapi']);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('damaged shared ciphertext never falls back to an app-specific decryptor or overwrites the file', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-linking-corrupt-'));
  try {
    const target = path.join(dir, 'shared.dpapi');
    const original = Buffer.from('SHARED:damaged');
    fs.writeFileSync(target, original);
    const legacyDecrypt = vi.fn();
    const storage = createLinkingStorage({ target,
      safeStorage: { isEncryptionAvailable: () => true, decryptString: legacyDecrypt },
      sharedCrypto: { ...shared, decryptString: () => { throw new Error('Invalid authentication'); } } });
    await assert.rejects(storage.initialize(), /Invalid authentication/);
    assert.equal(legacyDecrypt.mock.calls.length, 0);
    assert.deepEqual(fs.readFileSync(target), original);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
