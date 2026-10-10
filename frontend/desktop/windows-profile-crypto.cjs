const { execFileSync } = require('node:child_process');
const path = require('node:path');

const MAGIC = Buffer.from('TPV-ERP-DPAPI-V1\0', 'ascii');
const MAX_PLAINTEXT_BYTES = 64 * 1024;
const MAX_ENCRYPTED_BYTES = 256 * 1024;
const ENTROPY = 'TPV-ERP-DESKTOP-LINKING-V1';
const POWERSHELL_RELATIVE_PATH = path.win32.join('System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');

// Only the fixed operation changes; input data is read from stdin.
function script(operation) {
  return [
    "$ErrorActionPreference='Stop'",
    'Add-Type -AssemblyName System.Security',
    '$inputBytes=[Convert]::FromBase64String([Console]::In.ReadToEnd())',
    `$entropy=[Text.Encoding]::UTF8.GetBytes('${ENTROPY}')`,
    `$outputBytes=[Security.Cryptography.ProtectedData]::${operation}($inputBytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)`,
    '[Console]::Out.Write([Convert]::ToBase64String($outputBytes))'
  ].join(';');
}

const ENCRYPT_SCRIPT = script('Protect');
const DECRYPT_SCRIPT = script('Unprotect');

function unavailable() {
  const error = new Error('Almacenamiento compartido de Windows no disponible');
  error.code = 'SHARED_STORAGE_UNAVAILABLE';
  return error;
}

function isEncrypted(value) {
  return Buffer.isBuffer(value) && value.length >= MAGIC.length && value.subarray(0, MAGIC.length).equals(MAGIC);
}

function decodeOutput(output, limit) {
  const encoded = Buffer.isBuffer(output) ? output.toString('utf8') : output;
  if (typeof encoded !== 'string' || !encoded || encoded.length > Math.ceil(limit / 3) * 4 + 4
      || /[^A-Za-z0-9+/=]/.test(encoded)) {
    throw unavailable();
  }
  const result = Buffer.from(encoded, 'base64');
  if (result.length > limit || result.toString('base64') !== encoded) throw unavailable();
  return result;
}

function createWindowsProfileCrypto({ run = execFileSync, platform = process.platform,
  systemRoot = process.env.SystemRoot, maxPlaintextBytes = MAX_PLAINTEXT_BYTES } = {}) {
  if (!Number.isInteger(maxPlaintextBytes) || maxPlaintextBytes < 1 || maxPlaintextBytes > 8 * 1024 * 1024) throw unavailable();
  const maxEncryptedBytes = Math.max(MAX_ENCRYPTED_BYTES, maxPlaintextBytes + 65536);
  function protect(input, operation, outputLimit) {
    if (platform !== 'win32' || typeof systemRoot !== 'string' || !path.win32.isAbsolute(systemRoot)
        || !/^[A-Za-z]:\\(?:[^<>:"|?*\x00-\x1f]+\\?)*$/.test(systemRoot)) throw unavailable();
    const executable = path.win32.join(systemRoot, POWERSHELL_RELATIVE_PATH);
    let output;
    try {
      output = run(executable, ['-NoProfile', '-NonInteractive', '-Command', operation], {
        input: input.toString('base64'), encoding: 'utf8', windowsHide: true,
        shell: false, timeout: 10000, maxBuffer: Math.ceil(maxEncryptedBytes / 3) * 4 + 16384,
        stdio: ['pipe', 'pipe', 'pipe']
      });
    } catch { throw unavailable(); }
    return decodeOutput(output, outputLimit);
  }

  return {
    encryptString(text) {
      if (typeof text !== 'string') throw unavailable();
      const plaintext = Buffer.from(text, 'utf8');
      if (plaintext.length > maxPlaintextBytes) throw unavailable();
      const encrypted = protect(plaintext, ENCRYPT_SCRIPT, maxEncryptedBytes);
      if (!encrypted.length) throw unavailable();
      return Buffer.concat([MAGIC, encrypted]);
    },
    decryptString(value) {
      if (!isEncrypted(value) || value.length <= MAGIC.length
          || value.length - MAGIC.length > maxEncryptedBytes) throw unavailable();
      const plaintext = protect(value.subarray(MAGIC.length), DECRYPT_SCRIPT, maxPlaintextBytes);
      const text = plaintext.toString('utf8');
      if (!Buffer.from(text, 'utf8').equals(plaintext)) throw unavailable();
      return text;
    },
    isEncrypted
  };
}

module.exports = { createWindowsProfileCrypto, MAGIC, isEncrypted };
