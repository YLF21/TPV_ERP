import { test, expect } from 'vitest';
import { createRequire } from 'node:module';
import { EventEmitter } from 'node:events';
const require = createRequire(import.meta.url);
const { discoverBackends, parseRecords, encodeName, SERVICE } = require('./linking-discovery.cjs');
const INSTANCE = `esPOS-test.${SERVICE}`;
const TARGET = 'server.local';
const URL = 'https://shop.example:8443';
const ID = '9f726a66-3906-45f8-bf0c-51db4fd51a99';

function record(name, type, payload, ttl = 120) {
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0); head.writeUInt16BE(1, 2);
  head.writeUInt32BE(ttl, 4); head.writeUInt16BE(payload.length, 8);
  return Buffer.concat([encodeName(name), head, payload]);
}
function packet(...records) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0x8400, 2); header.writeUInt16BE(records.length, 6);
  return Buffer.concat([header, ...records]);
}
function txt(fields = { url: URL, protocol: '1', installationId: ID }) {
  return Buffer.concat(Object.entries(fields).map(([key, value]) => {
    const data = Buffer.from(`${key}=${value}`);
    return Buffer.concat([Buffer.from([data.length]), data]);
  }));
}
function srv(port = 8443, target = TARGET) {
  const fields = Buffer.alloc(6);
  fields.writeUInt16BE(port, 4);
  return Buffer.concat([fields, encodeName(target)]);
}
function queryType(query) { return query.readUInt16BE(query.length - 4); }

class FakeSocket extends EventEmitter {
  constructor(reply, failedMembership) {
    super(); this.reply = reply; this.failedMembership = failedMembership;
    this.queries = []; this.sentInterfaces = []; this.memberships = []; this.closed = false;
  }
  bind(_port, callback) { callback(); }
  addMembership(_group, address) {
    if (address === this.failedMembership) throw new Error('adapter unavailable');
    this.memberships.push(address);
  }
  setMulticastInterface(address) { this.interface = address; }
  send(query, _port, _group, callback) {
    this.queries.push(queryType(query));
    this.sentInterfaces.push(this.interface);
    const response = this.reply(queryType(query), this.interface);
    if (response) queueMicrotask(() => this.emit('message', response));
    callback();
  }
  close() { this.closed = true; }
}
const oneInterface = () => ({ ethernet: [{ address: '192.168.1.10', family: 'IPv4', internal: false }] });
async function discoverWith(reply, { networkInterfaces = oneInterface, failedMembership } = {}) {
  const socket = new FakeSocket(reply, failedMembership);
  const candidates = await discoverBackends({ timeoutMs: 100, socketFactory: () => socket,
    networkInterfaces, request: async () => { throw new Error('no local backend'); } });
  return { candidates, socket };
}

test('DNS-SD parses PTR, TXT, SRV and A with TTL and bounds', () => {
  const response = packet(record(SERVICE, 12, encodeName(INSTANCE)), record(INSTANCE, 16, txt()),
    record(INSTANCE, 33, srv()), record(TARGET, 1, Buffer.from([192, 168, 1, 20])));
  expect(parseRecords(response)).toEqual([
    { type: 'PTR', name: SERVICE, target: INSTANCE, ttl: 120 },
    { type: 'TXT', name: INSTANCE, txt: { url: URL, protocol: '1', installationId: ID }, ttl: 120 },
    { type: 'SRV', name: INSTANCE, port: 8443, target: TARGET, ttl: 120 },
    { type: 'A', name: TARGET, address: '192.168.1.20', ttl: 120 }
  ]);
  expect(parseRecords(Buffer.from([1, 2, 3]))).toEqual([]);
  expect(() => encodeName(`long.${'x'.repeat(64)}.local`)).toThrow();
});

test('queries PTR, TXT, SRV and A and returns only the linked physical address', async () => {
  const { candidates, socket } = await discoverWith((type) => {
    if (type === 12) return packet(record(SERVICE, 12, encodeName(INSTANCE)));
    if (type === 16) return packet(record(INSTANCE, 16, txt()));
    if (type === 33) return packet(record(INSTANCE, 33, srv()));
    if (type === 1) return packet(record(TARGET, 1, Buffer.from([192, 168, 1, 20])),
      record('unrelated.local', 1, Buffer.from([10, 0, 0, 99])));
  });
  expect(socket.queries).toEqual([12, 16, 33, 1]);
  expect(socket.closed).toBe(true);
  expect(candidates).toEqual([{ backendUrl: URL, label: 'esPOS-test', installationId: ID,
    addresses: ['192.168.1.20'] }]);
});

