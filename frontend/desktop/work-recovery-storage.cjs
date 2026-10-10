const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { assertRegular } = require('./linking-storage.cjs');
const LIMIT = 8 * 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function recoveryScope(appKind, identity) {
  const ids = ['installationId', 'storeId', 'terminalId', 'bindingId'].map(key => identity?.[key]);
  if (!['venta', 'gestion'].includes(appKind) || !ids.every(id => typeof id === 'string' && UUID.test(id))) {
    throw new Error('WORK_RECOVERY_NOT_LINKED');
  }
  return [appKind, ...ids.map(id => id.toLowerCase())].join(':');
}
function assertNoSecrets(value) {
  const stack = [value];
  while (stack.length) {
    const next = stack.pop();
    if (!next || typeof next !== 'object') continue;
    for (const [key, child] of Object.entries(next)) {
      if (/^(accessToken|refreshToken|terminalCredential|password)$/i.test(key)) throw new Error('WORK_RECOVERY_SECRET');
      if (child && typeof child === 'object') stack.push(child);
    }
  }
}
function createWorkRecoveryStorage({ userDataPath, appKind, getIdentity, profileCrypto } = {}) {
  function location() {
    const scope = recoveryScope(appKind, getIdentity());
    return { scope, target: path.join(userDataPath, 'work-recovery', `${crypto.createHash('sha256').update(scope).digest('hex')}.dpapi`) };
  }
  function load() {
    const { scope, target } = location(); assertRegular(target);
    if (!fs.existsSync(target)) return null;
    if (fs.statSync(target).size > LIMIT + 65536) throw new Error('WORK_RECOVERY_TOO_LARGE');
    const cipher = fs.readFileSync(target);
    if (!profileCrypto.isEncrypted(cipher)) throw new Error('WORK_RECOVERY_INVALID');
    const record = JSON.parse(profileCrypto.decryptString(cipher));
    if (record.version !== 1 || record.scope !== scope) throw new Error('WORK_RECOVERY_WRONG_IDENTITY');
    assertNoSecrets(record.value);
    return record.value;
  }
  function save(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('WORK_RECOVERY_INVALID');
    const { scope, target } = location();
    const serialized = JSON.stringify({ version: 1, scope, value });
    if (Buffer.byteLength(serialized) > LIMIT) throw new Error('WORK_RECOVERY_TOO_LARGE');
    assertNoSecrets(JSON.parse(serialized));
    assertRegular(target); fs.mkdirSync(path.dirname(target), { recursive: true });
    const temporary = `${target}.${process.pid}.${crypto.randomUUID()}.tmp`;
    let fd;
    try {
      fd = fs.openSync(temporary, 'wx', 0o600);
      fs.writeFileSync(fd, profileCrypto.encryptString(serialized)); fs.fsyncSync(fd); fs.closeSync(fd); fd = undefined;
      fs.renameSync(temporary, target);
    } finally {
      if (fd !== undefined) fs.closeSync(fd);
      try { fs.unlinkSync(temporary); } catch {}
    }
  }
  function clear() {
    const { target } = location(); assertRegular(target);
    try { fs.unlinkSync(target); } catch (failed) { if (failed.code !== 'ENOENT') throw failed; }
  }
  return { load, save, clear };
}
module.exports = { createWorkRecoveryStorage, recoveryScope };
