import assert from "node:assert/strict";
import net from "node:net";
import { createRequire } from "node:module";
import { it as test } from "vitest";

const require = createRequire(import.meta.url);
const { buildLineDisplayBuffer, lineDisplayScrollLength, sendLineDisplay } = require("./customer-line-display.cjs");

const serialConfig = {
  customerDisplayConnection: "SERIAL",
  customerDisplayProtocol: "ESC_POS_DISPLAY",
  customerDisplayColumns: 20,
  customerDisplayDevicePath: "COM3",
  customerDisplaySerialBaudRate: 9600,
};

test("ESC/POS display uses clear, overwrite and explicit one-based row positioning", () => {
  const data = buildLineDisplayBuffer({ line1: "Café €", line2: "Gracias\r\n\x1b" }, serialConfig);
  assert.deepEqual(data.subarray(0, 7), Buffer.from([0x0c, 0x1f, 0x01, 0x1f, 0x24, 0x01, 0x01]));
  assert.equal(data.subarray(7, 27).toString("ascii"), "Cafe EUR            ");
  assert.deepEqual(data.subarray(27, 31), Buffer.from([0x1f, 0x24, 0x01, 0x02]));
  assert.equal(data.subarray(31).toString("ascii"), "Gracias             ");
  assert.equal(data.includes(Buffer.from([0x1b])), false);
});

test("plain text sends exactly two cropped, padded ASCII lines with CRLF", () => {
  const data = buildLineDisplayBuffer({ line1: "0123456789ABCDE", line2: "Ñandú" }, {
    ...serialConfig, customerDisplayProtocol: "PLAIN_TEXT", customerDisplayColumns: 10,
  });
  assert.equal(data.toString("ascii"), "0123456789\r\nNandu     \r\n");
});

function sale(item = {}, sale = {}) {
  return {
    phase: "SALE", line1: "stale", line2: "stale",
    sale: {
      items: [{ id: "product", name: "Café", quantity: 2, unitPrice: 1, total: 2, ...item }],
      lastItemId: "product", total: 2, status: "READY", ...sale,
    },
  };
}

function plainLines(state, columns = 20, encoding = "CP858", options = {}) {
  const data = buildLineDisplayBuffer(state, {
    ...serialConfig, customerDisplayProtocol: "PLAIN_TEXT",
    customerDisplayColumns: columns, customerDisplayEncoding: encoding,
  }, options);
  assert.equal(data.length, columns * 2 + 4);
  assert.deepEqual(data.subarray(columns, columns + 2), Buffer.from("\r\n"));
  return [data.subarray(0, columns), data.subarray(columns + 2, -2)];
}

test("sale selects the requested product and displays quantity left, explicit unit price right", () => {
  const state = sale({}, { items: [
    { id: "product", name: "Café", quantity: 2, unitPrice: 1, total: 2 },
    { id: "discount", name: "Descuento", quantity: 1, unitPrice: null, total: -1 },
  ], total: 1 });
  for (const columns of [10, 20, 40]) {
    const [name, detail] = plainLines(state, columns);
    assert.deepEqual(name.subarray(0, 4), Buffer.from([0x43, 0x61, 0x66, 0x82]));
    assert.equal(detail.subarray(0, 3).toString("ascii"), "2 x");
    assert.deepEqual(detail.subarray(-5), Buffer.from([0x31, 0x2e, 0x30, 0x30, 0xd5]));
    assert.equal(detail.length, columns);
  }
  const [zeroName, zeroDetail] = plainLines(sale({ unitPrice: 0 }), 10);
  assert.equal(zeroName[0], 0x43);
  assert.deepEqual(zeroDetail.subarray(-5), Buffer.from([0x30, 0x2e, 0x30, 0x30, 0xd5]));
});