test('requires matching SRV port and an address on its target', async () => {
  const identity = [record(SERVICE, 12, encodeName(INSTANCE)), record(INSTANCE, 16, txt())];
  const wrongPort = packet(...identity, record(INSTANCE, 33, srv(9443)),
    record(TARGET, 1, Buffer.from([192, 168, 1, 20])));
  const wrongTarget = packet(...identity, record(INSTANCE, 33, srv()),
    record('unrelated.local', 1, Buffer.from([192, 168, 1, 20])));
  for (const response of [wrongPort, wrongTarget]) {
    const { candidates } = await discoverWith((type) => type === 12 ? response : null);
    expect(candidates).toEqual([]);
  }
});

test('TTL-zero goodbyes remove stale PTR, SRV and A records', async () => {
  const initial = [record(SERVICE, 12, encodeName(INSTANCE)), record(INSTANCE, 16, txt()),
    record(INSTANCE, 33, srv()), record(TARGET, 1, Buffer.from([192, 168, 1, 20]))];
  for (const goodbye of [
    record(SERVICE, 12, encodeName(INSTANCE), 0),
    record(INSTANCE, 16, txt(), 0),
    record(INSTANCE, 33, srv(), 0),
    record(TARGET, 1, Buffer.from([192, 168, 1, 20]), 0)
  ]) {
    const { candidates } = await discoverWith((type) => type === 12 ? packet(...initial, goodbye) : null);
    expect(candidates).toEqual([]);
  }
});

test('replaces an old physical IP while preserving the logical HTTPS URL', async () => {
  const response = packet(record(SERVICE, 12, encodeName(INSTANCE)), record(INSTANCE, 16, txt()),
    record(INSTANCE, 33, srv()), record(TARGET, 1, Buffer.from([192, 168, 1, 20])),
    record(TARGET, 1, Buffer.from([192, 168, 1, 20]), 0),
    record(TARGET, 1, Buffer.from([192, 168, 1, 21])));
  const { candidates } = await discoverWith((type) => type === 12 ? response : null);
  expect(candidates).toEqual([{ backendUrl: URL, label: 'esPOS-test', installationId: ID,
    addresses: ['192.168.1.21'] }]);
});

test('joins and sends every DNS-SD query on both private IPv4 interfaces', async () => {
  const hostOnly = '192.168.82.101';
  const networkInterfaces = () => ({
    nat: [{ address: '10.0.2.15', family: 'IPv4', internal: false }],
    loopback: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
    hostOnly: [{ address: hostOnly, family: 'IPv4', internal: false }],
    public: [{ address: '8.8.8.8', family: 'IPv4', internal: false }]
  });
  const { candidates, socket } = await discoverWith((type, address) => {
    if (address !== hostOnly) return null;
    if (type === 12) return packet(record(SERVICE, 12, encodeName(INSTANCE)));
    if (type === 16) return packet(record(INSTANCE, 16, txt()));
    if (type === 33) return packet(record(INSTANCE, 33, srv()));
    if (type === 1) return packet(record(TARGET, 1, Buffer.from([192, 168, 82, 2])));
    return null;
  }, { networkInterfaces });
  expect(socket.memberships).toEqual(['10.0.2.15', hostOnly]);
  expect(socket.sentInterfaces).toEqual(['10.0.2.15', hostOnly,
    '10.0.2.15', '10.0.2.15', hostOnly, hostOnly, '10.0.2.15', hostOnly]);
  expect(candidates[0]?.addresses).toEqual(['192.168.82.2']);
});

test('a failed private adapter does not prevent discovery on the next adapter', async () => {
  const hostOnly = '192.168.82.101';
  const networkInterfaces = () => ({
    nat: [{ address: '10.0.2.15', family: 'IPv4', internal: false }],
    hostOnly: [{ address: hostOnly, family: 'IPv4', internal: false }]
  });
  const response = packet(record(SERVICE, 12, encodeName(INSTANCE)), record(INSTANCE, 16, txt()),
    record(INSTANCE, 33, srv()), record(TARGET, 1, Buffer.from([192, 168, 82, 2])));
  const { candidates, socket } = await discoverWith(type => type === 12 ? response : null,
    { networkInterfaces, failedMembership: '10.0.2.15' });
  expect(socket.memberships).toEqual([hostOnly]);
  expect(socket.sentInterfaces).toEqual([hostOnly, hostOnly, hostOnly, hostOnly]);
  expect(candidates[0]?.addresses).toEqual(['192.168.82.2']);
});
