"use strict";

const fs = require("node:fs");
const net = require("node:net");
const { execFile } = require("node:child_process");

const IO_TIMEOUT_MS = 5000;
// The upper half of IBM CP858, indexed from 0x80. In particular, 0xD5 is €.
const CP858_HIGH = "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00f8\u00a3\u00d8\u00d7\u0192\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u00ae\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb\u2591\u2592\u2593\u2502\u2524\u00c1\u00c2\u00c0\u00a9\u2563\u2551\u2557\u255d\u00a2\u00a5\u2510\u2514\u2534\u252c\u251c\u2500\u253c\u00e3\u00c3\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u00a4\u00f0\u00d0\u00ca\u00cb\u00c8\u20ac\u00cd\u00ce\u00cf\u2518\u250c\u2588\u2584\u00a6\u00cc\u2580\u00d3\u00df\u00d4\u00d2\u00f5\u00d5\u00b5\u00fe\u00de\u00da\u00db\u00d9\u00fd\u00dd\u00af\u00b4\u00ad\u00b1\u2017\u00be\u00b6\u00a7\u00f7\u00b8\u00b0\u00a8\u00b7\u00b9\u00b3\u00b2\u25a0\u00a0";
const CP858_BYTES = new Map(Array.from(CP858_HIGH, (character, index) => [character, index + 0x80]));
const UNAVAILABLE = "NO DISP.";

function displayFormat(config) {
  const columns = Number(config?.customerDisplayColumns);
  const encoding = config?.customerDisplayEncoding ?? "ASCII";
  if (!Number.isInteger(columns) || columns < 10 || columns > 80) throw new Error("CUSTOMER_DISPLAY_COLUMNS_INVALID");
  if (encoding !== "ASCII" && encoding !== "CP858") throw new Error("CUSTOMER_DISPLAY_ENCODING_INVALID");
  return { columns, encoding };
}

function validatedConfig(config) {
  const { columns, encoding } = displayFormat(config);
  const protocol = config?.customerDisplayProtocol;
  if (protocol !== "ESC_POS_DISPLAY" && protocol !== "PLAIN_TEXT") throw new Error("CUSTOMER_DISPLAY_PROTOCOL_INVALID");
  if (config?.customerDisplayConnection === "SERIAL") {
    const device = String(config.customerDisplayDevicePath || "").trim().toUpperCase();
    const baud = Number(config.customerDisplaySerialBaudRate);
    if (!/^COM(?:[1-9][0-9]{0,2})$/.test(device)) throw new Error("CUSTOMER_DISPLAY_COM_INVALID");
    if (!Number.isSafeInteger(baud) || baud <= 0) throw new Error("CUSTOMER_DISPLAY_BAUD_INVALID");
    return { columns, protocol, encoding, connection: "SERIAL", device, baud };
  }
  if (config?.customerDisplayConnection === "NETWORK") {
    const host = String(config.customerDisplayHost || "").trim();
    const port = Number(config.customerDisplayPort);
    if (!host || host.length > 253 || /[\s\x00-\x1f\x7f]/.test(host)) throw new Error("CUSTOMER_DISPLAY_HOST_INVALID");
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("CUSTOMER_DISPLAY_PORT_INVALID");
    return { columns, protocol, encoding, connection: "NETWORK", host, port };
  }
  throw new Error("CUSTOMER_DISPLAY_CONNECTION_INVALID");
}

function encodedText(value, encoding) {
  const safe = String(value ?? "")
    .normalize("NFC")
    .replace(/[\x00-\x1f\x7f-\x9f]/g, "");
  if (encoding === "CP858") {
    return Buffer.from(Array.from(safe, character => {
      const code = character.codePointAt(0);
      return code >= 0x20 && code <= 0x7e ? code : CP858_BYTES.get(character) ?? 0x3f;
    }));
  }
  const ascii = safe
    .replace(/€/g, "EUR")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^\x20-\x7e]/g, "?");
  return Buffer.from(ascii, "ascii");
}

function paddedLine(value, columns, encoding) {
  const bytes = encodedText(value, encoding).subarray(0, columns);
  return Buffer.concat([bytes, Buffer.alloc(columns - bytes.length, 0x20)]);
}

function selectedSaleItem(state) {
  if (state?.phase !== "SALE" || state.sale?.lastItemId == null) return null;
  return state.sale.items?.find(item => item?.id === state.sale.lastItemId) ?? null;
}

function saleNameBytes(state, encoding) {
  return encodedText(selectedSaleItem(state)?.name, encoding);
}

function lineDisplayScrollLength(state, config) {
  if (state?.phase !== "SALE") return 0;
  const { columns, encoding } = displayFormat(config);
  return Math.max(0, saleNameBytes(state, encoding).length - columns);
}

