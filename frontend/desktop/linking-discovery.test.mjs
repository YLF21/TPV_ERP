import { test, expect } from 'vitest';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { parseRecords, encodeName, SERVICE } = require('./linking-discovery.cjs');

function record(name, type, payload) {
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0); head.writeUInt16BE(1, 2);
  head.writeUInt16BE(120, 4); head.writeUInt16BE(payload.length, 8);
  return Buffer.concat([encodeName(name), head, payload]);
}
test('DNS-SD reads service PTR and TXT URL without trusting SRV target', () => {
  const instance = `esPOS-test.${SERVICE}`;
  const txt = Buffer.concat(['url=https://shop.example:8443', 'protocol=1', 'installationId=id']
    .map(value => Buffer.concat([Buffer.from([Buffer.byteLength(value)]), Buffer.from(value)])));
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0x8400, 2); header.writeUInt16BE(2, 6);
  const packet = Buffer.concat([header, record(SERVICE, 12, encodeName(instance)), record(instance, 16, txt)]);
  expect(parseRecords(packet)).toEqual([
    { type: 'PTR', name: SERVICE, target: instance },
    { type: 'TXT', name: instance, txt: { url: 'https://shop.example:8443', protocol: '1', installationId: 'id' } }
  ]);
  expect(parseRecords(Buffer.from([1, 2, 3]))).toEqual([]);
});
