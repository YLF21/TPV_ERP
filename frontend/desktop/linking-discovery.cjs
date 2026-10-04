const dgram = require('node:dgram');
const { candidateUrl, verifyBootstrap, readTextLimited } = require('./terminal-linking.cjs');
const SERVICE = '_tpv-erp._tcp.local';

function encodeName(name) {
  return Buffer.concat(name.split('.').map((part) => { const data = Buffer.from(part, 'utf8'); return Buffer.concat([Buffer.from([data.length]), data]); }).concat(Buffer.from([0])));
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
      break;
    }
    if (length === 0) { next = cursor; break; }
    if (length > 63 || cursor + length > packet.length) throw new Error('DNS label invalid');
    labels.push(packet.toString('utf8', cursor, cursor + length));
    cursor += length;
    next = cursor;
  }
  return { name: labels.join('.'), next };
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
    const type = packet.readUInt16BE(offset); const length = packet.readUInt16BE(offset + 8);
    offset += 10; const end = offset + length;
    if (end > packet.length) break;
    if (type === 12) found.push({ type: 'PTR', name: parsed.name, target: readName(packet, offset).name });
    if (type === 16) {
      const txt = {};
      let pos = offset;
      while (pos < end) {
        const size = packet[pos++]; if (pos + size > end) break;
        const item = packet.toString('utf8', pos, pos + size); pos += size;
        const split = item.indexOf('='); if (split > 0) txt[item.slice(0, split)] = item.slice(split + 1);
      }
      found.push({ type: 'TXT', name: parsed.name, txt });
    }
    offset = end;
  }
  return found;
}
async function discoverBackends({ timeoutMs = 1800, socketFactory = () => dgram.createSocket({ type: 'udp4', reuseAddr: true }),
  localUrl = 'http://127.0.0.1:8080', request = fetch } = {}) {
  const results = new Map();
  try {
    const challenge = require('node:crypto').randomBytes(32).toString('base64url');
    const response = await request(`${localUrl}/api/v1/terminal-linking/bootstrap?challenge=${challenge}`,
      { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(1200) });
    if (response.ok && Number(response.headers?.get('content-length') || 0) <= 65536) {
      verifyBootstrap(JSON.parse(await readTextLimited(response)), challenge);
      results.set(localUrl, { backendUrl: localUrl, label: 'Este equipo' });
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
    socket.addMembership('224.0.0.251');
    const name = encodeName(SERVICE);
    const query = Buffer.alloc(12 + name.length + 4);
    query.writeUInt16BE(1, 4); name.copy(query, 12);
    query.writeUInt16BE(12, 12 + name.length); query.writeUInt16BE(1, 14 + name.length);
    socket.send(query, 5353, '224.0.0.251');
    const bounded = Math.min(Math.max(timeoutMs, 100), 5000);
    await new Promise((resolve) => setTimeout(resolve, Math.min(bounded, 700)));
    for (const instance of new Set(records.filter((item) => item.type === 'PTR' && item.name.toLowerCase() === SERVICE).map((item) => item.target))) {
      const instanceName = encodeName(instance);
      const txtQuery = Buffer.alloc(12 + instanceName.length + 4);
      txtQuery.writeUInt16BE(1, 4); instanceName.copy(txtQuery, 12);
      txtQuery.writeUInt16BE(16, 12 + instanceName.length); txtQuery.writeUInt16BE(1, 14 + instanceName.length);
      socket.send(txtQuery, 5353, '224.0.0.251');
    }
    if (bounded > 700) await new Promise((resolve) => setTimeout(resolve, bounded - 700));
  } catch { /* Local candidate remains available if multicast is unavailable. */ }
  finally { try { socket.close(); } catch {} }
  const instances = new Set(records.filter((item) => item.type === 'PTR' && item.name.toLowerCase() === SERVICE).map((item) => item.target.toLowerCase()));
  for (const record of records) {
    if (record.type !== 'TXT' || !instances.has(record.name.toLowerCase()) || record.txt.protocol !== '1' || !record.txt.url) continue;
    try {
      const backendUrl = candidateUrl(record.txt.url);
      if (results.size < 64 || results.has(backendUrl))
        results.set(backendUrl, { backendUrl, label: record.name.replace(/\._tpv-erp\._tcp\.local$/i, '') });
    } catch { /* DNS-SD is untrusted; probe still performs TLS and signature checks. */ }
  }
  return [...results.values()];
}
module.exports = { discoverBackends, parseRecords, encodeName, SERVICE };
