const fs = require('node:fs');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { candidateUrl } = require('./terminal-linking.cjs');
const { readBackendConfig, resolveBackendConfig } = require('./backend-config.cjs');

function powershellQuote(value) { return `'${String(value).replaceAll("'", "''")}'`; }
function elevatedCommit(backendUrl, helper) {
  return new Promise((resolve, reject) => {
    const script = `$argsLine='-NoProfile -ExecutionPolicy Bypass -File "' + ${powershellQuote(helper)} + '" -BackendUrl "' + ${powershellQuote(backendUrl)} + '"'; $p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $argsLine -Wait -PassThru -WindowStyle Hidden; if ($p.ExitCode -ne 0) { exit $p.ExitCode }`;
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, timeout: 120000 },
      (err) => err ? reject(err) : resolve());
  });
}
function createLinkingConfig({ configPath, packaged, helperPath, elevation = elevatedCommit } = {}) {
  function read() {
    try {
      const configuration = readBackendConfig(configPath);
      if (!configuration.backendUrl) return { configuration: null };
      const resolved = resolveBackendConfig({ configPath, useEnvironment: false });
      return { configuration: { backendUrl: resolved.backendUrl } };
    } catch (err) {
      return { configuration: null, error: err?.message || 'Configuración no válida' };
    }
  }
  async function commit(rawUrl) {
    const backendUrl = candidateUrl(rawUrl);
    if (!packaged) {
      const parent = path.dirname(configPath);
      fs.mkdirSync(parent, { recursive: true });
      const temp = `${configPath}.${process.pid}.tmp`;
      const host = new URL(backendUrl).hostname;
      try {
        fs.writeFileSync(temp, JSON.stringify({ backendUrl, allowedHosts: [host] }), { flag: 'w', mode: 0o600 });
        fs.renameSync(temp, configPath);
      } finally { try { fs.rmSync(temp, { force: true }); } catch {} }
    } else {
      if (process.platform !== 'win32') throw new Error('El cambio protegido requiere Windows');
      await elevation(backendUrl, helperPath);
    }
    const saved = read();
    if (saved.error || saved.configuration?.backendUrl !== backendUrl) throw new Error('No se pudo verificar la configuración protegida');
  }
  return { read, commit };
}
module.exports = { createLinkingConfig, elevatedCommit };
