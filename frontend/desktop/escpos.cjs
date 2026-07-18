const fs = require("node:fs");
const net = require("node:net");

const ESC = 0x1b;
const GS = 0x1d;

function textBuffer(value = "") {
  // Raw ESC/POS is configured for a single-byte Latin code page. Unsupported
  // glyphs are replaced deterministically; deployments needing CJK must pass
  // printable/transliterated labels supported by their configured printer page.
  const printable = Array.from(String(value).normalize("NFD"))
    .filter((character) => !/[\u0300-\u036f]/.test(character))
    .map((character) => character.codePointAt(0) <= 0xff ? character : "?")
    .join("");
  return Buffer.from(printable, "latin1");
}

function line(value = "") {
  return Buffer.concat([textBuffer(value), Buffer.from([0x0a])]);
}

function money(value) {
  return Number(value || 0).toFixed(2);
}

function padColumns(left, right, width = 42) {
  const safeLeft = String(left);
  const safeRight = String(right);
  const spaces = Math.max(1, width - safeLeft.length - safeRight.length);
  return `${safeLeft}${" ".repeat(spaces)}${safeRight}`;
}

function buildCashDrawerBuffer() {
  return Buffer.from([ESC, 0x70, 0x00, 0x19, 0xfa]);
}

function buildTicketBuffer(ticket) {
  const suppliedLabels = ticket.escposLabels || ticket.labels;
  const labels = { terminal: "Terminal", item: "Item", quantity: "Qty.", price: "Price", total: "TOTAL", ...(suppliedLabels || {}) };
  const raw = ticket.escposContent;
  const chunks = [
    Buffer.from([ESC, 0x40]),
    Buffer.from([ESC, 0x61, 0x01]),
    line(raw?.storeName || ticket.storeName || "APP VENTA"),
    line(raw?.documentNumber || ticket.documentNumber || ""),
    line(`${labels.terminal} ${raw?.terminalCode || ticket.terminalCode || ""}`),
    line(ticket.issuedAt || ""),
    Buffer.from([ESC, 0x61, 0x00]),
    line("------------------------------------------")
  ];
  const partyLabels = { issuer: "Emisor", customer: "Cliente", taxId: "NIF", ...(ticket.partyLabels || {}) };
  for (const [label, party] of [[partyLabels.issuer, ticket.issuer], [partyLabels.customer, ticket.customer]]) {
    if (!party) continue;
    chunks.push(line(`${label}: ${party.name || ""}`));
    chunks.push(line(`${partyLabels.taxId}: ${party.taxId || ""}`));
    if (party.address) chunks.push(line(party.address));
  }
  if (ticket.issuer || ticket.customer) chunks.push(line("------------------------------------------"));
  if (suppliedLabels) chunks.push(line(padColumns(`${labels.item} / ${labels.quantity} / ${labels.price}`, labels.total)));

  for (const [index, item] of (ticket.lines || []).entries()) {
    chunks.push(line(String(raw?.lineNames?.[index] || item.name || "").slice(0, 42)));
    chunks.push(line(padColumns(`${item.quantity} x ${money(item.price)}`, money(item.total))));
  }

  chunks.push(line("------------------------------------------"));
  for (const [index, payment] of (ticket.payments || []).entries()) {
    chunks.push(line(padColumns(raw?.paymentMethods?.[index] || payment.method || "", money(payment.amount))));
  }
  chunks.push(line("------------------------------------------"));
  chunks.push(Buffer.from([ESC, 0x45, 0x01]));
  chunks.push(line(padColumns(labels.total, money(ticket.total))));
  chunks.push(Buffer.from([ESC, 0x45, 0x00]));
  chunks.push(Buffer.from([0x0a, 0x0a, 0x0a]));
  chunks.push(Buffer.from([GS, 0x56, 0x00]));

  return Buffer.concat(chunks);
}

function normalizeSerialPath(value) {
  const path = String(value || "").trim();
  if (!path) {
    return "";
  }
  if (path.startsWith("\\\\.\\")) {
    return path;
  }
  if (/^COM\d+$/i.test(path)) {
    return `\\\\.\\${path.toUpperCase()}`;
  }
  return path;
}

function normalizePaymentMethod(value) {
  return String(value || "")
    .trim()
    .toUpperCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}

function shouldOpenCashDrawerForTicket(config, ticket) {
  if (!config?.openCashDrawerWithTicket) {
    return false;
  }
  const configuredMethods = Array.isArray(config.cashDrawerOpeningPaymentMethods)
    ? config.cashDrawerOpeningPaymentMethods
    : ["EFECTIVO"];
  const enabledMethods = new Set(configuredMethods.map(normalizePaymentMethod));
  if (enabledMethods.size === 0) {
    return false;
  }
  return (ticket?.payments || []).some((payment) => enabledMethods.has(normalizePaymentMethod(payment.method)));
}

function writeNetwork(host, port, buffer) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port, timeout: 5000 }, () => {
      socket.write(buffer, () => {
        socket.end();
        resolve();
      });
    });
    socket.on("error", reject);
    socket.on("timeout", () => {
      socket.destroy(new Error("ESCPOS_NETWORK_TIMEOUT"));
    });
  });
}

function writeSerial(devicePath, buffer) {
  return new Promise((resolve, reject) => {
    const stream = fs.createWriteStream(normalizeSerialPath(devicePath), { flags: "w" });
    stream.on("error", reject);
    stream.on("finish", resolve);
    stream.end(buffer);
  });
}

async function sendEscposBuffer(config, buffer) {
  if (config.escposConnectionType === "NETWORK") {
    if (!config.escposHost || !config.escposPort) {
      throw new Error("Falta IP o puerto ESC/POS");
    }
    await writeNetwork(config.escposHost, Number(config.escposPort), buffer);
    return;
  }

  if (config.escposConnectionType === "SERIAL") {
    if (!config.escposDevicePath) {
      throw new Error("Falta puerto COM ESC/POS");
    }
    await writeSerial(config.escposDevicePath, buffer);
    return;
  }

  throw new Error("USB ESC/POS directo aun no disponible. Usa COM o LAN.");
}

module.exports = {
  buildCashDrawerBuffer,
  buildTicketBuffer,
  normalizeSerialPath,
  shouldOpenCashDrawerForTicket,
  sendEscposBuffer
};
