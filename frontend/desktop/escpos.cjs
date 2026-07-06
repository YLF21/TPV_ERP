const fs = require("node:fs");
const net = require("node:net");

const ESC = 0x1b;
const GS = 0x1d;

function textBuffer(value = "") {
  return Buffer.from(String(value), "latin1");
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
  const chunks = [
    Buffer.from([ESC, 0x40]),
    Buffer.from([ESC, 0x61, 0x01]),
    line(ticket.storeName || "APP VENTA"),
    line(ticket.documentNumber || ""),
    line(`Terminal ${ticket.terminalCode || ""}`),
    line(ticket.issuedAt || ""),
    Buffer.from([ESC, 0x61, 0x00]),
    line("------------------------------------------")
  ];

  for (const item of ticket.lines || []) {
    chunks.push(line(String(item.name || "").slice(0, 42)));
    chunks.push(line(padColumns(`${item.quantity} x ${money(item.price)}`, money(item.total))));
  }

  chunks.push(line("------------------------------------------"));
  for (const payment of ticket.payments || []) {
    chunks.push(line(padColumns(payment.method || "", money(payment.amount))));
  }
  chunks.push(line("------------------------------------------"));
  chunks.push(Buffer.from([ESC, 0x45, 0x01]));
  chunks.push(line(padColumns("TOTAL", money(ticket.total))));
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
  sendEscposBuffer
};
