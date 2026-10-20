import assert from "node:assert/strict";
import { it as test } from "vitest";
import { createRequire } from "node:module";
import { JSDOM } from "jsdom";

const require = createRequire(import.meta.url);
const { renderCustomerDisplayHtml } = require("./customer-display-renderer.cjs");

function display(state = {}, options) {
  const dom = new JSDOM(renderCustomerDisplayHtml(state, options), { runScripts: "dangerously" });
  return { window: dom.window, document: dom.window.document, close: () => dom.window.close() };
}

const readySale = {
  items: [{ id: "a", name: "Calcetines", quantity: 1, unitPrice: 5.9, total: 5.31, discount: .59, discountPercent: 10 }],
  lastItemId: "a", subtotal: 5.9, discount: .59, total: 5.31, status: "READY"
};

test("idle shows both configured messages together and hides sale panels", () => {
  const idle = { phase: "IDLE", line1: "BIENVENIDO A NUESTRA TIENDA", line2: "TE ESPERAMOS PRONTO", identity: { storeName: "Tienda" } };
  const view = display(idle);
  try {
    const node = (id) => view.document.getElementById(id);
    assert.equal(node("idle-panel").hidden, false);
    assert.equal(node("idle-line-1").textContent, idle.line1);
    assert.equal(node("idle-line-2").textContent, idle.line2);
    assert.equal(node("left-panel").hidden, true);
    assert.equal(node("cart-panel").hidden, true);
    assert.equal(node("cart-heading").hidden, true);
    assert.equal(node("store-name").textContent, "Tienda");
    assert.equal(node("thanks").textContent, "Gracias por su visita");
    assert.ok(node("clock").textContent);
    view.window.customerDisplayUpdate({ phase: "SALE", sale: readySale });
    assert.equal(node("idle-panel").hidden, true);
    assert.equal(node("idle-line-1").textContent, "");
    assert.equal(node("idle-line-2").textContent, "");
    assert.equal(node("left-panel").hidden, false);
    assert.equal(node("cart-panel").hidden, false);
    assert.equal(node("cart-heading").hidden, false);
    assert.match(node("total-value").textContent, /5,31\s*€/);
    view.window.customerDisplayUpdate(idle);
    assert.equal(node("idle-panel").hidden, false);
    assert.equal(node("idle-line-1").textContent, idle.line1);
    assert.equal(node("idle-line-2").textContent, idle.line2);
    assert.equal(node("left-panel").hidden, true);
    assert.equal(node("cart-panel").hidden, true);
    assert.equal(node("cart-list").children.length, 0);
  } finally { view.close(); }
});

test("escapes data in the initial script and inserts names as text on every update", () => {
  const attack = '</script><img src=x onerror="window.pwned=1">';
  const html = renderCustomerDisplayHtml({ phase: "SALE", identity: { storeName: attack }, sale: { ...readySale, items: [{ ...readySale.items[0], name: attack }] } });
  assert.equal(html.includes(attack), false);
  const view = display({ phase: "SALE", identity: { storeName: attack }, sale: { ...readySale, items: [{ ...readySale.items[0], name: attack }] } });
  try {
    assert.equal(view.document.getElementById("cart-title").textContent, "Tu carrito");
    assert.equal(view.document.getElementById("cart-heading").textContent, "Tu compra");
    assert.equal(view.document.getElementById("store-name").textContent, attack);
    assert.equal(view.document.querySelector(".cart-item").textContent.startsWith(attack), true);
    assert.equal(view.document.querySelectorAll("img[src=x]").length, 0);
    view.window.customerDisplayUpdate({ phase: "SALE", sale: { ...readySale, items: [{ ...readySale.items[0], name: attack }] } });
    assert.equal(view.window.pwned, undefined);
  } finally { view.close(); }
});