test("pending, error or absent unit price never substitute a line or sale total", () => {
  for (const state of [
    sale({ unitPrice: null }),
    sale({}, { status: "PENDING" }),
    sale({}, { status: "ERROR" }),
  ]) {
    const [name, detail] = plainLines(state);
    assert.equal(name.subarray(0, 4).toString("hex"), "43616682");
    assert.equal(detail.toString("ascii"), "2 x                 ");
  }
  const [missingName, missingDetail] = plainLines(sale({}, { lastItemId: "discount" }));
  assert.equal(missingName.toString("ascii"), " ".repeat(20));
  assert.equal(missingDetail.toString("ascii"), " ".repeat(20));
});

test("keeps the supplied currency and normalizes equivalent accented product names", () => {
  const state = { ...sale({ name: "Cafe\u0301" }), identity: { currency: "USD" } };
  const [name, detail] = plainLines(state);
  assert.equal(name.subarray(0, 4).toString("hex"), "43616682");
  assert.equal(detail.toString("ascii"), "2 x          1.00USD");
});

test("quantity uses up to three decimals without grouping; impossible fit hides numbers", () => {
  const [, quantity] = plainLines(sale({ quantity: 1234.125, unitPrice: null }), 20);
  assert.equal(quantity.toString("ascii"), "1234.125 x          ");
  const [, impossible] = plainLines(sale({ quantity: 12345, unitPrice: 99999.99 }), 10);
  assert.equal(impossible.toString("ascii"), "NO DISP.  ");
  assert.equal(impossible.includes(Buffer.from("999")), false);
});

test("CP858 selects table 19 in ESC/POS and encodes the euro without control injection", () => {
  const data = buildLineDisplayBuffer(sale({ name: "Ñandú\x1b\r\n €" }), {
    ...serialConfig, customerDisplayEncoding: "CP858",
  });
  assert.deepEqual(data.subarray(0, 10), Buffer.from([0x0c, 0x1b, 0x74, 0x13, 0x1f, 0x01, 0x1f, 0x24, 0x01, 0x01]));
  assert.deepEqual(data.subarray(10, 17), Buffer.from([0xa5, 0x61, 0x6e, 0x64, 0xa3, 0x20, 0xd5]));
  assert.equal(data.subarray(10).includes(0x1b), false);
  assert.deepEqual(data.subarray(30, 34), Buffer.from([0x1f, 0x24, 0x01, 0x02]));
});

test("legacy ASCII keeps EUR and accent folding when no encoding is configured", () => {
  const [, detail] = plainLines(sale(), 20, "ASCII");
  assert.equal(detail.toString("ascii"), "2 x          1.00EUR");
  const state = sale({ name: "Café" });
  assert.equal(lineDisplayScrollLength(state, serialConfig), 0);
  assert.equal(buildLineDisplayBuffer(state, serialConfig).subarray(7, 11).toString("ascii"), "Cafe");
});

test("long names scroll by encoded columns while the second line remains fixed", () => {
  const state = sale({ name: "Café con leche grande" });
  const config = { ...serialConfig, customerDisplayColumns: 10, customerDisplayEncoding: "CP858" };
  const maxOffset = lineDisplayScrollLength(state, config);
  assert.equal(maxOffset, 11);
  const first = buildLineDisplayBuffer(state, config);
  const last = buildLineDisplayBuffer(state, config, { nameOffset: maxOffset, firstLineOnly: true });
  assert.deepEqual(first.subarray(10, 20), Buffer.from([0x43, 0x61, 0x66, 0x82, 0x20, 0x63, 0x6f, 0x6e, 0x20, 0x6c]));
  assert.equal(last.subarray(0, 3).toString("hex"), "1b7413");
  assert.equal(last.includes(0x0c), false);
  assert.equal(last.length, 9 + 10);
  assert.equal(last.subarray(-10).toString("ascii"), "che grande");
  assert.equal(last.includes(Buffer.from([0x1f, 0x24, 0x01, 0x02])), false);
  const plainConfig = { ...config, customerDisplayProtocol: "PLAIN_TEXT" };
  const [, firstDetail] = plainLines(state, 10);
  const [plainLast, lastDetail] = plainLines(state, 10, "CP858", { nameOffset: maxOffset, firstLineOnly: true });
  assert.equal(plainLast.toString("ascii"), "che grande");
  assert.deepEqual(lastDetail, firstDetail);
  assert.equal(lineDisplayScrollLength({ phase: "PAYMENT" }, config), 0);
  assert.equal(lineDisplayScrollLength({ line1: "legacy", line2: "raw" }, { customerDisplayEnabled: true }), 0);
  assert.equal(lineDisplayScrollLength(state, { customerDisplayColumns: 10, customerDisplayEncoding: "CP858" }), maxOffset);
  assert.equal(buildLineDisplayBuffer(state, plainConfig, { nameOffset: maxOffset }).subarray(0, 10).toString("ascii"), "che grande");
});

