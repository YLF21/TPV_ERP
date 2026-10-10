const http = require('node:http');
const https = require('node:https');
const tls = require('node:tls');
const net = require('node:net');

// A discovered address changes the socket destination, never the authorised TLS identity.
function createFixedBackendTransport({ backendUrl, connectIp, ca } = {}) {
  const authority = new URL(backendUrl);
  const hostname = authority.hostname.replace(/^\[|\]$/g, '');
  if (!net.isIP(connectIp)) throw new Error('BACKEND_ADDRESS_INVALID');
  if (!['https:', 'http:'].includes(authority.protocol)
      || authority.username || authority.password || authority.pathname !== '/'
      || authority.search || authority.hash
      || (authority.protocol === 'http:' && (!['127.0.0.1', '::1'].includes(connectIp)
        || !['127.0.0.1', '::1', 'localhost'].includes(hostname)))) {
    throw new Error('BACKEND_TRANSPORT_INVALID');
  }
  const transport = authority.protocol === 'https:' ? https : http;
  const agent = authority.protocol === 'https:' ? new https.Agent({ keepAlive: false }) : new http.Agent({ keepAlive: false });
  if (authority.protocol === 'https:') {
    agent.createConnection = options => tls.connect({ ...options, host: connectIp,
      servername: net.isIP(hostname) ? '' : hostname, rejectUnauthorized: true,
      checkServerIdentity: (_host, certificate) => tls.checkServerIdentity(hostname, certificate),
      ...(ca ? { ca } : {}) });
  }
  function requestOptions(options = {}) {
    return { ...options, protocol: authority.protocol,
      hostname: authority.protocol === 'https:' ? hostname : connectIp,
      port: authority.port || (authority.protocol === 'https:' ? 443 : 80), agent,
      rejectUnauthorized: true, headers: { ...options.headers, host: authority.host } };
  }
  async function request(url, options = {}) {
    const target = new URL(url);
    if (target.origin !== authority.origin) throw new Error('BACKEND_AUTHORITY_CHANGED');
    return new Promise((resolve, reject) => {
      const req = transport.request(requestOptions({ method: options.method || 'GET',
        path: target.pathname + target.search, headers: options.headers, signal: options.signal }), response => {
        const chunks = []; let size = 0;
        response.on('data', chunk => {
          size += chunk.length;
          if (size > 65536) { response.destroy(new Error('BACKEND_RESPONSE_TOO_LARGE')); return; }
          chunks.push(chunk);
        });
        response.on('error', reject);
        response.on('end', () => resolve({ status: response.statusCode,
          ok: response.statusCode >= 200 && response.statusCode < 300,
          headers: new Headers(Object.entries(response.headers).filter(([, value]) => value !== undefined)
            .map(([name, value]) => [name, Array.isArray(value) ? value.join(', ') : String(value)])),
          text: async () => Buffer.concat(chunks).toString('utf8') }));
      });
      req.on('error', reject);
      req.setTimeout(8000, () => req.destroy(Object.assign(new Error('Backend timeout'), { code: 'ETIMEDOUT' })));
      req.end(options.body);
    });
  }
  return { backendUrl: authority.origin, connectIp, request, requestOptions, transport,
    close: () => agent.destroy() };
}
module.exports = { createFixedBackendTransport };
