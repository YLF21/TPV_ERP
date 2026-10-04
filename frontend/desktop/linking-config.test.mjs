import { test, expect } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { createLinkingConfig } = require('./linking-config.cjs');

async function withPlatform(platform, action) {
  const original = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { ...original, value: platform });
  try { await action(); }
  finally { Object.defineProperty(process, 'platform', original); }
}

test('protected commit passes only a validated URL to elevation and verifies readback', async () => {
  await withPlatform('win32', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tpv-config-test-'));
    try {
      const configPath = path.join(dir, 'backend-config.json');
      const calls = [];
      const config = createLinkingConfig({ configPath, packaged: true, helperPath: 'helper.ps1', elevation: async (url, helper) => {
        calls.push({ url, helper });
        fs.writeFileSync(configPath, JSON.stringify({ backendUrl: url, allowedHosts: [new URL(url).hostname] }));
      } });
      await expect(config.commit('http://backend.example:8080')).rejects.toThrow(/HTTPS/);
      expect(calls).toHaveLength(0);
      await config.commit('https://backend.example:8443');
      expect(calls).toEqual([{ url: 'https://backend.example:8443', helper: 'helper.ps1' }]);
      expect(config.read().configuration).toEqual({ backendUrl: 'https://backend.example:8443' });
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
  });
});

test.each(['linux', 'darwin'])('protected commit rejects %s before invoking elevation', async platform => {
  await withPlatform(platform, async () => {
    const calls = [];
    const config = createLinkingConfig({ packaged: true, elevation: async (...args) => calls.push(args) });
    await expect(config.commit('https://backend.example:8443')).rejects.toThrow(/requiere Windows/);
    expect(calls).toHaveLength(0);
  });
});

test('protected commit rejects a successful elevation that did not save the requested configuration', async () => {
  await withPlatform('win32', async () => {
    const config = createLinkingConfig({ packaged: true, elevation: async () => {} });
    await expect(config.commit('https://backend.example:8443')).rejects.toThrow(/verificar la configuración protegida/);
  });
});