test("serial destination accepts only literal COM numbers and positive safe integer baud rates", async () => {
  for (const path of ["\\\\.\\COM3", "COM0", "COM3: & calc", "LPT1", "COM1000", "COM1\\other"]) {
    await assert.rejects(sendLineDisplay({ ...serialConfig, customerDisplayDevicePath: path }, { line1: "x", line2: "y" }),
      /CUSTOMER_DISPLAY_COM_INVALID/);
  }
  for (const baud of [300, 600, 12345, 31250]) {
    assert.ok(buildLineDisplayBuffer({}, { ...serialConfig, customerDisplaySerialBaudRate: baud }).length > 0);
  }
  for (const baud of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, "9600 & calc"]) {
    assert.throws(() => buildLineDisplayBuffer({}, { ...serialConfig, customerDisplaySerialBaudRate: baud }),
      /CUSTOMER_DISPLAY_BAUD_INVALID/);
  }
  assert.throws(() => buildLineDisplayBuffer({}, { ...serialConfig, customerDisplayColumns: 81 }),
    /CUSTOMER_DISPLAY_COLUMNS_INVALID/);
  assert.throws(() => buildLineDisplayBuffer({}, { ...serialConfig, customerDisplayProtocol: "ESC_POS_PRINTER" }),
    /CUSTOMER_DISPLAY_PROTOCOL_INVALID/);
  assert.throws(() => buildLineDisplayBuffer({}, { ...serialConfig, customerDisplayEncoding: "UTF8" }),
    /CUSTOMER_DISPLAY_ENCODING_INVALID/);
  await assert.rejects(sendLineDisplay({ ...serialConfig, customerDisplayConnection: "NETWORK",
    customerDisplayHost: "host\nother", customerDisplayPort: 9100 }, {}), /CUSTOMER_DISPLAY_HOST_INVALID/);
});

test("network sends the requested scrolled frame to a temporary TCP endpoint", async () => {
  let received = Buffer.alloc(0);
  let acceptedSocket;
  let receivedComplete;
  const complete = new Promise((resolve) => { receivedComplete = resolve; });
  const server = net.createServer((socket) => {
    acceptedSocket = socket;
    socket.on("data", (chunk) => { received = Buffer.concat([received, chunk]); });
    socket.on("end", () => { socket.end(); receivedComplete(); });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    const config = {
      ...serialConfig,
      customerDisplayConnection: "NETWORK",
      customerDisplayProtocol: "ESC_POS_DISPLAY",
      customerDisplayEncoding: "CP858",
      customerDisplayHost: "127.0.0.1",
      customerDisplayPort: address.port,
    };
    const state = sale({ name: "Café con leche grande" });
    const options = { nameOffset: lineDisplayScrollLength(state, config), firstLineOnly: true };
    await sendLineDisplay(config, state, options);
    await complete;
    assert.deepEqual(received, buildLineDisplayBuffer(state, config, options));
    assert.equal(received.includes(0x0c), false);
  } finally {
    acceptedSocket?.destroy();
    await new Promise((resolve) => server.close(resolve));
  }
});