test("shows only supplied READY amounts and discounts", () => {
  const view = display({ phase: "SALE", locale: "es", sale: readySale });
  try {
    assert.match(view.document.getElementById("total-value").textContent, /5,31\s*€/);
    assert.match(view.document.getElementById("last-discount").textContent, /10%.*0,59\s*€/);
    assert.match(view.document.getElementById("last-discount").textContent, /^Descuento/);
    assert.match(view.document.querySelector(".cart-discount").textContent, /0,59\s*€/);
    view.window.customerDisplayUpdate({ phase: "SALE", sale: { ...readySale, items: [{ ...readySale.items[0], discount: null, discountPercent: null }], discount: null } });
    assert.equal(view.document.getElementById("last-discount").hidden, true);
    assert.equal(view.document.querySelector(".cart-discount"), null);
    assert.equal(view.document.getElementById("discount-row").hidden, true);
    assert.equal(view.document.getElementById("total-value").textContent, "5,31 €");
  } finally { view.close(); }
});

test("pending and error sale values remain unknown and payment does not imply completion", () => {
  const view = display({ phase: "SALE", sale: { ...readySale, status: "PENDING" } });
  try {
    assert.equal(view.document.getElementById("total-value").textContent, "—");
    assert.equal(view.document.getElementById("last-price").textContent, "—");
    assert.equal(view.document.getElementById("status-note").textContent, "Pendiente");
    assert.equal(view.document.querySelector(".cart-amount").textContent, "—");
    view.window.customerDisplayUpdate({ phase: "SALE", sale: { ...readySale, status: "ERROR" } });
    assert.equal(view.document.body.dataset.status, "ERROR");
    assert.equal(view.document.getElementById("status-note").textContent, "Importe no disponible");
    assert.equal(view.document.getElementById("total-value").textContent, "—");
    view.window.customerDisplayUpdate({ phase: "PAYMENT", sale: readySale, payment: { total: 5.31, pending: true, change: 2 } });
    assert.equal(view.document.getElementById("payment-label").textContent, "Cobro pendiente");
    assert.equal(view.document.getElementById("change-row").hidden, true);
    assert.match(view.document.getElementById("total-value").textContent, /5,31\s*€/);
  } finally { view.close(); }
});

test("separates nonzero return and member balance adjustments from discounts", () => {
  const sale = { ...readySale, adjustments: [
    { kind: "RETURN_ADJUSTMENT", amount: -3 }, { kind: "MEMBER_BALANCE", amount: 2 },
    { kind: "OTHER", amount: 0 }, { kind: "UNKNOWN", amount: 100 }
  ] };
  const view = display({ phase: "SALE", sale });
  try {
    const rows = [...view.document.querySelectorAll(".adjustment-row")];
    assert.equal(rows.length, 2);
    assert.match(rows[0].textContent, /Ajustes de devolución/);
    assert.match(rows[1].textContent, /Saldo socio/);
    assert.doesNotMatch(rows.map((row) => row.textContent).join(" "), /Descuento/);
  } finally { view.close(); }
});

test("uses requested locale and store timezone, falling back for invalid timezone", () => {
  const view = display({ phase: "IDLE", locale: "en", identity: { companyName: "Company", timeZone: "UTC", currency: "GBP" } });
  try {
    assert.equal(view.document.documentElement.lang, "en");
    assert.equal(view.document.getElementById("store-name").textContent, "Company");
    assert.equal(view.document.getElementById("thanks").textContent, "Thank you for visiting");
    assert.equal(view.document.getElementById("clock").textContent.split("\n").length, 2);
    view.window.customerDisplayUpdate({ phase: "SALE", locale: "zh", identity: { timeZone: "invalid/zone", currency: "EUR" }, sale: readySale });
    assert.equal(view.document.documentElement.lang, "zh");
    assert.equal(view.document.getElementById("thanks").textContent, "感谢您的光临");
    assert.equal(view.document.getElementById("clock").textContent.split("\n").length, 2);
  } finally { view.close(); }
});

