const { execFile } = require('node:child_process');
const path = require('node:path');

const POWERSHELL = path.join(
  process.env.SystemRoot || 'C:\\Windows',
  'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe',
);
const LIST_PORTS_SCRIPT = '[System.IO.Ports.SerialPort]::GetPortNames() | ConvertTo-Json -Compress';

function parsePorts(output) {
  const json = String(output).replace(/^\uFEFF/, '').trim();
  if (!json) return [];

  const names = JSON.parse(json);
  if (!Array.isArray(names) && typeof names !== 'string') {
    throw new Error('Respuesta inesperada al enumerar puertos serie');
  }

  const ports = new Map();
  for (const name of Array.isArray(names) ? names : [names]) {
    if (typeof name !== 'string') continue;
    const match = /^COM0*([1-9]\d*)$/i.exec(name.trim());
    if (!match) continue;
    const number = BigInt(match[1]);
    ports.set(number, { path: `COM${number}` });
  }
  return [...ports.entries()]
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
    .map(([, port]) => port);
}

function listSerialPorts({ executor = execFile, platform = process.platform } = {}) {
  if (platform !== 'win32') return Promise.reject(new Error('La enumeración de puertos COM requiere Windows'));

  return new Promise((resolve, reject) => {
    executor(POWERSHELL, ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', LIST_PORTS_SCRIPT], {
      windowsHide: true,
      timeout: 5000,
      maxBuffer: 64 * 1024,
      encoding: 'utf8',
    }, (error, stdout) => {
      if (error) return reject(error);
      try {
        resolve(parsePorts(stdout));
      } catch (parseError) {
        reject(parseError);
      }
    });
  });
}

module.exports = { listSerialPorts };
