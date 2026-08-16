const fs = require("node:fs");
const net = require("node:net");

const ESC = 0x1b;
const GS = 0x1d;
const MAX_ADDITIONAL_FEED_LINES = 12;

function additionalFeedLines(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return 0;
  return Math.min(MAX_ADDITIONAL_FEED_LINES, Math.max(0, Math.trunc(numeric)));
}

function finalFeedBuffer(baseLines, configuredAdditionalLines) {
  return Buffer.alloc(baseLines + additionalFeedLines(configuredAdditionalLines), 0x0a);
}

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

function buildRasterImageBuffer(raster, appendLineFeed = true) {
  if (!raster || !Number.isInteger(raster.width) || !Number.isInteger(raster.height)
      || raster.width <= 0 || raster.height <= 0 || raster.width > 576
      || raster.height > 512 || !Buffer.isBuffer(raster.bgra)
      || raster.bgra.length !== raster.width * raster.height * 4) {
    return Buffer.alloc(0);
  }
  const bytesPerRow = Math.ceil(raster.width / 8);
  const pixels = Buffer.alloc(bytesPerRow * raster.height);
  for (let y = 0; y < raster.height; y += 1) {
    for (let x = 0; x < raster.width; x += 1) {
      const offset = (y * raster.width + x) * 4;
      const blue = raster.bgra[offset];
      const green = raster.bgra[offset + 1];
      const red = raster.bgra[offset + 2];
      const alpha = raster.bgra[offset + 3];
      const luminance = alpha < 64 ? 255 : (red * 299 + green * 587 + blue * 114) / 1000;
      if (luminance < 160) {
        pixels[y * bytesPerRow + Math.floor(x / 8)] |= 0x80 >> (x % 8);
      }
    }
  }
  return Buffer.concat([
    Buffer.from([GS, 0x76, 0x30, 0x00,
      bytesPerRow & 0xff, (bytesPerRow >> 8) & 0xff,
      raster.height & 0xff, (raster.height >> 8) & 0xff]),
    pixels,
    ...(appendLineFeed ? [Buffer.from([0x0a])] : [])
  ]);
}

function buildRasterDocumentBuffer(raster, configuredAdditionalLines = 0) {
  if (!raster || !Number.isInteger(raster.width) || !Number.isInteger(raster.height)
      || raster.width <= 0 || raster.height <= 0 || raster.width > 576
      || raster.height > 30000 || !Buffer.isBuffer(raster.bgra)
      || raster.bgra.length !== raster.width * raster.height * 4) {
    throw new Error("ESC_POS_DOCUMENT_RASTER_INVALID");
  }
  const chunks = [Buffer.from([ESC, 0x40]), Buffer.from([ESC, 0x61, 0x01])];
  const rowsPerBand = 256;
  const rowBytes = raster.width * 4;
  for (let y = 0; y < raster.height; y += rowsPerBand) {
    const height = Math.min(rowsPerBand, raster.height - y);
    chunks.push(buildRasterImageBuffer({
      width: raster.width,
      height,
      bgra: raster.bgra.subarray(y * rowBytes, (y + height) * rowBytes)
    }, false));
  }
  chunks.push(finalFeedBuffer(4, configuredAdditionalLines));
  chunks.push(Buffer.from([GS, 0x56, 0x00]));
  return Buffer.concat(chunks);
}

function buildTicketBuffer(ticket, configuredAdditionalLines = 0) {
  const suppliedLabels = ticket.escposLabels || ticket.labels;
  const labels = { terminal: "Terminal", item: "Item", quantity: "Qty.", price: "Price", discount: "Descuento", base: "Base", tax: "IVA", total: "TOTAL", ...(suppliedLabels || {}) };
  const raw = ticket.escposContent;
  const giftReceipt = ticket.layout === "GIFT_RECEIPT";
  const cancellationReceipt = ticket.layout === "CANCELLATION_RECEIPT";
  const chunks = [
    Buffer.from([ESC, 0x40]),
    Buffer.from([ESC, 0x61, 0x01]),
    ...(ticket.logoRaster ? [buildRasterImageBuffer(ticket.logoRaster)] : []),
    line(ticket.title || (giftReceipt ? "TICKET REGALO" : raw?.storeName || ticket.storeName || "APP VENTA")),
    ...(giftReceipt || cancellationReceipt ? [line(raw?.storeName || ticket.storeName || "APP VENTA")] : []),
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
  for (const detail of ticket.details || []) {
    chunks.push(line(`${detail.label}: ${detail.value}`));
  }
  if ((ticket.details || []).length > 0) chunks.push(line("------------------------------------------"));
  if (suppliedLabels) chunks.push(line(giftReceipt
    ? `${labels.item} / ${labels.quantity}`
    : padColumns(`${labels.item} / ${labels.quantity} / ${labels.price}`, labels.total)));

  for (const [index, item] of (ticket.lines || []).entries()) {
    if (item.code) chunks.push(line(String(item.code).slice(0, 42)));
    chunks.push(line(String(raw?.lineNames?.[index] || item.name || "").slice(0, 42)));
    chunks.push(line(giftReceipt
      ? `${labels.quantity}: ${item.quantity}`
      : padColumns(`${item.quantity} x ${money(item.price)}`, money(item.total))));
    for (const serial of (item.serialNumbers || [])) {
      chunks.push(line(`  S/N: ${String(serial).slice(0, 35)}`));
    }
  }

  if (!giftReceipt) {
    chunks.push(line("------------------------------------------"));
    for (const [index, payment] of (ticket.payments || []).entries()) {
      chunks.push(line(padColumns(raw?.paymentMethods?.[index] || payment.method || "", money(payment.amount))));
      if (payment.reference) chunks.push(line(`  ${String(payment.reference).slice(0, 40)}`));
    }
    if (Number(ticket.discount || 0) !== 0) {
      chunks.push(line(padColumns(labels.discount, `-${money(Math.abs(ticket.discount))}`)));
    }
    if (ticket.subtotal !== undefined || ticket.tax !== undefined) {
      chunks.push(line(padColumns(labels.base, money(ticket.subtotal))));
      chunks.push(line(padColumns(labels.tax, money(ticket.tax))));
    }
    chunks.push(line("------------------------------------------"));
    chunks.push(Buffer.from([ESC, 0x45, 0x01]));
    chunks.push(line(padColumns(labels.total, money(ticket.total))));
    chunks.push(Buffer.from([ESC, 0x45, 0x00]));
  }
  if (ticket.notice) {
    chunks.push(Buffer.from([ESC, 0x61, 0x01]));
    chunks.push(Buffer.from([ESC, 0x45, 0x01]));
    chunks.push(line(ticket.notice));
    chunks.push(Buffer.from([ESC, 0x45, 0x00]));
  }
  const notes = (ticket.notes || []).filter(Boolean);
  if (notes.length > 0) {
    chunks.push(Buffer.from([ESC, 0x61, 0x00]));
    chunks.push(line("------------------------------------------"));
    for (const note of notes) chunks.push(line(String(note).slice(0, 500)));
  }
  chunks.push(finalFeedBuffer(3, configuredAdditionalLines));
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
  if (ticket?.layout === "CANCELLATION_RECEIPT") {
    return false;
  }
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
  buildRasterDocumentBuffer,
  buildRasterImageBuffer,
  buildTicketBuffer,
  normalizeSerialPath,
  shouldOpenCashDrawerForTicket,
  sendEscposBuffer
};