test("accepts only bounded image data URIs for store and footer logos", () => {
  const good = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/+FsAAAAASUVORK5CYII=";
  const view = display({ identity: { logoDataUri: "javascript:alert(1)" } }, { esposLogo: "https://example.com/logo.png" });
  try {
    assert.equal(view.document.getElementById("store-logo").hidden, true);
    assert.equal(view.document.getElementById("footer-logo").hidden, true);
    view.window.customerDisplayUpdate({ identity: { logoDataUri: good } });
    assert.equal(view.document.getElementById("store-logo").hidden, false);
    assert.equal(view.document.getElementById("store-logo").getAttribute("src"), good);
    const base = Buffer.from(good.slice(good.indexOf(",") + 1), "base64");
    const acceptedLarge = `data:image/png;base64,${Buffer.concat([base, Buffer.alloc(1200000)]).toString("base64")}`;
    view.window.customerDisplayUpdate({ identity: { logoDataUri: acceptedLarge } });
    assert.equal(view.document.getElementById("store-logo").hidden, false);
    const rejectedLarge = `data:image/png;base64,${Buffer.concat([base, Buffer.alloc(2 * 1024 * 1024)]).toString("base64")}`;
    view.window.customerDisplayUpdate({ identity: { logoDataUri: rejectedLarge } });
    assert.equal(view.document.getElementById("store-logo").hidden, true);
  } finally { view.close(); }
});

test("keeps the last cart row visible after a viewport resize", () => {
  const view = display({ phase: "SALE", sale: readySale });
  try {
    const list = view.document.getElementById("cart-list");
    const lastRow = list.querySelector(".cart-row.is-last");
    list.getBoundingClientRect = () => ({ top: 0, bottom: 100 });
    lastRow.getBoundingClientRect = () => ({ top: 120, bottom: 170 });
    list.scrollTop = 0;
    view.window.dispatchEvent(new view.window.Event("resize"));
    assert.equal(list.scrollTop, 70);
  } finally { view.close(); }
});

test("uses trimmed company name when store name is blank", () => {
  const view = display({ identity: { storeName: "  ", companyName: " EMPRESA DEMO " } });
  try {
    assert.equal(view.document.getElementById("store-name").textContent, "EMPRESA DEMO");
  } finally { view.close(); }
});

test("confirmed payment without a sale does not show an unrelated pending sale", () => {
  const view = display({ phase: "PAYMENT", payment: { total: 5.31, change: 1.25 } });
  try {
    assert.equal(view.document.getElementById("last-label").textContent, "Pago");
    assert.equal(view.document.getElementById("last-name").textContent, "Pago");
    assert.equal(view.document.getElementById("status-note").hidden, true);
    assert.equal(view.document.getElementById("subtotal-label").parentElement.hidden, true);
    assert.equal(view.document.getElementById("discount-row").hidden, true);
    assert.equal(view.document.getElementById("cart-summary").textContent, "");
    assert.match(view.document.getElementById("total-value").textContent, /5,31\s*€/);
    assert.match(view.document.getElementById("change-value").textContent, /1,25\s*€/);
  } finally { view.close(); }
});

test("long cart values use two-row layout without dropping amounts", () => {
  const big = 9999999.99;
  const view = display({ phase: "SALE", sale: { items: [{ id: "big", name: "Artículo de importe alto", quantity: 1, unitPrice: big, total: big, discount: null }], lastItemId: "big", subtotal: big, discount: null, total: big, status: "READY" } });
  try {
    assert.equal(view.document.getElementById("last-name").classList.contains("is-long"), true);
    assert.equal(view.document.getElementById("cart-list").classList.contains("has-long-values"), true);
    assert.equal(view.document.getElementById("cart-columns").classList.contains("has-long-values"), true);
    assert.match(view.document.querySelector(".cart-amount").textContent, /9\.999\.999,99\s*€/);
    assert.match(view.document.getElementById("total-value").textContent, /9\.999\.999,99\s*€/);
  } finally { view.close(); }
});
