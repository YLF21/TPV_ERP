const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function sharedLinkingPath(profile = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming')) {
  return path.join(profile, 'TPV ERP', 'desktop-linking.dpapi');
}
function assertRegular(target) {
  let current = path.resolve(target);
  while (true) {
    if (fs.existsSync(current)) {
      const stat = fs.lstatSync(current);
      if (stat.isSymbolicLink() || (current === path.resolve(target) ? !stat.isFile() : !stat.isDirectory())) {
        throw new Error('Ruta de identidad no segura');
      }
    }
    const parent = path.dirname(current);
    if (parent === current) return;
    current = parent;
  }
}
function migrationRequired() {
  return Object.assign(new Error('Cierra VENTA y GESTIÓN. Abre primero la aplicación actualizada con la que vinculaste este equipo para actualizar su identidad compartida; después abre la otra aplicación.'),
    { code: 'LINKING_STORAGE_MIGRATION_REQUIRED' });
}
function createLinkingStorage({ safeStorage, sharedCrypto, target = sharedLinkingPath(), legacyPath, legacyPaths = legacyPath ? [legacyPath] : [] } = {}) {
  if (!sharedCrypto?.encryptString || !sharedCrypto?.decryptString || !sharedCrypto?.isEncrypted) {
    throw new Error('Almacenamiento compartido de Windows no disponible');
  }
  let cachedCiphertext;
  let cachedPlaintext;
  function readFile(file) {
    assertRegular(file);
    if (!fs.existsSync(file)) return null;
    const stat = fs.statSync(file);
    if (stat.size > 96 * 1024) throw new Error('Identidad demasiado grande');
    const ciphertext = fs.readFileSync(file);
    const legacy = !sharedCrypto.isEncrypted(ciphertext);
    let plaintext;
    if (cachedCiphertext?.equals(ciphertext)) plaintext = cachedPlaintext;
    else if (!legacy) plaintext = sharedCrypto.decryptString(ciphertext);
    else {
      try {
        if (!safeStorage?.isEncryptionAvailable?.()) throw migrationRequired();
        plaintext = safeStorage.decryptString(ciphertext);
      } catch { throw migrationRequired(); }
    }
    const state = JSON.parse(plaintext);
    cachedCiphertext = ciphertext;
    cachedPlaintext = plaintext;
    return { state, legacy };
  }
  function write(state) {
    assertRegular(target);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const temp = `${target}.${process.pid}.${Math.random().toString(16).slice(2)}.tmp`;
    let descriptor;
    try {
      descriptor = fs.openSync(temp, 'wx', 0o600);
      fs.writeFileSync(descriptor, sharedCrypto.encryptString(JSON.stringify(state)));
      fs.fsyncSync(descriptor);
      fs.closeSync(descriptor);
      descriptor = undefined;
      // A same-volume replacement leaves either the old or complete new encrypted state.
      fs.renameSync(temp, target);
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor);
      try { fs.rmSync(temp, { force: true }); } catch {}
    }
  }
  function read() {
    return readFile(target)?.state || null;
  }
  async function initialize() {
    return withLock(() => {
      const current = readFile(target);
      if (current) {
        if (current.legacy) write(current.state);
        return current.state;
      }
      let unreadable;
      for (const legacyFile of legacyPaths) {
        let previous;
        try { previous = readFile(legacyFile); }
        catch (error) {
          if (error?.code !== 'LINKING_STORAGE_MIGRATION_REQUIRED') throw error;
          unreadable = error;
          continue;
        }
        if (!previous) continue;
        const identity = previous.state;
        const migrated = { identity, deviceId: identity.deviceId || null };
        write(migrated);
        return migrated;
      }
      if (unreadable) throw unreadable;
      return null;
    });
  }
  async function withLock(work, { timeoutMs = 180000 } = {}) {
    const lockPath = `${target}.lock`;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const token = `${process.pid}:${require('node:crypto').randomUUID()}`;
    const started = Date.now();
    while (true) {
      assertRegular(lockPath);
      let fd;
      try { fd = fs.openSync(lockPath, 'wx', 0o600); }
      catch (err) {
        if (err?.code !== 'EEXIST') throw err;
        let stat;
        try { stat = fs.lstatSync(lockPath); }
        catch (missing) { if (missing?.code === 'ENOENT') continue; throw missing; }
        if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Bloqueo de identidad no seguro');
        if (Date.now() - stat.mtimeMs > 2000) {
          let owner;
          try { owner = JSON.parse(fs.readFileSync(lockPath, 'utf8')); } catch { owner = null; }
          let alive = true;
          if (Number.isInteger(owner?.pid) && owner.pid > 0) {
            try { process.kill(owner.pid, 0); } catch (error) { if (error?.code === 'ESRCH') alive = false; }
          } else if (Date.now() - stat.mtimeMs > timeoutMs) alive = false;
          if (!alive) { try { fs.rmSync(lockPath); } catch {} continue; }
        }
        if (Date.now() - started >= timeoutMs) throw new Error('La identidad está ocupada por otra aplicación');
        await new Promise((resolve) => setTimeout(resolve, 100));
        continue;
      }
      try {
        try {
          fs.writeFileSync(fd, JSON.stringify({ pid: process.pid, token }));
          fs.fsyncSync(fd);
        } finally { fs.closeSync(fd); }
        return await work();
      }
      finally {
        try {
          const saved = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
          if (saved.token === token) fs.rmSync(lockPath);
        } catch {}
      }
    }
  }
  return { read, write, initialize, withLock, target };
}
module.exports = { createLinkingStorage, sharedLinkingPath, assertRegular };
