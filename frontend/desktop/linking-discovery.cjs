const dgram = require('node:dgram');
const os = require('node:os');
const { candidateUrl, verifyBootstrap, readTextLimited } = require('./terminal-linking.cjs');
const SERVICE = '_tpv-erp._tcp.local';
const MULTICAST_ADDRESS = '224.0.0.251';
const MAX_INTERFACES = 8;

function privateIpv4Interfaces(networkInterfaces) {
  const addresses = new Set();
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.internal || (entry.family !== 'IPv4' && entry.family !== 4)) continue;
      const parts = entry.address?.split('.').map(Number);
      if (parts?.length !== 4 || parts.some(part => !Number.isInteger(part) || part < 0 || part > 255)) continue;
      if (parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
          || (parts[0] === 192 && parts[1] === 168)) addresses.add(entry.address);
      if (addresses.size >= MAX_INTERFACES) return [...addresses];
    }
  }
  return [...addresses];
}

function encodeName(name) {
  const labels = name.split('.').map((part) => Buffer.from(part, 'utf8'));
  if (labels.some((part) => !part.length || part.length > 63)
      || labels.reduce((length, part) => length + part.length + 1, 1) > 255) throw new Error('DNS name invalid');
  return Buffer.concat(labels.map((part) => Buffer.concat([Buffer.from([part.length]), part])).concat(Buffer.from([0])));
}
function readName(packet, offset, depth = 0) {
  if (depth > 8) throw new Error('DNS compression recursion');
  const labels = [];
  let cursor = offset, next = offset;
  for (let count = 0; count < 30; count++) {
    if (cursor >= packet.length) throw new Error('DNS name truncated');
    const length = packet[cursor++];
    if ((length & 0xc0) === 0xc0) {
      if (cursor >= packet.length) throw new Error('DNS pointer truncated');
      const pointer = ((length & 0x3f) << 8) | packet[cursor++];
      if (pointer >= packet.length) throw new Error('DNS pointer invalid');
      const pointed = readName(packet, pointer, depth + 1);
      labels.push(pointed.name);
      next = cursor;
      return { name: labels.join('.'), next };
    }
    if (length === 0) return { name: labels.join('.'), next: cursor };
    if (length > 63 || cursor + length > packet.length) throw new Error('DNS label invalid');
    labels.push(packet.toString('utf8', cursor, cursor + length));
    cursor += length;
    next = cursor;
  }
  throw new Error('DNS name too long');
}
function parseRecords(packet) {
  if (packet.length < 12 || (packet.readUInt16BE(2) & 0x8000) === 0) return [];
  let offset = 12;
  const questions = packet.readUInt16BE(4);
  const records = packet.readUInt16BE(6) + packet.readUInt16BE(8) + packet.readUInt16BE(10);
  if (questions > 100 || records > 200) return [];
  for (let i = 0; i < questions; i++) { offset = readName(packet, offset).next + 4; if (offset > packet.length) return []; }
  const found = [];
  for (let i = 0; i < records; i++) {
    const parsed = readName(packet, offset); offset = parsed.next;
    if (offset + 10 > packet.length) break;
    const type = packet.readUInt16BE(offset);
    const dnsClass = packet.readUInt16BE(offset + 2) & 0x7fff;
    const ttl = packet.readUInt32BE(offset + 4);
    const length = packet.readUInt16BE(offset + 8);
    offset += 10; const end = offset + length;
    if (end > packet.length) break;
    if (dnsClass !== 1) { offset = end; continue; }
    if (type === 12) {
      const target = readName(packet, offset);
      if (target.next === end) found.push({ type: 'PTR', name: parsed.name, target: target.name, ttl });
    }
    if (type === 33 && length >= 7) {
      const target = readName(packet, offset + 6);
      if (target.next === end) found.push({ type: 'SRV', name: parsed.name,
        port: packet.readUInt16BE(offset + 4), target: target.name, ttl });
    }
    if (type === 1 && length === 4) found.push({ type: 'A', name: parsed.name,
      address: [...packet.subarray(offset, end)].join('.'), ttl });
    if (type === 16) {
      const txt = {};
      let pos = offset;
      while (pos < end) {
        const size = packet[pos++]; if (pos + size > end) break;
        const item = packet.toString('utf8', pos, pos + size); pos += size;
        const split = item.indexOf('='); if (split > 0) txt[item.slice(0, split)] = item.slice(split + 1);
      }
      if (pos === end) found.push({ type: 'TXT', name: parsed.name, txt, ttl });
    }
    offset = end;
  }
  return found;
}
function activeRecords(records) {
  const instances = new Map(), txt = new Map(), srv = new Map(), addresses = new Map();
  for (const record of records) {
    const name = record.name.toLowerCase();
    if (record.type === 'PTR' && name === SERVICE) {
      const target = record.target.toLowerCase();
      if (!target.endsWith(`.${SERVICE}`)) continue;
      if (record.ttl === 0) instances.delete(target);
      else instances.set(target, record.target);
    } else if (record.type === 'TXT') {
      if (record.ttl === 0) txt.delete(name);
      else txt.set(name, record.txt);
    } else if (record.type === 'SRV') {
      if (record.ttl === 0) srv.delete(name);
      else srv.set(name, record);
    } else if (record.type === 'A') {
      if (!addresses.has(name)) addresses.set(name, new Set());
      if (record.ttl === 0) addresses.get(name).delete(record.address);
      else addresses.get(name).add(record.address);
    }
  }
  return { instances, txt, srv, addresses };
}
function sendQuery(socket, name, type) {
  const encoded = encodeName(name);
  const query = Buffer.alloc(12 + encoded.length + 4);
  query.writeUInt16BE(1, 4); encoded.copy(query, 12);
  query.writeUInt16BE(type, 12 + encoded.length); query.writeUInt16BE(1, 14 + encoded.length);
  return new Promise((resolve, reject) => socket.send(query, 5353, MULTICAST_ADDRESS,
    error => error ? reject(error) : resolve()));
}
async function sendQueries(socket, interfaces, queries) {
  for (const address of interfaces) {
    try {
      socket.setMulticastInterface(address);
      for (const [name, type] of queries) await sendQuery(socket, name, type);
    } catch { /* One failed adapter must not suppress queries on the others. */ }
  }
}
function addRemoteCandidates(records, results) {
  const active = activeRecords(records);
  for (const [instance, labelName] of active.instances) {
    const metadata = active.txt.get(instance), service = active.srv.get(instance);
    if (!metadata || !service || metadata.protocol !== '1' || !metadata.url || !service.port) continue;
    const physicalAddresses = [...(active.addresses.get(service.target.toLowerCase()) || [])];
    if (!physicalAddresses.length) continue;
    try {
      const backendUrl = candidateUrl(metadata.url);
      const parsed = new URL(backendUrl);
      if (service.port !== (parsed.port ? Number(parsed.port) : 443)) continue;
      if (results.size >= 64 && !results.has(backendUrl)) continue;
      const previous = results.get(backendUrl);
      const installationId = metadata.installationId;
      const safeInstallationId = typeof installationId === 'string' && installationId.length > 0
        && installationId.length <= 100 && !/[\x00-\x1f\x7f]/.test(installationId) ? installationId : undefined;
      if (previous?.installationId && safeInstallationId && previous.installationId !== safeInstallationId) continue;
      results.set(backendUrl, { backendUrl, label: labelName.replace(/\._tpv-erp\._tcp\.local$/i, ''),
        ...(safeInstallationId ? { installationId: safeInstallationId } : {}),
        addresses: [...new Set([...(previous?.addresses || []), ...physicalAddresses])] });
    } catch { /* DNS-SD data is untrusted; bootstrap still checks TLS and the signed identity. */ }
  }
}
async function discoverBackends({ timeoutMs = 1800, socketFactory = () => dgram.createSocket({ type: 'udp4', reuseAddr: true }),
  localUrl = 'http://127.0.0.1:8080', request = fetch, networkInterfaces = os.networkInterfaces } = {}) {
  const results = new Map();
  try {
    const challenge = require('node:crypto').randomBytes(32).toString('base64url');
    const response = await request(`${localUrl}/api/v1/terminal-linking/bootstrap?challenge=${challenge}`,
      { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(1200) });
    if (response.ok && Number(response.headers?.get('content-length') || 0) <= 65536) {
      verifyBootstrap(JSON.parse(await readTextLimited(response)), challenge);
      results.set(localUrl, { backendUrl: localUrl, label: 'Este equipo', addresses: [] });
    }
  } catch { /* The local backend may not be installed yet. */ }
  const socket = socketFactory();
  const records = [];
  let packets = 0;
  socket.on('error', () => {}); // UDP errors after bind must not become uncaught exceptions.
  socket.on('message', (packet) => {
    if (++packets > 128 || packet.length > 9000 || records.length >= 512) return;
    try { records.push(...parseRecords(packet).slice(0, 512 - records.length)); } catch {}
  });
  try {
    await new Promise((resolve, reject) => { socket.once('error', reject); socket.bind(5353, () => { socket.off('error', reject); resolve(); }); });
    const interfaces = [];
    for (const address of privateIpv4Interfaces(networkInterfaces)) {
      try { socket.addMembership(MULTICAST_ADDRESS, address); interfaces.push(address); }
      catch { /* Adapter may have disappeared since enumeration. */ }
    }
    if (!interfaces.length) return [...results.values()];
    const nameLimit = Math.max(1, Math.floor(64 / interfaces.length));
    await sendQueries(socket, interfaces, [[SERVICE, 12]]);
    const bounded = Math.min(Math.max(timeoutMs, 100), 5000);
    const phase = Math.min(500, Math.max(30, Math.floor(bounded / 3)));
    await new Promise((resolve) => setTimeout(resolve, phase));
    await sendQueries(socket, interfaces, [...activeRecords(records).instances.values()].slice(0, nameLimit)
      .flatMap(instance => [[instance, 16], [instance, 33]]));
    await new Promise((resolve) => setTimeout(resolve, phase));
    const current = activeRecords(records);
    const targets = new Set([...current.instances.keys()]
      .map((instance) => current.srv.get(instance)?.target).filter(Boolean));
    await sendQueries(socket, interfaces, [...targets].slice(0, nameLimit).map(target => [target, 1]));
    await new Promise((resolve) => setTimeout(resolve, bounded - phase * 2));
  } catch { /* Local candidate remains available if multicast is unavailable. */ }
  finally { try { socket.close(); } catch {} }
  addRemoteCandidates(records, results);
  return [...results.values()];
}
module.exports = { discoverBackends, parseRecords, encodeName, SERVICE };