function finiteNumber(value) {
  if (value == null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function saleQuantity(value) {
  const number = finiteNumber(value);
  if (number == null) return null;
  return `${Number(number.toFixed(3))} x`;
}

function saleLines(state, columns, encoding, options) {
  const item = selectedSaleItem(state);
  if (!item) return [paddedLine("", columns, encoding), paddedLine("", columns, encoding)];
  const name = saleNameBytes(state, encoding);
  const maxOffset = Math.max(0, name.length - columns);
  const requestedOffset = Number(options?.nameOffset);
  const offset = Number.isInteger(requestedOffset) ? Math.min(maxOffset, Math.max(0, requestedOffset)) : 0;
  const visibleName = name.subarray(offset, offset + columns);
  const line1 = Buffer.concat([visibleName, Buffer.alloc(columns - visibleName.length, 0x20)]);
  const quantity = saleQuantity(item.quantity);
  const left = quantity == null ? null : encodedText(quantity, encoding);
  const price = state.sale.status === "READY" ? finiteNumber(item.unitPrice) : null;
  const currency = /^[A-Z]{3}$/.test(state.identity?.currency ?? "") ? state.identity.currency : "EUR";
  const right = price == null ? null : encodedText(`${price.toFixed(2)}${currency === "EUR" ? "€" : currency}`, encoding);
  if (left == null || left.length > columns || (right && left.length + right.length > columns)) {
    return [line1, paddedLine(UNAVAILABLE, columns, encoding)];
  }
  const line2 = right
    ? Buffer.concat([left, Buffer.alloc(columns - left.length - right.length, 0x20), right])
    : Buffer.concat([left, Buffer.alloc(columns - left.length, 0x20)]);
  return [line1, line2];
}

function buildLineDisplayBuffer(state, config, options = {}) {
  const { columns, protocol, encoding } = validatedConfig(config);
  const [line1, line2] = state?.phase === "SALE"
    ? saleLines(state, columns, encoding, options)
    : [paddedLine(state?.line1, columns, encoding), paddedLine(state?.line2, columns, encoding)];
  if (protocol === "PLAIN_TEXT") return Buffer.concat([line1, Buffer.from("\r\n"), line2, Buffer.from("\r\n")]);
  // Epson ESC/POS DM display commands: FF clears, US MD1 selects overwrite
  // mode, and US $ n m positions at column n, row m (both one based).
  const characterTable = encoding === "CP858" ? Buffer.from([0x1b, 0x74, 0x13]) : Buffer.alloc(0);
  if (options.firstLineOnly) return Buffer.concat([
    characterTable, Buffer.from([0x1f, 0x01, 0x1f, 0x24, 0x01, 0x01]), line1,
  ]);
  return Buffer.concat([
    Buffer.from([0x0c]), characterTable, Buffer.from([0x1f, 0x01, 0x1f, 0x24, 0x01, 0x01]),
    line1,
    Buffer.from([0x1f, 0x24, 0x01, 0x02]),
    line2,
  ]);
}

function configureSerial(device, baud) {
  return new Promise((resolve, reject) => {
    execFile("mode.com", [`${device}:`, `BAUD=${baud}`, "PARITY=n", "DATA=8", "STOP=1"],
      { windowsHide: true, timeout: IO_TIMEOUT_MS }, (error) => error ? reject(error) : resolve());
  });
}

function writeSerial(device, buffer) {
  return new Promise((resolve, reject) => {
    const stream = fs.createWriteStream(`\\\\.\\${device}`, { flags: "w" });
    const timer = setTimeout(() => stream.destroy(new Error("CUSTOMER_DISPLAY_TIMEOUT")), IO_TIMEOUT_MS);
    stream.once("error", (error) => { clearTimeout(timer); reject(error); });
    stream.once("finish", () => { clearTimeout(timer); resolve(); });
    stream.end(buffer);
  });
}

function writeNetwork(host, port, buffer) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let settled = false;
    function finish(error) {
      if (settled) return;
      settled = true;
      if (error) {
        socket.destroy();
        reject(error);
      } else {
        resolve();
      }
    }
    socket.setTimeout(IO_TIMEOUT_MS, () => {
      if (settled) socket.destroy();
      else finish(new Error("CUSTOMER_DISPLAY_TIMEOUT"));
    });
    socket.once("error", finish);
    socket.once("connect", () => socket.end(buffer));
    socket.once("finish", () => { socket.unref(); finish(); });
    socket.once("close", (hadError) => {
      if (!settled) finish(new Error(hadError ? "CUSTOMER_DISPLAY_NETWORK_ERROR" : "CUSTOMER_DISPLAY_CONNECTION_CLOSED"));
    });
  });
}

async function sendLineDisplay(config, state, options = {}) {
  const destination = validatedConfig(config);
  const buffer = buildLineDisplayBuffer(state, config, options);
  if (destination.connection === "SERIAL") {
    await configureSerial(destination.device, destination.baud);
    await writeSerial(destination.device, buffer);
  } else {
    await writeNetwork(destination.host, destination.port, buffer);
  }
}

module.exports = { buildLineDisplayBuffer, lineDisplayScrollLength, sendLineDisplay };
